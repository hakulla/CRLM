package org.mobileagent.agent;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/**
 * Runs ordinary shell commands inside the app process with Android's
 * /system/bin/sh + toybox, so daily work does not depend on the Termux app.
 * Termux remains optional for package management and build toolchains.
 *
 * The hard limits that keep a task from hanging are the same ones the Termux
 * bridge used: a heartbeat while the command runs, stdin closed to /dev/null so
 * an interactive prompt cannot block forever, and a silence watchdog that gives
 * up on a command that stops producing output.
 */
final class ShellRunner {
    /** A command that prints nothing for this long is treated as frozen. */
    private static final long SILENCE_LIMIT_MS = 45_000L;
    /** How often the caller's heartbeat is refreshed while the command runs. */
    private static final long PROGRESS_INTERVAL_MS = 5_000L;
    private static final int OUTPUT_CAP_BYTES = 1024 * 1024;

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

    /** Called on a background thread while the command is still running. */
    interface Progress {
        void tick();
    }

    /** Receives the live process so the host can kill it on stop. */
    interface ProcessSink {
        void attach(Process process);
    }

    private ShellRunner() {
    }

    static Result run(Context context, Workspace workspace, String command, long timeoutMs) {
        return run(context, workspace, command, timeoutMs, null, null);
    }

    static Result run(Context context, Workspace workspace, String command, long timeoutMs,
                      Progress progress, ProcessSink sink) {
        return run(context, workspace, command, timeoutMs, progress, sink, SILENCE_LIMIT_MS);
    }

    /**
     * @param silenceLimitMs give up after this long without output; 0 disables the
     * watchdog for commands that are legitimately quiet (javac, d8, aapt2).
     */
    static Result run(Context context, Workspace workspace, String command, long timeoutMs,
                      final Progress progress, final ProcessSink sink, long silenceLimitMs) {
        if (command == null || command.trim().isEmpty()) {
            return Result.failure("Missing shell Command");
        }
        File root = workspace.root();
        long seconds = Math.max(5L, Math.min(1800L, timeoutMs / 1000L));
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(Arrays.asList(
                    "/system/bin/timeout", "-k", "5", String.valueOf(seconds),
                    "/system/bin/sh", "-c", command));
            builder.directory(root);
            builder.redirectErrorStream(true);
            // A command that reads stdin (git/ssh prompts, bare cat) would block
            // forever on the inherited pipe; /dev/null gives it immediate EOF.
            builder.redirectInput(new File("/dev/null"));
            Map<String, String> env = builder.environment();
            // The app's extracted native lib dir is exec-allowed, so binaries shipped
            // in lib/<abi>/ become callable tools without depending on Termux.
            String nativeDir = context.getApplicationInfo().nativeLibraryDir;
            env.put("PATH", "/system/bin:/vendor/bin:/product/bin"
                    + (nativeDir == null || nativeDir.isEmpty() ? "" : ":" + nativeDir));
            env.put("HOME", root.getAbsolutePath());
            env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
            env.put("LANG", "C.UTF-8");
            // The app's own userland, when unpacked, takes precedence over toybox so
            // git/bash/java resolve to bundled binaries instead of a Termux install.
            Rootfs.get(context).applyEnv(builder);
            process = builder.start();
            if (sink != null) {
                sink.attach(process);
            }

            final Process running = process;
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final long[] lastOutputAt = {System.currentTimeMillis()};
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        InputStream input = running.getInputStream();
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            lastOutputAt[0] = System.currentTimeMillis();
                            synchronized (bytes) {
                                if (bytes.size() < OUTPUT_CAP_BYTES) {
                                    bytes.write(buffer, 0, Math.min(read,
                                            OUTPUT_CAP_BYTES - bytes.size()));
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }, "mobileagent-shell-reader");
            reader.setDaemon(true);
            reader.start();

            final Process watched = process;
            final boolean[] frozen = {false};
            Thread watchdog = new Thread(new Runnable() {
                @Override
                public void run() {
                    long started = System.currentTimeMillis();
                    while (watched.isAlive()) {
                        long now = System.currentTimeMillis();
                        if (progress != null && now - started >= PROGRESS_INTERVAL_MS) {
                            try {
                                progress.tick();
                            } catch (Throwable ignored) {
                            }
                        }
                        long quiet = now - lastOutputAt[0];
                        if (silenceLimitMs > 0L && quiet > silenceLimitMs
                                && now - started > silenceLimitMs) {
                            frozen[0] = true;
                            watched.destroyForcibly();
                            return;
                        }
                        try {
                            Thread.sleep(1000L);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }, "mobileagent-shell-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            // /system/bin/timeout owns the hard limit; waitFor is API-24 safe.
            process.waitFor();
            reader.join(1500L);
            String output;
            synchronized (bytes) {
                output = bytes.toString(StandardCharsets.UTF_8.name());
            }
            if (output.length() > 30000) {
                output = output.substring(output.length() - 30000);
            }
            if (frozen[0]) {
                return Result.failure("Internal shell Command exceeded " + (silenceLimitMs / 1000L)
                        + " Seconds don't have any output, stop waiting."
                        + "It may be waiting for interactive input (cipher)/Confirmed) or systematically frozen; please change to non-interactive parameters."
                        + "Output end:\n" + output);
            }
            int exit = process.exitValue();
            if (exit == 124) {
                return Result.failure("Internal shell Command up to hard timeout (" + seconds
                        + " s) ,terminated. Log end:\n" + output);
            }
            return Result.success(exit, output.isEmpty() ? "(No output)" : output);
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return Result.failure("Internal shell Implementation failed:"
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            if (sink != null) {
                sink.attach(null);
            }
        }
    }
}
