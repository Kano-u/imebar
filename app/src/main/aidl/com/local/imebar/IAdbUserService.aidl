package com.local.imebar;

import android.os.Bundle;

/**
 * 跑 adb 命令的 UserService：由 Shizuku 以 shell/root 身份加载本 App 的 AdbUserService 后，
 * 模块 App 进程通过它拿到 shell 身份去执行命令。
 */
interface IAdbUserService {

    /** 返回 {status, message, exit, output}，与 ConfigProvider 的 run_adb 同一套约定 */
    Bundle run(String command) = 1;

    /** Shizuku 规定的销毁方法事务号（服务进程不会自己退出，必须在这里 System.exit） */
    void destroy() = 16777114;
}
