package com.local.imebar;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * 跑一条「ADB 命令」——本质就是一条 shell 命令。
 *
 * 为什么优先用 su：普通 App 身份下 shell 几乎没有权限（input / am / pm 这类都用不了），
 * 想让命令真的能干活就得有 root。su 不存在时会自动退化成普通身份再跑一次，
 * 结果原样返回——绝不静默失败。
 *
 * 命令在调用方的后台线程里跑，最多等 {@link #TIMEOUT_SECONDS} 秒，免得把输入法卡住。
 */
final class AdbCommand {

    private static final long TIMEOUT_SECONDS = 10;
    private static final int MAX_OUTPUT = 4000;

    private AdbCommand() {
    }

    static final class Result {
        /** 是不是用 su 跑的 */
        final boolean rooted;
        /** 退出码；-1 表示没拿到（超时/起不来） */
        final int exitCode;
        /** stdout + stderr（已 trim 并截断） */
        final String output;
        /** 起不来/超时这类问题的可读描述，正常为 null */
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
        String cmd = command == null ? "" : command.trim();
        if (cmd.length() == 0) {
            return new Result(false, -1, "", "命令是空的");
        }
        // 先试 root；连 su 都起不来（没 root）就退化成普通身份
        Result result = exec(cmd, true);
        if (result != null) {
            return result;
        }
        result = exec(cmd, false);
        if (result != null) {
            return result;
        }
        return new Result(false, -1, "", "起不了进程，命令没法执行");
    }

    /** @return null 表示进程都没起来（例如没有 su），交给上层换一种方式再试 */
    private static Result exec(String command, boolean useSu) {
        final Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    useSu ? new String[]{"su", "-c", command}
                            : new String[]{"sh", "-c", command});
            builder.redirectErrorStream(true);
            process = builder.start();
        } catch (Throwable t) {
            return null;
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
                return new Result(useSu, -1, tidy(output.toString()),
                        "命令超过 " + TIMEOUT_SECONDS + " 秒还没结束，已中断");
            }
            reader.join(500);
            int code = process.exitValue();
            String text = tidy(output.toString());
            if (useSu && code != 0 && looksLikeRootDenied(text)) {
                return new Result(true, code, text, "root 被拒绝了（去 Magisk/授权管理里允许本输入法）");
            }
            return new Result(useSu, code, text, null);
        } catch (Throwable t) {
            process.destroy();
            return new Result(useSu, -1, tidy(output.toString()), "执行失败: " + t);
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

    private static String tidy(String text) {
        String value = text == null ? "" : text.trim();
        return value.length() > MAX_OUTPUT ? value.substring(0, MAX_OUTPUT) + "…" : value;
    }
}
