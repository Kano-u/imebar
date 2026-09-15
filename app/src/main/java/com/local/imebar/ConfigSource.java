package com.local.imebar;

/**
 * 配置读取器。
 *
 * 为什么要做成"每次调用都重新读"而不是缓存一个 BarConfig：
 * libxposed 的 getRemotePreferences() 返回的是**内存快照**
 * （RemotePreferences 内部只有一个 volatile Map，值只在创建时从远程取一次），
 * 所以输入法进程里必须每次重新取，否则用户改了设置也读不到新值。
 */
interface ConfigSource {
    BarConfig get();
}
