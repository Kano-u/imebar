package com.local.imebar;

import android.content.Context;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;

/**
 * 按钮动作。
 *
 * 除了用户自己配的 `adb`（shell 命令）之外，都是本机操作、不联网。
 * 动作名故意起得短（copy / paste / hide / app / adb ...），方便在设置页里手写。
 */
final class BarActions {

    private static final String TAG = "ImeBar";

    private BarActions() {
    }

    static void run(InputMethodService service, String action, String arg) {
        if (action == null || action.length() == 0) {
            return;
        }
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
            } else if ("home".equals(action)) {
                goHome(service);
            } else if ("text".equals(action)) {
                commitText(service, arg == null ? "" : arg);
            } else if ("app".equals(action)) {
                openApp(service, arg);
            } else if ("url".equals(action)) {
                openUrl(service, arg);
            } else if ("adb".equals(action) || "sh".equals(action)) {
                runAdb(service, arg);
            } else if ("log".equals(action) || "copylog".equals(action)) {
                copyLog(service);
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

    /**
     * 回主页。用桌面的启动入口实现，**不需要 root** —— 是 `input keyevent 3` 的免 root 替代。
     * 输入法作为"当前 IME"是被允许启动 Activity 的，所以在国产 ROM 上一般也能正常回到桌面。
     */
    private static void goHome(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "回主页失败", t);
            RunLog.add("回主页失败: " + t);
        }
    }

    /**
     * 「ADB 命令」：把用户配的那条 shell 命令跑掉。
     *
     * 放在后台线程跑（最多 10 秒），结果写进运行日志、并用 Toast 报一句，
     * 所以不管成功还是失败都能看到发生了什么。
     */
    private static void runAdb(final InputMethodService service, String command) {
        final String cmd = command == null ? "" : command.trim();
        if (cmd.length() == 0) {
            toast(service, "ADB 命令是空的");
            return;
        }
        RunLog.add("ADB 命令: " + cmd);
        new Thread(new Runnable() {
            public void run() {
                AdbCommand.Result result = AdbCommand.run(cmd);
                String detail = result.detail();
                RunLog.add("ADB 结果: " + (detail.length() > 800 ? detail.substring(0, 800) + "…" : detail));
                toastOnMain(service, result.summary());
            }
        }, "imebar-adb").start();
    }

    /** 把输入法进程里的运行日志复制到剪贴板，方便贴出来排查问题 */
    private static void copyLog(InputMethodService service) {
        service.requestHideSelf(0);
        RunLog.add("用户点击了工具栏的「复制日志」");
        boolean ok = RunLog.copyToClipboard(service);
        toast(service, ok ? "日志已复制到剪贴板，去粘贴即可" : "复制失败");
    }

    private static void toastOnMain(final Context context, final String text) {
        try {
            new Handler(context.getMainLooper()).post(new Runnable() {
                public void run() {
                    toast(context, text);
                }
            });
        } catch (Throwable t) {
            toast(context, text);
        }
    }

    private static void toast(Context context, String text) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }
}
