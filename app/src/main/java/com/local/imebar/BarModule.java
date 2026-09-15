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

    /** 每次调用都重新取快照，保证设置改动能被读到 */
    private final ConfigSource source = new ConfigSource() {
        public BarConfig get() {
            try {
                return BarConfig.fromPrefs(BarModule.this.getRemotePreferences(BarConfig.GROUP));
            } catch (Throwable t) {
                Log.w(TAG, "读取远程配置失败，先用默认值", t);
                RunLog.add("远程配置读取失败: " + t);
                return BarConfig.defaults();
            }
        }
    };

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
        RunLog.add("模块已加载 进程=" + process);

        try {
            SharedPreferences remote = getRemotePreferences(BarConfig.GROUP);
            int count = remote == null ? 0 : remote.getAll().size();
            Log.i(TAG, "远程配置可读, 键数量=" + count);
            RunLog.add("远程配置 键数量=" + count + " 内容=" + trim(String.valueOf(
                    remote == null ? null : remote.getAll())));
            RunLog.add("框架=" + getFrameworkName() + " api=" + getApiVersion());
        } catch (Throwable t) {
            Log.w(TAG, "读不到远程配置，先用默认值", t);
            RunLog.add("远程配置异常: " + t);
        }
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
            BarHook.install(this, param.getClassLoader(), source, pkg);
        } catch (Throwable t) {
            Log.e(TAG, "onPackageReady 出错", t);
            RunLog.add("onPackageReady 出错: " + t);
        }
    }

    private static String trim(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
