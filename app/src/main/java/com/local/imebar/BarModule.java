package com.local.imebar;

import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块入口（META-INF/xposed/java_init.list 里写的就是这个类名）。
 *
 * 生命周期：框架先把模块加载进目标进程 -> onModuleLoaded()，
 * 然后每个被勾选的应用进程就绪 -> onPackageReady()。
 */
public final class BarModule extends XposedModule {

    public static final String TAG = "ImeBar";

    private volatile BarConfig config;

    public BarModule() {
        super();
    }

    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        String process = null;
        try {
            process = param.getProcessName();
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "模块已加载, 进程=" + process);

        SharedPreferences remote = null;
        try {
            remote = getRemotePreferences(BarConfig.GROUP);
        } catch (Throwable t) {
            Log.w(TAG, "读不到远程配置，先用默认值", t);
        }
        config = new BarConfig(remote);
    }

    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        try {
            if (!param.isFirstPackage()) {
                return;
            }
            String pkg = param.getPackageName();
            if (pkg == null || BarConfig.MODULE_PKG.equals(pkg)) {
                return; // 不处理自己
            }
            BarConfig cfg = config;
            if (cfg == null) {
                cfg = new BarConfig(null);
                config = cfg;
            }
            BarHook.install(this, param.getClassLoader(), cfg, pkg);
        } catch (Throwable t) {
            Log.e(TAG, "onPackageReady 出错", t);
        }
    }
}
