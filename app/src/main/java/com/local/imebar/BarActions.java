package com.local.imebar;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 按钮动作。全部是本机操作，不发网络请求，不执行 shell。 */
final class BarActions {

    private static final String TAG = "ImeBar";

    private BarActions() {
    }

    static void run(InputMethodService service, String action, String arg) {
        if (action == null || action.length() == 0) {
            return;
        }
        // 记一笔：出问题时能从「复制日志」里看出"到底哪一步没执行"
        RunLog.add("执行动作: " + action
                + (arg == null || arg.length() == 0 ? "" : " 参数=" + arg));
        try {
            if ("copy".equals(action)) {
                contextMenu(service, android.R.id.copy, "复制");
            } else if ("cut".equals(action)) {
                contextMenu(service, android.R.id.cut, "剪切");
            } else if ("paste".equals(action)) {
                contextMenu(service, android.R.id.paste, "粘贴");
            } else if ("select_all".equals(action)) {
                contextMenu(service, android.R.id.selectAll, "全选");
            } else if ("clear".equals(action)) {
                clearAll(service);
            } else if ("enter".equals(action)) {
                sendKey(service, KeyEvent.KEYCODE_ENTER);
            } else if ("delete".equals(action)) {
                sendKey(service, KeyEvent.KEYCODE_DEL);
            } else if ("left".equals(action)) {
                sendKey(service, KeyEvent.KEYCODE_DPAD_LEFT);
            } else if ("right".equals(action)) {
                sendKey(service, KeyEvent.KEYCODE_DPAD_RIGHT);
            } else if ("hide".equals(action)) {
                service.requestHideSelf(0);
            } else if ("switch_ime".equals(action)) {
                switchInputMethod(service);
            } else if ("insert_date".equals(action)) {
                commitText(service, new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date()));
            } else if ("insert_time".equals(action)) {
                commitText(service, new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            } else if ("settings".equals(action) || "open_settings".equals(action)
                    || "open_toolbar_settings".equals(action)) {
                openSettings(service);
            } else if ("log".equals(action) || "copylog".equals(action)) {
                copyLog(service);
            } else if ("text".equals(action)) {
                commitText(service, arg == null ? "" : arg);
            } else if ("app".equals(action)) {
                openApp(service, arg);
            } else if ("url".equals(action)) {
                openUrl(service, arg);
            } else {
                Log.w(TAG, "未知动作: " + action);
                RunLog.add("未知动作: " + action);
            }
        } catch (Throwable t) {
            Log.w(TAG, "执行动作失败: " + action, t);
            RunLog.add("执行动作失败 " + action + ": " + t);
        }
    }

    private static void contextMenu(InputMethodService service, int id, String name) {
        InputConnection ic = service.getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        if (!ic.performContextMenuAction(id)) {
            Log.i(TAG, name + " 这个输入框不支持");
        }
    }

    private static void clearAll(InputMethodService service) {
        InputConnection ic = service.getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        ic.performContextMenuAction(android.R.id.selectAll);
        ic.commitText("", 1);
    }

