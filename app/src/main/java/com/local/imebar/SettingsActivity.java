package com.local.imebar;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置页：写进 SharedPreferences("config")，输入法进程通过远程偏好读到。
 * 排布模仿 AI超级工具栏 的"显示设置"：白卡片 + 标题 + 滑动条 + 右侧数值。
 */
public final class SettingsActivity extends Activity {

    private SharedPreferences prefs;

    private CheckBox enabledBox;
    private Slider edgeSlider;
    private Slider sideSlider;
    private Slider textSizeSlider;
    private Slider opacitySlider;
    private RadioGroup positionGroup;
    private RadioGroup styleGroup;
    private RadioGroup layoutGroup;
    private EditText textColorBox;
    private EditText pillColorBox;
    private EditText barBgBox;
    private EditText buttonsBox;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(BarConfig.GROUP, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF2F2F7);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, dp(28));

        // ---------- 显示设置 ----------
        LinearLayout display = card(root, "显示设置");

        enabledBox = new CheckBox(this);
        enabledBox.setText("启用工具栏");
        enabledBox.setTextSize(16f);
        enabledBox.setTextColor(0xFF1A1A1A);
        display.addView(enabledBox);

        edgeSlider = addSlider(display, "底部距离", "工具栏距键盘底部的高度",
                0, 60, BarConfig.DEF_EDGE_DISTANCE, " dp");
        sideSlider = addSlider(display, "左右边距", "工具栏两侧与屏幕边缘的距离",
                0, 60, BarConfig.DEF_SIDE_MARGIN, " dp");
        textSizeSlider = addSlider(display, "文字大小", "文字按钮的字号大小",
                10, 24, BarConfig.DEF_TEXT_SIZE, " dp");
        opacitySlider = addSlider(display, "显示透明度", "工具栏整体显示透明度",
                20, 100, BarConfig.DEF_OPACITY, " %");

        // ---------- 样式 ----------
        LinearLayout style = card(root, "样式");
        positionGroup = addRadio(style, "位置", new String[]{"键盘底部", "键盘顶部"}, 0);
        styleGroup = addRadio(style, "按钮样式", new String[]{"纯文字", "胶囊"}, 0);
        layoutGroup = addRadio(style, "排列方式", new String[]{"均分铺满", "左对齐"}, 0);
        textColorBox = addColor(style, "文字颜色", BarConfig.DEF_TEXT_COLOR);
        pillColorBox = addColor(style, "胶囊底色", BarConfig.DEF_PILL_BG);
        barBgBox = addColor(style, "整条栏背景色", BarConfig.DEF_BAR_BG);

        // ---------- 按钮 ----------
        LinearLayout buttons = card(root, "按钮");
        TextView tip = new TextView(this);
        tip.setText("一行一个：显示文字|动作|参数");
        tip.setTextSize(13f);
        tip.setTextColor(0xFF8A8A8E);
        buttons.addView(tip);

        buttonsBox = new EditText(this);
        buttonsBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        buttonsBox.setMinLines(6);
        buttonsBox.setTextSize(14f);
        buttonsBox.setGravity(Gravity.TOP | Gravity.START);
        buttons.addView(buttonsBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView help = new TextView(this);
        help.setText(helpText());
        help.setTextSize(12f);
        help.setTextColor(0xFF8A8A8E);
        help.setPadding(0, dp(8), 0, 0);
        buttons.addView(help);

        // ---------- 底部按钮 ----------
        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                save();
            }
        });
        root.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button reset = new Button(this);
        reset.setText("恢复默认");
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                loadDefaults();
                save();
            }
        });
        root.addView(reset, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView footer = new TextView(this);
        footer.setText("本模块没有联网权限、没有存储权限、不会执行 shell。");
        footer.setTextSize(12f);
        footer.setTextColor(0xFF8A8A8E);
        footer.setPadding(0, dp(12), 0, 0);
        root.addView(footer);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF2F2F7);
        scroll.addView(root);
        setContentView(scroll);

        load();
    }

    // ---------- 读取 / 保存 ----------

    private void load() {
        BarConfig cfg = new BarConfig(prefs);
        enabledBox.setChecked(cfg.enabled());
        edgeSlider.set(cfg.edgeDistanceDp());
        sideSlider.set(cfg.sideMarginDp());
        textSizeSlider.set(cfg.textSizeSp());
        opacitySlider.set(cfg.opacityPercent());
        positionGroup.check(positionGroup.getChildAt(cfg.isBottom() ? 0 : 1).getId());
        styleGroup.check(styleGroup.getChildAt(cfg.isPill() ? 1 : 0).getId());
        layoutGroup.check(layoutGroup.getChildAt(cfg.isStretch() ? 0 : 1).getId());
        textColorBox.setText(cfg.textColorHex());
        pillColorBox.setText(cfg.pillColorHex());
        barBgBox.setText(cfg.barBackgroundHex());
        buttonsBox.setText(cfg.buttonsRaw());
    }

    private void loadDefaults() {
        enabledBox.setChecked(true);
        edgeSlider.set(BarConfig.DEF_EDGE_DISTANCE);
        sideSlider.set(BarConfig.DEF_SIDE_MARGIN);
        textSizeSlider.set(BarConfig.DEF_TEXT_SIZE);
        opacitySlider.set(BarConfig.DEF_OPACITY);
        positionGroup.check(positionGroup.getChildAt(0).getId());
        styleGroup.check(styleGroup.getChildAt(0).getId());
        layoutGroup.check(layoutGroup.getChildAt(0).getId());
        textColorBox.setText(BarConfig.DEF_TEXT_COLOR);
        pillColorBox.setText(BarConfig.DEF_PILL_BG);
        barBgBox.setText(BarConfig.DEF_BAR_BG);
        buttonsBox.setText(BarConfig.DEFAULT_BUTTONS);
    }

    private void save() {
        prefs.edit()
                .putBoolean(BarConfig.KEY_ENABLED, enabledBox.isChecked())
                .putString(BarConfig.KEY_POSITION,
                        radioIndex(positionGroup) == 0 ? BarConfig.POSITION_BOTTOM : BarConfig.POSITION_TOP)
                .putInt(BarConfig.KEY_EDGE_DISTANCE, edgeSlider.value())
                .putInt(BarConfig.KEY_SIDE_MARGIN, sideSlider.value())
                .putInt(BarConfig.KEY_TEXT_SIZE, textSizeSlider.value())
                .putInt(BarConfig.KEY_OPACITY, opacitySlider.value())
                .putString(BarConfig.KEY_STYLE,
                        radioIndex(styleGroup) == 1 ? BarConfig.STYLE_PILL : BarConfig.STYLE_TEXT)
                .putString(BarConfig.KEY_LAYOUT,
                        radioIndex(layoutGroup) == 1 ? BarConfig.LAYOUT_LEFT : BarConfig.LAYOUT_STRETCH)
                .putString(BarConfig.KEY_TEXT_COLOR, textColorBox.getText().toString().trim())
                .putString(BarConfig.KEY_PILL_BG, pillColorBox.getText().toString().trim())
                .putString(BarConfig.KEY_BAR_BG, barBgBox.getText().toString().trim())
                .putString(BarConfig.KEY_BUTTONS, buttonsBox.getText().toString())
                .apply();

        // 立刻通知输入法进程重读配置：这样不用切输入法就能看到效果
        try {
            sendBroadcast(new Intent(BarConfig.ACTION_CONFIG_CHANGED));
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, "已保存并即时生效", Toast.LENGTH_SHORT).show();
    }

    // ---------- 组件 ----------

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(0xFFFFFFFF, dp(18)));
        int p = dp(16);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);

        TextView head = new TextView(this);
        head.setText(title);
        head.setTextSize(18f);
        head.setTextColor(0xFF0F7B6C);
        card.addView(head);
        parent.addView(card);
        return card;
    }

    private Slider addSlider(LinearLayout card, String title, String desc,
                             int min, int max, int value, String unit) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(14), 0, 0);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(16f);
        name.setTextColor(0xFF1A1A1A);
        box.addView(name);

        TextView note = new TextView(this);
        note.setText(desc);
        note.setTextSize(12f);
        note.setTextColor(0xFF8A8A8E);
        box.addView(note);
        head.addView(box);

        TextView valueText = new TextView(this);
        valueText.setTextSize(16f);
        valueText.setTextColor(0xFF1A1A1A);
        head.addView(valueText);
        card.addView(head);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setPadding(0, dp(6), 0, dp(6));
        card.addView(bar);

        final Slider slider = new Slider(bar, valueText, min, unit);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                slider.refresh(progress);
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        slider.set(value);
        return slider;
    }

    private RadioGroup addRadio(LinearLayout card, String title, String[] labels, int selected) {
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(16f);
        name.setTextColor(0xFF1A1A1A);
        name.setPadding(0, dp(14), 0, 0);
        card.addView(name);

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            RadioButton radio = new RadioButton(this);
            radio.setText(labels[i]);
            radio.setTextSize(14f);
            radio.setId(View.generateViewId());
            group.addView(radio);
            if (i == selected) {
                group.check(radio.getId());
            }
        }
        card.addView(group);
        return group;
    }

    private EditText addColor(LinearLayout card, String title, String def) {
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(16f);
        name.setTextColor(0xFF1A1A1A);
        name.setPadding(0, dp(14), 0, 0);
        card.addView(name);

        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(14f);
        edit.setHint(def);
        card.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return edit;
    }

    private int radioIndex(RadioGroup group) {
        int checked = group.getCheckedRadioButtonId();
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i).getId() == checked) {
                return i;
            }
        }
        return 0;
    }

    private GradientDrawable roundRect(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String helpText() {
        return "普通按钮（第二列写动作）：\n"
                + "copy 复制 / cut 剪切 / paste 粘贴 / select_all 全选 / clear 清空输入框\n"
                + "enter 回车 / delete 退格 / left 光标左移 / right 光标右移 / hide 收起键盘\n"
                + "switch_ime 切换输入法 / insert_date 插入日期 / insert_time 插入时间\n"
                + "settings 打开本设置页\n"
                + "text 插入固定文字（第三列写内容）\n"
                + "app 打开某个 App（第三列写包名） / url 打开网址（第三列写链接）\n\n"
                + "菜单按钮（第二列写 menu）：\n"
                + "第三列写菜单项，多项用 ; 分隔，每项是 文字=动作（也可以 文字=动作=参数）。\n"
                + "例子：更多|menu|切输入法=switch_ime;插入日期=insert_date;打开设置=settings\n\n"
                + "窗口位置说明：位置=键盘底部时，工具栏在键盘那排按键的下面；"
                + "此时「底部距离」是它距键盘底边的高度。";
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
