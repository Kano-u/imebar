package com.local.imebar;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块配置：一个不可变的值对象。
 *
 * 它有两种来源，都要产出同样的字段：
 *   1) fromPrefs()  —— 模块 App 自己的 SharedPreferences("config")；
 *   2) fromBundle() —— 设置页保存后通过广播直接推送给输入法进程的数值。
 *
 * 为什么要有 (2)：输入法进程里读到的"远程偏好"（libxposed getRemotePreferences）
 * 是内存快照，LSPosed 侧还可能缓存这个对象，改了设置重读也拿不到新值。
 * 所以改设置时干脆把数值本身放进广播送过去，输入法收到就直接用，不再依赖读取。
 */
public final class BarConfig {

    public static final String GROUP = "config";
    public static final String MODULE_PKG = "com.local.imebar";

    /** 设置页保存后发的广播：里面带着配置的数值 */
    public static final String ACTION_CONFIG_CHANGED = "com.local.imebar.action.CONFIG_CHANGED";

    // 显示
    public static final String KEY_ENABLED = "bar_enabled";
    public static final String KEY_POSITION = "bar_position";          // bottom | top
    public static final String KEY_EDGE_DISTANCE = "edge_distance_dp";
    public static final String KEY_SIDE_MARGIN = "side_margin_dp";
    public static final String KEY_TEXT_SIZE = "text_size_sp";
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

    private final boolean enabled;
    private final boolean bottom;
    private final int edgeDistance;
    private final int sideMargin;
    private final int textSize;
    private final int opacity;
    private final boolean pill;
    private final boolean stretch;
    private final String textColorHex;
    private final String pillColorHex;
    private final String barBackgroundHex;
    private final String buttonsRaw;
    private final List<Button> buttons;

    private BarConfig(boolean enabled, boolean bottom, int edgeDistance, int sideMargin, int textSize,
                      int opacity, boolean pill, boolean stretch, String textColorHex,
                      String pillColorHex, String barBackgroundHex, String buttonsRaw) {
        this.enabled = enabled;
        this.bottom = bottom;
        this.edgeDistance = clamp(edgeDistance, 0, 60);
        this.sideMargin = clamp(sideMargin, 0, 60);
        this.textSize = clamp(textSize, 10, 24);
        this.opacity = clamp(opacity, 20, 100);
        this.pill = pill;
        this.stretch = stretch;
        this.textColorHex = safe(textColorHex, DEF_TEXT_COLOR);
        this.pillColorHex = safe(pillColorHex, DEF_PILL_BG);
        this.barBackgroundHex = safe(barBackgroundHex, DEF_BAR_BG);
        this.buttonsRaw = (buttonsRaw == null || buttonsRaw.trim().length() == 0)
                ? DEFAULT_BUTTONS : buttonsRaw;
        this.buttons = parseButtons(this.buttonsRaw);
    }

    // ---------- 两个来源 ----------

    public static BarConfig fromPrefs(SharedPreferences prefs) {
        if (prefs == null) {
            return defaults();
        }
        return new BarConfig(
                getBoolean(prefs, KEY_ENABLED, true),
                !POSITION_TOP.equals(getString(prefs, KEY_POSITION, POSITION_BOTTOM)),
                getInt(prefs, KEY_EDGE_DISTANCE, DEF_EDGE_DISTANCE),
                getInt(prefs, KEY_SIDE_MARGIN, DEF_SIDE_MARGIN),
                getInt(prefs, KEY_TEXT_SIZE, DEF_TEXT_SIZE),
                getInt(prefs, KEY_OPACITY, DEF_OPACITY),
                STYLE_PILL.equals(getString(prefs, KEY_STYLE, STYLE_TEXT)),
                !LAYOUT_LEFT.equals(getString(prefs, KEY_LAYOUT, LAYOUT_STRETCH)),
                getString(prefs, KEY_TEXT_COLOR, DEF_TEXT_COLOR),
                getString(prefs, KEY_PILL_BG, DEF_PILL_BG),
                getString(prefs, KEY_BAR_BG, DEF_BAR_BG),
                getString(prefs, KEY_BUTTONS, DEFAULT_BUTTONS));
    }