    private static void commitText(InputMethodService service, String text) {
        InputConnection ic = service.getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(text, 1);
        }
    }

    private static void sendKey(InputMethodService service, int keyCode) {
        InputConnection ic = service.getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private static void openApp(InputMethodService service, String pkg) {
        if (pkg == null || pkg.length() == 0) {
            return;
        }
        Intent intent = service.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) {
            Log.i(TAG, "找不到这个 App: " + pkg);
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        service.startActivity(intent);
    }

    private static void openUrl(Context context, String url) {
        if (url == null || url.length() == 0) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** 切到下一个输入法；失败就退化成系统输入法选择器 */
    private static void switchInputMethod(InputMethodService service) {
        try {
            InputMethodManager manager =
                    (InputMethodManager) service.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                Dialog dialog = service.getWindow();
                if (dialog != null && dialog.getWindow() != null) {
                    IBinder token = dialog.getWindow().getDecorView().getWindowToken();
                    if (token != null && manager.switchToNextInputMethod(token, false)) {
                        return;
                    }
                }
                manager.showInputMethodPicker();
            }
        } catch (Throwable t) {
            Log.w(TAG, "切换输入法失败", t);
        }
    }

    /**
     * 打开本模块的设置页。
     *
     * 这里运行在**输入法进程**里，service.getPackageName() 是输入法的包名，
     * 所以只能用模块自己的包名（编译期常量 BuildConfig.APPLICATION_ID，避免手写不一致）。
     *
     * 三条老经验（对齐原版 AI超级工具栏 的写法，它能打开就照它来）：
     *   1) 用 Application Context，而不是 Service 本身；
     *   2) NEW_TASK | CLEAR_TOP | SINGLE_TOP —— 设置页已经在后台任务里时也拉到最前面；
     *   3) 启动后 requestHideSelf(0) 收起键盘，别让键盘挡在设置页前面。
     * 另外：失败必须说出来（Toast + 日志），不能像以前那样"点了没反应"。
     */
    private static void openSettings(InputMethodService service) {
        // 设置页的类名跟 applicationId 同包（清单里就是 .SettingsActivity）
        final String pkg = BuildConfig.APPLICATION_ID;
        final String cls = pkg + ".SettingsActivity";
        RunLog.add("打开设置页：准备启动 " + pkg + "/" + cls);

        final long launchedAt = System.currentTimeMillis();
        Throwable explicitError = startSettings(service, explicitIntent(pkg, cls), "显式组件");
        if (explicitError == null) {
            hideKeyboard(service);
            confirmOpened(service, launchedAt);
            return;
        }

        Throwable launcherError = startSettings(service, launcherIntent(pkg), "桌面入口");
        if (launcherError == null) {
            hideKeyboard(service);
            confirmOpened(service, launchedAt);
            return;
        }

        RunLog.add("打开设置页失败：显式=" + explicitError + " 桌面入口=" + launcherError);
        toast(service, "打不开设置页，请从桌面图标打开「简易输入法工具栏」");
    }

    /**
     * 发完 startActivity 之后回查一次：问模块 App「设置页刚才起来了吗」。
     *
     * 为什么需要这一步：startActivity 成功返回**不代表**页面真的出来了——系统把后台启动
     * Activity 拦掉时，屏幕上什么都不会发生（用户看到的就是"点了没反应"）。
     * 这里用模块 App 记录的时间戳来判断，真被拦了就明确提示一句，别再让人摸不着头脑。
     */
    private static void confirmOpened(final Context context, final long launchedAt) {
        new Thread(new Runnable() {
            public void run() {
                long[] waits = {1000, 1000, 1500, 2000, 3000};
                for (long wait : waits) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    if (readSettingsOpenedAt(context) >= launchedAt) {
                        RunLog.add("打开设置页: 已确认设置页启动");
                        return;
                    }
                }
                RunLog.add("打开设置页: 没等到设置页启动（很可能是系统拦了后台启动 Activity）");
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        toast(context, "没看到设置页打开（可能被系统限制了后台启动），"
                                + "可从桌面图标打开「简易输入法工具栏」");
                    }
                });
            }
        }, "imebar-settings-check").start();
    }

    private static long readSettingsOpenedAt(Context context) {
        try {
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://" + ConfigProvider.AUTHORITY),
                    ConfigProvider.METHOD_SETTINGS_STATUS, null, null);
            return result == null ? 0L : result.getLong(ConfigProvider.KEY_SETTINGS_OPENED_AT, 0L);
        } catch (Throwable t) {
            Log.w(TAG, "回查设置页状态失败", t);
            return 0L;
        }
    }

    private static Intent explicitIntent(String pkg, String cls) {
        Intent intent = new Intent();
        intent.setClassName(pkg, cls);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    /** 退化成"点桌面图标"那种启动方式：不指定组件，让系统按包名 + MAIN/LAUNCHER 自己解析 */
    private static Intent launcherIntent(String pkg) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setPackage(pkg);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    /** @return null 表示已经发出；否则是失败原因 */
    private static Throwable startSettings(InputMethodService service, Intent intent, String how) {
        try {
            Context context = service.getApplicationContext();
            if (context == null) {
                context = service;
            }
            context.startActivity(intent);
            RunLog.add("打开设置页(" + how + "): 已发出 startActivity");
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "打开设置页失败(" + how + ")", t);
            RunLog.add("打开设置页(" + how + ")失败: " + t);
            return t;
        }
    }

    private static void hideKeyboard(InputMethodService service) {
        try {
            service.requestHideSelf(0);
        } catch (Throwable t) {
            Log.w(TAG, "收起键盘失败", t);
        }
    }

    private static void toast(Context context, String text) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    /** 把输入法进程里的运行日志复制到剪贴板，方便贴出来排查问题 */
    private static void copyLog(InputMethodService service) {
        service.requestHideSelf(0);
        RunLog.add("用户点击了工具栏的「复制日志」");
        boolean ok = RunLog.copyToClipboard(service);
        try {
            Toast.makeText(service, ok ? "日志已复制到剪贴板，去粘贴即可" : "复制失败",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }
}
