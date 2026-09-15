package com.local.imebar;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodService;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 把一条按钮栏画到输入法窗口的最上方。
 *
 * 输入法窗口本身是个 Dialog（InputMethodService.getWindow()），
 * 我们把 View 加进它的 DecorView（一个 FrameLayout），gravity=TOP 就是"键盘上方"。
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

    private static View build(Context context, final InputMethodService service, final BarConfig cfg) {
        float density = context.getResources().getDisplayMetrics().density;

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int rowPad = dp(density, 6);
        row.setPadding(rowPad, 0, rowPad, 0);

        for (final BarConfig.Button button : cfg.buttons()) {
            TextView item = new TextView(context);
            item.setText(button.label);
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            item.setTextColor(cfg.textColor());
            item.setGravity(Gravity.CENTER);
            item.setSingleLine(true);
            int hPad = dp(density, 10);
            int vPad = dp(density, 4);
            item.setPadding(hPad, vPad, hPad, vPad);
            item.setBackground(chipBackground());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(density, 6);
            item.setLayoutParams(lp);
            item.setClickable(true);
            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    BarActions.run(service, button.action, button.arg);
                }
            });
            row.addView(item);
        }

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(cfg.backgroundColor());
        scroll.addView(row, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private static GradientDrawable chipBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0x33FFFFFF);
        drawable.setCornerRadius(14f);
        return drawable;
    }

    private static int dp(float density, int value) {
        return (int) (value * density + 0.5f);
    }
}
