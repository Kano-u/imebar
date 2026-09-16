package com.local.imebar;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;

import java.util.concurrent.CountDownLatch;
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
 * 加载本 APK 里的 {@link AdbUserService}，我们 bind 上去、跑完一条命令、unbind（remove=true 会触发
 * destroy → 进程退出）。所以整个项目里没有任何 su 通道，也没有"普通身份"回退。
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
    /** 等 UserService 连上的上限（Shizuku 要先 fork 一个进程出来） */
    private static final long CONNECT_WAIT_MS = 5000;

    /** 同一时刻只跑一条命令：并发 bind 同一个 UserService 没意义，还会互相拖慢 */
    private static final Object LOCK = new Object();

    private static Shizuku.UserServiceArgs userServiceArgs;

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

            return runInUserService(cmd);
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
     * bind UserService → 跑命令 → unbind（remove=true 会让 Shizuku 调 destroy，进程随即退出）。
     * 用完即走，不留常驻的 shell 身份进程。
     */
    private static Bundle runInUserService(final String cmd) {
        final CountDownLatch connected = new CountDownLatch(1);
        final IBinder[] serviceBinder = new IBinder[1];
        final ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                serviceBinder[0] = binder;
                connected.countDown();
            }

            public void onServiceDisconnected(ComponentName name) {
                // 连上之后再断开就是命令跑完了（或服务挂了），结果那边自己会报
            }
        };

        try {
            Shizuku.bindUserService(userServiceArgs(), connection);
        } catch (Throwable t) {
            return reply(SERVICE_FAIL, "Shizuku 用户服务起不来：" + brief(t));
        }

        boolean ok;
        try {
            ok = connected.await(CONNECT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            ok = false;
        }
        if (!ok || serviceBinder[0] == null || !serviceBinder[0].pingBinder()) {
            unbind(connection);
            return reply(SERVICE_FAIL, "Shizuku 用户服务起不来（等了 "
                    + (CONNECT_WAIT_MS / 1000) + " 秒没连上）");
        }

        try {
            Bundle result = IAdbUserService.Stub.asInterface(serviceBinder[0]).run(cmd);
            if (result == null) {
                return reply(ERROR, "Shizuku 用户服务没返回结果");
            }
            return result;
        } catch (Throwable t) {
            return reply(ERROR, "执行失败：" + brief(t));
        } finally {
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

    /** 异常压成一行，免得 Toast 太长 */
    private static String brief(Throwable t) {
        String text = String.valueOf(t);
        return text.length() > 100 ? text.substring(0, 100) + "…" : text;
    }
}
