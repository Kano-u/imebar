package com.local.imebar;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 把工具栏画到输入法窗口里，并负责把设置页的值拿进来。
 *
 * 形态固定：贴在键盘最底部、纯文字按钮、等宽铺满（都不做可选项）。
 * 坐标系就是"输入法窗口"本身：底部距离 = 距键盘底边的高度，左右边距 = 距窗口两侧的距离。
 *
 * 配置有三条来源，按可靠性排序：
 *   1) **拉取**：每次弹出键盘，后台跨进程向模块 App 的 ConfigProvider 要一份当前配置
 *      （App 进程刚写过的偏好在它自己内存里一定是最新的）——这条最可靠；
 *   2) **推送**：设置页保存时把数值打包进广播发过来，收到立刻生效（不用等下次弹键盘）；
 *   3) **兜底**：libxposed 的远程偏好快照（它不会自己刷新，只在进程刚启动时读一次）。
 */
final class ImeBar {

    private static final String TAG = "ImeBar";
    private static final String VERSION = "0.11.0";
    /** 按钮间距固定 8dp */
    private static final int BUTTON_GAP_DP = 8;
    /** 拉取配置的最小间隔，避免频繁跨进程调用 */
    private static final long PULL_INTERVAL_MS = 1500;

    private static ConfigSource source;
    private static WeakReference<InputMethodService> lastService =
            new WeakReference<InputMethodService>(null);
    /** 当前生效的配置 */
    private static volatile BarConfig current;
    /** 记着上次挂上去的那条栏，配置变了就重建 */
    private static View lastBar;
    private static String lastSignature;
    private static boolean receiverRegistered;
    private static boolean firstAttachLogged;
    private static boolean firstPullLogged;
    private static long lastPullAt;

    private ImeBar() {
    }

    static void setSource(ConfigSource configSource) {
        source = configSource;
    }

    /** 键盘弹出 / 窗口显示时调用 */
    static void attach(Object serviceObject) {
        if (!(serviceObject instanceof InputMethodService)) {
            return;
        }
        InputMethodService service = (InputMethodService) serviceObject;
        lastService = new WeakReference<InputMethodService>(service);
        registerConfigReceiver(service);

        BarConfig config = current;
        if (config == null) {
            config = sourceGet();
            current = config;
        }
        render(service, config);

        if (!firstAttachLogged) {
            firstAttachLogged = true;
            RunLog.add("首次弹出键盘，生效配置 " + config.summary());
            toast(service, "简易输入法工具栏 " + VERSION + " 已生效：距离 " + config.edgeDistanceDp()
                    + "dp / 字号 " + config.textSizeSp() + "dp / 透明度 " + config.opacityPercent() + "%");
        }
        pullFromApp(service);
    }

    // ---------- 配置来源 ----------

    private static BarConfig sourceGet() {
        ConfigSource configSource = source;
        try {
            return configSource != null ? configSource.get() : BarConfig.defaults();
        } catch (Throwable t) {
            Log.w(TAG, "读远程配置失败，用默认值", t);
            return BarConfig.defaults();
        }
    }

