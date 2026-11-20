package org.mobileagent.agent;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Locale;

/** Build-artifact browser: list, archive, install and share APKs. */
final class ApkPages {
    interface Host {
        void hideKeyboard();
        void showMenu(View anchor, String title, String[] labels, String[] icons,
                      int[] colors, Runnable[] actions);
        void toast(CharSequence text);
        void confirm(String title, String message, String action, boolean danger, Runnable run);
        int dp(float value);
        ImageView icon(String name, int color);
        LinearLayout.LayoutParams iconSize(int size, int rightMargin);
    }

    private final Activity activity;
    private final Workspace workspace;
    private final Host host;

    ApkPages(Activity activity, Workspace workspace, Host host) {
        this.activity = activity;
        this.workspace = workspace;
        this.host = host;
    }

    void show() {
        host.hideKeyboard();
        ScrollView scroll = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = host.dp(16);
        content.setPadding(pad, pad, pad, host.dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));

        final Dialog dialog = AgentKit.page(activity, "Build products", scroll);

        LinearLayout summary = AgentKit.card(activity);
        summary.addView(AgentKit.section(activity, "Current installation"),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        TextView installInfo = new TextView(activity);
        String installed = "Unknown";
        try {
            installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        installInfo.setText("Version " + installed + " · " + activity.getPackageName());
        installInfo.setTextColor(MainActivity.MUTED);
        installInfo.setTextSize(12f);
        summary.addView(installInfo, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        content.addView(summary, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        content.addView(AgentKit.spacer(activity, 10));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button archive = AgentKit.primary(activity, "Save the latest build");
        archive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                archiveLatestApk();
            }
        });
        actions.addView(archive, new LinearLayout.LayoutParams(0, MainActivity.WRAP, 1f));
        Button refresh = AgentKit.secondary(activity, "Refresh");
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP);
        refreshParams.leftMargin = host.dp(8);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dialog != null) {
                    dialog.dismiss();
                }
                show();
            }
        });
        actions.addView(refresh, refreshParams);
        content.addView(actions, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        content.addView(AgentKit.spacer(activity, 14));

        ArrayList<File> archives = new ArrayList<>();
        ArrayList<File> builds = new ArrayList<>();
        collectApkFiles(workspace.baseRoot(), builds, archives, 0);
        content.addView(AgentKit.section(activity, "Archive Save (" + archives.size() + ")"),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        if (archives.isEmpty()) {
            content.addView(AgentKit.hint(activity, "No copies have been saved. Point up.'Save the latest build'Current APK Copy to apk-archive/ Contents."),
                    new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        } else {
            for (int i = 0; i < archives.size(); i++) {
                content.addView(buildApkRow(archives.get(i)), new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
            }
        }
        content.addView(AgentKit.spacer(activity, 14));
        content.addView(AgentKit.section(activity, "Workspace Build (" + builds.size() + ")"),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        if (builds.isEmpty()) {
            content.addView(AgentKit.hint(activity, "Not yet in the work area. .apk File, first. Agent Builds an application."),
                    new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        } else {
            for (int i = 0; i < builds.size(); i++) {
                content.addView(buildApkRow(builds.get(i)), new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
            }
        }
    }

    private void collectApkFiles(File dir, ArrayList<File> builds, ArrayList<File> archives, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 5) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (int i = 0; i < children.length; i++) {
            File child = children[i];
            if (child.isDirectory()) {
                String name = child.getName();
                if (name.startsWith(".") || name.equals("build") || name.equals("src")
                        || name.equals("res") || name.equals("assets")) {
                    continue;
                }
                collectApkFiles(child, builds, archives, depth + 1);
            } else if (child.getName().toLowerCase(Locale.US).endsWith(".apk")) {
                if (child.getParentFile() != null
                        && "apk-archive".equals(child.getParentFile().getName())) {
                    archives.add(child);
                } else {
                    builds.add(child);
                }
            }
        }
    }

    private View buildApkRow(final File apk) {
        LinearLayout row = AgentKit.card(activity);
        row.setClickable(true);
        LinearLayout header = AgentKit.row(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = host.icon("ic_archive", MainActivity.TEXT);
        header.addView(icon, host.iconSize(host.dp(20), host.dp(10)));
        TextView name = new TextView(activity);
        name.setText(apk.getName());
        name.setTextColor(MainActivity.TEXT);
        name.setTextSize(13f);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, MainActivity.WRAP, 1f);
        nameParams.leftMargin = host.dp(8);
        header.addView(name, nameParams);
        row.addView(header, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));

        String relative;
        try {
            relative = workspace.baseRelativePath(apk);
        } catch (Throwable error) {
            relative = apk.getName();
        }
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        TextView meta = new TextView(activity);
        meta.setText(UiText.formatSize(apk.length()) + " · " + format.format(new java.util.Date(apk.lastModified()))
                + " · " + relative);
        meta.setTextColor(MainActivity.DIM);
        meta.setTextSize(11f);
        meta.setPadding(host.dp(28), host.dp(4), 0, 0);
        row.addView(meta, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.showMenu(v, apk.getName(),
                        new String[]{"Install", "Save Copy", "Share", "Delete"},
                        new String[]{"ic_device", "ic_archive", "ic_file", "ic_close"},
                        new int[]{MainActivity.TEXT, MainActivity.TEXT, MainActivity.TEXT, MainActivity.ERROR},
                        new Runnable[]{
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        installApkFile(apk);
                                    }
                                },
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        archiveApk(apk);
                                    }
                                },
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        shareApkFile(apk);
                                    }
                                },
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        host.confirm("Remove Build",
                                                "Confirm Delete " + apk.getName() + " ? Can not be restored to application after deletion.",
                                                "Delete", true, new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        if (apk.delete()) {
                                                            host.toast("Deleted");
                                                        } else {
                                                            host.toast("Failed to delete");
                                                        }
                                                    }
                                                });
                                    }
                                }
                        });
            }
        });
        return row;
    }

    private void archiveLatestApk() {
        ArrayList<File> builds = new ArrayList<>();
        collectApkFiles(workspace.baseRoot(), builds, new ArrayList<File>(), 0);
        File newest = null;
        for (int i = 0; i < builds.size(); i++) {
            File candidate = builds.get(i);
            if (newest == null || candidate.lastModified() > newest.lastModified()) {
                newest = candidate;
            }
        }
        if (newest == null) {
            host.toast("There's no safe in the area. APK");
            return;
        }
        archiveApk(newest);
    }

    private void archiveApk(File apk) {
        if (apk == null || !apk.isFile()) {
            return;
        }
        try {
            File dir = new File(workspace.baseRoot(), "apk-archive");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new java.io.IOException("Could not close temporary folder: %s");
            }
            SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
            String base = apk.getName();
            int dot = base.toLowerCase(Locale.US).lastIndexOf(".apk");
            if (dot > 0) {
                base = base.substring(0, dot);
            }
            File target = new File(dir, base + "-" + stamp.format(new java.util.Date()) + ".apk");
            InputStream in = new java.io.FileInputStream(apk);
            OutputStream out = new FileOutputStream(target);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            host.toast("Saved to " + workspace.baseRelativePath(target));
        } catch (Throwable error) {
            host.toast("Save failed:" + (error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    private Uri apkContentUri(File apk) {
        String relative;
        try {
            relative = workspace.baseRelativePath(apk);
        } catch (Throwable error) {
            relative = apk.getName();
        }
        Uri.Builder builder = new Uri.Builder().scheme("content").authority(ApkProvider.AUTHORITY);
        String[] segments = relative.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (segments[i] != null && segments[i].length() > 0) {
                builder.appendPath(segments[i]);
            }
        }
        return builder.build();
    }

    private void installApkFile(File apk) {
        if (apk == null || !apk.isFile()) {
            host.toast("APK File does not exist");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkContentUri(apk), "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable error) {
            host.toast("Could not close temporary folder: %s" + error.getMessage());
        }
    }

    private void shareApkFile(File apk) {
        if (apk == null || !apk.isFile()) {
            host.toast("APK File does not exist");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/vnd.android.package-archive");
            intent.putExtra(Intent.EXTRA_STREAM, apkContentUri(apk));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, "Share or Send APK"));
        } catch (Throwable error) {
            host.toast("Can not get message: %s %s" + error.getMessage());
        }
    }

}
