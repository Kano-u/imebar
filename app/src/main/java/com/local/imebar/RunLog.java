package com.local.imebar;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 极简运行日志：每个进程各自保留一份环形缓冲。
 *
 * 用途：排查"设置改了但界面没变"这类跨进程问题——直接把现场信息复制出来。
 * 输入法进程那份用工具栏按钮「复制日志」取；模块 App 那份用设置页的「复制日志」取。
 */
public final class RunLog {

    private static final int MAX_LINES = 300;
    private static final ArrayDeque<String> LINES = new ArrayDeque<String>();
    private static final SimpleDateFormat FORMAT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private RunLog() {
    }

    public static void add(String message) {
        String time;
        synchronized (FORMAT) {
            time = FORMAT.format(new Date());
        }
        synchronized (LINES) {
            LINES.addLast(time + "  " + message);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
    }

    public static String dump() {
        StringBuilder builder = new StringBuilder();
        builder.append("imebar ").append(BuildConfig.VERSION_NAME)
                .append("  Android ").append(Build.VERSION.RELEASE)
                .append("  pid=").append(android.os.Process.myPid())
                .append('\n');
        synchronized (LINES) {
            for (String line : LINES) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    public static boolean copyToClipboard(Context context) {
        try {
            ClipboardManager manager =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) {
                return false;
            }
            manager.setPrimaryClip(ClipData.newPlainText("imebar-log", dump()));
            return true;
        } catch (Throwable t) {
            add("复制日志失败: " + t);
            return false;
        }
    }
}
