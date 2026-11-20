package org.mobileagent.agent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Seeds the workspace with the app's own source so a fresh install can edit and rebuild itself. */
final class SourceBootstrap {
    private static final String TAG = "AgentSourceBootstrap";
    private static final String ASSET = "source-seed.zip";
    private static final String TARGET = "projects/mobile-agent";
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private SourceBootstrap() {
    }

    static void ensureAsync(final Context context) {
        if (!RUNNING.compareAndSet(false, true)) {
            return;
        }
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                String message = null;
                try {
                    Workspace workspace = new Workspace(app);
                    if (!workspace.hasStoragePermission()) {
                        return;
                    }
                    File base = workspace.baseRoot();
                    File target = new File(base, TARGET);
                    if (isReady(target)) {
                        return;
                    }
                    File temp = new File(base, "projects/.source-seed-" + UUID.randomUUID());
                    deleteTree(temp);
                    if (!temp.mkdirs()) {
                        throw new IllegalStateException("Could not close temporary folder: %s");
                    }
                    int files = extract(app, temp);
                    if (target.exists()) {
                        File old = new File(base, "projects/.source-old-"
                                + System.currentTimeMillis());
                        if (!target.renameTo(old)) {
                            throw new IllegalStateException("Could not close temporary folder: %s");
                        }
                    }
                    if (!temp.renameTo(target)) {
                        throw new IllegalStateException("Could not install source directory");
                    }
                    message = "Source Code Synchronized " + TARGET + "(" + files + " (a)";
                    Log.i(TAG, message);
                } catch (Throwable e) {
                    Log.w(TAG, "source seed failed", e);
                    message = "Synchronising folder" + e.getMessage();
                } finally {
                    RUNNING.set(false);
                    if (message != null) {
                        final String text = message;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(app, text, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }
            }
        }, "mobileagent-source-seed").start();
    }

    private static boolean isReady(File target) {
        return new File(target, "AndroidManifest.xml").isFile()
                && new File(target, "src").isDirectory()
                && new File(target, "build-phone.sh").isFile();
    }

    private static int extract(Context context, File destination) throws Exception {
        int count = 0;
        InputStream raw = context.getAssets().open(ASSET);
        ZipInputStream zip = new ZipInputStream(raw);
        try {
            byte[] buffer = new byte[8192];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                while (name.startsWith("./")) {
                    name = name.substring(2);
                }
                if (name.isEmpty() || name.equals(".")) {
                    continue;
                }
                if (name.startsWith("/") || name.contains("../") || name.endsWith("/..")) {
                    throw new IllegalStateException("Illegal source entry:" + name);
                }
                File out = new File(destination, name);
                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) {
                        throw new IllegalStateException("Can not create folder lock on %s: %s" + name);
                    }
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IllegalStateException("Can not create folder lock on %s: %s" + name);
                }
                FileOutputStream stream = new FileOutputStream(out);
                try {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        stream.write(buffer, 0, read);
                    }
                } finally {
                    stream.close();
                }
                if (name.endsWith(".sh")) {
                    out.setExecutable(true, false);
                }
                count++;
                zip.closeEntry();
            }
        } finally {
            zip.close();
            raw.close();
        }
        if (count == 0) {
            throw new IllegalStateException("Source package is empty");
        }
        return count;
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
