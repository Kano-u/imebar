package com.local.imebar;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
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

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 把工具栏画到输入法窗口里。
 *
 * 位置：默认贴在窗口【底部】，也就是键盘那排按键的下面。
 * 坐标系是"输入法窗口"本身：底部距离 = 距键盘底边的高度，左右边距 = 距窗口两侧的距离。
 *
 * 配置读取有两条路：
 *   1) 每次键盘弹出（onStartInputView / onWindowShown）都重新读一次快照 —— 保证设置一定生效；
 *   2) 设置页保存后会发一条广播，收到就立刻重读并重建 —— 不用切输入法，马上看到效果。
 * 之所以不能只读一次：libxposed 的远程偏好是内存快照，创建之后不会自己更新。
 */
final class ImeBar {

    private static final String TAG = "ImeBar";
    /** 按钮间距固定 8dp（原版没有这项设置，就不做成可调） */
    private static final int BUTTON_GAP_DP = 8;

    private static ConfigSource source;
    private static WeakReference<InputMethodService> lastService =
            new WeakReference<InputMethodService>(null);
    /** 记着上次挂上去的那条栏，配置变了就重建 */
    private static View lastBar;
    private static String lastSignature;
    private static boolean receiverRegistered;
    /** 设置页刚刚通过广播推过来的配置（进程内优先用它，一定是最新的） */
    private static volatile BarConfig pushed;

    private ImeBar() {
    }

    static void setSource(ConfigSource configSource) {
        source = configSource;
    }

    /** 键盘弹出 / 窗口显示时调用：重新读配置并按需重建 */
    static void attach(Object serviceObject) {
        if (!(serviceObject instanceof InputMethodService)) {
            return;
        }
        InputMethodService service = (InputMethodService) serviceObject;
        lastService = new WeakReference<InputMethodService>(service);
        registerConfigReceiver(service);
        attach(service, readConfig());
    }

    /** 收到"设置已保存"广播后调用：立刻重读并重建 */
    static void refresh() {
        InputMethodService service = lastService.get();
        if (service == null) {
            return;
        }
        attach(service, readConfig());
    }

    private static BarConfig readConfig() {
        BarConfig pushedConfig = pushed;
        if (pushedConfig != null) {
            return pushedConfig;
        }
        ConfigSource current = source;
        return current != null ? current.get() : BarConfig.defaults();
    }

    private static void attach(InputMethodService service, BarConfig cfg) {
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
            float density = viewContext.getResources().getDisplayMetrics().density;

            View bar = build(viewContext, service, cfg);
            bar.setAlpha(cfg.opacityPercent() / 100f); // 显示透明度：整条栏有效

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            int edge = (int) (cfg.edgeDistanceDp() * density + 0.5f);
            if (cfg.isBottom()) {
                lp.gravity = Gravity.BOTTOM;
                lp.bottomMargin = edge;
            } else {
                lp.gravity = Gravity.TOP;
                lp.topMargin = edge;
            }

            root.addView(bar, lp);
            lastBar = bar;
            lastSignature = signature;
            Log.i(TAG, "工具栏已挂载(" + (cfg.isBottom() ? "键盘底部" : "键盘顶部")
                    + "), 按钮数=" + cfg.buttons().size()
                    + ", 底部距离=" + cfg.edgeDistanceDp() + "dp"
                    + ", 左右边距=" + cfg.sideMarginDp() + "dp"
                    + ", 字号=" + cfg.textSizeSp() + "dp"
                    + ", 透明度=" + cfg.opacityPercent() + "%");
        } catch (Throwable t) {
            Log.e(TAG, "挂载工具栏失败", t);
        }
    }

    /**
     * 让设置页的改动能立刻生效。
     *
     * 关键点：广播里带的是**配置的数值本身**，收到就直接用，不再去读远程偏好
     * （远程偏好是快照，LSPosed 侧还可能缓存对象，重读拿不到新值）。
     */
    private static void registerConfigReceiver(Context context) {
        if (receiverRegistered) {
            return;
        }
        receiverRegistered = true;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                public void onReceive(Context ctx, Intent intent) {
                    if (intent == null || !BarConfig.ACTION_CONFIG_CHANGED.equals(intent.getAction())) {
                        return;
                    }
                    BarConfig cfg = BarConfig.fromBundle(intent.getExtras());
                    pushed = cfg;
                    Log.i(TAG, "收到设置推送：底部距离=" + cfg.edgeDistanceDp() + "dp"
                            + ", 左右边距=" + cfg.sideMarginDp() + "dp"
                            + ", 字号=" + cfg.textSizeSp() + "dp"
                            + ", 透明度=" + cfg.opacityPercent() + "%"
                            + ", 位置=" + (cfg.isBottom() ? "底部" : "顶部"));
                    refresh();
                }
            };
            IntentFilter filter = new IntentFilter(BarConfig.ACTION_CONFIG_CHANGED);
            int flags = Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_EXPORTED : 0;
            context.registerReceiver(receiver, filter, null, null, flags);
            Log.i(TAG, "已注册配置变更接收器");
        } catch (Throwable t) {
            Log.w(TAG, "注册配置变更接收器失败（改动仍会在下次弹出键盘时生效）", t);
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

        // 用 px 直接给定字号：版式完全按 dp 走，不受系统字体缩放影响（否则用户改字体大小会把栏撑变形）
        float textPx = cfg.textSizeSp() * density;
        int gap = (int) (BUTTON_GAP_DP * density + 0.5f);
        int sidePad = (int) (cfg.sideMarginDp() * density + 0.5f);
        int vPad = (int) ((cfg.isPill() ? 6 : 8) * density + 0.5f);
        int hPad = (int) ((cfg.isPill() ? 12 : 6) * density + 0.5f);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        List<BarConfig.Button> buttons = cfg.buttons();
        boolean stretch = cfg.isStretch();

        for (int i = 0; i < buttons.size(); i++) {
            final BarConfig.Button button = buttons.get(i);

            TextView item = new TextView(context);
            item.setText(button.label);
            item.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);
            item.setTextColor(cfg.textColor());
            item.setGravity(Gravity.CENTER);
            item.setSingleLine(true);
            item.setIncludeFontPadding(false);
            item.setPadding(hPad, vPad, hPad, vPad);
            if (cfg.isPill()) {
                item.setBackground(pillBackground(cfg.pillColor(),
                        (textPx + 2 * (float) vPad) / 2f));
            }
            item.setClickable(true);
            item.setOnClickListener(new View.OnClickListener() {
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

            LinearLayout.LayoutParams lp;
            if (stretch) {
                // 均分铺满：每个按钮等宽，像 AI超级工具栏 那样横向排开
                lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(gap / 2, 0, gap / 2, 0);
            } else {
                lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i < buttons.size() - 1) {
                    lp.rightMargin = gap;
                }
            }
            item.setLayoutParams(lp);
            row.addView(item);
        }

        View bar;
        if (stretch) {
            row.setPadding(sidePad, vPad, sidePad, vPad);
            bar = row;
        } else {
            HorizontalScrollView scroll = new HorizontalScrollView(context);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setPadding(sidePad, vPad, sidePad, vPad);
            scroll.addView(row, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));
            bar = scroll;
        }
        bar.setBackgroundColor(cfg.barBackgroundColor());
        return bar;
    }

    /** 胶囊背景：圆角取高度的一半，得到全圆角 */
    private static GradientDrawable pillBackground(int color, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }
}
