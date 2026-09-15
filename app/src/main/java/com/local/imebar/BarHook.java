package com.local.imebar;

import android.util.Log;
import android.view.inputmethod.EditorInfo;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 只 hook 系统类 android.inputmethodservice.InputMethodService。
 *
 * 好处：任何输入法都通用，不用去碰各家输入法的私有类（原版那样 hook 各家 WebView 页面风险高得多）。
 */
final class BarHook {

    private static final String TAG = "ImeBar";
    private static volatile boolean installed;

    private BarHook() {
    }

    private interface After {
        void run(Object thisObject);
    }

    static void install(XposedModule module, ClassLoader classLoader, BarConfig cfg, String pkg) {
        if (installed) {
            return;
        }
        installed = true;
        try {
            Class<?> ims = Class.forName("android.inputmethodservice.InputMethodService", false, classLoader);

            // 键盘每次弹出
            hook(module, ims, "onStartInputView", new Class<?>[]{EditorInfo.class, boolean.class}, new After() {
                public void run(Object service) {
                    ImeBar.attach(service, cfg);
                }
            });

            // 窗口显示（有些场景不触发 onStartInputView）
            hook(module, ims, "onWindowShown", new Class<?>[0], new After() {
                public void run(Object service) {
                    ImeBar.attach(service, cfg);
                }
            });

            Log.i(TAG, "已在 " + pkg + " 中安装 hook");
        } catch (Throwable t) {
            Log.e(TAG, "安装 hook 失败: " + pkg, t);
        }
    }

    private static void hook(XposedModule module, Class<?> cls, String name, Class<?>[] params, final After after) {
        Method method = findMethod(cls, name, params);
        if (method == null) {
            Log.w(TAG, "这个方法不存在，跳过: " + cls.getName() + "#" + name);
            return;
        }
        try {
            module.hook(method).intercept(new XposedInterface.Hooker() {
                // 注意：Hooker.intercept 的签名里带 throws Throwable（Chain.proceed() 会抛），
                // 这里必须原样声明，否则编译不过。
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        after.run(chain.getThisObject());
                    } catch (Throwable t) {
                        Log.w(TAG, "处理失败", t);
                    }
                    return result;
                }
            });
            Log.i(TAG, "已 hook " + cls.getName() + "#" + name);
        } catch (Throwable t) {
            Log.e(TAG, "hook 失败: " + name, t);
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>[] params) {
        Class<?> current = cls;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
