package com.local.imebar;

import android.content.SharedPreferences;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块配置。数据存在"模块 App"自己的 SharedPreferences("config") 里，
 * 输入法进程通过 libxposed 的 getRemotePreferences("config") 读到同一份内容。
 *
 * 按钮格式（一行一个）：显示文字|动作|参数
 *   - 普通按钮  复制|copy
 *   - 带参数    插入地址|text|广东省深圳市xx路1号
 *   - 菜单按钮  更多|menu|切输入法=switch_ime;插入日期=insert_date
 *     菜单项之间用 ; 分隔，每项是 文字=动作 或 文字=动作=参数
 */
public final class BarConfig {

    public static final String GROUP = "config";
    public static final String MODULE_PKG = "com.local.imebar";

    public static final String KEY_ENABLED = "bar_enabled";
    public static final String KEY_HEIGHT = "bar_height_dp";
    public static final String KEY_BAR_BG = "bar_bg_color";
    public static final String KEY_PILL_BG = "pill_bg_color";
    public static final String KEY_TEXT = "text_color";
    public static final String KEY_BUTTONS = "buttons";

    public static final int DEFAULT_HEIGHT_DP = 46;
    /** 默认全透明：让输入法自己的背景透出来，整条栏"没有存在感" */
    public static final String DEFAULT_BAR_BG = "#00000000";
    /** 胶囊按钮底色：接近白，键盘上是浅灰背景时观感最接近系统键盘 */
    public static final String DEFAULT_PILL_BG = "#F2F3F5";
    public static final String DEFAULT_TEXT = "#202124";

    public static final String DEFAULT_BUTTONS =
            "复制|copy\n"
                    + "粘贴|paste\n"
                    + "全选|select_all\n"
                    + "更多|menu|切输入法=switch_ime;插入日期=insert_date;插入时间=insert_time;收起键盘=hide;打开设置=settings";

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
        if (value < 30) {
            value = 30;
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

    public int barBackgroundColor() {
        return parseColor(KEY_BAR_BG, DEFAULT_BAR_BG, Color.TRANSPARENT);
    }

    public int pillColor() {
        return parseColor(KEY_PILL_BG, DEFAULT_PILL_BG, 0xFFF2F3F5);
    }

    public int textColor() {
        return parseColor(KEY_TEXT, DEFAULT_TEXT, 0xFF202124);
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
            List<Item> items = "menu".equals(action) ? parseMenu(arg) : null;
            if (items != null && items.isEmpty()) {
                continue; // 菜单里一条都没有，就不显示这个按钮
            }
            list.add(new Button(label, action, arg, items));
        }
        return list;
    }

    private static List<Item> parseMenu(String raw) {
        List<Item> items = new ArrayList<>();
        if (raw == null) {
            return items;
        }
        for (String part : raw.split(";")) {
            if (part == null) {
                continue;
            }
            String text = part.trim();
            if (text.length() == 0) {
                continue;
            }
            String[] pieces = text.split("=", 3);
            String label = pieces[0].trim();
            String action = pieces.length > 1 ? pieces[1].trim() : "";
            String arg = pieces.length > 2 ? pieces[2].trim() : "";
            if (label.length() == 0) {
                label = action;
            }
            if (label.length() == 0 && action.length() == 0) {
                continue;
            }
            items.add(new Item(label, action, arg));
        }
        return items;
    }

    /** 用来判断配置有没有变，变了就重建工具栏 */
    public String signature() {
        return enabled() + "|" + heightDp() + "|" + barBackgroundColor() + "|"
                + pillColor() + "|" + textColor() + "|" + buttonsRaw();
    }

    public static final class Button {
        public final String label;
        public final String action;
        public final String arg;
        /** 只有当 action 是 "menu" 时非空 */
        public final List<Item> menuItems;

        Button(String label, String action, String arg, List<Item> menuItems) {
            this.label = label;
            this.action = action;
            this.arg = arg;
            this.menuItems = menuItems;
        }
    }

    public static final class Item {
        public final String label;
        public final String action;
        public final String arg;

        Item(String label, String action, String arg) {
            this.label = label;
            this.action = action;
            this.arg = arg;
        }
    }
}
