package com.local.imebar;

import android.content.SharedPreferences;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块配置。
 *
 * 真正的数据存在"模块 App"自己的 SharedPreferences("config") 里；
 * 输入法进程通过 libxposed 的 getRemotePreferences("config") 读到同一份内容，
 * 所以设置页改完，输入法进程也能看到（不用给存储权限，也不用 root）。
 */
public final class BarConfig {

    public static final String GROUP = "config";
    public static final String MODULE_PKG = "com.local.imebar";

    public static final String KEY_ENABLED = "bar_enabled";
    public static final String KEY_HEIGHT = "bar_height_dp";
    public static final String KEY_BG = "bar_bg_color";
    public static final String KEY_FG = "bar_fg_color";
    public static final String KEY_BUTTONS = "buttons";

    public static final int DEFAULT_HEIGHT_DP = 44;
    public static final String DEFAULT_BG = "#E6212121";
    public static final String DEFAULT_FG = "#FFFFFFFF";

    public static final String DEFAULT_BUTTONS =
            "复制|copy\n"
                    + "剪切|cut\n"
                    + "粘贴|paste\n"
                    + "全选|select_all\n"
                    + "清空|clear\n"
                    + "回车|enter\n"
                    + "收起键盘|hide";

    private final SharedPreferences prefs;

    public BarConfig(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public boolean enabled() {
        try {
            return prefs == null || prefs.getBoolean(KEY_ENABLED, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public int heightDp() {
        int value = DEFAULT_HEIGHT_DP;
        try {
            if (prefs != null) {
                value = prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT_DP);
            }
        } catch (Throwable ignored) {
        }
        if (value < 24) {
            value = 24;
        }
        if (value > 96) {
            value = 96;
        }
        return value;
    }

    public String buttonsRaw() {
        try {
            if (prefs != null) {
                String value = prefs.getString(KEY_BUTTONS, null);
                if (value != null && value.trim().length() > 0) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_BUTTONS;
    }

    public int backgroundColor() {
        return parseColor(KEY_BG, DEFAULT_BG, 0xE6212121);
    }

    public int textColor() {
        return parseColor(KEY_FG, DEFAULT_FG, Color.WHITE);
    }

    private int parseColor(String key, String def, int fallback) {
        try {
            if (prefs != null) {
                String value = prefs.getString(key, def);
                if (value != null && value.trim().length() > 0) {
                    return Color.parseColor(value.trim());
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** 按钮列表；每行格式：显示文字|动作|参数（参数可省略） */
    public List<Button> buttons() {
        List<Button> list = new ArrayList<>();
        for (String line : buttonsRaw().split("\n")) {
            if (line == null) {
                continue;
            }
            String text = line.trim();
            if (text.length() == 0 || text.startsWith("#")) {
                continue;
            }
            String[] parts = text.split("\\|", 3);
            String label = parts[0].trim();
            String action = parts.length > 1 ? parts[1].trim() : "";
            String arg = parts.length > 2 ? parts[2].trim() : "";
            if (label.length() == 0) {
                label = action;
            }
            if (label.length() == 0) {
                continue;
            }
            list.add(new Button(label, action, arg));
        }
        return list;
    }

    /** 用来判断配置有没有变，变了就重建工具栏 */
    public String signature() {
        return enabled() + "|" + heightDp() + "|" + backgroundColor() + "|" + textColor() + "|" + buttonsRaw();
    }

    public static final class Button {
        public final String label;
        public final String action;
        public final String arg;

        Button(String label, String action, String arg) {
            this.label = label;
            this.action = action;
            this.arg = arg;
        }
    }
}
