package org.mobileagent.agent;

import android.content.Context;
import android.content.Intent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class TermuxBridge {
    static final class Result {
        final int exitCode;
        final String output;
        final String error;

        private Result(int exitCode, String output, String error) {
            this.exitCode = exitCode;
            this.output = output;
            this.error = error;
        }

        static Result success(int exitCode, String output) {
            return new Result(exitCode, output, null);
        }

        static Result failure(String error) {
            return new Result(-1, "", error);
        }
    }

    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final long POLL_MS = 300;
    private static final long START_GRACE_MS = 1800;
    private static final long START_WAIT_MS = 7000;
    private static final long TERMUX_WARM_MS = 1800;

    private TermuxBridge() {
    }

    static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static Result run(Context context, Workspace workspace, String command, long timeoutMs) {
        return run(context, workspace, command, timeoutMs, null);
    }

    static Result run(Context context, Workspace workspace, String command, long timeoutMs,
                      Runnable onProgress) {
        if (!isInstalled(context)) {
            return Result.failure("Termux Not installed");
        }
        try {
            File runs = workspace.resolve(".agent/runs");
            if (!runs.isDirectory() && !runs.mkdirs()) {
                return Result.failure("Could not create Termux Run Directory");
            }
            String workDir = workspace.root().getAbsolutePath();
            if (!ensureTermux(context, runs, workDir)) {
                return Result.failure("Termux Cannot start automatically. Please open once Termux, "
                        + "Confirm that external orders have been allowed and check RUN_COMMAND Permission.");
            }

            String id = "run-" + System.currentTimeMillis();
            File script = new File(runs, id + ".sh");
            File log = new File(runs, id + ".log");
            File done = new File(runs, id + ".done");
            if (log.exists() && !log.delete()) {
                return Result.failure("Could not clear old log");
            }
            if (done.exists() && !done.delete()) {
                return Result.failure("Could not clear old state");
            }

            File cmd = new File(runs, id + ".cmd.sh");
            writeText(cmd, command + "\n");
            long shellSeconds = Math.max(15L, Math.min(1800L, timeoutMs / 1000L + 5L));
            String content = "#!/data/data/com.termux/files/usr/bin/bash\n"
                    + "cd " + shellQuote(workDir) + " || exit 90\n"
                    + "export PATH=/data/data/com.termux/files/usr/bin:$PATH\n"
                    + "export DEBIAN_FRONTEND=noninteractive\n"
                    + "export PIP_NO_INPUT=1\n"
                    + "if command -v timeout >/dev/null 2>&1; then\n"
                    + "  timeout -k 5 " + shellSeconds + " bash " + shellQuote(cmd.getAbsolutePath()) + "\n"
                    + "else\n"
                    + "  bash " + shellQuote(cmd.getAbsolutePath()) + "\n"
                    + "fi\n";
            writeText(script, content);

            Intent intent = buildRunIntent(script, log, done, workDir);
            context.startService(intent);
            if (!waitForStart(log, done, START_WAIT_MS)) {
                return Result.failure("Termux Started, but the order was not implemented."
                        + "Check it out. Termux Whether or not to be cleaned up and retry.");
            }

            long deadline = System.currentTimeMillis() + timeoutMs;
            long startedWaiting = System.currentTimeMillis();
            long lastSize = -1L;
            long lastChange = startedWaiting;
            long lastNotify = 0L;
            while (System.currentTimeMillis() < deadline) {
                if (onProgress != null
                        && System.currentTimeMillis() - lastNotify >= 5000L) {
                    onProgress.run();
                    lastNotify = System.currentTimeMillis();
                }
                if (done.isFile() && done.length() > 0) {
                    int exit = parseExit(readText(done));
                    String output = readText(log);
                    if (output.length() > 30000) {
                        output = output.substring(output.length() - 30000);
                    }
                    if (exit == 124) {
                        return Result.failure("Termux The order was severed and terminated."
                                + "Please split the task or shorten the command; at the end of the log:\n" + tail(output, 2000));
                    }
                    return Result.success(exit,
                            output.isEmpty() ? "(No output)" : output);
                }
                long size = log.isFile() ? log.length() : 0L;
                if (size != lastSize) {
                    lastSize = size;
                    lastChange = System.currentTimeMillis();
                }
                if (System.currentTimeMillis() - startedWaiting < 20_000L
                        && size > 0 && size < 20_000L) {
                    String pending = readText(log).toLowerCase(Locale.ROOT);
                    if (pending.contains("[y/n]") || pending.contains("(y/n)")
                            || pending.contains("do you want to continue")
                            || pending.contains("password:") || pending.contains("enter passphrase")
                            || pending.contains("press any key") || pending.contains("select an option")) {
                        return Result.failure("Termux command is waiting for cross-entry and has stopped waiting."
                                + "Please use -y / Non-interactive parameters, not used pkg/apt installation;command output end:\n"
                                + tail(readText(log), 1200));
                    }
                }
                if (System.currentTimeMillis() - lastChange > 45_000L
                        && System.currentTimeMillis() - startedWaiting > 45_000L) {
                    String output = readText(log);
                    return Result.failure("Termux Command suspected of being systematically frozen: more than 45 The second doesn't have any output."
                            + "Waiting has stopped (the back-office process may still be in progress) Termux , can be run Termux It's over in there."
                            + "End of the log:\n" + tail(output, 2000));
                }
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Result.failure("Wait Termux Command interrupted");
                }
            }
            String output = readText(log);
            return Result.failure("Termux Command timed out. Log ends:\n" + tail(output, 6000));
        } catch (SecurityException e) {
            return Result.failure("No, I'm not. Termux Runs command privileges."
                    + "Please go to System Settings → Apply → CRLM → Permissions → Other competences,"
                    + "GrantedRun commands in Termux environment'. ");
        } catch (Exception e) {
            return Result.failure("Termux Implementation failed:" + e.getMessage());
        }
    }

    private static boolean ensureTermux(Context context, File runs, String workDir) {
        File warmScript = new File(runs, "termux-warmup.sh");
        File warmLog = new File(runs, "termux-warmup.log");
        File warmDone = new File(runs, "termux-warmup.done");
        warmLog.delete();
        warmDone.delete();
        try {
            writeText(warmScript, "#!/data/data/com.termux/files/usr/bin/bash\n"
                    + "cd " + shellQuote(workDir) + " || exit 90\n"
                    + "export PATH=/data/data/com.termux/files/usr/bin:$PATH\n"
                    + "echo TERMUX_READY\n");
            Intent intent = buildRunIntent(warmScript, warmLog, warmDone, workDir);
            context.startService(intent);
            if (waitForStart(warmLog, warmDone, START_GRACE_MS)) {
                return true;
            }
            launchTermux(context);
            sleepQuietly(TERMUX_WARM_MS);
            warmLog.delete();
            warmDone.delete();
            context.startService(intent);
            boolean started = waitForStart(warmLog, warmDone, START_WAIT_MS);
            returnToAgent(context);
            return started;
        } catch (Exception e) {
            return false;
        }
    }

    private static Intent buildRunIntent(File script, File log, File done, String workDir) {
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, TERMUX_SERVICE);
        intent.setAction(ACTION_RUN_COMMAND);
        intent.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{
                "-c",
                "bash " + script.getAbsolutePath() + " > "
                        + log.getAbsolutePath() + " 2>&1; echo $? > "
                        + done.getAbsolutePath()
        });
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", workDir);
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "CRLM");
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION",
                "ByCRLM Do local tasks");
        return intent;
    }

    private static boolean waitForStart(File log, File done, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (log.isFile() || done.isFile()) {
                return true;
            }
            sleepQuietly(POLL_MS);
        }
        return log.isFile() || done.isFile();
    }

    private static void launchTermux(Context context) {
        try {
            Intent launch = context.getPackageManager()
                    .getLaunchIntentForPackage(TERMUX_PACKAGE);
            if (launch == null) {
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(launch);
        } catch (Exception ignored) {
        }
    }

    private static void returnToAgent(Context context) {
        try {
            Intent back = new Intent(context, MainActivity.class);
            back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(back);
        } catch (Exception ignored) {
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void writeText(File file, String text) throws Exception {
        FileOutputStream output = new FileOutputStream(file, false);
        try {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            output.close();
        }
    }

    private static String readText(File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try {
            FileInputStream input = new FileInputStream(file);
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toString("UTF-8");
            } finally {
                input.close();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static int parseExit(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String tail(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return "...[Front is cut.]\n" + text.substring(text.length() - max);
    }
}
