package com.local.imebar;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.Arrays;
import java.util.List;

/**
 * 模块 App 侧提供配置的接口（运行在**模块 App 自己的进程**里）。
 *
 * 为什么需要它：输入法进程里读到的"远程偏好"是内存快照，还可能被缓存，拿不到最新值；
 * 而模块 App 进程自己刚写过的偏好**在它自己的内存里一定是最新的**。
 * 所以输入法进程每次弹出键盘时，直接跨进程问 App 要一份当前配置，就一定是最新的。
 *
 * 另外还提供 run_adb：adb 动作要 Shizuku（只在 App 进程里有），输入法进程只能发请求过来。
 * 这个方法开了个口子，所以卡了两道：调用方必须是"启用的输入法"，而且命令必须是当前按钮配置里
 * 已经写着的那一条——别人（或用户装的其他输入法）最多只能触发你自己配过的那几条命令。
 *
 * 说明：这个 Provider 是 exported 的，get_config 返回的只是界面显示参数（不含任何隐私数据）。
 */
public final class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "com.local.imebar.config";
    public static final String METHOD_GET_CONFIG = "get_config";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        if (METHOD_GET_CONFIG.equals(method)) {
            return getConfig(context);
        }
        if (AdbBridge.METHOD_RUN_ADB.equals(method)) {
            return runAdb(context, extras);
        }
        return null;
    }

    private Bundle getConfig(Context context) {
        BarConfig config = BarConfig.fromPrefs(context.getSharedPreferences(BarConfig.GROUP, 0));
        // 配置里确实有 adb/sh 按钮时，顺便在后台把 Shizuku 的 UserService 预热好，
        // 这样第一次点那个按钮也是快的（没配 adb 按钮的设备完全不会碰 Shizuku）
        if (hasShellButton(config.buttons())) {
            AdbBridge.warmUpAsync();
        }
        Bundle bundle = config.toBundle();
        bundle.putLong("timestamp", System.currentTimeMillis());
        return bundle;
    }

    /** 配置里有没有 adb / sh 动作的按钮（菜单子项也算） */
    private static boolean hasShellButton(List<BarConfig.Button> buttons) {
        if (buttons == null) {
            return false;
        }
        for (BarConfig.Button button : buttons) {
            if (button == null) {
                continue;
            }
            if ("adb".equals(button.action) || "sh".equals(button.action)) {
                return true;
            }
            if (hasShellButton(button.menuItems)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 跑一条 adb 命令（走 Shizuku）。
     * 注意：这里跑在 provider 的 binder 线程上（不是 App 主线程），所以命令慢也不会卡住设置页。
     */
    private Bundle runAdb(Context context, Bundle extras) {
        String command = AdbBridge.normalize(extras == null ? null : extras.getString(AdbBridge.KEY_CMD));
        if (command.length() == 0) {
            return AdbBridge.reply(AdbBridge.ERROR, "命令是空的");
        }
        if (!isEnabledInputMethod(context)) {
            return AdbBridge.reply(AdbBridge.REJECTED, "调用方不是启用的输入法");
        }
        if (!isConfiguredCommand(context, command)) {
            return AdbBridge.reply(AdbBridge.REJECTED, "这条命令不在当前按钮配置里，先点保存再试");
        }
        return AdbBridge.run(command);
    }

    /** 调用方 UID 名下的包，必须出现在"已启用的输入法"里 */
    private static boolean isEnabledInputMethod(Context context) {
        int uid = Binder.getCallingUid();
        try {
            PackageManager pm = context.getPackageManager();
            String[] calling = pm.getPackagesForUid(uid);
            if (calling == null || calling.length == 0) {
                return false;
            }
            List<String> packages = Arrays.asList(calling);
            InputMethodManager imm = (InputMethodManager)
                    context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) {
                return false;
            }
            for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
                if (packages.contains(info.getPackageName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 拿不到输入法列表就当作不信任
        }
        return false;
    }

    /** 命令必须等于当前按钮配置里某条 adb/sh 按钮的 arg（菜单子项也算） */
    private static boolean isConfiguredCommand(Context context, String command) {
        try {
            BarConfig config = BarConfig.fromPrefs(
                    context.getSharedPreferences(BarConfig.GROUP, 0));
            return containsCommand(config.buttons(), command);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean containsCommand(List<BarConfig.Button> buttons, String command) {
        if (buttons == null) {
            return false;
        }
        for (BarConfig.Button button : buttons) {
            if (button == null) {
                continue;
            }
            if (("adb".equals(button.action) || "sh".equals(button.action))
                    && command.equals(AdbBridge.normalize(button.arg))) {
                return true;
            }
            if (button.menuItems != null && !button.menuItems.isEmpty()
                    && containsCommand(button.menuItems, command)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
