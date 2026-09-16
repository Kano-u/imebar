package com.local.imebar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.inputmethodservice.InputMethodService;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 弹出菜单：在被点击的那个按钮**正上方**弹出一张白色圆角卡片（圆角 12dp + 阴影），
 * 紧凑条目、白底黑字、条目之间有细分隔线。
 *
 * 支持**多级菜单**：菜单项本身还能是菜单。子菜单不另开卡片，而是把这张卡片的内容
 * 原地换成下一层，最上面多一行「← 返回」——卡片宽度是屏宽的 1/3，叠第二张卡片会互相遮挡。
 *
 * 没有用 PopupWindow，而是直接把一层覆盖 View 加到输入法窗口的 DecorView 上。
 * 原因：输入法窗口的高度只到键盘底部，PopupWindow 很容易被窗口边界裁掉；
 * 直接在窗口内部画反而更稳，也能做出"卡片浮在键盘上"的效果。
 *
 * 注意：这里跑在输入法进程，拿到的是输入法的 Resources，用不了模块自己的 R.color，
 * 所以颜色只能写死（和 res/values/colors.xml 里的 MD3 token 对应）。
 */
final class BarMenu {

    private static final int SCRIM_COLOR = 0x14000000;   // 很轻的一层压暗
    private static final int DIVIDER_COLOR = 0xFFC4C6D0; // outline variant
    private static final int TEXT_COLOR = 0xFF191C20;    // on surface：菜单文字固定深色
    private static final String BACK_LABEL = "← 返回";

    /** 一次打开的菜单：环境 + 从根到当前层的路径（「返回」用） */
    private static final class Session {
        final ViewGroup decor;
        final View anchor;
        final Context context;
        final InputMethodService service;
        final List<BarConfig.Button> path = new ArrayList<BarConfig.Button>();
        FrameLayout scrim;
        LinearLayout card;

        Session(ViewGroup decor, View anchor, Context context, InputMethodService service) {
            this.decor = decor;
            this.anchor = anchor;
            this.context = context;
            this.service = service;
        }
    }

    private static Session session;

    private BarMenu() {
    }

    static boolean isShowing() {
        return session != null;
    }

    static void dismiss() {
        Session current = session;
        session = null;
        if (current == null) {
            return;
        }
        try {
            FrameLayout scrim = current.scrim;
            if (scrim != null && scrim.getParent() instanceof ViewGroup) {
                ((ViewGroup) scrim.getParent()).removeView(scrim);
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
        } catch (Throwable ignored) {
            dismiss();   // 弹不出来就收干净，别留一层浮在上面
        }
    }

    /** 搭骨架：压暗层 + 空卡片，内容交给 showLevel() 填 */
    private static void show(ViewGroup decor, View anchor, Context context,
                             InputMethodService service, BarConfig.Button button) {
        List<BarConfig.Button> items = button.menuItems;
        if (items == null || items.isEmpty()) {
            return;
        }

        Session current = new Session(decor, anchor, context, service);
        current.path.add(button);

        FrameLayout scrim = new FrameLayout(context);
        scrim.setBackgroundColor(SCRIM_COLOR);
        scrim.setClickable(true);
        scrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dismiss();   // 点卡片外面收起
            }
        });

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);   // 吃掉点击，避免点到卡片里把菜单关掉
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFFFFFFF);
        background.setCornerRadius(dp(context, 12));
        card.setBackground(background);
        card.setMinimumWidth(dp(context, 156));

        current.scrim = scrim;
        current.card = card;

        scrim.addView(card, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        decor.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        session = current;

        showLevel();
    }

    /**
     * 渲染当前这一层：把卡片内容换成 path 末尾那一层的 menuItems。
     * 进子菜单、返回上一层都走这里——原地换内容，不叠第二张卡片。
     */
    private static void showLevel() {
        Session current = session;
        if (current == null) {
            return;
        }
        LinearLayout card = current.card;
        BarConfig.Button level = current.path.get(current.path.size() - 1);

        card.removeAllViews();
        int cardPadding = dp(current.context, 4);
        card.setPadding(0, cardPadding, 0, cardPadding);

        // 进过子菜单才有「返回」：点它退回上一层
        if (current.path.size() > 1) {
            card.addView(makeRow(BACK_LABEL, current.context, new View.OnClickListener() {
                public void onClick(View v) {
                    back();
                }
            }));
            card.addView(makeDivider(current.context));
        }

        List<BarConfig.Button> items = level.menuItems;
        for (int i = 0; i < items.size(); i++) {
            final BarConfig.Button item = items.get(i);
            if (i > 0) {
                card.addView(makeDivider(current.context));
            }
            card.addView(makeRow(item.label, current.context, new View.OnClickListener() {
                public void onClick(View v) {
                    onItemClick(item, v);
                }
            }));
        }

        // 换层后高度变了，重新算一次位置：anchoredParams 是手动 measure 的，天然支持
        card.setLayoutParams(anchoredParams(card, current.anchor, current.decor, current.context));
    }

    private static void onItemClick(BarConfig.Button item, View row) {
        Session current = session;
        if (current == null) {
            return;
        }
        if (item.menuItems != null && !item.menuItems.isEmpty()) {
            current.path.add(item);   // 进下一层
            showLevel();
            return;
        }

        final InputMethodService service = current.service;
        // 先收起菜单，再把动作丢到下一轮消息循环执行：
        // 触摸事件里直接 startActivity，个别 ROM 会当成"触摸过程中的动作"忽略掉；
        // 原版 AI超级工具栏 也是这么做的（view.post）。这里跟着来。
        row.post(new Runnable() {
            public void run() {
                dismiss();
                BarActions.run(service, item.action, item.arg);
            }
        });
    }

    /** 退回上一层 */
    private static void back() {
        Session current = session;
        if (current == null || current.path.size() <= 1) {
            return;
        }
        current.path.remove(current.path.size() - 1);
        showLevel();
    }

    /** 一行菜单项：紧凑、白底黑字、点按有涟漪、长文字打省略号 */
    private static TextView makeRow(String label, Context context, View.OnClickListener listener) {
        TextView row = new TextView(context);
        row.setText(label);
        row.setTextSize(14f);
        row.setTextColor(TEXT_COLOR);
        row.setGravity(Gravity.CENTER);
        row.setSingleLine(true);
        row.setEllipsize(TextUtils.TruncateAt.END);   // 卡片宽度固定，长文字打省略号
        int hPad = dp(context, 16);
        int vPad = dp(context, 8);
        row.setPadding(hPad, vPad, hPad, vPad);
        row.setBackground(ripple());
        row.setOnClickListener(listener);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
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

    /**
     * 卡片位置：贴在被点的按钮正上方（水平居中对齐按钮），两侧夹在窗口内；
     * 上方放不下就改到按钮下方，下方也放不下就贴窗口顶部；拿不到有效坐标就退回居中，
     * 保证菜单一定弹得出来。
     *
     * 因为要"弹出/换层瞬间就是最终位置"，这里先手动 measure 出卡片尺寸再算坐标，
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
            int below = anchorBottom + gap;
            // 上方放不下：优先落到按钮下方；下方也放不下（菜单太长）就贴窗口顶部
            y = below + cardH > decorH - margin ? margin : below;
        }

        params.width = cardW;   // 不再 WRAP_CONTENT，宽度就是上面定的 1/3
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = Math.max(x, 0);
        params.topMargin = Math.max(y, 0);
        return params;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