    /** 后台向模块 App 拉取最新配置（最可靠的一条路） */
    private static void pullFromApp(final Context context) {
        long now = SystemClock.uptimeMillis();
        if (now - lastPullAt < PULL_INTERVAL_MS) {
            return;
        }
        lastPullAt = now;
        new Thread(new Runnable() {
            public void run() {
                Bundle result = null;
                try {
                    result = context.getContentResolver().call(
                            Uri.parse("content://" + ConfigProvider.AUTHORITY),
                            ConfigProvider.METHOD_GET_CONFIG, null, null);
                } catch (Throwable t) {
                    Log.w(TAG, "拉取配置失败（模块 App 可能被强制停止）", t);
                    RunLog.add("拉取配置失败: " + t);
                }
                if (result == null) {
                    RunLog.add("拉取配置返回空");
                    return;
                }
                final BarConfig config = BarConfig.fromBundle(result);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        if (!firstPullLogged) {
                            firstPullLogged = true;
                            RunLog.add("首次拉取成功: " + config.summary());
                        }
                        applyConfig(context, config, "拉取");
                    }
                });
            }
        }, "imebar-config-pull").start();
    }

    /**
     * 让设置页的改动能立刻生效。
     * 广播里带的是**配置的数值本身**，收到就直接用，不再去读远程偏好。
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
                    Log.i(TAG, "收到设置页推送");
                    applyConfig(ctx, BarConfig.fromBundle(intent.getExtras()), "推送");
                }
            };
            IntentFilter filter = new IntentFilter(BarConfig.ACTION_CONFIG_CHANGED);
            int flags = Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_EXPORTED : 0;
            context.registerReceiver(receiver, filter, null, null, flags);
            Log.i(TAG, "已注册配置变更接收器");
            RunLog.add("已注册配置变更接收器");
        } catch (Throwable t) {
            Log.w(TAG, "注册配置变更接收器失败", t);
            RunLog.add("注册配置变更接收器失败: " + t);
        }
    }

    private static void applyConfig(Context context, BarConfig config, String how) {
        BarConfig previous = current;
        current = config;
        InputMethodService service = lastService.get();
        if (service != null) {
            render(service, config);
        }
        boolean changed = previous == null || !previous.signature().equals(config.signature());
        if (!changed) {
            return;
        }
        RunLog.add("配置更新(" + how + "): " + config.summary());
        Log.i(TAG, "配置已更新(" + how + ")：距离 " + config.edgeDistanceDp() + "dp, 边距 "
                + config.sideMarginDp() + "dp, 字号 " + config.textSizeSp() + "dp, 透明度 "
                + config.opacityPercent() + "%");
        toast(context, "设置已生效(" + how + ")：距离 " + config.edgeDistanceDp() + "dp / 字号 "
                + config.textSizeSp() + "dp / 透明度 " + config.opacityPercent() + "%");
    }

    // ---------- 绘制 ----------

    private static void render(InputMethodService service, BarConfig cfg) {
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
            lp.gravity = Gravity.BOTTOM;
            lp.bottomMargin = (int) (cfg.edgeDistanceDp() * density + 0.5f);

            root.addView(bar, lp);
            lastBar = bar;
            lastSignature = signature;
            RunLog.add("重建工具栏: " + cfg.summary());
            Log.i(TAG, "工具栏已挂载(键盘底部), 按钮数=" + cfg.buttons().size()
                    + ", 底部距离=" + cfg.edgeDistanceDp() + "dp"
                    + ", 左右边距=" + cfg.sideMarginDp() + "dp"
                    + ", 字号=" + cfg.textSizeSp() + "dp"
                    + ", 透明度=" + cfg.opacityPercent() + "%");
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

        // 用 px 直接给定字号：版式完全按 dp 走，不受系统字体缩放影响（否则用户改字体大小会把栏撑变形）
        float textPx = cfg.textSizeSp() * density;
        int gap = (int) (BUTTON_GAP_DP * density + 0.5f);
        int sidePad = (int) (cfg.sideMarginDp() * density + 0.5f);
        int vPad = (int) (8 * density + 0.5f);
        int hPad = (int) (6 * density + 0.5f);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(sidePad, vPad, sidePad, vPad);
        row.setBackgroundColor(cfg.barBackgroundColor());

        List<BarConfig.Button> buttons = cfg.buttons();
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
            item.setClickable(true);
            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (button.menuItems != null && !button.menuItems.isEmpty()) {
                        View decor = v.getRootView();
                        if (decor instanceof ViewGroup) {
                            BarMenu.toggle((ViewGroup) decor, context, service, button, cfg);
                        }
                    } else {
                        // 丢到下一轮消息循环再执行：在触摸事件里直接 startActivity，
                        // 个别 ROM 会把这次启动忽略掉。
                        v.post(new Runnable() {
                            public void run() {
                                BarActions.run(service, button.action, button.arg);
                            }
                        });
                    }
                }
            });

            // 均分铺满：每个按钮等宽
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(gap / 2, 0, gap / 2, 0);
            item.setLayoutParams(lp);
            row.addView(item);
        }
        return row;
    }

    /** 屏幕上可见的提示：方便不看日志也能判断哪一环生效了 */
    private static void toast(Context context, String text) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }
}
