package com.local.imebar;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodService;

/** 按钮动作。全部是本机操作，不发网络请求，不执行 shell。 */
final class BarActions {

    private static final String TAG = "ImeBar";

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
            } else {
                Log.w(TAG, "未知动作: " + action);
            }
        } catch (Throwable t) {
            Log.w(TAG, "执行动作失败: " + action, t);
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
}
