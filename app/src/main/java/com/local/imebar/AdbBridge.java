package com.local.imebar;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * adb 动作的执行通道——**只依赖 Shizuku**（含 Shizuku 官方 provider 自带的 Sui 初始化）。
 *
 * 这个类跑在**模块 App 自己的进程**里，原因是硬的：Shizuku 的 binder 只会投递给清单里声明了
 * {@code rikka.shizuku.ShizukuProvider} 的包，输入法进程拿不到；而且也不能把 binder 递进输入法进程
 * （那等于把 shell 能力交给输入法 App 和同进程里的其他代码）。
 *
 * 真正干活的 shell 身份来自 Shizuku 的 UserService：Shizuku 会以 shell（uid 2000）/root（uid 0）
 * 加载本 APK 里的 {@link AdbUserService}。所以整个项目里没有任何 su 通道，也没有"普通身份"回退。
 *
 * 速度：起一个 UserService 进程要几百毫秒（要 fork + 加载 dex），所以**连上之后一直复用**，
 * 只在空闲 {@link #IDLE_UNBIND_MS} 毫秒没有任何命令时才解绑（解绑触发 destroy → 进程退出），
 * 并且弹键盘时（配置里确实有 adb 按钮的话）提前在后台把它热好。这样点按钮基本是几十毫秒的事。
 *
 * 失败一律把原因写进返回的 message（Toast 是唯一能说清楚"为什么失败"的地方）。
 */
final class AdbBridge {

    /** 输入法进程通过 ConfigProvider 发请求用的方法名 */
    static final String METHOD_RUN_ADB = "run_adb";

    static final String KEY_CMD = "cmd";
    static final String KEY_STATUS = "status";
    static final String KEY_MESSAGE = "message";
    static final String KEY_EXIT = "exit";
    static final String KEY_OUTPUT = "output";

    static final int OK = 0;
    /** Shizuku 没在运行（没装 / 没启动 / binder 没送达） */
    static final int NO_SERVICE = 1;
    /** 没有权限，已经替你发起了授权请求 */
    static final int NEED_PERMISSION = 2;
    /** 权限被拒（含"拒绝且不再询问"） */
    static final int DENIED = 3;
    /** 调用方或命令不在允许范围内，直接不执行 */
    static final int REJECTED = 4;
    /** UserService 起不来 / 连不上 */
    static final int SERVICE_FAIL = 5;
    static final int TIMEOUT = 6;
    static final int EXIT_NONZERO = 7;
    static final int ERROR = 8;

    /** 申请 Shizuku 权限用的 requestCode（只用来对结果，不做别的） */
    static final int REQUEST_CODE = 1001;

    /** 等 Shizuku binder 的上限：App 进程刚被拉起来时 binder 可能还在路上 */
    private static final long BINDER_WAIT_MS = 2000;
    /** 等 UserService 连上的上限（冷启动要 fork 一个进程出来，只在预热没赶上时才会用到） */
    private static final long CONNECT_WAIT_MS = 5000;
    /** 这么久没有命令就把 UserService 放掉，别让 shell 身份的进程一直挂着 */
    private static final long IDLE_UNBIND_MS = TimeUnit.MINUTES.toMillis(5);
    /** 预热失败后的退避时间（没装 Shizuku、权限被拒时别每次弹键盘都去撞一遍） */
    private static final long WARMUP_BACKOFF_MS = 30_000;

    /** 同一时刻只跑一条命令：并发 bind 同一个 UserService 没意义，还会互相拖慢 */
    private static final Object LOCK = new Object();

    private static Shizuku.UserServiceArgs userServiceArgs;
    /** 已经连上并复用的 UserService（就是它让第二次以后点按钮变快） */
    private static IAdbUserService service;
    private static IBinder serviceBinder;
    private static ServiceConnection serviceConnection;
    private static long lastUsedAt;
    private static boolean warmingUp;
    private static long warmUpBlockedUntil;
    private static ScheduledExecutorService idleTimer;
    private static ScheduledFuture<?> idleTask;
    /** 空闲解绑的定时任务（只用到 LOCK，不依赖主线程 Looper：UserService 进程里没有 Looper） */
    private static final Runnable IDLE_UNBIND = new Runnable() {
        public void run() {
            synchronized (LOCK) {
                if (service == null) {
                    return;
                }
                if (SystemClock.uptimeMillis() - lastUsedAt < IDLE_UNBIND_MS) {
                    return;
                }
                releaseServiceLocked();
            }
        }
    };

    private AdbBridge() {
    }

    // ---------- 与输入法进程的约定 ----------

    /**
     * 把用户写的那条命令归一化：去掉开头的 {@code adb shell } / {@code adb }。
     * 配置匹配和真正执行都用归一化后的字符串，两边必须一致。
     */
    static String normalize(String command) {
        String cmd = command == null ? "" : command.trim();
        if (cmd.startsWith("adb shell ")) {
            return cmd.substring("adb shell ".length()).trim();
        }
        if (cmd.startsWith("adb ")) {
            return cmd.substring("adb ".length()).trim();
        }
        return cmd;
    }

    static Bundle reply(int status, String message) {
        return reply(status, message, -1, "");
    }

    static Bundle reply(int status, String message, int exitCode, String output) {
        Bundle bundle = new Bundle();
        bundle.putInt(KEY_STATUS, status);
        bundle.putString(KEY_MESSAGE, message == null ? "" : message);
        bundle.putInt(KEY_EXIT, exitCode);
        bundle.putString(KEY_OUTPUT, output == null ? "" : output);
        return bundle;
    }

    // ---------- 执行 ----------

    /** @param command 已经归一化过的命令；返回 ConfigProvider 直接丢回去的那份 Bundle */
    static Bundle run(String command) {
        String cmd = normalize(command);
        if (cmd.length() == 0) {
            return reply(ERROR, "命令是空的");
        }
        synchronized (LOCK) {
            if (!waitForBinder()) {
                return reply(NO_SERVICE, "Shizuku 没在运行（启动 Shizuku 后再点一次）");
            }

            Bundle permissionProblem = requirePermission();
            if (permissionProblem != null) {
                return permissionProblem;
            }

            Bundle result = execLocked(cmd);
            if (result != null) {
                return result;
            }
            // 复用的那个服务可能刚死掉（系统回收 / Shizuku 重启）：重连一次再试
            releaseServiceLocked();
            result = execLocked(cmd);
            if (result != null) {
                return result;
            }
            releaseServiceLocked();
            return reply(SERVICE_FAIL, "Shizuku 用户服务起不来（等了 "
                    + (CONNECT_WAIT_MS / 1000) + " 秒没连上）");
        }
    }

    /**
     * 预热：模块 App 每次被问配置时（弹键盘）调一次，把 UserService 提前连好，
     * 这样第一次点 adb 按钮也是快的。
     *
     * 静默：不申请权限、不弹任何提示，失败就退避一会儿再来；没连上也不影响打字。
     */
    static void warmUpAsync() {
        synchronized (LOCK) {
            if (service != null || warmingUp) {
                return;
            }
            if (SystemClock.uptimeMillis() < warmUpBlockedUntil) {
                return;   // 刚失败过，退避中
            }
            warmingUp = true;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    warmUp();
                } finally {
                    synchronized (LOCK) {
                        warmingUp = false;
                    }
                }
            }
        }, "imebar-adb-warm").start();
    }

    private static void warmUp() {
        synchronized (LOCK) {
            if (service != null) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now < warmUpBlockedUntil) {
                return;
            }
            try {
                // 只热"本来就能用"的情况：Shizuku 在跑 + 已经有权限
                if (!Shizuku.pingBinder() || Shizuku.isPreV11()
                        || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
            } catch (Throwable t) {
                return;
            }
            if (ensureServiceLocked() == null) {
                warmUpBlockedUntil = now + WARMUP_BACKOFF_MS;
                return;
            }
            lastUsedAt = now;
            scheduleIdleUnbindLocked();
        }
    }

    /** @return null 表示有权限可以继续，否则就是该直接返回给输入法的失败结果 */
    private static Bundle requirePermission() {
        try {
            if (Shizuku.isPreV11()) {
                return reply(DENIED, "Shizuku 版本太旧，请升级");
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return null;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                // 用户点过"拒绝且不再询问"：再请求也不会弹窗，只能去 Shizuku 里手动开
                return reply(DENIED, "Shizuku 权限被拒绝了：去 Shizuku → 应用管理 → imebar 手动允许");
            }
            Shizuku.requestPermission(REQUEST_CODE);
            return reply(NEED_PERMISSION, "还没有 Shizuku 权限：在弹窗里点允许后再点一次这个按钮");
        } catch (Throwable t) {
            // 最常见的原因是 binder 刚死掉（Shizuku 被停）
            return reply(NO_SERVICE, "Shizuku 没在运行（启动 Shizuku 后再点一次）");
        }
    }

    /**
     * 在当前连接上跑一条命令。
     *
     * @return null 表示服务没连上（或半路死掉），交给调用方重连一次
     */
    private static Bundle execLocked(String cmd) {
        IAdbUserService svc = ensureServiceLocked();
        if (svc == null) {
            return null;
        }
        try {
            Bundle result = svc.run(cmd);
            return result == null ? reply(ERROR, "Shizuku 用户服务没返回结果") : result;
        } catch (Throwable t) {
            return null;
        } finally {
            lastUsedAt = SystemClock.uptimeMillis();
            scheduleIdleUnbindLocked();
        }
    }

    /** @return null 表示连不上（或超时）；连上就缓存起来复用 */
    private static IAdbUserService ensureServiceLocked() {
        if (service != null) {
            try {
                if (serviceBinder != null && serviceBinder.pingBinder()) {
                    return service;
                }
            } catch (Throwable ignored) {
            }
            releaseServiceLocked();
        }

        final CountDownLatch connected = new CountDownLatch(1);
        final IBinder[] received = new IBinder[1];
        final ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                received[0] = binder;
                connected.countDown();
            }

            public void onServiceDisconnected(ComponentName name) {
                // 服务挂了（Shizuku 重启 / 被回收）：丢掉缓存，下次重新连
                synchronized (LOCK) {
                    if (serviceBinder != null && serviceBinder == received[0]) {
                        service = null;
                        serviceBinder = null;
                        serviceConnection = null;
                    }
                }
            }
        };

        try {
            Shizuku.bindUserService(userServiceArgs(), connection);
        } catch (Throwable t) {
            return null;
        }

        boolean ok;
        try {
            ok = connected.await(CONNECT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            ok = false;
        }
        if (!ok || received[0] == null || !received[0].pingBinder()) {
            unbind(connection);
            return null;
        }

        serviceBinder = received[0];
        serviceConnection = connection;
        service = IAdbUserService.Stub.asInterface(received[0]);
        return service;
    }

    /** 放掉当前连接：Shizuku 会调 UserService 的 destroy（我们的实现里 System.exit） */
    private static void releaseServiceLocked() {
        cancelIdleUnbindLocked();
        ServiceConnection connection = serviceConnection;
        service = null;
        serviceBinder = null;
        serviceConnection = null;
        if (connection != null) {
            unbind(connection);
        }
    }

    private static void unbind(ServiceConnection connection) {
        try {
            Shizuku.unbindUserService(userServiceArgs(), connection, true);
        } catch (Throwable ignored) {
            // 解绑失败无所谓：服务不是 daemon，连接没了 Shizuku 也会自己回收
        }
    }

    private static void scheduleIdleUnbindLocked() {
        cancelIdleUnbindLocked();
        try {
            if (idleTimer == null) {
                idleTimer = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, "imebar-adb-idle");
                        thread.setDaemon(true);
                        return thread;
                    }
                });
            }
            idleTask = idleTimer.schedule(IDLE_UNBIND, IDLE_UNBIND_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            // 起不了定时器就靠"下次连不上时重连"，不致命
        }
    }

    private static void cancelIdleUnbindLocked() {
        if (idleTask != null) {
            try {
                idleTask.cancel(false);
            } catch (Throwable ignored) {
            }
            idleTask = null;
        }
    }

    private static Shizuku.UserServiceArgs userServiceArgs() {
        if (userServiceArgs == null) {
            userServiceArgs = new Shizuku.UserServiceArgs(
                    new ComponentName(BuildConfig.APPLICATION_ID, AdbUserService.class.getName()))
                    .daemon(false)                     // 用完即走
                    .tag("imebar-adb")                 // tag + 类名决定 Shizuku 复用哪个 UserService
                    .processNameSuffix("adb")          // ps 里能看到 com.local.imebar:adb
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);
        }
        return userServiceArgs;
    }

    /** App 进程刚起来时 binder 可能还在路上，等一下再判定"没在运行" */
    private static boolean waitForBinder() {
        try {
            if (Shizuku.pingBinder()) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        final CountDownLatch received = new CountDownLatch(1);
        Shizuku.OnBinderReceivedListener listener = new Shizuku.OnBinderReceivedListener() {
            public void onBinderReceived() {
                received.countDown();
            }
        };
        try {
            Shizuku.addBinderReceivedListenerSticky(listener);
            try {
                received.await(BINDER_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                Shizuku.removeBinderReceivedListener(listener);
            } catch (Throwable ignored) {
            }
        }
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

}
