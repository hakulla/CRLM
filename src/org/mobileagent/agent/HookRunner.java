package org.mobileagent.agent;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/** Runs a user hook with /system/bin/sh, separate stdout/stderr and a hard timeout. */
final class HookRunner {
    static final class Result {
        final int exitCode;
        final String stdout;
        final String stderr;
        final boolean timedOut;

        Result(int exitCode, String stdout, String stderr, boolean timedOut) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.timedOut = timedOut;
        }
    }

    private static final int MAX_STDOUT = 64 * 1024;
    private static final int MAX_STDERR = 16 * 1024;

    private HookRunner() {
    }

    static Result run(Context context, Workspace workspace, String command,
                      String stdin, long timeoutMs) {
        if (command == null || command.trim().isEmpty()) {
            return new Result(-1, "", "Missing hook Command", false);
        }
        long seconds = Math.max(1L, Math.min(15L, timeoutMs / 1000L));
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(Arrays.asList(
                    "/system/bin/timeout", "-k", "2", String.valueOf(seconds),
                    "/system/bin/sh", "-c", command));
            builder.directory(workspace.root());
            builder.redirectErrorStream(false);
            Map<String, String> env = builder.environment();
            env.put("PATH", "/system/bin:/vendor/bin:/product/bin");
            env.put("HOME", workspace.root().getAbsolutePath());
            env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
            env.put("LANG", "C.UTF-8");
            process = builder.start();
            OutputStream input = process.getOutputStream();
            try {
                input.write((stdin == null ? "" : stdin).getBytes(StandardCharsets.UTF_8));
                input.flush();
            } finally {
                input.close();
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread outReader = reader(process.getInputStream(), out, MAX_STDOUT, "mobileagent-hook-out");
            Thread errReader = reader(process.getErrorStream(), err, MAX_STDERR, "mobileagent-hook-err");
            process.waitFor();
            outReader.join(1200L);
            errReader.join(600L);
            int exit = process.exitValue();
            return new Result(exit, text(out), text(err), exit == 124);
        } catch (Exception error) {
            if (process != null) {
                process.destroyForcibly();
            }
            return new Result(-1, "", "hook Implementation failed:"
                    + (error.getMessage() == null ? error.getClass().getSimpleName()
                    : error.getMessage()), false);
        }
    }

    private static Thread reader(final InputStream input, final ByteArrayOutputStream target,
                                 final int cap, String name) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        synchronized (target) {
                            int room = cap - target.size();
                            if (room > 0) {
                                target.write(buffer, 0, Math.min(read, room));
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String text(ByteArrayOutputStream bytes) {
        synchronized (bytes) {
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
