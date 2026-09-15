package com.local.imebar;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * 跑一条「ADB 命令」——本质是**设备内部**的一条 shell 命令（不是在电脑上执行 adb）。
 *
 * 权限才是这里的关键：`input` / `am` / `pm` 这些命令要求发起者是 shell(2000) 或 root，
 * 本模块跑在输入法进程里，身份就是输入法 App 的普通 UID，没有 INJECT_EVENTS 之类的权限，
 * 一定会被系统拒绝（典型报错：Injecting input events requires the caller to have
 * the INJECT_EVENTS permission）。所以执行顺序是：
 *   1) 找一个能用的 su（PATH 里的 su，加几个常见绝对路径），用 `su -c` 跑；
 *      第一次请求 root 时系统会弹授权框，这一步等 30 秒让人去点；
 *   2) 完全没有可用的 su（设备没 root，或 root 管理器没给本输入法放行）时，
 *      退化成普通身份跑一次，并把真正的失败原因（Permission denied / SecurityException）
 *      原样写进日志告诉你——不静默失败；
 *   3) 想免 root，只有 Shizuku 那条路（要另装 Shizuku 并授权）。
 *
 * 命令在调用方的后台线程里跑，避免卡住输入法。
 */
final class AdbCommand {

    private static final int MAX_OUTPUT = 4000;
    private static final long TIMEOUT_SECONDS = 10;
    /** 第一次请求 root 时可能在等授权框，给足时间 */
    private static final long FIRST_ROOT_TIMEOUT_SECONDS = 30;
    /** 常见 su 位置（PATH 里的 su 排第一；KernelSU / APatch 也挂在 /system/bin/su） */
    private static final String[] SU_CANDIDATES = {
            "su", "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/magisk/su"};

    /** 这台设备上已经验证可用的 su；null = 还没找到 */
    private static String workingSu;
    /** 已经为 root 授权框等过一次了，后面用普通超时 */
    private static boolean waitedForRootPrompt;

    private AdbCommand() {
    }

    static final class Result {
        /** 是不是用 su 跑的 */
        final boolean rooted;
        /** 退出码；-1 表示没拿到（超时/起不来） */
        final int exitCode;
        /** stdout + stderr（已 trim 并截断） */
        final String output;
        /** 起不来/超时/被系统拒绝这类问题的可读描述，正常为 null */
        final String error;

        Result(boolean rooted, int exitCode, String output, String error) {
            this.rooted = rooted;
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
            this.error = error;
        }

        boolean ok() {
            return error == null && exitCode == 0;
        }

        /** 给 Toast 用的一句话 */
        String summary() {
            if (error != null) {
                return error;
            }
            return ok() ? (rooted ? "命令完成（root）" : "命令完成（普通身份）")
                    : "命令退出码 " + exitCode;
        }

        /** 给日志框用：多行结果 */
        String detail() {
            StringBuilder text = new StringBuilder();
            text.append(summary());
            if (output.length() > 0) {
                text.append('\n').append(output);
            }
            return text.toString();
        }
    }

    static Result run(String command) {
        String cmd = normalize(command);
        if (cmd.length() == 0) {
            return new Result(false, -1, "", "命令是空的");
        }

        Result rooted = runWithSu(cmd);
        if (rooted != null) {
            return rooted;
        }

        // 没有可用的 root：退化成普通身份跑一次（多数命令会被系统拒绝，但要把原因说清楚）
        Process plain = start("sh", cmd, false);
        if (plain == null) {
            return new Result(false, -1, "", "起不了进程，命令没法执行");
        }
        return wait(plain, false, "sh");
    }

    /**
     * 去掉从电脑上照搬来的前缀：设备内部没有 adb 程序，
     * 但用户很容易把 `adb shell input keyevent 3` 整句贴进配置里（那样只会得到 127）。
     */
    private static String normalize(String command) {
        String cmd = command == null ? "" : command.trim();
        if (cmd.startsWith("adb shell ")) {
            cmd = cmd.substring("adb shell ".length()).trim();
        } else if (cmd.startsWith("adb ")) {
            cmd = cmd.substring("adb ".length()).trim();
        }
        return cmd;
    }

