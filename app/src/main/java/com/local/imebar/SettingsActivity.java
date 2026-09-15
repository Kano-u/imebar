package com.local.imebar;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：写进 SharedPreferences("config")，输入法进程通过远程偏好读到。 */
public final class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private CheckBox enabledBox;
    private EditText heightBox;
    private EditText barBgBox;
    private EditText pillBgBox;
    private EditText textBox;
    private EditText buttonsBox;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(BarConfig.GROUP, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        root.addView(title("简易输入法工具栏"));
        root.addView(hint("启用步骤：在 LSPosed 里勾选本模块 → 作用域只勾你要用的输入法 → "
                + "启用后切换一次输入法（或重启输入法进程）。\n"
                + "改完下面的设置后，同样切一次输入法生效。"));

        enabledBox = new CheckBox(this);
        enabledBox.setText("启用工具栏");
        enabledBox.setTextSize(15f);
        root.addView(enabledBox);

        root.addView(label("工具栏高度（dp，30~96；默认 46）"));
        heightBox = new EditText(this);
        heightBox.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(heightBox);

        root.addView(label("整条栏背景色（#AARRGGBB；默认 #00000000 全透明）"));
        barBgBox = new EditText(this);
        root.addView(barBgBox);

        root.addView(label("胶囊按钮底色（#AARRGGBB；默认 #F2F3F5）"));
        pillBgBox = new EditText(this);
        root.addView(pillBgBox);

        root.addView(label("按钮文字颜色（#AARRGGBB；默认 #202124）"));
        textBox = new EditText(this);
        root.addView(textBox);

        root.addView(label("按钮（一行一个：显示文字|动作|参数）"));
        buttonsBox = new EditText(this);
        buttonsBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        buttonsBox.setMinLines(7);
        buttonsBox.setGravity(Gravity.TOP | Gravity.START);
        root.addView(buttonsBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(hint(helpText()));

        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                save();
            }
        });
        root.addView(save);

        Button reset = new Button(this);
        reset.setText("恢复默认");
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                load(BarConfig.DEFAULT_HEIGHT_DP, BarConfig.DEFAULT_BAR_BG, BarConfig.DEFAULT_PILL_BG,
                        BarConfig.DEFAULT_TEXT, BarConfig.DEFAULT_BUTTONS, true);
                save();
            }
        });
        root.addView(reset);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        load(prefs.getInt(BarConfig.KEY_HEIGHT, BarConfig.DEFAULT_HEIGHT_DP),
                prefs.getString(BarConfig.KEY_BAR_BG, BarConfig.DEFAULT_BAR_BG),
                prefs.getString(BarConfig.KEY_PILL_BG, BarConfig.DEFAULT_PILL_BG),
                prefs.getString(BarConfig.KEY_TEXT, BarConfig.DEFAULT_TEXT),
                prefs.getString(BarConfig.KEY_BUTTONS, BarConfig.DEFAULT_BUTTONS),
                prefs.getBoolean(BarConfig.KEY_ENABLED, true));
    }

    private void load(int height, String barBg, String pillBg, String text, String buttons, boolean enabled) {
        heightBox.setText(String.valueOf(height));
        barBgBox.setText(barBg);
        pillBgBox.setText(pillBg);
        textBox.setText(text);
        buttonsBox.setText(buttons);
        enabledBox.setChecked(enabled);
    }

    private void save() {
        int height = BarConfig.DEFAULT_HEIGHT_DP;
        try {
            height = Integer.parseInt(heightBox.getText().toString().trim());
        } catch (Throwable ignored) {
        }
        if (height < 30) {
            height = 30;
        }
        if (height > 96) {
            height = 96;
        }
        prefs.edit()
                .putBoolean(BarConfig.KEY_ENABLED, enabledBox.isChecked())
                .putInt(BarConfig.KEY_HEIGHT, height)
                .putString(BarConfig.KEY_BAR_BG, barBgBox.getText().toString().trim())
                .putString(BarConfig.KEY_PILL_BG, pillBgBox.getText().toString().trim())
                .putString(BarConfig.KEY_TEXT, textBox.getText().toString().trim())
                .putString(BarConfig.KEY_BUTTONS, buttonsBox.getText().toString())
                .apply();
        Toast.makeText(this, "已保存，切换一次输入法生效", Toast.LENGTH_SHORT).show();
    }

    private TextView title(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(20f);
        view.setPadding(0, 0, 0, dp(8));
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14f);
        view.setPadding(0, dp(12), 0, 0);
        return view;
    }

    private TextView hint(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12f);
        view.setPadding(0, dp(6), 0, 0);
        return view;
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
                + "text 插入固定文字（第三列写内容，例：插入地址|text|广东省深圳市xx路1号）\n"
                + "app 打开某个 App（第三列写包名） / url 打开网址（第三列写链接）\n\n"
                + "菜单按钮（第二列写 menu）：\n"
                + "第三列写菜单项，多项用 ; 分隔，每项是 文字=动作（也可以 文字=动作=参数）。\n"
                + "例子：\n"
                + "更多|menu|切输入法=switch_ime;插入日期=insert_date;收起键盘=hide;打开设置=settings\n\n"
                + "外观说明：整条栏默认全透明，只显示文字胶囊按钮；菜单会以浅色圆角卡片居中弹出。\n\n"
                + "隐私说明：本模块没有联网权限、没有存储权限、不会执行 shell，"
                + "也不会把任何内容发给任何服务器。";
    }
}
