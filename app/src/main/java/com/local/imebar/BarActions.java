package com.local.imebar;

import android.content.Context;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;

/**
 * 按钮动作。
 *
 * 除了用户自己配的 `adb`（走 Shizuku 的 shell 命令）之外，都是本机操作、不联网。
 * 动作名故意起得短（copy / paste / hide / app / adb ...），方便在设置页里手写。
 * 项目里已经没有日志了：动作出问题一律当场弹一句 Toast，不静默失败。
 */
final class BarActions {

    private BarActions() {
    }

    static void run(InputMethodService service, String action, String arg) {
        if (action == null || action.length() == 0) {
            return;
        }
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
            } else if ("text".equals(action)) {
                commitText(service, arg == null ? "" : arg);
            } else if ("app".equals(action)) {
                openApp(service, arg);
            } else if ("url".equals(action)) {
                openUrl(service, arg);
            } else if ("adb".equals(action) || "sh".equals(action)) {
                runAdb(service, arg);
            } else {
                toast(service, "未知动作：" + action);
            }
        } catch (Throwable t) {
            // 没有日志可看了：出问题只能当场用 Toast 说清楚
            toast(service, "执行失败：" + action + "，" + reason(t));
        }
    }

    private static void contextMenu(InputMethodService service, int id, String name) {
        InputConnection ic = service.getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        if (!ic.performContextMenuAction(id)) {
            toast(service, "这个输入框不支持" + name);
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
            toast(service, "找不到这个 App：" + pkg);
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
     * 「ADB 命令」：把用户配的那条 shell 命令跑掉。
     *
     * 命令不在这个进程里跑：Shizuku 只在模块 App 进程里有（binder 只会投给声明了
     * ShizukuProvider 的包），所以这里只是后台线程跨进程把命令交给 App，
     * 由 Shizuku 的 UserService 以 shell/root 身份执行。
     *
     * **只有失败才提示**：命令干了什么自己看得见；失败时 App 会把原因写在 message 里，
     * 这里原样加个前缀弹出去。
     */
    private static void runAdb(final InputMethodService service, String command) {
        final String cmd = command == null ? "" : command.trim();
        if (cmd.length() == 0) {
            toast(service, "ADB 命令是空的");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                String failure = sendAdb(service, cmd);
                if (failure != null) {
                    toastOnMain(service, "ADB 失败：" + failure);
                }
            }
        }, "imebar-adb").start();
    }

    /** @return 失败原因；成功（status = OK）返回 null */
    private static String sendAdb(Context context, String cmd) {
        Bundle reply = null;
        try {
            Bundle request = new Bundle();
            request.putString(AdbBridge.KEY_CMD, cmd);
            reply = context.getContentResolver().call(
                    Uri.parse("content://" + ConfigProvider.AUTHORITY),
                    AdbBridge.METHOD_RUN_ADB, null, request);
        } catch (Throwable ignored) {
            // App 被强制停止/没起来时调用会抛，下面统一报"没响应"
        }
        if (reply == null) {
            return "模块 App 没响应（可能刚被系统停止，打开一次 imebar 再试）";
        }
        int status = reply.getInt(AdbBridge.KEY_STATUS, AdbBridge.ERROR);
        if (status == AdbBridge.OK) {
            return null;
        }
        String message = reply.getString(AdbBridge.KEY_MESSAGE);
        return message == null || message.length() == 0 ? "未知错误" : message;
    }

    /** 异常原因压成一行，免得 Toast 太长 */
    private static String reason(Throwable t) {
        String text = String.valueOf(t);
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
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
