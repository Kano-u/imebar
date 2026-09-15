package com.local.imebar;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 设置页。
 *
 * 关键行为：**改动即自动保存并即时下发**（跟 AI超级工具栏 一样，它的说明文字就是
 * "所有滑块/开关改完自动保存、即时生效"）。不需要点保存：
 *   - 滑块：松手时保存
 *   - 开关/单选：改变时保存
 *   - 文本框（颜色/按钮）：停止输入 0.6 秒后保存；离开页面时也保存一次
 * 每次保存都会写偏好 + 广播配置数值给输入法进程。
 */
public final class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    /** 正在把已保存的值填进控件时，不要触发自动保存 */
    private boolean loading = true;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingTextApply;

    private CheckBox enabledBox;
    private Slider edgeSlider;
    private Slider sideSlider;
    private Slider textSizeSlider;
    private Slider opacitySlider;
    private EditText textColorBox;
    private EditText pillColorBox;
    private EditText barBgBox;
    private EditText buttonsBox;
    private TextView statusView;
    private TextView logView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(BarConfig.GROUP, MODE_PRIVATE);
        // 记一笔"设置页起来了"：输入法进程发完 startActivity 会回查这个时间戳，
        // 用来区分"打不开"和"系统把这次启动拦掉了"。
        prefs.edit()
                .putLong(ConfigProvider.KEY_SETTINGS_OPENED_AT, System.currentTimeMillis())
                .apply();
        RunLog.add("打开设置页（模块 App 进程）");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF2F2F7);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, dp(28));

        // ---------- 显示设置 ----------
        LinearLayout display = card(root, "显示设置");

        statusView = new TextView(this);
        statusView.setTextSize(12f);
        statusView.setTextColor(0xFF0F7B6C);
        display.addView(statusView);

        enabledBox = new CheckBox(this);
        enabledBox.setText("启用工具栏");
        enabledBox.setTextSize(16f);
        enabledBox.setTextColor(0xFF1A1A1A);
        enabledBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                applyNow("启用开关");
            }
        });
        display.addView(enabledBox);

        edgeSlider = addSlider(display, "底部距离", "工具栏距键盘底部的高度",
                0, 60, BarConfig.DEF_EDGE_DISTANCE, " dp");
        sideSlider = addSlider(display, "左右边距", "工具栏两侧与屏幕边缘的距离",
                0, 60, BarConfig.DEF_SIDE_MARGIN, " dp");
        textSizeSlider = addSlider(display, "文字大小", "文字按钮的字号大小",
                10, 24, BarConfig.DEF_TEXT_SIZE, " dp");
        opacitySlider = addSlider(display, "显示透明度", "工具栏整体显示透明度",
                20, 100, BarConfig.DEF_OPACITY, " %");

        // ---------- 配色 ----------
        LinearLayout style = card(root, "配色");
        TextView styleTip = new TextView(this);
        styleTip.setText("位置固定为「键盘底部」、按钮固定为「纯文字」、排列固定为「均分铺满」，不需要选择。");
        styleTip.setTextSize(12f);
        styleTip.setTextColor(0xFF8A8A8E);
        style.addView(styleTip);
        textColorBox = addColor(style, "文字颜色", BarConfig.DEF_TEXT_COLOR);
        pillColorBox = addColor(style, "胶囊底色", BarConfig.DEF_PILL_BG);
        barBgBox = addColor(style, "整条栏背景色", BarConfig.DEF_BAR_BG);

        // ---------- 按钮 ----------
        LinearLayout buttons = card(root, "按钮");
        TextView tip = new TextView(this);
        tip.setText("一行一个：显示文字|动作|参数（改完停一下会自动保存）");
        tip.setTextSize(13f);
        tip.setTextColor(0xFF8A8A8E);
        buttons.addView(tip);

        buttonsBox = new EditText(this);
        buttonsBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        buttonsBox.setMinLines(6);
        buttonsBox.setTextSize(14f);
        buttonsBox.setGravity(Gravity.TOP | Gravity.START);
        buttonsBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            public void afterTextChanged(Editable s) {
                scheduleApply("按钮列表");
            }
        });
        buttons.addView(buttonsBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView help = new TextView(this);
        help.setText(helpText());
        help.setTextSize(12f);
        help.setTextColor(0xFF8A8A8E);
        help.setPadding(0, dp(8), 0, 0);
        buttons.addView(help);

        // ---------- 运行日志 ----------
        LinearLayout logCard = card(root, "运行日志");
        TextView logTip = new TextView(this);
        logTip.setText("这里显示模块 App 进程的日志。输入法进程的日志请用工具栏上的"
                + "「更多 → 复制日志」按钮取；两边都发我最容易定位问题。");
        logTip.setTextSize(12f);
        logTip.setTextColor(0xFF8A8A8E);
        logCard.addView(logTip);

        logView = new TextView(this);
        logView.setTextSize(11f);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(0xFF1A1A1A);
        logView.setBackground(roundRect(0xFFF5F5F7, dp(10)));
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setText(RunLog.dump());
        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(logView);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        logParams.topMargin = dp(8);
        logCard.addView(logScroll, logParams);

        logCard.addView(actionButton("复制日志", false, new View.OnClickListener() {
            public void onClick(View v) {
                RunLog.add("点击了设置页的「复制日志」");
                boolean ok = RunLog.copyToClipboard(SettingsActivity.this);
                logView.setText(RunLog.dump());
                Toast.makeText(SettingsActivity.this, ok ? "日志已复制到剪贴板" : "复制失败",
                        Toast.LENGTH_SHORT).show();
            }
        }));

        // ---------- 底部按钮（改动已自动保存，这两个只是兜底） ----------
        root.addView(actionButton("立即应用（改动已自动保存，一般不用点）", true,
                new View.OnClickListener() {
                    public void onClick(View v) {
                        applyNow("点击立即应用");
                    }
                }));
        root.addView(actionButton("恢复默认", false, new View.OnClickListener() {
            public void onClick(View v) {
                loadDefaults();
                applyNow("恢复默认");
            }
        }));

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

    @Override
    protected void onStop() {
        super.onStop();
        applyNow("离开设置页");
    }

    // ---------- 自动保存 ----------

    /** 把当前控件的值写进偏好，并广播给输入法进程 */
    private void applyNow(String reason) {
        if (loading) {
            return;
        }
        try {
            prefs.edit()
                    .putBoolean(BarConfig.KEY_ENABLED, enabledBox.isChecked())
                    .putInt(BarConfig.KEY_EDGE_DISTANCE, edgeSlider.value())
                    .putInt(BarConfig.KEY_SIDE_MARGIN, sideSlider.value())
                    .putInt(BarConfig.KEY_TEXT_SIZE, textSizeSlider.value())
                    .putInt(BarConfig.KEY_OPACITY, opacitySlider.value())
                    .putString(BarConfig.KEY_TEXT_COLOR, textColorBox.getText().toString().trim())
                    .putString(BarConfig.KEY_PILL_BG, pillColorBox.getText().toString().trim())
                    .putString(BarConfig.KEY_BAR_BG, barBgBox.getText().toString().trim())
                    .putString(BarConfig.KEY_BUTTONS, buttonsBox.getText().toString())
                    .apply();
        } catch (Throwable t) {
            RunLog.add("写入偏好失败(" + reason + "): " + t);
            return;
        }

        BarConfig cfg = BarConfig.fromPrefs(prefs);
        boolean sent = false;
        try {
            Intent intent = new Intent(BarConfig.ACTION_CONFIG_CHANGED);
            intent.putExtras(cfg.toBundle());
            sendBroadcast(intent);
            sent = true;
        } catch (Throwable t) {
            RunLog.add("广播失败: " + t);
        }
        RunLog.add("已应用(" + reason + "): " + cfg.summary() + " 广播=" + sent);
        updateStatus(cfg);
        if (logView != null) {
            logView.setText(RunLog.dump());
        }
    }

    /** 文本框用：停手 0.6 秒后再保存，避免边打字边刷 */
    private void scheduleApply(String reason) {
        if (loading) {
            return;
        }
        if (pendingTextApply != null) {
            handler.removeCallbacks(pendingTextApply);
        }
        final String tag = reason;
        pendingTextApply = new Runnable() {
            public void run() {
                applyNow(tag);
            }
        };
        handler.postDelayed(pendingTextApply, 600);
    }

    // ---------- 读取 / 默认值 ----------

    private void load() {
        loading = true;
        BarConfig cfg = BarConfig.fromPrefs(prefs);
        enabledBox.setChecked(cfg.enabled());
        edgeSlider.set(cfg.edgeDistanceDp());
        sideSlider.set(cfg.sideMarginDp());
        textSizeSlider.set(cfg.textSizeSp());
        opacitySlider.set(cfg.opacityPercent());
        textColorBox.setText(cfg.textColorHex());
        pillColorBox.setText(cfg.pillColorHex());
        barBgBox.setText(cfg.barBackgroundHex());
        buttonsBox.setText(cfg.buttonsRaw());
        RunLog.add("读取到已保存的配置: " + cfg.summary());
        updateStatus(cfg);
        if (logView != null) {
            logView.setText(RunLog.dump());
        }
        loading = false;
    }

    private void loadDefaults() {
        loading = true;
        enabledBox.setChecked(true);
        edgeSlider.set(BarConfig.DEF_EDGE_DISTANCE);
        sideSlider.set(BarConfig.DEF_SIDE_MARGIN);
        textSizeSlider.set(BarConfig.DEF_TEXT_SIZE);
        opacitySlider.set(BarConfig.DEF_OPACITY);
        textColorBox.setText(BarConfig.DEF_TEXT_COLOR);
        pillColorBox.setText(BarConfig.DEF_PILL_BG);
        barBgBox.setText(BarConfig.DEF_BAR_BG);
        buttonsBox.setText(BarConfig.DEFAULT_BUTTONS);
        loading = false;
    }

    private void updateStatus(BarConfig cfg) {
        if (statusView == null) {
            return;
        }
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        statusView.setText("已自动保存并下发（" + time + "）：" + cfg.summary());
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
                applyNow("拖动滑块");   // 松手就保存并下发
            }
        });
        slider.set(value);
        return slider;
    }

    /**
     * 自绘按钮：和卡片同一套配色（主按钮=青底白字，次按钮=浅青底青字）。
     * 不用系统默认样式的 Button —— 它自带的背景/文字配色和这套界面不搭。
     */
    private TextView actionButton(String text, boolean primary, View.OnClickListener listener) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15f);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), dp(14), dp(16), dp(14));
        if (primary) {
            view.setTextColor(0xFFFFFFFF);
            view.setBackground(roundRect(0xFF0F7B6C, dp(14)));
        } else {
            view.setTextColor(0xFF0F7B6C);
            view.setBackground(roundRect(0xFFE3F1EF, dp(14)));
        }
        view.setClickable(true);
        view.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        view.setLayoutParams(lp);
        return view;
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
        edit.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            public void afterTextChanged(Editable s) {
                scheduleApply("颜色");
            }
        });
        card.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return edit;
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
                + "settings 打开本设置页 / log 复制输入法进程日志到剪贴板\n"
                + "text 插入固定文字（第三列写内容）\n"
                + "app 打开某个 App（第三列写包名） / url 打开网址（第三列写链接）\n\n"
                + "菜单按钮（第二列写 menu）：\n"
                + "第三列写菜单项，多项用 ; 分隔，每项是 文字=动作（也可以 文字=动作=参数）。\n"
                + "例子：更多|menu|切输入法=switch_ime;插入日期=insert_date;打开设置=settings\n\n"
                + "所有滑块和开关都是改完自动保存、即时生效，不需要点保存。";
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
