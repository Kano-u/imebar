package com.local.imebar;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置页。改动即自动保存并即时下发，不需要点保存：
 *   - 滑块：松手时保存
 *   - 开关：改变时保存
 *   - 文本框（颜色 / 按钮）：停止输入 0.6 秒后保存；离开页面时再补一次
 * 每次保存都会写偏好 + 广播配置数值给输入法进程。
 *
 * 配色统一走资源里的浅绿（见 res/values/colors.xml），不在这里散落颜色常量。
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
    private EditText barBgBox;
    private EditText buttonsBox;
    private EditText adbBox;
    private TextView adbOutput;
    private TextView logView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(BarConfig.GROUP, MODE_PRIVATE);
        RunLog.add("打开设置页（模块 App 进程）");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, dp(8), pad, dp(24));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(20f);
        title.setTextColor(color(R.color.green_accent));
        title.setPadding(dp(6), dp(6), 0, dp(10));
        root.addView(title);

        // ---------- 显示设置 ----------
        LinearLayout display = card(root, "显示设置");
        enabledBox = new CheckBox(this);
        enabledBox.setText("启用工具栏");
        enabledBox.setTextSize(16f);
        enabledBox.setTextColor(color(R.color.text_main));
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
                BarConfig.MIN_TEXT_SIZE, BarConfig.MAX_TEXT_SIZE, BarConfig.DEF_TEXT_SIZE, " dp");
        opacitySlider = addSlider(display, "显示透明度", "工具栏整体显示透明度",
                20, 100, BarConfig.DEF_OPACITY, " %");

        // ---------- 配色 ----------
        LinearLayout style = card(root, "配色");
        textColorBox = addColor(style, "文字颜色", BarConfig.DEF_TEXT_COLOR);
        barBgBox = addColor(style, "整条栏背景色", BarConfig.DEF_BAR_BG);

        // ---------- 按钮 ----------
        LinearLayout buttons = card(root, "按钮");
        buttons.addView(hint("一行一个：显示文字|动作|参数。以 # 开头的行是注释，会被忽略。"));

        buttonsBox = new EditText(this);
        buttonsBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        buttonsBox.setMinLines(6);
        buttonsBox.setTextSize(14f);
        buttonsBox.setTextColor(color(R.color.text_main));
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

        TextView help = hint(helpText());
        help.setPadding(0, dp(8), 0, 0);
        buttons.addView(help);

        // ---------- ADB 命令 ----------
        LinearLayout adb = card(root, "ADB 命令");
        adb.addView(hint("跑一条 shell 命令，需要 root（没有 root 会按普通身份试一次）。"
                + "想做成按钮，就在「按钮」里写：显示文字|adb|命令"));

        adbBox = new EditText(this);
        adbBox.setSingleLine(true);
        adbBox.setHint("例如 input keyevent 4");
        adbBox.setTextSize(14f);
        adbBox.setTextColor(color(R.color.text_main));
        adb.addView(adbBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        adb.addView(actionButton("执行命令", false, new View.OnClickListener() {
            public void onClick(View v) {
                runAdbCommand();
            }
        }));

        adbOutput = new TextView(this);
        adbOutput.setTextSize(12f);
        adbOutput.setTypeface(Typeface.MONOSPACE);
        adbOutput.setTextColor(color(R.color.text_main));
        adbOutput.setBackground(roundRect(color(R.color.green_soft), dp(10)));
        adbOutput.setPadding(dp(10), dp(10), dp(10), dp(10));
        adbOutput.setText("（执行结果会显示在这里）");
        LinearLayout.LayoutParams outParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        outParams.topMargin = dp(8);
        adb.addView(adbOutput, outParams);

        // ---------- 运行日志 ----------
        LinearLayout logCard = card(root, "运行日志");
        logCard.addView(hint("这里显示模块 App 进程的日志；输入法进程的日志用工具栏上的"
                + "「更多 → 复制日志」取。"));

        logView = new TextView(this);
        logView.setTextSize(11f);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(color(R.color.text_main));
        logView.setBackground(roundRect(color(R.color.green_soft), dp(10)));
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setText(RunLog.dump());
        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(logView);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200));
        logParams.topMargin = dp(8);
        logCard.addView(logScroll, logParams);

        logCard.addView(actionButton("复制日志", false, new View.OnClickListener() {
            public void onClick(View v) {
                RunLog.add("点击了设置页的「复制日志」");
                boolean ok = RunLog.copyToClipboard(SettingsActivity.this);
                logView.setText(RunLog.dump());
                toast(ok ? "日志已复制到剪贴板" : "复制失败");
            }
        }));

        // ---------- 底部 ----------
        root.addView(actionButton("恢复默认", true, new View.OnClickListener() {
            public void onClick(View v) {
                loadDefaults();
                applyNow("恢复默认");
            }
        }));

        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(color(R.color.page_bg));
        scroll.addView(root);
        // Android 15（targetSdk 35）默认边到边：不加这段，最上面的字会被状态栏盖住
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
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
                view.setPadding(left, top, right, bottom);
                return insets;
            }
        });
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
        refreshLog();
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

    private void refreshLog() {
        if (logView != null) {
            logView.setText(RunLog.dump());
        }
    }

    // ---------- ADB 命令 ----------

    private void runAdbCommand() {
        final String command = adbBox.getText().toString().trim();
        if (command.length() == 0) {
            toast("先写一条命令");
            return;
        }
        adbOutput.setText("执行中…\n" + command);
        RunLog.add("（设置页）ADB 命令: " + command);
        new Thread(new Runnable() {
            public void run() {
                final AdbCommand.Result result = AdbCommand.run(command);
                RunLog.add("（设置页）ADB 结果: " + result.detail());
                runOnUiThread(new Runnable() {
                    public void run() {
                        adbOutput.setText(result.detail());
                        refreshLog();
                    }
                });
            }
        }, "imebar-adb-settings").start();
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
        barBgBox.setText(cfg.barBackgroundHex());
        buttonsBox.setText(cfg.buttonsRaw());
        RunLog.add("读取到已保存的配置: " + cfg.summary());
        refreshLog();
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
        barBgBox.setText(BarConfig.DEF_BAR_BG);
        buttonsBox.setText(BarConfig.DEFAULT_BUTTONS);
        loading = false;
    }

    // ---------- 组件 ----------

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(color(R.color.card_bg), dp(18)));
        int p = dp(16);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);

        TextView head = new TextView(this);
        head.setText(title);
        head.setTextSize(18f);
        head.setTextColor(color(R.color.green_accent));
        card.addView(head);
        parent.addView(card);
        return card;
    }

    private TextView hint(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12f);
        view.setTextColor(color(R.color.text_hint));
        return view;
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
        name.setTextColor(color(R.color.text_main));
        box.addView(name);

        box.addView(hint(desc));
        head.addView(box);

        TextView valueText = new TextView(this);
        valueText.setTextSize(16f);
        valueText.setTextColor(color(R.color.green_accent));
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
     * 自绘按钮：主按钮=浅绿底白字，次按钮=浅绿浅底深色字。
     * 不用系统默认样式的 Button —— 它自带的配色和这套界面不搭。
     */
    private TextView actionButton(String text, boolean primary, View.OnClickListener listener) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15f);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), dp(14), dp(16), dp(14));
        if (primary) {
            view.setTextColor(0xFFFFFFFF);
            view.setBackground(roundRect(color(R.color.green_accent), dp(14)));
        } else {
            view.setTextColor(color(R.color.text_main));
            view.setBackground(roundRect(color(R.color.green_soft), dp(14)));
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
        name.setTextColor(color(R.color.text_main));
        name.setPadding(0, dp(14), 0, 0);
        card.addView(name);

        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(14f);
        edit.setTextColor(color(R.color.text_main));
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

    private int color(int resId) {
        return getColor(resId);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String helpText() {
        return "可用动作（第二列）：\n"
                + "copy 复制 / cut 剪切 / paste 粘贴 / select_all 全选 / clear 清空输入框\n"
                + "enter 回车 / delete 退格 / left 光标左移 / right 光标右移 / hide 收起键盘\n"
                + "text 插入固定文字（第三列写内容）\n"
                + "app 打开某个 App（第三列写包名） / url 打开网址（第三列写链接）\n"
                + "adb 执行 shell 命令（第三列写命令，需要 root） / log 复制输入法日志\n\n"
                + "菜单按钮：第二列写 menu，第三列写 文字=动作，多项用 ; 分隔。\n"
                + "所有滑块和开关都是改完自动保存、即时生效。";
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
