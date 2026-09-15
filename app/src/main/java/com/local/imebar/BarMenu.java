package com.local.imebar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.inputmethodservice.InputMethodService;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 弹出菜单：在被点击的那个按钮**正上方**弹出一张白色圆角卡片（圆角 12dp + 阴影），
 * 条目紧凑、白底黑字、条目之间有细分隔线。
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
    static void toggle(ViewGroup decor, View anchor, Context context, InputMethodService service,
                       BarConfig.Button button) {
        if (isShowing()) {
            dismiss();
            return;
        }
        try {
            show(decor, anchor, context, service, button);
        } catch (Throwable t) {
            Log.e(TAG, "弹出菜单失败", t);
            dismiss();
        }
    }

    private static void show(ViewGroup decor, View anchor, Context context,
                             final InputMethodService service, BarConfig.Button button) {
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
        // 菜单卡片：白底 + 黑字，圆角 12dp（紧凑一点，不占地方）
        cardBackground.setColor(0xFFFFFFFF);
        cardBackground.setCornerRadius(dp(context, 12));
        card.setBackground(cardBackground);
        card.setElevation(dp(context, 3));   // Level2：从键盘上浮起来
        card.setMinimumWidth(dp(context, 156));
        int cardPadding = dp(context, 4);
        card.setPadding(0, cardPadding, 0, cardPadding);

        boolean first = true;
        for (final BarConfig.Item item : items) {
            if (!first) {
                card.addView(makeDivider(context));
            }
            first = false;

            TextView row = new TextView(context);
            row.setText(item.label);
            row.setTextSize(14f);
            row.setTextColor(TEXT_COLOR);
            row.setGravity(Gravity.CENTER);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);   // 宽度固定了，长文字打省略号
            // 紧凑条目：14sp 文字 + 上下 8dp ≈ 36dp，好点又不挤
            int hPad = dp(context, 16);
            int vPad = dp(context, 8);
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

        scrim.addView(card, anchoredParams(card, anchor, decor, context));

        decor.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        overlay = scrim;
        RunLog.add("弹出菜单: " + button.label + "（" + items.size() + " 项）");
    }

    /**
     * 卡片位置：贴在被点的按钮正上方（水平居中对齐按钮），两侧夹在窗口内；
     * 上方实在放不下就改到按钮下方；拿不到有效坐标就退回居中，保证菜单一定弹得出来。
     *
     * 因为要"弹出瞬间就是最终位置"，这里先手动 measure 出卡片尺寸再算坐标，
     * 不依赖布局完成后的回调（否则会闪一帧错误位置）。
     */
    private static FrameLayout.LayoutParams anchoredParams(LinearLayout card, View anchor,
                                                          ViewGroup decor, Context context) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);

        int decorW = decor.getWidth();
        int decorH = decor.getHeight();
        if (decorW <= 0 || decorH <= 0 || anchor == null || anchor.getWidth() <= 0) {
            params.gravity = Gravity.CENTER;   // 兜底：还是弹得出来
            return params;
        }

        // 宽度固定为窗口宽（=屏宽）的 1/3：内容多宽都只占三分之一，不遮键盘
        int cardW = decorW / 3;
        card.measure(View.MeasureSpec.makeMeasureSpec(cardW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int cardH = card.getMeasuredHeight();
        if (cardW <= 0 || cardH <= 0) {
            params.gravity = Gravity.CENTER;
            return params;
        }

        int[] anchorLoc = new int[2];
        int[] decorLoc = new int[2];
        anchor.getLocationInWindow(anchorLoc);
        decor.getLocationInWindow(decorLoc);
        int anchorCenterX = anchorLoc[0] - decorLoc[0] + anchor.getWidth() / 2;
        int anchorTop = anchorLoc[1] - decorLoc[1];
        int anchorBottom = anchorTop + anchor.getHeight();

        int margin = dp(context, 8);
        int gap = dp(context, 6);
        int x = anchorCenterX - cardW / 2;
        int y = anchorTop - cardH - gap;
        if (x < margin) {
            x = margin;
        }
        if (x + cardW > decorW - margin) {
            x = decorW - margin - cardW;
        }
        if (y < margin) {
            y = anchorBottom + gap;   // 上方放不下，改到按钮下方
        }

        params.width = cardW;   // 不再 WRAP_CONTENT，宽度就是上面定的 1/3
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = Math.max(x, 0);
        params.topMargin = Math.max(y, 0);
        return params;
    }

    private static View makeDivider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(DIVIDER_COLOR);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1) / 2));
        int side = dp(context, 12);
        lp.setMargins(side, 0, side, 0);
        divider.setLayoutParams(lp);
        return divider;
    }

    private static RippleDrawable ripple() {
        // 纯白底黑字配中性黑涟漪更协调（主色涟漪留给设置页的按钮）
        return new RippleDrawable(ColorStateList.valueOf(0x1F000000), null, null);
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
