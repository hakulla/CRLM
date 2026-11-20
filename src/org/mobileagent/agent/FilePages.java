package org.mobileagent.agent;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/** Workspace file list with open, create and delete actions. */
final class FilePages {
    interface Host {
        boolean canBrowse();
        void toast(CharSequence text);
        void confirm(String title, String message, String action, boolean danger, Runnable run);
        String currentWorkspacePath();
        void openEditor(String path, String workspacePath);
        int dp(float value);
        ImageView icon(String name, int color);
        LinearLayout.LayoutParams iconSize(int size, int rightMargin);
    }

    private final Activity activity;
    private final Workspace workspace;
    private final Host host;
    private Dialog currentDialog;

    FilePages(Activity activity, Workspace workspace, Host host) {
        this.activity = activity;
        this.workspace = workspace;
        this.host = host;
    }

    void show() {
        if (!host.canBrowse()) {
            host.toast("Reading-only; browsing documents after returning to ongoing conversations");
            return;
        }
        LinearLayout content = AgentKit.column(activity);
        content.setPadding(host.dp(16), host.dp(4), host.dp(16), host.dp(16));

        TextView path = AgentKit.hint(activity, "Workspace:" + workspace.label()
                + " · Total " + workspace.listRecursive(200).size() + " File/Contents");
        content.addView(path, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, host.dp(6), 0, host.dp(4));
        LinearLayout list = AgentKit.column(activity);
        scroll.addView(list, new ScrollView.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        content.addView(scroll, new LinearLayout.LayoutParams(MainActivity.MATCH, 0, 1f));

        List<File> files = workspace.listRecursive(200);
        if (files.isEmpty()) {
            TextView empty = AgentKit.hint(activity,
                    "The workspace is empty. Agent Creates a file, or a 'New File' dot.");
            list.addView(empty, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        } else {
            for (File file : files) {
                list.addView(fileRow(file), new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
            }
        }

        LinearLayout actions = AgentKit.row(activity);
        actions.setPadding(0, host.dp(10), 0, 0);
        Button create = AgentKit.primary(activity, "New File");
        Button refresh = AgentKit.secondary(activity, "Refresh");
        actions.addView(create, new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP));
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP);
        refreshParams.setMargins(host.dp(8), 0, 0, 0);
        actions.addView(refresh, refreshParams);
        content.addView(actions, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));

        final Dialog dialog = AgentKit.page(activity, "Workspace File", content);
        create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                showNewFileDialog();
            }
        });
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                show();
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface unused) {
                if (currentDialog == dialog) {
                    currentDialog = null;
                }
            }
        });
        currentDialog = dialog;
    }

    private View fileRow(File file) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, host.dp(6), 0, host.dp(6));

        final String relative = workspace.relativePath(file);
        ImageView icon = host.icon(file.isDirectory() ? "ic_folder" : "ic_file",
                file.isDirectory() ? Color.rgb(226, 178, 84) : MainActivity.MUTED);
        row.addView(icon, host.iconSize(host.dp(17), host.dp(9)));
        LinearLayout info = AgentKit.column(activity);
        TextView name = new TextView(activity);
        name.setText(file.isDirectory() ? file.getName() : file.getName()
                + (file.isFile() ? "  ·  " + file.length() + " B" : ""));
        name.setTextColor(MainActivity.TEXT);
        name.setTextSize(12.5f);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        int slash = relative.lastIndexOf('/');
        String parent = slash < 0 ? "Workspace Root Directory" : relative.substring(0, slash);
        TextView path = AgentKit.hint(activity, parent);
        path.setSingleLine(true);
        path.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(path, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        row.addView(info, new LinearLayout.LayoutParams(0, MainActivity.WRAP, 1f));

        if (file.isFile()) {
            info.setClickable(true);
            info.setFocusable(true);
            info.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openEditor(relative);
                }
            });
            Button delete = AgentKit.ghost(activity, "Delete", MainActivity.ERROR);
            delete.setTextSize(10.5f);
            delete.setPadding(host.dp(9), host.dp(5), host.dp(9), host.dp(5));
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (currentDialog != null) {
                        currentDialog.dismiss();
                    }
                    confirmDelete(file, relative);
                }
            });
            row.addView(delete, new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP));
        }
        return row;
    }

    private void openEditor(String relativePath) {
        host.openEditor(relativePath, host.currentWorkspacePath());
    }

    

    private void openEditor(String relativePath, String workspacePath) {
        Intent intent = new Intent(activity, EditorActivity.class);
        intent.putExtra("path", relativePath);
        intent.putExtra("workspace", workspacePath);
        activity.startActivity(intent);
    }

    private void showNewFileDialog() {
        AgentKit.input(activity, "New File", "For example: notes/todo.md", "", "Open Editor",
                new AgentKit.InputHandler() {
                    @Override
                    public boolean onInput(String path, Dialog dialog) {
                        if (path.isEmpty()) {
                            host.toast("Please enter a path");
                            return false;
                        }
                        try {
                            workspace.resolve(path);
                        } catch (Exception e) {
                            host.toast(e.getMessage());
                            return false;
                        }
                        openEditor(path);
                        return true;
                    }
                });
    }

    private void confirmDelete(final File file, String relative) {
        host.confirm("Delete files?", relative, "Delete", true,
                new Runnable() {
                    @Override
                    public void run() {
                        if (file.delete()) {
                            host.toast("Deleted");
                            show();
                        } else {
                            host.toast("Failed to delete");
                        }
                    }
                });
    }

}
