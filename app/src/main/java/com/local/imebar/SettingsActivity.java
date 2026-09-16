package com.local.imebar;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置页：Material Design 3 风格，零依赖（只用系统控件 + 代码自绘）。
 *
 * 逻辑：改完点「保存」才写偏好 + 广播下发（手动保存）；「恢复默认」= 填好默认值并立即保存。
 * 版式：最上面一条固定顶栏（APP 名称 + 右边「⋮」），下面才是可滚动的卡片区。
 * 视觉：卡片 12dp 圆角 + 1dp 阴影、胶囊按钮带涟漪、Switch 代替复选框、滑条染色、
 * 输入框是 MD3 outlined 样式（常态 1dp 描边、聚焦 2dp 主色）。颜色全在 res/values/colors.xml。
 */
public final class SettingsActivity extends Activity {

    /** MD3 里的中等字重 */
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private SharedPreferences prefs;

    private Switch enabledSwitch;
    private Slider edgeSlider;
    private Slider sideSlider;
    private Slider textSizeSlider;
    private Slider opacitySlider;
    private EditText textColorBox;
    private EditText barBgBox;
    private EditText buttonsBox;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(BarConfig.GROUP, MODE_PRIVATE);

        // 整页 = [顶栏][1dp 分隔线][可滚动的内容]
        final LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(color(R.color.md_surface));

        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(color(R.color.md_surface));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(22f);
        title.setTypeface(medium);
        title.setTextColor(color(R.color.md_on_surface));
        title.setSingleLine(true);
        bar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final View more = new DotsButton(this);
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showInfoMenu(v);
            }
        });

        final View saveTop = new SaveButton(this);
        saveTop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                save();
            }
        });

        bar.addView(saveTop, new LinearLayout.LayoutParams(dp(48), dp(48)));
        bar.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        column.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(color(R.color.md_outline_variant));
        column.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(16), dp(24), dp(24));

        // ---------- 显示设置 ----------
        LinearLayout display = card(root, "显示设置");

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("启用工具栏");
        enabledSwitch.setTextSize(16f);
        enabledSwitch.setTextColor(color(R.color.md_on_surface));
        enabledSwitch.setTrackTintList(states(
                color(R.color.md_primary), color(R.color.md_surface_container_high)));
        enabledSwitch.setThumbTintList(states(
                color(R.color.md_surface_bright), color(R.color.md_outline_variant)));
        display.addView(enabledSwitch);

        edgeSlider = addSlider(display, "底部距离", "工具栏距键盘底部的高度",
                0, 60, BarConfig.DEF_EDGE_DISTANCE, " dp");
        sideSlider = addSlider(display, "左右边距", "工具栏两侧与屏幕边缘的距离",
                0, 60, BarConfig.DEF_SIDE_MARGIN, " dp");
        textSizeSlider = addSlider(display, "文字大小", "文字按钮的字号大小",
                BarConfig.MIN_TEXT_SIZE, BarConfig.MAX_TEXT_SIZE, BarConfig.DEF_TEXT_SIZE, " dp");
        opacitySlider = addSlider(display, "显示透明度", "工具栏整体显示透明度",
                20, 100, BarConfig.DEF_OPACITY, " %");

        // ---------- 配色 ----------
        LinearLayout style = card(root, "配色");
        textColorBox = addColor(style, "文字颜色", BarConfig.DEF_TEXT_COLOR);
        barBgBox = addColor(style, "整条栏背景色", BarConfig.DEF_BAR_BG);

        // ---------- 按钮 ----------
        LinearLayout buttons = card(root, "按钮");
        buttons.addView(hint("JSON 数组，一个元素一个按钮："
                + "{\"label\":\"显示文字\",\"action\":\"动作\",\"arg\":\"参数\"}。"
                + "菜单按钮用 \"action\":\"menu\" + \"menu\":[...] 子数组。整行 // 开头是注释。"));

        buttonsBox = outlinedEdit(true);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxParams.topMargin = dp(8);
        buttons.addView(buttonsBox, boxParams);

        TextView help = hint(helpText());
        help.setPadding(0, dp(8), 0, 0);
        buttons.addView(help);

        // ---------- 底部按钮：手动保存 ----------
        root.addView(actionButton("保存", true, new View.OnClickListener() {
            public void onClick(View v) {
                save();
            }
        }));
        root.addView(actionButton("恢复默认", false, new View.OnClickListener() {
            public void onClick(View v) {
                loadDefaults();
                save();
            }
        }));

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Android 15（targetSdk 35）默认边到边：顶栏吃上面的 inset、滚动区吃下面的，
        // 这样标题不会被状态栏盖住，"保存"按钮也不会被导航栏压住。
        column.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                int left;
                int top;
                int right;
                int bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    left = bars.left;
                    top = bars.top;
                    right = bars.right;
                    bottom = bars.bottom;
                } else {
                    left = insets.getSystemWindowInsetLeft();
                    top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                bar.setPadding(dp(24) + left, top, dp(8) + right, 0);
                LinearLayout.LayoutParams barParams =
                        (LinearLayout.LayoutParams) bar.getLayoutParams();
                barParams.height = dp(64) + top;   // 顶栏内容固定 64dp，再垫上状态栏的高度
                bar.setLayoutParams(barParams);
                scroll.setPadding(0, 0, 0, bottom);
                return insets;
            }
        });
        setContentView(column);

        load();
    }

    // ---------- 保存 ----------

    /** 把当前控件的值写进偏好，并广播给输入法进程 */
    private void save() {
        try {
            prefs.edit()
                    .putBoolean(BarConfig.KEY_ENABLED, enabledSwitch.isChecked())
                    .putInt(BarConfig.KEY_EDGE_DISTANCE, edgeSlider.value())
                    .putInt(BarConfig.KEY_SIDE_MARGIN, sideSlider.value())
                    .putInt(BarConfig.KEY_TEXT_SIZE, textSizeSlider.value())
                    .putInt(BarConfig.KEY_OPACITY, opacitySlider.value())
                    .putString(BarConfig.KEY_TEXT_COLOR, textColorBox.getText().toString().trim())
                    .putString(BarConfig.KEY_BAR_BG, barBgBox.getText().toString().trim())
                    .putString(BarConfig.KEY_BUTTONS, buttonsBox.getText().toString())
                    .apply();
        } catch (Throwable t) {
            toast("保存失败：" + t);
            return;
        }

        BarConfig cfg = BarConfig.fromPrefs(prefs);
        boolean sent = false;
        try {
            Intent intent = new Intent(BarConfig.ACTION_CONFIG_CHANGED);
            intent.putExtras(cfg.toBundle());
            sendBroadcast(intent);
            sent = true;
        } catch (Throwable ignored) {
            // 广播被拦（个别 ROM 会）不急：下次弹键盘还会重新拉一次配置
        }
        toast(sent ? "已保存并生效" : "已保存（广播失败，重启输入法后生效）");
    }

    // ---------- 读取 / 默认值 ----------

    private void load() {
        BarConfig cfg = BarConfig.fromPrefs(prefs);
        enabledSwitch.setChecked(cfg.enabled());
        edgeSlider.set(cfg.edgeDistanceDp());
        sideSlider.set(cfg.sideMarginDp());
        textSizeSlider.set(cfg.textSizeSp());
        opacitySlider.set(cfg.opacityPercent());
        textColorBox.setText(cfg.textColorHex());
        barBgBox.setText(cfg.barBackgroundHex());
        String buttons = cfg.buttonsRaw();
        if (!buttons.trim().startsWith("[")) {
            // 旧版是「显示文字|动作|参数」这种竖线格式，本版本不再支持：
            // 直接把默认值（JSON）填进输入框，让界面看到的就是实际生效的那份
            buttons = BarConfig.DEFAULT_BUTTONS;
        }
        buttonsBox.setText(buttons);
    }

    private void loadDefaults() {
        enabledSwitch.setChecked(true);
        edgeSlider.set(BarConfig.DEF_EDGE_DISTANCE);
        sideSlider.set(BarConfig.DEF_SIDE_MARGIN);
        textSizeSlider.set(BarConfig.DEF_TEXT_SIZE);
        opacitySlider.set(BarConfig.DEF_OPACITY);
        textColorBox.setText(BarConfig.DEF_TEXT_COLOR);
        barBgBox.setText(BarConfig.DEF_BAR_BG);
        buttonsBox.setText(BarConfig.DEFAULT_BUTTONS);
    }

    // ---------- 顶栏 ----------

    /**
     * 顶栏上的图标按钮：48dp 触摸区 + 圆形中性涟漪。
     * 图标一律自己画，不用字符——有的 ROM 缺这些字形，会渲染成方框。
     */
    private abstract class IconButton extends View {

        IconButton(Context context) {
            super(context);
            setClickable(true);
            // 圆形涟漪：掩膜只决定形状，颜色随便写
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.OVAL);
            mask.setColor(0xFFFFFFFF);
            setBackground(new RippleDrawable(
                    ColorStateList.valueOf(color(R.color.md_ripple_neutral)), null, mask));
        }
    }

    /** 保存：两根线画的对勾，点一下和页面底部那个「保存」按钮完全一样 */
    private final class SaveButton extends IconButton {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path check = new Path();

        SaveButton(Context context) {
            super(context);
            paint.setColor(color(R.color.md_on_surface));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setContentDescription("保存");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float centerX = getWidth() / 2f;
            float centerY = getHeight() / 2f;
            check.reset();   // 约 11dp 宽、9dp 高，居中
            check.moveTo(centerX - dpf(5.5f), centerY + dpf(0.5f));
            check.lineTo(centerX - dpf(1.5f), centerY + dpf(4.5f));
            check.lineTo(centerX + dpf(5.5f), centerY - dpf(4.5f));
            canvas.drawPath(check, paint);
        }
    }

    /** 「⋮」：三个点竖排 */
    private final class DotsButton extends IconButton {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float dot = dp(4);
        /** 相邻两点圆心的距离 */
        private final float step = dp(7);

        DotsButton(Context context) {
            super(context);
            paint.setColor(color(R.color.md_on_surface));
            paint.setStyle(Paint.Style.FILL);
            setContentDescription("更多");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float centerX = getWidth() / 2f;
            float top = getHeight() / 2f - step;   // 三点整体垂直居中
            for (int i = 0; i < 3; i++) {
                canvas.drawCircle(centerX, top + step * i, dot / 2f, paint);
            }
        }
    }

    /**
     * 「⋮」菜单：两行只读信息（版本 / 包名），点卡片外面收起。
     * 用 PopupWindow 而不是自绘浮层——这里是正常的 Activity，不用输入法窗口那套兜底。
     */
    private void showInfoMenu(View anchor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(outlinedCard(dp(12)));
        card.setPadding(0, dp(8), 0, dp(8));
        card.addView(infoRow("版本 " + BuildConfig.VERSION_NAME));
        card.addView(infoDivider());
        card.addView(infoRow("包名 " + getPackageName()));

        // 先量出内容本身有多宽，再夹在 [168dp, 屏宽-16dp] 之间
        card.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int margin = dp(8);
        int width = Math.min(Math.max(card.getMeasuredWidth(), dp(168)),
                getResources().getDisplayMetrics().widthPixels - margin * 2);

        PopupWindow popup = new PopupWindow(card, width, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 少了这层透明背景：点外面收不起来，卡片的阴影也会被窗口边界裁掉
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        // 让卡片右边缘对齐「⋮」；越界时 PopupWindow 会自己夹回屏幕内
        popup.showAsDropDown(anchor, anchor.getWidth() - width, dp(4));
    }

    /** 一行只读信息 */
    private TextView infoRow(String text) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextSize(14f);
        row.setTextColor(color(R.color.md_on_surface));
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        return row;
    }

    /** 两行信息之间的细分隔线（和键盘上弹出的菜单卡片一个样子） */
    private View infoDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(color(R.color.md_outline_variant));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        int side = dp(12);
        lp.setMargins(side, 0, side, 0);
        divider.setLayoutParams(lp);
        return divider;
    }

    // ---------- 组件 ----------

    /**
     * 一节内容 = 一张卡片：MD3 圆角 12dp、不用阴影——靠填充底色（surface container）
     * 和页面底色拉开，页面浅、卡片深一点，边界一眼就能看出来。
     * 标题用 Title Medium（16sp、中等字重）+ 主色蓝：全页只有这一种蓝，
     * 和主按钮/开关/滑条同色，克制、不花。
     */
    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(color(R.color.md_surface_container), dp(12)));
        int p = dp(24);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        card.setLayoutParams(lp);

        TextView head = new TextView(this);
        head.setText(title);
        head.setTextSize(16f);
        head.setTypeface(medium);
        head.setTextColor(color(R.color.md_primary));
        // 标题 → 内容留 8dp，别和下面的开关/提示挤在一起
        head.setPadding(0, 0, 0, dp(8));
        card.addView(head);
        parent.addView(card);
        return card;
    }

    /** Body Small：说明文字 */
    private TextView hint(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12f);
        view.setTextColor(color(R.color.md_on_surface_variant));
        return view;
    }

    private Slider addSlider(LinearLayout card, String title, String desc,
                             int min, int max, int value, String unit) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(20), 0, 0);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(16f);
        name.setTextColor(color(R.color.md_on_surface));
        box.addView(name);
        box.addView(hint(desc));
        head.addView(box);

        TextView valueText = new TextView(this);
        valueText.setTextSize(16f);
        valueText.setTypeface(medium);
        valueText.setTextColor(color(R.color.md_primary));
        head.addView(valueText);
        card.addView(head);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setPadding(0, dp(6), 0, dp(6));
        ColorStateList accent = ColorStateList.valueOf(color(R.color.md_primary));
        bar.setProgressTintList(accent);
        bar.setThumbTintList(accent);
        bar.setProgressBackgroundTintList(
                ColorStateList.valueOf(color(R.color.md_surface_container_high)));
        card.addView(bar);

        final Slider slider = new Slider(bar, valueText, min, unit);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                slider.refresh(progress);
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
                // 手动保存：拖动只改数值，点「保存」才写进去
            }
        });
        slider.set(value);
        return slider;
    }

    /**
     * MD3 按钮（胶囊）：主按钮 = 主色底 + on-primary 字；次按钮 = tonal（容器色底）。
     * 都带涟漪反馈；用 TextView 自绘，不用系统默认样式的 Button。
     */
    private TextView actionButton(String text, boolean primary, View.OnClickListener listener) {
        float radius = dp(28);
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14f);
        view.setTypeface(medium);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(20), dp(12), dp(20), dp(12));
        view.setMinHeight(dp(40));
        if (primary) {
            view.setTextColor(color(R.color.md_on_primary));
            view.setBackground(ripple(roundRect(color(R.color.md_primary), radius), radius));
        } else {
            view.setTextColor(color(R.color.md_on_primary_container));
            view.setBackground(ripple(
                    roundRect(color(R.color.md_primary_container), radius), radius));
        }
        view.setClickable(true);
        view.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        view.setLayoutParams(lp);
        return view;
    }

    private EditText addColor(LinearLayout card, String title, String def) {
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(16f);
        name.setTextColor(color(R.color.md_on_surface));
        name.setPadding(0, dp(16), 0, dp(8));
        card.addView(name);

        EditText edit = outlinedEdit(false);
        edit.setHint(def);
        card.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return edit;
    }

    /** MD3 outlined 输入框：常态 1dp outline-variant 描边，聚焦换 2dp 主色 */
    private EditText outlinedEdit(boolean multiLine) {
        EditText edit = new EditText(this);
        edit.setTextSize(14f);
        edit.setTextColor(color(R.color.md_on_surface));
        edit.setHintTextColor(color(R.color.md_on_surface_variant));
        edit.setBackground(outline(false));
        edit.setPadding(dp(12), dp(10), dp(12), dp(10));
        edit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(outline(hasFocus));
            }
        });
        if (multiLine) {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            edit.setMinLines(6);
            edit.setGravity(Gravity.TOP | Gravity.START);
        } else {
            edit.setSingleLine(true);
        }
        return edit;
    }

    private GradientDrawable outline(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color(R.color.md_surface_bright));
        drawable.setCornerRadius(dp(8));
        if (focused) {
            drawable.setStroke(dp(2), color(R.color.md_primary));
        } else {
            drawable.setStroke(dp(1), color(R.color.md_outline_variant));
        }
        return drawable;
    }

    /** 选中态 / 未选中态两组颜色（Switch、滑条用） */
    private ColorStateList states(int checked, int unchecked) {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[0]},
                new int[]{checked, unchecked});
    }

    /** 给按钮补上按压反馈（MD3 状态层） */
    private RippleDrawable ripple(GradientDrawable content, float radius) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(radius);
        return new RippleDrawable(
                ColorStateList.valueOf(color(R.color.md_ripple_primary)), content, mask);
    }

    private void toast(String text) {
        try {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private GradientDrawable roundRect(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    /** 白色圆角卡 + 1dp 描边：没有阴影，浮在内容上的卡片靠这圈线分清边界 */
    private GradientDrawable outlinedCard(float radius) {
        GradientDrawable drawable = roundRect(color(R.color.md_surface_bright), radius);
        drawable.setStroke(Math.max(1, dp(1)), color(R.color.md_outline_variant));
        return drawable;
    }

    private int color(int resId) {
        return getColor(resId);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 需要小数 dp 的地方（比如自绘图标的几个点）用这个 */
    private float dpf(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private String helpText() {
        return "可用动作（action 字段）：\n"
                + "copy 复制 / cut 剪切 / paste 粘贴 / select_all 全选 / clear 清空输入框\n"
                + "enter 回车 / delete 退格 / left 光标左移 / right 光标右移 / hide 收起键盘\n"
                + "text 插入固定文字（arg 写内容）\n"
                + "app 打开某个 App（arg 写包名） / url 打开网址（arg 写链接）\n"
                + "adb 执行 shell 命令（arg 写命令，走 Shizuku，首次会弹授权框）\n\n"
                + "字段：label 显示文字（不写就用 action 顶上）、action 动作、arg 参数（可省）。\n"
                + "菜单按钮：\"action\":\"menu\"，子项放 \"menu\":[{\"label\":\"剪切\",\"action\":\"cut\"}]。\n"
                + "子项里还能再放 menu（多级嵌套，深度不限）；进子菜单后卡片最上面有「← 返回」。\n"
                + "未知字段会被忽略，随便扩。\n\n"
                + "例子：\n"
                + "[\n"
                + "  {\"label\": \"复制\", \"action\": \"copy\"},\n"
                + "  {\"label\": \"插入地址\", \"action\": \"text\", \"arg\": \"广东省深圳市\"},\n"
                + "  {\"label\": \"更多\", \"action\": \"menu\", \"menu\": [\n"
                + "    {\"label\": \"截屏\", \"action\": \"adb\", \"arg\": \"screencap -p /sdcard/1.png\"},\n"
                + "    {\"label\": \"编辑\", \"action\": \"menu\", \"menu\": [\n"
                + "      {\"label\": \"粘贴\", \"action\": \"paste\"},\n"
                + "      {\"label\": \"清空\", \"action\": \"clear\"}\n"
                + "    ]}\n"
                + "  ]}\n"
                + "]\n\n"
                + "改完点下面的「保存」生效。";
    }

    /** 一行滑动条：标题 + 说明 + 右侧数值 */
    private final class Slider {
        private final SeekBar bar;
        private final TextView valueText;
        private final int min;
        private final String unit;

        Slider(SeekBar bar, TextView valueText, int min, String unit) {
            this.bar = bar;
            this.valueText = valueText;
            this.min = min;
            this.unit = unit;
        }

        void refresh(int progress) {
            valueText.setText((min + progress) + unit);
        }

        int value() {
            return min + bar.getProgress();
        }

        void set(int value) {
            int progress = value - min;
            if (progress < 0) {
                progress = 0;
            }
            if (progress > bar.getMax()) {
                progress = bar.getMax();
            }
            bar.setProgress(progress);
            refresh(progress);
        }
    }
}
