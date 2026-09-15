package com.local.imebar;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块配置：一个不可变的值对象。
 *
 * 两个来源，产出的字段完全一样：
 *   1) fromPrefs()  —— 模块 App 自己的 SharedPreferences("config")；
 *   2) fromBundle() —— 设置页保存后，把数值本身推给输入法进程的广播。
 *
 * 为什么非要 (2)：libxposed 的"远程偏好"是内存快照（只在创建时取一次），
 * 改了设置重读也拿不到新值；而广播里带的是**数值本身**，收到就能直接用。
 *
 * 位置固定键盘底部、按钮固定纯文字、排列固定均分铺满——都不做成可选项，
 * 所以这里没有这几项字段，也就没有对应的分支。
 */
public final class BarConfig {

    public static final String GROUP = "config";
    public static final String MODULE_PKG = "com.local.imebar";

    /** 设置页保存后发的广播：里面带着配置的数值 */
    public static final String ACTION_CONFIG_CHANGED = "com.local.imebar.action.CONFIG_CHANGED";

    // 显示
    public static final String KEY_ENABLED = "bar_enabled";
    public static final String KEY_EDGE_DISTANCE = "edge_distance_dp";
    public static final String KEY_SIDE_MARGIN = "side_margin_dp";
    public static final String KEY_TEXT_SIZE = "text_size_sp";
    public static final String KEY_OPACITY = "opacity_percent";

    // 配色
    public static final String KEY_TEXT_COLOR = "text_color";
    public static final String KEY_BAR_BG = "bar_bg_color";

    // 按钮
    public static final String KEY_BUTTONS = "buttons";

    public static final int DEF_EDGE_DISTANCE = 0;
    public static final int DEF_SIDE_MARGIN = 50;
    public static final int DEF_TEXT_SIZE = 10;
    public static final int DEF_OPACITY = 65;

    /** 文字大小可调范围（下限 5dp） */
    public static final int MIN_TEXT_SIZE = 5;
    public static final int MAX_TEXT_SIZE = 24;

    public static final String DEF_TEXT_COLOR = "#202124";
    public static final String DEF_BAR_BG = "#00000000";

    /**
     * 默认按钮：JSON 数组。字段是 label（显示文字）/ action（动作）/ arg（可选参数）/ menu（菜单子项）。
     * 整行 // 开头是注释（JSON 本身不支持注释，解析前会先剔除），最后一行就是 adb 的示例。
     */
    public static final String DEFAULT_BUTTONS =
            "[\n"
                    + "  {\"label\": \"复制\", \"action\": \"copy\"},\n"
                    + "  {\"label\": \"粘贴\", \"action\": \"paste\"},\n"
                    + "  {\"label\": \"全选\", \"action\": \"select_all\"},\n"
                    + "  {\"label\": \"收起键盘\", \"action\": \"hide\"},\n"
                    + "  {\"label\": \"复制日志\", \"action\": \"log\"}\n"
                    + "  // {\"label\": \"截屏\", \"action\": \"adb\", \"arg\": \"screencap -p /sdcard/imebar.png\"}\n"
                    + "]";

    private final boolean enabled;
    private final int edgeDistance;
    private final int sideMargin;
    private final int textSize;
    private final int opacity;
    private final String textColorHex;
    private final String barBackgroundHex;
    private final String buttonsRaw;
    private final List<Button> buttons;