    /** @return null 表示这台设备上没有可用的 su（交给上层退化成普通身份） */
    private static Result runWithSu(String command) {
        if (workingSu != null) {
            Process process = start(workingSu, command, true);
            if (process != null) {
                return wait(process, true, workingSu);
            }
            RunLog.add("ADB: " + workingSu + " 这次起不来了，重新找一遍 su");
            workingSu = null;
        }

        for (String candidate : SU_CANDIDATES) {
            Process process = start(candidate, command, true);
            if (process == null) {
                continue;   // 换下一个候选（失败原因 start() 已经写进日志）
            }
            workingSu = candidate;
            RunLog.add("ADB: 用 " + candidate + " 执行");
            return wait(process, true, candidate);
        }

        RunLog.add("ADB: 这台设备上没有可用的 su，改用普通身份（需要 root 的命令会被系统拒绝）");
        return null;
    }

    /** @return null 表示进程没起来（例如没有 su），失败原因写进日志 */
    private static Process start(String exe, String command, boolean rooted) {
        try {
            ProcessBuilder builder = new ProcessBuilder(rooted
                    ? new String[]{exe, "-c", command}
                    : new String[]{"sh", "-c", command});
            builder.redirectErrorStream(true);
            return builder.start();
        } catch (Throwable t) {
            RunLog.add("ADB: " + (rooted ? exe + " 起不来: " : "sh 起不来: ") + t.getMessage());
            return null;
        }
    }

    private static Result wait(Process process, final boolean rooted, String exe) {
        final StringBuilder output = new StringBuilder();
        Thread reader = new Thread(new Runnable() {
            public void run() {
                readAll(process.getInputStream(), output);
            }
        }, "imebar-adb-out");
        reader.setDaemon(true);
        reader.start();

        long timeout = rooted && !waitedForRootPrompt ? FIRST_ROOT_TIMEOUT_SECONDS : TIMEOUT_SECONDS;
        if (rooted && !waitedForRootPrompt) {
            waitedForRootPrompt = true;
            RunLog.add("ADB: 首次用 root 执行，最多等 " + timeout + " 秒"
                    + "（如果弹出 root 授权框，请点允许）");
        }

        try {
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroy();
                return new Result(rooted, -1, tidy(output.toString()),
                        "命令超过 " + timeout + " 秒还没结束，已中断");
            }
            reader.join(500);
            int code = process.exitValue();
            String text = tidy(output.toString());
            if (rooted && code != 0 && looksLikeRootDenied(text)) {
                return new Result(true, code, text,
                        "root 被拒绝了（去 Magisk / 授权管理里允许本输入法，然后重试）");
            }
            if (!rooted && code != 0 && looksLikePermissionDenied(text)) {
                return new Result(false, code, text,
                        "普通 App 身份被系统拒绝：这条命令需要 root（或 Shizuku）");
            }
            return new Result(rooted, code, text, null);
        } catch (Throwable t) {
            process.destroy();
            return new Result(rooted, -1, tidy(output.toString()), "执行失败: " + t);
        }
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

    private static boolean looksLikeRootDenied(String text) {
        return text != null && (text.contains("denied") || text.contains("not allowed")
                || text.contains("Permission denied"));
    }

    /** 普通身份被挡：input / am 这类命令的典型报错 */
    private static boolean looksLikePermissionDenied(String text) {
        return text != null && (text.contains("SecurityException")
                || text.contains("INJECT_EVENTS")
                || text.contains("Permission denied")
                || text.contains("requires the caller to have"));
    }

    private static String tidy(String text) {
        String value = text == null ? "" : text.trim();
        return value.length() > MAX_OUTPUT ? value.substring(0, MAX_OUTPUT) + "…" : value;
    }
}
