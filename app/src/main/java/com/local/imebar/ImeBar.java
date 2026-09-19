package com.local.imebar;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.inputmethodservice.InputMethodService;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 把工具栏画到输入法窗口里，并负责把设置页的值拿进来。
 *
 * 形态固定：贴在键盘最底部、纯文字按钮、等宽铺满（都不做可选项）。
 * 坐标系就是"输入法窗口"本身：底部距离 = 距键盘底边的高度，左右边距 = 距窗口两侧的距离。
 *
 * 配置有四条来源，按可靠性排序：
 *   1) **本地快照**：每次成功拿到的完整配置写进输入法进程自己的 SharedPreferences，
 *      冷启动或模块 App 被系统冻结时先读它，避免首屏闪回默认值；
 *   2) **拉取**：弹出键盘时后台跨进程向模块 App 的 ConfigProvider 要一份当前配置；
 *   3) **推送**：设置页保存时把数值打包进广播，收到立刻生效；
 *   4) **兜底**：libxposed 的远程偏好快照（它不会自己刷新，只在进程刚启动时读一次）。
 */
final class ImeBar {

    /** 按钮间距固定 8dp */
    private static final int BUTTON_GAP_DP = 8;
    /** 拉取配置的最小间隔，避免频繁跨进程调用 */
    private static final long PULL_INTERVAL_MS = 1500;
    /** 首次拉取失败后的短重试：给模块进程一点启动时间，但不阻塞键盘首帧 */
    private static final long PULL_RETRY_MS = 400;
    private static final int PULL_RETRY_COUNT = 2;
    /** 输入法进程自己的配置快照，不会被系统冻结模块 App 影响 */
    private static final String SNAPSHOT_GROUP = "config_snapshot";
    private static final String SNAPSHOT_KEY = "config";

    private static ConfigSource source;
    private static WeakReference<InputMethodService> lastService =
            new WeakReference<InputMethodService>(null);
    /** 当前生效的配置 */
    private static volatile BarConfig current;
    /** 记着上次挂上去的那条栏，配置变了就重建 */
    private static View lastBar;
    private static String lastSignature;
    private static boolean receiverRegistered;
    private static long lastPullAt;
    /** 只让最新一轮拉取结果生效，避免旧请求覆盖新广播/新配置 */
    private static int pullGeneration;

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
        boolean fromSnapshot = false;
        if (config == null) {
            config = loadSnapshot(service);   // 优先用输入法进程自己的快照
            fromSnapshot = config != null;
        }
        if (config == null) {
            config = sourceGet();   // 远程偏好只作兜底，拿不到时不能挡住设置页已有值
        }
        current = config;
        render(service, config);

        pullFromApp(service, fromSnapshot);
    }

    // ---------- 配置来源 ----------

    private static BarConfig sourceGet() {
        ConfigSource configSource = source;
        try {
            return configSource != null ? configSource.get() : BarConfig.defaults();
        } catch (Throwable t) {
            return BarConfig.defaults();   // 读不到就用默认值，别让输入法看到异常
        }
    }

    /** 读输入法进程上次成功保存的快照；没有时返回 null，继续走其它来源 */
    private static BarConfig loadSnapshot(Context context) {
        try {
            String raw = context.getSharedPreferences(SNAPSHOT_GROUP, 0)
                    .getString(SNAPSHOT_KEY, null);
            if (raw == null || raw.length() == 0) {
                return null;
            }
            return BarConfig.fromSnapshot(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把完整配置存到输入法进程自己的快照，供下次冷启动首屏使用 */
    private static void saveSnapshot(Context context, BarConfig config) {
        if (config == null) {
            return;
        }
        try {
            context.getApplicationContext().getSharedPreferences(SNAPSHOT_GROUP, 0)
                    .edit().putString(SNAPSHOT_KEY, config.toSnapshot()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 后台向模块 App 拉取最新配置（最可靠的一条路） */
    private static void pullFromApp(final Context context, boolean immediate) {
        long now = SystemClock.uptimeMillis();
        if (!immediate && now - lastPullAt < PULL_INTERVAL_MS) {
            return;
        }
        lastPullAt = now;
        final int generation = ++pullGeneration;
        new Thread(new Runnable() {
            public void run() {
                for (int attempt = 0; attempt <= PULL_RETRY_COUNT; attempt++) {
                    Bundle result = null;
                    try {
                        result = context.getContentResolver().call(
                                Uri.parse("content://" + ConfigProvider.AUTHORITY),
                                ConfigProvider.METHOD_GET_CONFIG, null, null);
                    } catch (Throwable ignored) {
                        // 模块 App 可能被冻结或暂时拉不起来：短等后重试，失败就继续用快照
                    }
                    if (result != null) {
                        final BarConfig config = BarConfig.fromBundle(result);
                        final Context appContext = context.getApplicationContext();
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            public void run() {
                                if (generation != pullGeneration) {
                                    return;
                                }
                                saveSnapshot(appContext, config);
                                applyConfig(config);
                            }
                        });
                        return;
                    }
                    if (attempt < PULL_RETRY_COUNT) {
                        try {
                            Thread.sleep(PULL_RETRY_MS * (attempt + 1));
                        } catch (InterruptedException ignored) {
                            return;
                        }
                    }
                }
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
                    Bundle extras = intent.getExtras();
                    if (extras == null) {
                        return;   // 空广播不能把已有快照覆盖成默认值
                    }
                    BarConfig config = BarConfig.fromBundle(extras);
                    pullGeneration++;   // 已在途的旧拉取结果不得再覆盖这份推送
                    saveSnapshot(ctx, config);
                    applyConfig(config);
                }
            };
            IntentFilter filter = new IntentFilter(BarConfig.ACTION_CONFIG_CHANGED);
            int flags = Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_EXPORTED : 0;
            context.registerReceiver(receiver, filter, null, null, flags);
        } catch (Throwable ignored) {
            // 注册不上就只剩"下次弹键盘重新拉取"这一条路，不致命
        }
    }

    /** 收到一份新配置（推送或拉取都走这里）：存下来，然后立刻重画工具栏 */
    private static void applyConfig(BarConfig config) {
        current = config;
        InputMethodService service = lastService.get();
        if (service != null) {
            render(service, config);
        }
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
                return;   // 拿不到能挂 View 的容器
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
        } catch (Throwable ignored) {
            // 挂不上就算了：绝不能把异常抛回输入法进程
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
                            // 把被点的按钮传下去：菜单要贴着它正上方弹出来
                            BarMenu.toggle((ViewGroup) decor, v, context, service, button);
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

}
