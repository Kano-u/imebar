package com.local.imebar;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
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
    private EditText bgBox;
    private EditText fgBox;
    private EditText buttonsBox;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(BarConfig.GROUP, MODE_PRIVATE);

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density + 0.5f);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(title("简易输入法工具栏"));
        root.addView(hint("第一步：在 LSPosed 里勾选本模块，作用域只勾你要用的输入法。\n"
                + "第二步：改完这里的设置后，切换一次输入法（或重启输入法进程）生效。"));

        enabledBox = new CheckBox(this);
        enabledBox.setText("启用工具栏");
        enabledBox.setTextSize(15f);
        root.addView(enabledBox);

        root.addView(label("工具栏高度（dp，24~96）"));
        heightBox = new EditText(this);
        heightBox.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(heightBox);

        root.addView(label("背景色（#AARRGGBB）"));
        bgBox = new EditText(this);
        root.addView(bgBox);

        root.addView(label("文字颜色（#AARRGGBB）"));
        fgBox = new EditText(this);
        root.addView(fgBox);

        root.addView(label("按钮（一行一个：显示文字|动作|参数）"));
        buttonsBox = new EditText(this);
        buttonsBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        buttonsBox.setMinLines(8);
        buttonsBox.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
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
                load(BarConfig.DEFAULT_HEIGHT_DP, BarConfig.DEFAULT_BG, BarConfig.DEFAULT_FG,
                        BarConfig.DEFAULT_BUTTONS, true);
                save();
            }
        });
        root.addView(reset);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        load(prefs.getInt(BarConfig.KEY_HEIGHT, BarConfig.DEFAULT_HEIGHT_DP),
                prefs.getString(BarConfig.KEY_BG, BarConfig.DEFAULT_BG),
                prefs.getString(BarConfig.KEY_FG, BarConfig.DEFAULT_FG),
                prefs.getString(BarConfig.KEY_BUTTONS, BarConfig.DEFAULT_BUTTONS),
                prefs.getBoolean(BarConfig.KEY_ENABLED, true));
    }

    private void load(int height, String bg, String fg, String buttons, boolean enabled) {
        heightBox.setText(String.valueOf(height));
        bgBox.setText(bg);
        fgBox.setText(fg);
        buttonsBox.setText(buttons);
        enabledBox.setChecked(enabled);
    }

    private void save() {
        int height = BarConfig.DEFAULT_HEIGHT_DP;
        try {
            height = Integer.parseInt(heightBox.getText().toString().trim());
        } catch (Throwable ignored) {
        }
        if (height < 24) {
            height = 24;
        }
        if (height > 96) {
            height = 96;
        }
        prefs.edit()
                .putBoolean(BarConfig.KEY_ENABLED, enabledBox.isChecked())
                .putInt(BarConfig.KEY_HEIGHT, height)
                .putString(BarConfig.KEY_BG, bgBox.getText().toString().trim())
                .putString(BarConfig.KEY_FG, fgBox.getText().toString().trim())
                .putString(BarConfig.KEY_BUTTONS, buttonsBox.getText().toString())
                .apply();
        Toast.makeText(this, "已保存，切换一次输入法生效", Toast.LENGTH_SHORT).show();
    }

    private TextView title(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(20f);
        view.setPadding(0, 0, 0, (int) (8 * getResources().getDisplayMetrics().density + 0.5f));
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14f);
        view.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density + 0.5f), 0, 0);
        return view;
    }

    private TextView hint(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12f);
        view.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density + 0.5f), 0, 0);
        return view;
    }

    private String helpText() {
        return "可用动作（写在第二列）：\n"
                + "copy 复制 / cut 剪切 / paste 粘贴 / select_all 全选 / clear 清空输入框\n"
                + "enter 回车 / delete 退格 / left 光标左移 / right 光标右移 / hide 收起键盘\n"
                + "text 插入固定文字（第三列写内容）\n"
                + "app 打开某个 App（第三列写包名，如 com.tencent.mm）\n"
                + "url 打开网址（第三列写链接）\n\n"
                + "例子：\n"
                + "复制|copy\n"
                + "收货地址|text|广东省深圳市...\n"
                + "打开微信|app|com.tencent.mm\n\n"
                + "隐私说明：本模块没有联网权限、没有存储权限、不会执行 shell，"
                + "也不会把任何内容发给任何服务器。";
    }
}
