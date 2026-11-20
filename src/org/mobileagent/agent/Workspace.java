package org.mobileagent.agent;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class Workspace {
    private final Context context;
    private final String subPath;

    Workspace(Context context) {
        this(context, "");
    }

    private Workspace(Context context, String subPath) {
        this.context = context.getApplicationContext();
        this.subPath = cleanSubPath(subPath);
    }

    Workspace sub(String relativePath) {
        return new Workspace(context, relativePath);
    }

    String subPath() {
        return subPath;
    }

    boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    File baseRoot() {
        if (hasStoragePermission()
                && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File external = new File(Environment.getExternalStorageDirectory(), "MobileAgent");
            if (external.isDirectory() || external.mkdirs()) {
                return external;
            }
        }
        File internal = new File(context.getFilesDir(), "workspace");
        if (!internal.isDirectory()) {
            internal.mkdirs();
        }
        return internal;
    }

    File root() {
        File base = baseRoot();
        if (subPath.isEmpty()) {
            return base;
        }
        File target = new File(base, subPath);
        if (!target.isDirectory()) {
            target.mkdirs();
        }
        return target;
    }

    String label() {
        return root().getAbsolutePath();
    }

    File resolve(String relativePath) throws IOException {
        String value = relativePath == null ? "" : relativePath.trim().replace('\\', '/');
        File base = root();
        // Agents often receive the absolute workspace path from the UI. Accept it
        // when it is inside the workspace instead of rejecting it as "relative".
        File target = value.startsWith("/") ? new File(value) : new File(base, value);
        String basePath = base.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (!targetPath.equals(basePath)
                && !targetPath.startsWith(basePath + File.separator)) {
            throw new IOException("Unable to access path outside the workspace");
        }
        return target;
    }

    String relativePath(File file) {
        return relativePath(root(), file);
    }

    String baseRelativePath(File file) {
        return relativePath(baseRoot(), file);
    }

    private static String relativePath(File base, File file) {
        try {
            String basePath = base.getCanonicalPath();
            String path = file.getCanonicalPath();
            if (path.equals(basePath)) {
                return "";
            }
            if (path.startsWith(basePath + File.separator)) {
                return path.substring(basePath.length() + 1);
            }
        } catch (IOException ignored) {
        }
        return file.getName();
    }

    List<File> listRecursive(int limit) {
        return listRecursive(root(), limit);
    }

    List<File> listRecursive(File directory, int limit) {
        List<File> files = new ArrayList<>();
        collect(directory, files, limit);
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                if (left.isDirectory() != right.isDirectory()) {
                    return left.isDirectory() ? -1 : 1;
                }
                return left.getAbsolutePath().compareToIgnoreCase(right.getAbsolutePath());
            }
        });
        return files;
    }

    List<String> listWorkspaceDirs(int maxDepth, int limit) {
        List<String> dirs = new ArrayList<>();
        dirs.add("");
        collectDirs(baseRoot(), "", 0, maxDepth, limit, dirs);
        return dirs;
    }

    private void collectDirs(File directory, String prefix, int depth, int maxDepth,
                             int limit, List<String> dirs) {
        if (depth >= maxDepth || dirs.size() >= limit) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (dirs.size() >= limit) {
                return;
            }
            if (!child.isDirectory() || child.getName().startsWith(".")) {
                continue;
            }
            String relative = prefix.isEmpty()
                    ? child.getName() : prefix + "/" + child.getName();
            dirs.add(relative);
            collectDirs(child, relative, depth + 1, maxDepth, limit, dirs);
        }
    }

    private void collect(File directory, List<File> files, int limit) {
        if (files.size() >= limit || directory == null) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (files.size() >= limit) {
                return;
            }
            if (child.getName().startsWith(".")) {
                continue;
            }
            files.add(child);
            if (child.isDirectory()) {
                collect(child, files, limit);
            }
        }
    }

    private static String cleanSubPath(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.trim().replace('\\', '/');
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (".".equals(clean) || clean.contains("..")) {
            return "";
        }
        return clean;
    }
}
