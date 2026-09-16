package com.local.imebar;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块入口（META-INF/xposed/java_init.list 里写的就是这个类名）。
 *
 * 生命周期：框架先把模块加载进目标进程，然后每个被勾选的应用进程就绪 -> onPackageReady()。
 * onModuleLoaded() 不用覆盖：那里以前只是写日志，而日志系统已经整个删掉了。
 */
public final class BarModule extends XposedModule {

    /** 每次调用都重新取快照，保证设置改动能被读到 */
    private final ConfigSource source = new ConfigSource() {
        public BarConfig get() {
            try {
                return BarConfig.fromPrefs(BarModule.this.getRemotePreferences(BarConfig.GROUP));
            } catch (Throwable t) {
                return BarConfig.defaults();
            }
        }
    };

    public BarModule() {
        super();
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
            BarHook.install(this, param.getClassLoader(), source);
        } catch (Throwable ignored) {
            // 装不上就算了，别把目标进程搞崩
        }
    }
}
