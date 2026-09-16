package com.local.imebar;

import android.os.Bundle;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 真正跑命令的地方——**运行在 Shizuku 的 UserService 进程里**，所以这里的 {@code ProcessBuilder}
 * 起出来的进程就是 shell（uid 2000）或 root（uid 0）身份，跟 {@code adb shell} 等价。
 *
 * 约定：最多等 {@link #TIMEOUT_SECONDS} 秒（免得按钮按下去没反应），stderr 并进 stdout，
 * 输出截到 {@link #MAX_OUTPUT} 字；返回 {@link AdbBridge} 那套 {status, message, exit, output}。
 */
final class AdbShell {

    private static final long TIMEOUT_SECONDS = 10;
    private static final int MAX_OUTPUT = 4000;
    /** Shell 的 PATH：app_process 起来的进程环境不一定带全，显式给一份最保险 */
    private static final String SHELL_PATH = "/system/bin:/system/xbin:/vendor/bin:/sbin";

    private AdbShell() {
    }

    static Bundle run(String command) {
        String cmd = command == null ? "" : command.trim();
        if (cmd.length() == 0) {
            return AdbBridge.reply(AdbBridge.ERROR, "命令是空的");
        }

        final Process process = start(cmd);
        if (process == null) {
            return AdbBridge.reply(AdbBridge.ERROR, "起不了 shell，命令没法执行");
        }

        final StringBuilder output = new StringBuilder();
        Thread reader = new Thread(new Runnable() {
            public void run() {
                readAll(process.getInputStream(), output);
            }
        }, "imebar-adb-out");
        reader.setDaemon(true);
        reader.start();

        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy();
                return AdbBridge.reply(AdbBridge.TIMEOUT,
                        "命令超过 " + TIMEOUT_SECONDS + " 秒还没结束，已中断",
                        -1, tidy(output.toString()));
            }
            reader.join(500);
            int code = process.exitValue();
            String text = tidy(output.toString());
            if (code == 0) {
                return AdbBridge.reply(AdbBridge.OK, "", 0, text);
            }
            return AdbBridge.reply(AdbBridge.EXIT_NONZERO,
                    "退出码 " + code + "：" + firstLine(text), code, text);
        } catch (Throwable t) {
            process.destroy();
            return AdbBridge.reply(AdbBridge.ERROR, "执行失败：" + t, -1, tidy(output.toString()));
        }
    }

    /** @return null 表示起不来 */
    private static Process start(String cmd) {
        // /system/bin/sh 在所有 Android 上都有；万一没有（极少数精简 ROM）再退回 PATH 里的 sh
        String[] shells = {"/system/bin/sh", "sh"};
        for (String shell : shells) {
            try {
                ProcessBuilder builder = new ProcessBuilder(shell, "-c", cmd);
                builder.redirectErrorStream(true);
                Map<String, String> env = builder.environment();
                env.put("PATH", SHELL_PATH);
                env.remove("LD_PRELOAD");
                return builder.start();
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return null;
    }

    private static void readAll(InputStream in, StringBuilder sink) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (sink) {
                    if (sink.length() < MAX_OUTPUT) {
                        sink.append(line).append('\n');
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 输出的第一行（截到 80 字），Toast 放不下整段输出 */
    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        String line = (end < 0 ? text : text.substring(0, end)).trim();
        return line.length() > 80 ? line.substring(0, 80) + "…" : line;
    }

    private static String tidy(String text) {
        String value = text == null ? "" : text.trim();
        return value.length() > MAX_OUTPUT ? value.substring(0, MAX_OUTPUT) + "…" : value;
    }
}