    public static BarConfig fromBundle(Bundle bundle) {
        if (bundle == null) {
            return defaults();
        }
        return new BarConfig(
                bundle.getBoolean(KEY_ENABLED, true),
                !POSITION_TOP.equals(bundle.getString(KEY_POSITION, POSITION_BOTTOM)),
                bundle.getInt(KEY_EDGE_DISTANCE, DEF_EDGE_DISTANCE),
                bundle.getInt(KEY_SIDE_MARGIN, DEF_SIDE_MARGIN),
                bundle.getInt(KEY_TEXT_SIZE, DEF_TEXT_SIZE),
                bundle.getInt(KEY_OPACITY, DEF_OPACITY),
                STYLE_PILL.equals(bundle.getString(KEY_STYLE, STYLE_TEXT)),
                !LAYOUT_LEFT.equals(bundle.getString(KEY_LAYOUT, LAYOUT_STRETCH)),
                bundle.getString(KEY_TEXT_COLOR, DEF_TEXT_COLOR),
                bundle.getString(KEY_PILL_BG, DEF_PILL_BG),
                bundle.getString(KEY_BAR_BG, DEF_BAR_BG),
                bundle.getString(KEY_BUTTONS, DEFAULT_BUTTONS));
    }

    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putBoolean(KEY_ENABLED, enabled);
        bundle.putString(KEY_POSITION, bottom ? POSITION_BOTTOM : POSITION_TOP);
        bundle.putInt(KEY_EDGE_DISTANCE, edgeDistance);
        bundle.putInt(KEY_SIDE_MARGIN, sideMargin);
        bundle.putInt(KEY_TEXT_SIZE, textSize);
        bundle.putInt(KEY_OPACITY, opacity);
        bundle.putString(KEY_STYLE, pill ? STYLE_PILL : STYLE_TEXT);
        bundle.putString(KEY_LAYOUT, stretch ? LAYOUT_STRETCH : LAYOUT_LEFT);
        bundle.putString(KEY_TEXT_COLOR, textColorHex);
        bundle.putString(KEY_PILL_BG, pillColorHex);
        bundle.putString(KEY_BAR_BG, barBackgroundHex);
        bundle.putString(KEY_BUTTONS, buttonsRaw);
        return bundle;
    }

    public static BarConfig defaults() {
        return new BarConfig(true, true, DEF_EDGE_DISTANCE, DEF_SIDE_MARGIN, DEF_TEXT_SIZE,
                DEF_OPACITY, false, true, DEF_TEXT_COLOR, DEF_PILL_BG, DEF_BAR_BG, DEFAULT_BUTTONS);
    }

    // ---------- 取值 ----------

    public boolean enabled() {
        return enabled;
    }

    public boolean isBottom() {
        return bottom;
    }

    /** 工具栏距键盘那一条边缘的高度（位置=底部时是底边，位置=顶部时是顶边） */
    public int edgeDistanceDp() {
        return edgeDistance;
    }

    public int sideMarginDp() {
        return sideMargin;
    }

    public int textSizeSp() {
        return textSize;
    }

    public int opacityPercent() {
        return opacity;
    }

    public boolean isPill() {
        return pill;
    }

    public boolean isStretch() {
        return stretch;
    }

    public String textColorHex() {
        return textColorHex;
    }

    public String pillColorHex() {
        return pillColorHex;
    }

    public String barBackgroundHex() {
        return barBackgroundHex;
    }

    public int textColor() {
        return parseColor(textColorHex, 0xFF202124);
    }

    public int pillColor() {
        return parseColor(pillColorHex, 0xFFF2F3F5);
    }

    public int barBackgroundColor() {
        return parseColor(barBackgroundHex, Color.TRANSPARENT);
    }

    public String buttonsRaw() {
        return buttonsRaw;
    }

    public List<Button> buttons() {
        return buttons;
    }

    /** 用来判断配置有没有变，变了就重建工具栏 */
    public String signature() {
        return enabled + "|" + bottom + "|" + edgeDistance + "|" + sideMargin + "|" + textSize
                + "|" + opacity + "|" + pill + "|" + stretch + "|" + textColorHex + "|"
                + pillColorHex + "|" + barBackgroundHex + "|" + buttonsRaw;
    }

    // ---------- 解析 ----------

    private static List<Button> parseButtons(String raw) {
        List<Button> list = new ArrayList<>();
        for (String line : raw.split("\n")) {
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

    // ---------- 小工具 ----------

    private static boolean getBoolean(SharedPreferences prefs, String key, boolean def) {
        try {
            return prefs.getBoolean(key, def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static int getInt(SharedPreferences prefs, String key, int def) {
        try {
            return prefs.getInt(key, def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static String getString(SharedPreferences prefs, String key, String def) {
        try {
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

    private static String safe(String value, String def) {
        return value == null ? def : value;
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