    private BarConfig(boolean enabled, int edgeDistance, int sideMargin, int textSize, int opacity,
                      String textColorHex, String barBackgroundHex, String buttonsRaw) {
        this.enabled = enabled;
        this.edgeDistance = clamp(edgeDistance, 0, 60);
        this.sideMargin = clamp(sideMargin, 0, 60);
        this.textSize = clamp(textSize, MIN_TEXT_SIZE, MAX_TEXT_SIZE);
        this.opacity = clamp(opacity, 20, 100);
        this.textColorHex = safe(textColorHex, DEF_TEXT_COLOR);
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
                getInt(prefs, KEY_EDGE_DISTANCE, DEF_EDGE_DISTANCE),
                getInt(prefs, KEY_SIDE_MARGIN, DEF_SIDE_MARGIN),
                getInt(prefs, KEY_TEXT_SIZE, DEF_TEXT_SIZE),
                getInt(prefs, KEY_OPACITY, DEF_OPACITY),
                getString(prefs, KEY_TEXT_COLOR, DEF_TEXT_COLOR),
                getString(prefs, KEY_BAR_BG, DEF_BAR_BG),
                getString(prefs, KEY_BUTTONS, DEFAULT_BUTTONS));
    }

    public static BarConfig fromBundle(Bundle bundle) {
        if (bundle == null) {
            return defaults();
        }
        return new BarConfig(
                bundle.getBoolean(KEY_ENABLED, true),
                bundle.getInt(KEY_EDGE_DISTANCE, DEF_EDGE_DISTANCE),
                bundle.getInt(KEY_SIDE_MARGIN, DEF_SIDE_MARGIN),
                bundle.getInt(KEY_TEXT_SIZE, DEF_TEXT_SIZE),
                bundle.getInt(KEY_OPACITY, DEF_OPACITY),
                bundle.getString(KEY_TEXT_COLOR, DEF_TEXT_COLOR),
                bundle.getString(KEY_BAR_BG, DEF_BAR_BG),
                bundle.getString(KEY_BUTTONS, DEFAULT_BUTTONS));
    }

    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putBoolean(KEY_ENABLED, enabled);
        bundle.putInt(KEY_EDGE_DISTANCE, edgeDistance);
        bundle.putInt(KEY_SIDE_MARGIN, sideMargin);
        bundle.putInt(KEY_TEXT_SIZE, textSize);
        bundle.putInt(KEY_OPACITY, opacity);
        bundle.putString(KEY_TEXT_COLOR, textColorHex);
        bundle.putString(KEY_BAR_BG, barBackgroundHex);
        bundle.putString(KEY_BUTTONS, buttonsRaw);
        return bundle;
    }

    public static BarConfig defaults() {
        return new BarConfig(true, DEF_EDGE_DISTANCE, DEF_SIDE_MARGIN, DEF_TEXT_SIZE,
                DEF_OPACITY, DEF_TEXT_COLOR, DEF_BAR_BG, DEFAULT_BUTTONS);
    }

    // ---------- 取值 ----------

    public boolean enabled() {
        return enabled;
    }

    /** 工具栏距键盘底边的高度 */
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

    public String textColorHex() {
        return textColorHex;
    }

    public String barBackgroundHex() {
        return barBackgroundHex;
    }

    public int textColor() {
        return parseColor(textColorHex, 0xFF202124);
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
        return enabled + "|" + edgeDistance + "|" + sideMargin + "|" + textSize
                + "|" + opacity + "|" + textColorHex + "|" + barBackgroundHex + "|" + buttonsRaw;
    }

    /** 一行摘要，写日志用 */
    public String summary() {
        return "距离" + edgeDistance + " 边距" + sideMargin + " 字号" + textSize
                + " 透明度" + opacity + " 按钮数=" + buttons.size();
    }

    // ---------- 解析 ----------

    /**
     * 解析按钮配置（JSON 数组）。
     * 解析失败或者一个按钮都没有，就退回默认按钮——这段代码也在输入法进程里跑，
     * 宁可显示默认按钮，也不能让工具栏空着或者抛异常出来。
     */
    private static List<Button> parseButtons(String raw) {
        List<Button> buttons = parseJson(stripCommentLines(raw));
        if (!buttons.isEmpty()) {
            return buttons;
        }
        RunLog.add("按钮配置不是合法 JSON（或没有按钮），已改用默认按钮");
        return parseJson(stripCommentLines(DEFAULT_BUTTONS));
    }

    /** 整行以 // 开头的是注释：JSON 不支持注释，默认值里那条 adb 示例就是这么写的 */
    private static String stripCommentLines(String raw) {
        StringBuilder text = new StringBuilder();
        for (String line : raw.split("\n")) {
            if (!line.trim().startsWith("//")) {
                text.append(line).append('\n');
            }
        }
        return text.toString();
    }

    /** @return 解析出来的按钮；出任何问题都返回空列表（不往外抛） */
    private static List<Button> parseJson(String json) {
        List<Button> list = new ArrayList<Button>();
        String text = json.trim();
        if (text.length() == 0) {
            return list;
        }
        try {
            JSONArray array = new JSONArray(text);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) {
                    continue;   // 不是对象的元素直接跳过
                }
                Button button = toButton(obj);
                if (button != null) {
                    list.add(button);
                }
            }
        } catch (Throwable t) {
            RunLog.add("按钮 JSON 解析失败: " + t);
            list.clear();
        }
        return list;
    }

    private static Button toButton(JSONObject obj) {
        Item item = toItem(obj);
        if (item == null) {
            return null;
        }
        if (!"menu".equals(item.action)) {
            return new Button(item.label, item.action, item.arg, null);
        }
        List<Item> items = toMenuItems(obj.optJSONArray("menu"));
        // 菜单里一条都没有，这个按钮没意义
        return items.isEmpty() ? null : new Button(item.label, item.action, item.arg, items);
    }

    /** 一个 JSON 对象 → 一条 label/action/arg。label 没写就用 action 顶上，都空返回 null */
    private static Item toItem(JSONObject obj) {
        String label = field(obj, "label");
        String action = field(obj, "action");
        if (label.length() == 0) {
            label = action;
        }
        return label.length() == 0 ? null : new Item(label, action, field(obj, "arg"));
    }

    private static List<Item> toMenuItems(JSONArray array) {
        List<Item> items = new ArrayList<Item>();
        if (array == null) {
            return items;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            Item item = obj == null ? null : toItem(obj);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    /** 取一个字符串字段：未知字段不管，缺字段或显式 null 都当没写 */
    private static String field(JSONObject obj, String key) {
        return obj.isNull(key) ? "" : obj.optString(key, "").trim();
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
        /** 只有 action 是 "menu" 时非空 */
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
