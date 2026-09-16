package com.local.imebar;

import android.os.Bundle;
import android.os.RemoteException;

/**
 * 由 Shizuku 加载的 UserService：这个类的实例活在 **shell/root 身份的独立进程**里
 * （ps 里是 com.local.imebar:adb），所以在这里跑 {@link AdbShell} 就等于 adb shell。
 *
 * 必须有无参构造函数——Shizuku 反射 new 它（API 13 也支持带 Context 的构造函数，这里用不上）。
 */
public final class AdbUserService extends IAdbUserService.Stub {

    public AdbUserService() {
    }

    @Override
    public Bundle run(String command) throws RemoteException {
        return AdbShell.run(command);
    }

    /**
     * Shizuku 在解绑时调它（事务号 16777114）。UserService 进程不会自己消失，
     * 必须在这里退出，否则会一直挂着一个 shell 身份的进程。
     */
    @Override
    public void destroy() {
        System.exit(0);
    }
}
