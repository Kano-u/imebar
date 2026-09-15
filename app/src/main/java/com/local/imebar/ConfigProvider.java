package com.local.imebar;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 模块 App 侧提供配置的接口（运行在**模块 App 自己的进程**里）。
 *
 * 为什么需要它：输入法进程里读到的"远程偏好"是内存快照，还可能被缓存，拿不到最新值；
 * 而模块 App 进程自己刚写过的偏好**在它自己的内存里一定是最新的**。
 * 所以输入法进程每次弹出键盘时，直接跨进程问 App 要一份当前配置，就一定是最新的。
 *
 * 说明：这个 Provider 是 exported 的，返回的只是界面显示参数（不含任何隐私数据）。
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
        if (!METHOD_GET_CONFIG.equals(method) || getContext() == null) {
            return null;
        }
        BarConfig config = BarConfig.fromPrefs(
                getContext().getSharedPreferences(BarConfig.GROUP, 0));
        Bundle bundle = config.toBundle();
        bundle.putLong("timestamp", System.currentTimeMillis());
        return bundle;
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
