package org.mobileagent.agent;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;

/**
 * The app's own Linux userland: a Termux-style usr/ tree unpacked into internal
 * storage, so git, bash, java and the APK toolchain are app capabilities instead
 * of a separate app that the OS can freeze.
 *
 * Seed sources, in order: assets/rootfs.tar.gz, then
 * /sdcard/MobileAgent/rootfs/rootfs.tar.gz (used while the asset is not shipped yet).
 */
final class Rootfs {
    private static final String TAG = "AgentRootfs";
    private static final String STAMP_KEY = "rootfs_stamp";
    private static final long UNPACK_TIMEOUT_MS = 25L * 60L * 1000L;

    private static volatile Rootfs instance;

    private final Context app;
    private volatile boolean unpacking;

    static Rootfs get(Context context) {
        Rootfs local = instance;
        if (local == null) {
            synchronized (Rootfs.class) {
                local = instance;
                if (local == null) {
                    local = new Rootfs(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private Rootfs(Context context) {
        app = context;
    }

    /** Kicks off first-run unpacking off the main thread; safe to call every start. */
    static void startInBackground(final Context context) {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Rootfs rootfs = get(context);
                    String outcome = rootfs.ensureReady();
                    String mode = rootfs.probeExec();
                    Log.i(TAG, "rootfs ensure: " + outcome + " mode=" + mode);
                } catch (Throwable e) {
                    Log.w(TAG, "rootfs ensure failed", e);
                }
            }
        }, "mobileagent-rootfs");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Records how the userland is actually running, and why it is not. Written to
     * external storage on purpose: it must survive a crash and stay readable without
     * root, unlike logcat and the app-private directory.
     */
    String writeDiagnostics(String outcome) {
        try {
            File dir = new File(new Workspace(app).baseRoot(), ".agent/diagnostics");
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            StringBuilder json = new StringBuilder("{");
            json.append("\"mode\":\"").append(mode()).append("\",");
            json.append("\"outcome\":\"").append(outcome == null ? "" : oneLine(outcome)).append("\",");
            json.append("\"reason\":\"").append(oneLine(lastReason)).append("\",");
            json.append("\"usr\":\"").append(usrDir().getAbsolutePath()).append("\",");
            json.append("\"nativeLib\":\"").append(text(app.getApplicationInfo().nativeLibraryDir))
                    .append("\",");
            json.append("\"targetSdk\":").append(app.getApplicationInfo().targetSdkVersion).append(",");
            json.append("\"sdk\":").append(android.os.Build.VERSION.SDK_INT).append(",");
            json.append("\"device\":\"").append(oneLine(android.os.Build.MANUFACTURER + " "
                    + android.os.Build.MODEL)).append("\",");
            json.append("\"updated\":").append(System.currentTimeMillis());
            json.append('}');
            String body = json.toString();
            java.io.OutputStream out = new java.io.FileOutputStream(
                    new File(dir, "rootfs.json"));
            try {
                out.write(body.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return body;
        } catch (Exception e) {
            Log.w(TAG, "diagnostics write failed", e);
            return "{}";
        }
    }

    /** direct = exec from files/usr; compat = single-file binaries in the native lib dir; toybox = system only. */
    String mode() {
        if (probed) {
            return probedMode;
        }
        String outcome = probeExec();
        return outcome;
    }

    private volatile boolean probed;
    private volatile String probedMode = "toybox";
    private volatile String lastReason = "";
    private volatile String ensureOutcome = "Not yet depressed";

    /**
     * Probes whether the unpacked userland can actually be executed. Android blocks
     * exec from app data files for apps targeting API 29+, so a failure here is a
     * platform policy, not a broken tree: record it and fall back instead of letting
     * every later command fail with a confusing message.
     */
    synchronized String probeExec() {
        probedMode = "toybox";
        lastReason = "";
        if (!ready()) {
            lastReason = "rootfs Not ready:" + ensureOutcome;
            probed = true;
            writeDiagnostics("not-ready");
            return "not-ready";
        }
        File bash = new File(binDir(), "bash");
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    bash.getAbsolutePath(), "--noediting", "--version");
            builder.directory(app.getFilesDir());
            builder.redirectErrorStream(true);
            builder.redirectInput(new File("/dev/null"));
            builder.environment().put("LD_LIBRARY_PATH", libDir().getAbsolutePath());
            Process process = builder.start();
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            drainInto(process.getInputStream(), bytes);
            long deadline = System.currentTimeMillis() + 15000L;
            while (process.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200L);
            }
            if (process.isAlive()) {
                process.destroyForcibly();
                lastReason = "bash Start Timeout";
                probedMode = "toybox";
                probed = true;
                writeDiagnostics("probe-timeout");
                return "probe-timeout";
            }
            String text = bytes.toString("UTF-8");
            if (process.exitValue() == 0) {
                probedMode = "direct";
                probed = true;
                writeDiagnostics("direct-ok");
                return "direct";
            }
            lastReason = "bash Exit Code " + process.exitValue() + ": " + oneLine(text);
        } catch (Exception e) {
            lastReason = e.getClass().getSimpleName() + ": " + text(e.getMessage());
        }
        probed = true;
        writeDiagnostics("direct-blocked");
        return "direct-blocked";
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() > 180 ? flat.substring(0, 180) : flat;
    }

    private static void drainInto(final InputStream input, final java.io.OutputStream sink) {
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        sink.write(buffer, 0, read);
                    }
                } catch (Exception ignored) {
                }
            }
        }, "mobileagent-rootfs-probe");
        reader.setDaemon(true);
        reader.start();
    }

    String lastReason() {
        if (!probed) {
            probeExec();
        }
        return lastReason;
    }

    File usrDir() {
        return new File(app.getFilesDir(), "usr");
    }

    File binDir() {
        return new File(usrDir(), "bin");
    }

    File libDir() {
        return new File(usrDir(), "lib");
    }

    /** True when the tree is present and usable as a command source. */
    boolean ready() {
        return new File(binDir(), "sh").isFile() || new File(binDir(), "bash").isFile();
    }

    File seedFile() {
        File external = new File(new Workspace(app).baseRoot(), "rootfs/rootfs.tar.gz");
        return external.isFile() ? external : null;
    }

    private InputStream assetSeed() {
        try {
            InputStream input = app.getAssets().open("rootfs.tar.gz");
            return input;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Unpacks the seed once per app version. Runs tar from /system/bin because the
     * userland being unpacked is what provides the better tar.
     */
    synchronized String ensureReady() {
        if (ready() && currentStamp() == expectedStamp()) {
            return "ready";
        }
        if (unpacking) {
            return "Uncompression of self-enabled tool chain. Please wait";
        }
        InputStream fromAsset = assetSeed();
        File seed = seedFile();
        if (fromAsset == null && seed == null) {
            return ready() ? "ready" : "Missing rootfs Seeds (in thousands of thousands of thousands of thousands of thousands of thousands of thousands of thousands of thousands of thousands of dollars)assets/rootfs.tar.gz or rootfs/rootfs.tar.gz)";
        }
        unpacking = true;
        try {
            File work = new File(app.getCacheDir(), "rootfs-seed.tar.gz");
            if (fromAsset != null) {
                copy(fromAsset, work);
                fromAsset.close();
            } else {
                work = seed;
            }
            File target = app.getFilesDir();
            deleteRecursive(usrDir());
            int code = run(new String[]{"/system/bin/tar", "xzf", work.getAbsolutePath(),
                    "-C", target.getAbsolutePath()}, target, null);
            if (code != 0) {
                return "rootfs Unpression failed (%1)tar Exit Code " + code + ")";
            }
            run(new String[]{"/system/bin/chmod", "-R", "u+rx",
                    binDir().getAbsolutePath()}, target, null);
            run(new String[]{"/system/bin/chmod", "-R", "u+rx",
                    libDir().getAbsolutePath()}, target, null);
            if (seed != null && work == seed) {
                // keep the /sdcard seed for later reinstalls; assets are consumed once
                Log.i(TAG, "seeded from " + seed.getAbsolutePath());
            } else {
                work.delete();
            }
            writeStamp(expectedStamp());
            ensureOutcome = ready() ? "ready" : "Unpressure completed but missing sh";
            return ready() ? "ready" : "rootfs The decompression was complete but not found. sh";
        } catch (Exception e) {
            Log.w(TAG, "unpack failed", e);
            ensureOutcome = "Depression anomaly:" + e.getClass().getSimpleName() + " " + e.getMessage();
            return "rootfs Depression anomaly:" + e.getClass().getSimpleName() + " " + e.getMessage();
        } finally {
            unpacking = false;
        }
    }

    /** Applies the userland environment to a command about to be executed. */
    void applyEnv(ProcessBuilder builder) {
        if (!ready()) {
            return;
        }
        Map<String, String> env = builder.environment();
        env.put("PREFIX", usrDir().getAbsolutePath());
        env.put("LD_LIBRARY_PATH", libDir().getAbsolutePath());
        env.put("PATH", binDir().getAbsolutePath() + ":" + libDir().getAbsolutePath()
                + ":" + env.get("PATH"));
    }

    private int expectedStamp() {
        File seed = seedFile();
        long size = seed != null ? seed.length() : 0L;
        int version = 1;
        try {
            android.content.pm.PackageInfo info = app.getPackageManager()
                    .getPackageInfo(app.getPackageName(), 0);
            version = info.versionCode;
        } catch (Exception ignored) {
        }
        return version * 1000 + (int) (size % 1000);
    }

    private int currentStamp() {
        return app.getSharedPreferences("mobileagent_rootfs", Context.MODE_PRIVATE)
                .getInt(STAMP_KEY, 0);
    }

    private void writeStamp(int value) {
        app.getSharedPreferences("mobileagent_rootfs", Context.MODE_PRIVATE).edit()
                .putInt(STAMP_KEY, value).apply();
    }

    private static int run(String[] command, File dir, Map<String, String> extra) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(Arrays.asList(command));
        builder.directory(dir);
        builder.redirectErrorStream(true);
        builder.redirectInput(new File("/dev/null"));
        if (extra != null) {
            builder.environment().putAll(extra);
        }
        Process process = builder.start();
        drain(process.getInputStream());
        long deadline = System.currentTimeMillis() + UNPACK_TIMEOUT_MS;
        while (process.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(500L);
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            return 124;
        }
        return process.exitValue();
    }

    private static void drain(final InputStream input) {
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] buffer = new byte[8192];
                    while (input.read(buffer) != -1) {
                        // output is only consumed to keep the pipe from filling
                    }
                } catch (Exception ignored) {
                }
            }
        }, "mobileagent-rootfs-tar");
        reader.setDaemon(true);
        reader.start();
    }

    private static void copy(InputStream input, File dest) throws Exception {
        FileOutputStream out = new FileOutputStream(dest);
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            out.close();
        }
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        if (file.isFile()) {
            file.delete();
        }
    }
}
