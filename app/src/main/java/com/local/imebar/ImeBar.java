package com.local.imebar;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 把一条"胶囊按钮栏"画到输入法窗口的最上方。
 *
 * 输入法窗口本身是个 Dialog（InputMethodService.getWindow()），
 * 我们把 View 加进它的 DecorView（一个 FrameLayout），gravity=TOP 就是"键盘上方"。
 *
 * 视觉目标：
 *   - 整条栏默认全透明，让输入法自己的背景透出来（"没有存在感"）；
 *   - 按钮是文字为主的胶囊（全圆角、浅色底、深色字），不画图标；
 *   - 点普通按钮直接执行；点带菜单的按钮弹出居中卡片菜单。
 */
final class ImeBar {

    private static final String TAG = "ImeBar";

    /** 记着上次挂上去的那条栏，配置变了就重建 */
    private static View lastBar;
    private static String lastSignature;

    private ImeBar() {
    }

    static void attach(Object serviceObject, BarConfig cfg) {
        if (!(serviceObject instanceof InputMethodService)) {
            return;
        }
        InputMethodService service = (InputMethodService) serviceObject;
        try {
            if (!cfg.enabled()) {
                removeBar();
                return;
            }

            Dialog dialog = service.getWindow();
            if (dialog == null) {
                return;
            }
            Window window = dialog.getWindow();
            if (window == null) {
                return;
            }
            View decor = window.getDecorView();
            if (!(decor instanceof ViewGroup)) {
                Log.w(TAG, "DecorView 不是 ViewGroup，跳过");
                return;
            }
            ViewGroup root = (ViewGroup) decor;

            String signature = cfg.signature();
            if (lastBar != null && lastBar.getParent() == root && signature.equals(lastSignature)) {
                return; // 已经挂好且配置没变
            }
            BarMenu.dismiss(); // 重建之前先收掉可能还开着的菜单
            removeBarFrom(root);

            // 关键：用 DecorView 的 Context 建 View，它带主题；直接用 Service 当 Context 会崩
            Context viewContext = root.getContext();
            View bar = build(viewContext, service, cfg);

            float density = viewContext.getResources().getDisplayMetrics().density;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    (int) (cfg.heightDp() * density + 0.5f));
            lp.gravity = Gravity.TOP;

            root.addView(bar, lp);
            lastBar = bar;
            lastSignature = signature;
            Log.i(TAG, "工具栏已挂载, 按钮数=" + cfg.buttons().size());
        } catch (Throwable t) {
            Log.e(TAG, "挂载工具栏失败", t);
        }
    }

    private static void removeBar() {
        if (lastBar != null && lastBar.getParent() instanceof ViewGroup) {
            removeBarFrom((ViewGroup) lastBar.getParent());
        }
    }

    private static void removeBarFrom(ViewGroup parent) {
        try {
            if (lastBar != null) {
                parent.removeView(lastBar);
            }
        } catch (Throwable ignored) {
        }
        lastBar = null;
        lastSignature = null;
    }

    private static View build(final Context context, final InputMethodService service, final BarConfig cfg) {
        float density = context.getResources().getDisplayMetrics().density;
        int barHeight = (int) (cfg.heightDp() * density + 0.5f);

        // 胶囊高度：占满栏高减去上下留白
        int pillHeight = Math.max((int) (30 * density + 0.5f), barHeight - dp(density, 10));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int sidePad = dp(density, 8);
        row.setPadding(sidePad, 0, sidePad, 0);

        for (final BarConfig.Button button : cfg.buttons()) {
            TextView pill = new TextView(context);
            pill.setText(button.label);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            pill.setTextColor(cfg.textColor());
            pill.setGravity(Gravity.CENTER);
            pill.setSingleLine(true);
            pill.setIncludeFontPadding(false);
            int hPad = dp(density, 16);
            pill.setPadding(hPad, 0, hPad, 0);
            pill.setBackground(pillBackground(cfg.pillColor(), pillHeight / 2f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, pillHeight);
            lp.rightMargin = dp(density, 8);
            pill.setLayoutParams(lp);
            pill.setClickable(true);
            pill.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (button.menuItems != null && !button.menuItems.isEmpty()) {
                        View decor = v.getRootView();
                        if (decor instanceof ViewGroup) {
                            BarMenu.toggle((ViewGroup) decor, context, service, button, cfg);
                        }
                    } else {
                        BarActions.run(service, button.action, button.arg);
                    }
                }
            });
            row.addView(pill);
        }

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(cfg.barBackgroundColor());
        scroll.addView(row, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    /** 全圆角胶囊：圆角半径取高度的一半 */
    private static GradientDrawable pillBackground(int color, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    private static int dp(float density, int value) {
        return (int) (value * density + 0.5f);
    }
}
