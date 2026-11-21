package org.mobileagent.agent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs the optional build environment (rootfs + toolchain) for self-building. */
final class BootstrapInstaller {
    private static final String TAG = "AgentBootstrap";
    private static final String ASSET = "bootstrap.zip";
    private static final String URL_LATEST =
            "https://github.com/hakulla/CRLM/releases/latest/download/bootstrap.zip";
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private BootstrapInstaller() {
    }

    static void ensureAsync(final Context context, final Runnable onReady) {
        if (!RUNNING.compareAndSet(false, true)) {
            return;
        }
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ready = false;
                String message = null;
                try {
                    Workspace workspace = new Workspace(app);
                    if (!workspace.hasStoragePermission()) {
                        return;
                    }
                    File base = workspace.baseRoot();
                    ready = isReady(base);
                    if (!ready) {
                        File zip = new File(base, ".bootstrap-" + UUID.randomUUID() + ".zip");
                        try {
                            if (hasAsset(app)) {
                                copyAsset(app, zip);
                                message = "Installing built-in environments...";
                            } else {
                                message = "Downloading build environment (approximately) 150MB)...";
                                download(URL_LATEST, zip);
                            }
                            install(base, zip);
                            ready = isReady(base);
                            if (ready) {
                                message = "Build environment is ready, self-constructed APK";
                            }
                        } finally {
                            zip.delete();
                        }
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "bootstrap failed", e);
                    message = "The construction of the environment failed:" + e.getMessage();
                } finally {
                    RUNNING.set(false);
                    final boolean ok = ready;
                    final String text = message;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (text != null) {
                                LanguageManager.toast(app, text, Toast.LENGTH_LONG).show();
                            }
                            if (ok && onReady != null) {
                                onReady.run();
                            }
                        }
                    });
                }
            }
        }, "mobileagent-bootstrap").start();
    }

    private static boolean isReady(File base) {
        return new File(base, "rootfs/rootfs.tar.gz").isFile()
                && new File(base, "toolchain/android.jar").isFile()
                && new File(base, "toolchain/d8.jar").isFile()
                && new File(base, "toolchain/apksigner.jar").isFile()
                && new File(base, "toolchain/crlm-debug.keystore").isFile();
    }

    private static boolean hasAsset(Context context) {
        try {
            InputStream input = context.getAssets().open(ASSET);
            input.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void copyAsset(Context context, File target) throws Exception {
        InputStream input = context.getAssets().open(ASSET);
        OutputStream output = new FileOutputStream(target);
        try {
            copy(input, output);
        } finally {
            input.close();
            output.close();
        }
    }

    private static void download(String urlText, File target) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(120000);
        connection.setRequestProperty("User-Agent", "CRLM-Bootstrap");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code
                    + "(Private warehouse to log in; change to Offline APK)");
        }
        InputStream input = connection.getInputStream();
        OutputStream output = new FileOutputStream(target);
        try {
            copy(input, output);
        } finally {
            input.close();
            output.close();
            connection.disconnect();
        }
    }

    private static void install(File base, File zip) throws Exception {
        File temp = new File(base, ".bootstrap-tmp-" + UUID.randomUUID());
        if (!temp.mkdirs()) {
            throw new IllegalStateException("Could not create bootstrap Temporary Directory");
        }
        try {
            ZipInputStream input = new ZipInputStream(new java.io.FileInputStream(zip));
            try {
                byte[] buffer = new byte[16384];
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    String name = entry.getName().replace('\\', '/');
                    while (name.startsWith("./")) {
                        name = name.substring(2);
                    }
                    if (name.startsWith("/") || name.contains("../")) {
                        throw new IllegalStateException("Illegal bootstrap Entry");
                    }
                    File out = new File(temp, name);
                    if (entry.isDirectory()) {
                        out.mkdirs();
                        continue;
                    }
                    File parent = out.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    FileOutputStream stream = new FileOutputStream(out);
                    try {
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            stream.write(buffer, 0, n);
                        }
                    } finally {
                        stream.close();
                    }
                    if (name.endsWith(".sh")) {
                        out.setExecutable(true, false);
                    }
                    input.closeEntry();
                }
            } finally {
                input.close();
            }
            moveDir(new File(temp, "rootfs"), new File(base, "rootfs"));
            moveDir(new File(temp, "toolchain"), new File(base, "toolchain"));
        } finally {
            deleteTree(temp);
        }
    }

    private static void moveDir(File source, File target) throws Exception {
        if (!source.isDirectory()) {
            throw new IllegalStateException("bootstrap Missing " + source.getName());
        }
        if (target.exists()) {
            File old = new File(target.getParentFile(), target.getName()
                    + ".old-" + System.currentTimeMillis());
            if (!target.renameTo(old)) {
                deleteTree(target);
            }
        }
        if (!source.renameTo(target)) {
            throw new IllegalStateException("Cannot Install " + target.getName());
        }
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = input.read(buffer)) != -1) {
            output.write(buffer, 0, n);
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }
}
