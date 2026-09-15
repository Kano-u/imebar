package com.local.imebar;

import android.content.SharedPreferences;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块配置。数据存在"模块 App"自己的 SharedPreferences("config") 里，
 * 输入法进程通过 libxposed 的 getRemotePreferences("config") 读到同一份内容。
 *
 * 坐标参照的是"输入法窗口"本身，不是屏幕：
 *   位置     = 键盘底部（默认）/ 键盘顶部
 *   底部距离 = 工具栏距键盘那一条边缘的高度
 *   左右边距 = 工具栏两侧与窗口边缘的距离
 */
public final class BarConfig {

    public static final String GROUP = "config";
    public static final String MODULE_PKG = "com.local.imebar";

    // 显示
    public static final String KEY_ENABLED = "bar_enabled";
    public static final String KEY_POSITION = "bar_position";          // bottom | top
    public static final String KEY_EDGE_DISTANCE = "edge_distance_dp";
    public static final String KEY_SIDE_MARGIN = "side_margin_dp";
    public static final String KEY_TEXT_SIZE = "text_size_sp";
    public static final String KEY_BUTTON_GAP = "button_gap_dp";
    public static final String KEY_OPACITY = "opacity_percent";

    // 样式
    public static final String KEY_STYLE = "button_style";             // text | pill
    public static final String KEY_LAYOUT = "button_layout";           // stretch | left
    public static final String KEY_TEXT_COLOR = "text_color";
    public static final String KEY_PILL_BG = "pill_bg_color";
    public static final String KEY_BAR_BG = "bar_bg_color";

    // 按钮
    public static final String KEY_BUTTONS = "buttons";

    public static final String POSITION_BOTTOM = "bottom";
    public static final String POSITION_TOP = "top";
    public static final String STYLE_TEXT = "text";
    public static final String STYLE_PILL = "pill";
    public static final String LAYOUT_STRETCH = "stretch";
    public static final String LAYOUT_LEFT = "left";

    public static final int DEF_EDGE_DISTANCE = 12;
    public static final int DEF_SIDE_MARGIN = 15;
    public static final int DEF_TEXT_SIZE = 12;
    public static final int DEF_BUTTON_GAP = 8;
    public static final int DEF_OPACITY = 80;

    public static final String DEF_TEXT_COLOR = "#202124";
    public static final String DEF_PILL_BG = "#F2F3F5";
    public static final String DEF_BAR_BG = "#00000000";

    public static final String DEFAULT_BUTTONS =
            "复制|copy\n"
                    + "粘贴|paste\n"
                    + "全选|select_all\n"
                    + "收起键盘|hide\n"
                    + "更多|menu|切输入法=switch_ime;插入日期=insert_date;插入时间=insert_time;打开设置=settings";

    private final SharedPreferences prefs;

    public BarConfig(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    // ---------- 显示 ----------

    public boolean enabled() {
        return getBoolean(KEY_ENABLED, true);
    }

    public boolean isBottom() {
        return !POSITION_TOP.equals(getString(KEY_POSITION, POSITION_BOTTOM));
    }

    /** 工具栏距键盘那一条边缘的高度（位置=底部时是底边，位置=顶部时是顶边） */
    public int edgeDistanceDp() {
        return clamp(getInt(KEY_EDGE_DISTANCE, DEF_EDGE_DISTANCE), 0, 60);
    }

    public int sideMarginDp() {
        return clamp(getInt(KEY_SIDE_MARGIN, DEF_SIDE_MARGIN), 0, 60);
    }

    public int textSizeSp() {
        return clamp(getInt(KEY_TEXT_SIZE, DEF_TEXT_SIZE), 10, 24);
    }

    public int buttonGapDp() {
        return clamp(getInt(KEY_BUTTON_GAP, DEF_BUTTON_GAP), 0, 30);
    }

    public int opacityPercent() {
        return clamp(getInt(KEY_OPACITY, DEF_OPACITY), 20, 100);
    }

    // ---------- 样式 ----------

    public boolean isPill() {
        return STYLE_PILL.equals(getString(KEY_STYLE, STYLE_TEXT));
    }

    public boolean isStretch() {
        return !LAYOUT_LEFT.equals(getString(KEY_LAYOUT, LAYOUT_STRETCH));
    }

    public String textColorHex() {
        return getString(KEY_TEXT_COLOR, DEF_TEXT_COLOR);
    }

    public String pillColorHex() {
        return getString(KEY_PILL_BG, DEF_PILL_BG);
    }

    public String barBackgroundHex() {
        return getString(KEY_BAR_BG, DEF_BAR_BG);
    }

    public int textColor() {
        return parseColor(textColorHex(), 0xFF202124);
    }

    public int pillColor() {
        return parseColor(pillColorHex(), 0xFFF2F3F5);
    }

    public int barBackgroundColor() {
        return parseColor(barBackgroundHex(), Color.TRANSPARENT);
    }

    // ---------- 按钮 ----------

    public String buttonsRaw() {
        String value = getString(KEY_BUTTONS, null);
        if (value != null && value.trim().length() > 0) {
            return value;
        }
        return DEFAULT_BUTTONS;
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
                continue;
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
        return enabled() + "|" + isBottom() + "|" + edgeDistanceDp() + "|" + sideMarginDp()
                + "|" + textSizeSp() + "|" + buttonGapDp() + "|" + opacityPercent()
                + "|" + isPill() + "|" + isStretch() + "|" + textColorHex() + "|" + pillColorHex()
                + "|" + barBackgroundHex() + "|" + buttonsRaw();
    }

    // ---------- 读写小工具（远程偏好偶尔会抛异常，统一兜住） ----------

    private boolean getBoolean(String key, boolean def) {
        try {
            return prefs == null || prefs.getBoolean(key, def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private int getInt(String key, int def) {
        try {
            return prefs == null ? def : prefs.getInt(key, def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private String getString(String key, String def) {
        try {
            if (prefs == null) {
                return def;
            }
            String value = prefs.getString(key, def);
            return value == null ? def : value;
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static int parseColor(String value, int fallback) {
        try {
            if (value != null && value.trim().length() > 0) {
                return Color.parseColor(value.trim());
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
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
