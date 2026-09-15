package com.local.imebar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 弹出菜单：一张居中的白色圆角卡片（MD3 Large 圆角 16dp + Level2 阴影），条目之间有细分隔线。
 *
 * 没有用 PopupWindow，而是直接把一层覆盖 View 加到输入法窗口的 DecorView 上。
 * 原因：输入法窗口的高度只到键盘底部，PopupWindow 很容易被窗口边界裁掉；
 * 直接在窗口内部画反而更稳，也能做出"卡片浮在键盘上"的效果。
 *
 * 注意：这里跑在输入法进程，拿到的是输入法的 Resources，用不了模块自己的 R.color，
 * 所以颜色只能写死（和 res/values/colors.xml 里的 MD3 token 对应）。
 */
final class BarMenu {

    private static final String TAG = "ImeBar";
    private static final int SCRIM_COLOR = 0x14000000;   // 很轻的一层压暗
    private static final int DIVIDER_COLOR = 0xFFC4C6D0; // outline variant
    private static final int TEXT_COLOR = 0xFF191C20;    // on surface：菜单文字固定深色

    private static View overlay;

    private BarMenu() {
    }

    static boolean isShowing() {
        return overlay != null;
    }

    static void dismiss() {
        View view = overlay;
        overlay = null;
        try {
            if (view != null && view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 同一个按钮再点一次就收起来 */
    static void toggle(ViewGroup decor, Context context, InputMethodService service,
                       BarConfig.Button button) {
        if (isShowing()) {
            dismiss();
            return;
        }
        try {
            show(decor, context, service, button);
        } catch (Throwable t) {
            Log.e(TAG, "弹出菜单失败", t);
            dismiss();
        }
    }

    private static void show(ViewGroup decor, Context context, final InputMethodService service,
                             BarConfig.Button button) {
        List<BarConfig.Item> items = button.menuItems;
        if (items == null || items.isEmpty()) {
            return;
        }

        FrameLayout scrim = new FrameLayout(context);
        scrim.setBackgroundColor(SCRIM_COLOR);
        scrim.setClickable(true);
        scrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dismiss();
            }
        });

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true); // 吃掉点击，避免点到卡片时把菜单关掉

        GradientDrawable cardBackground = new GradientDrawable();
        // 菜单卡片：干净的白底 + 深色文字（MD3 菜单规格），圆角 16dp
        cardBackground.setColor(0xFFFFFFFF);
        cardBackground.setCornerRadius(dp(context, 16));
        card.setBackground(cardBackground);
        card.setElevation(dp(context, 3));   // Level2：从键盘上浮起来
        card.setMinimumWidth(dp(context, 196));
        int cardPadding = dp(context, 6);
        card.setPadding(0, cardPadding, 0, cardPadding);

        boolean first = true;
        for (final BarConfig.Item item : items) {
            if (!first) {
                card.addView(makeDivider(context));
            }
            first = false;

            TextView row = new TextView(context);
            row.setText(item.label);
            row.setTextSize(16f);
            row.setTextColor(TEXT_COLOR);
            row.setGravity(Gravity.CENTER);
            row.setSingleLine(true);
            // 条目最小高度按 MD3 菜单来（16sp 文字 + 上下 14dp ≈ 48dp）
            int hPad = dp(context, 20);
            int vPad = dp(context, 14);
            row.setPadding(hPad, vPad, hPad, vPad);
            row.setBackground(ripple());
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    RunLog.add("菜单项点击: " + item.label + " → " + item.action);
                    // 先收起菜单，再把动作丢到下一轮消息循环执行：
                    // 触摸事件里直接 startActivity，个别 ROM 会当成"触摸过程中的动作"忽略掉；
                    // 原版 AI超级工具栏 也是这么做的（view.post）。这里跟着来。
                    v.post(new Runnable() {
                        public void run() {
                            dismiss();
                            BarActions.run(service, item.action, item.arg);
                        }
                    });
                }
            });
            card.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER;
        scrim.addView(card, cardParams);

        decor.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        overlay = scrim;
        RunLog.add("弹出菜单: " + button.label + "（" + items.size() + " 项）");
    }

    private static View makeDivider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(DIVIDER_COLOR);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1) / 2));
        int side = dp(context, 18);
        lp.setMargins(side, 0, side, 0);
        divider.setLayoutParams(lp);
        return divider;
    }

    private static RippleDrawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(0x1F0B57D0), null, null);
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
