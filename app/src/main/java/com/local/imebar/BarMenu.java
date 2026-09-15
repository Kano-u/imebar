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
 * 弹出菜单：一张居中的浅色圆角卡片，条目之间有细分隔线。
 *
 * 没有用 PopupWindow，而是直接把一层覆盖 View 加到输入法窗口的 DecorView 上。
 * 原因：输入法窗口的高度只到键盘底部，PopupWindow 很容易被窗口边界裁掉；
 * 直接在窗口内部画反而更稳，也能做出"卡片浮在键盘上"的效果。
 */
final class BarMenu {

    private static final String TAG = "ImeBar";
    private static final int SCRIM_COLOR = 0x14000000;   // 很轻的一层压暗
    private static final int DIVIDER_COLOR = 0x1F000000;

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
                       BarConfig.Button button, BarConfig config) {
        if (isShowing()) {
            dismiss();
            return;
        }
        try {
            show(decor, context, service, button, config);
        } catch (Throwable t) {
            Log.e(TAG, "弹出菜单失败", t);
            dismiss();
        }
    }

    private static void show(ViewGroup decor, Context context, final InputMethodService service,
                             BarConfig.Button button, BarConfig config) {
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
        // 菜单卡片用干净的白色（和设置页的卡片一致），配深色文字
        cardBackground.setColor(0xFFFFFFFF);
        cardBackground.setCornerRadius(dp(context, 26));
        card.setBackground(cardBackground);
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
            row.setTextSize(17f);
            row.setTextColor(config.textColor());
            row.setGravity(Gravity.CENTER);
            row.setSingleLine(true);
            int hPad = dp(context, 28);
            int vPad = dp(context, 18);
            row.setPadding(hPad, vPad, hPad, vPad);
            row.setBackground(ripple());
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    dismiss();
                    BarActions.run(service, item.action, item.arg);
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
        return new RippleDrawable(ColorStateList.valueOf(0x1A000000), null, null);
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
