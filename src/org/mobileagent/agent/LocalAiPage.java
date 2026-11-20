package org.mobileagent.agent;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local GGUF model page: status, import, load/test, catalog download and cleanup. */
final class LocalAiPage {
    interface Host {
        void showMenu(View anchor, String title, String[] labels, String[] icons,
                      int[] colors, Runnable[] actions);
        void showMenu(View anchor, String title, String[] labels, String[] icons,
                      int[] colors, Runnable[] actions, boolean wide);
        void toast(CharSequence text);
        void confirm(String title, String message, String action, boolean danger, Runnable run);
        void importModel();
        int dp(float value);
        GradientDrawable rounded(int color, float radiusDp);
        ImageView icon(String name, int color);
        LinearLayout.LayoutParams iconSize(int size, int rightMargin);
    }

    private final Activity activity;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Dialog dialog;

    LocalAiPage(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    void show() {
        final LocalAi ai = LocalAi.get(activity);
        final Dialog[] holder = new Dialog[1];
        LinearLayout content = AgentKit.column(activity);
        content.setPadding(host.dp(16), host.dp(6), host.dp(16), host.dp(26));
        content.addView(AgentKit.hint(activity,
                        "The model is fully operated locally, and is not available after download."
                                + "Hazardous operation interception; model layers are used to determine vague intent and are not used by default."),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));

        LinearLayout status = AgentKit.card(activity);
        status.addView(AgentKit.title(activity, "Local Model", 13f),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        TextView statusText = AgentKit.hint(activity, ai.backendAvailable()
                ? ai.statusText() : "Local run library not available (liblocal_llm.so)");
        statusText.setTextColor(ai.backendAvailable() ? MainActivity.SUCCESS : MainActivity.ERROR);
        status.addView(statusText, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        status.addView(AgentKit.hint(activity, "Contents" + ai.modelDir().getAbsolutePath()),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP);
        statusParams.setMargins(0, host.dp(12), 0, 0);
        content.addView(status, statusParams);

        LinearLayout row1 = AgentKit.row(activity);
        row1.setPadding(0, host.dp(10), 0, 0);
        Button importButton = AgentKit.primary(activity, "Import GGUF");
        row1.addView(importButton, new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP));
        Button verify = AgentKit.secondary(activity, "Verify Model");
        LinearLayout.LayoutParams verifyParams = new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP);
        verifyParams.setMargins(host.dp(8), 0, 0, 0);
        row1.addView(verify, verifyParams);
        content.addView(row1, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));

        LinearLayout row2 = AgentKit.row(activity);
        row2.setPadding(0, host.dp(8), 0, 0);
        Button load = AgentKit.secondary(activity, ai.isLoaded() ? "Unmount Model" : "Load Model");
        row2.addView(load, new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP));
        Button test = AgentKit.secondary(activity, "Test judgement");
        LinearLayout.LayoutParams testParams = new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP);
        testParams.setMargins(host.dp(8), 0, 0, 0);
        row2.addView(test, testParams);
        content.addView(row2, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));

        content.addView(AgentKit.label(activity, "Test Text"),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        final EditText testInput = AgentKit.field(activity,
                "Write me a list of to-dos and check the format.", "Enter a test text", false);
        content.addView(testInput, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));

        content.addView(AgentKit.section(activity, "Open source model market"),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        TextView marketStatus = AgentKit.hint(activity, marketStatusLine(ai));
        content.addView(marketStatus, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        content.addView(AgentKit.hint(activity,
                        "Model from hf-mirror; Downloads support stop-and-go,GGUF Synchronising folder"
                                + "Long by model line, you can diagnose the source, delete the model or uncompleted file."),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        for (LocalAi.ModelSpec spec : LocalAi.catalog()) {
            addMarketRow(content, ai, spec, holder, marketStatus);
        }

        boolean hasPartial = false;
        for (LocalAi.ModelSpec spec : LocalAi.catalog()) {
            if (ai.hasPartial(spec) && !ai.isDownloading(spec)) {
                if (!hasPartial) {
                    content.addView(AgentKit.section(activity, "Download not completed"),
                            new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
                    hasPartial = true;
                }
                addPartialRow(content, ai, spec, holder, marketStatus);
            }
        }

        content.addView(AgentKit.section(activity, "Installed Model (deleble)"),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        List<File> models = ai.models();
        if (models.isEmpty()) {
            content.addView(AgentKit.hint(activity,
                            "Models not yet installed; can be downloaded or imported from the top market GGUF. "),
                    new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        } else {
            File selected = ai.selectedFile();
            for (final File file : models) {
                LinearLayout row = AgentKit.row(activity);
                row.setBackground(host.rounded(MainActivity.CARD, 14));
                row.setPadding(host.dp(12), host.dp(11), host.dp(8), host.dp(11));
                row.setClickable(true);
                row.setFocusable(true);
                row.addView(host.icon("ic_terminal", MainActivity.MUTED), host.iconSize(host.dp(18), host.dp(10)));
                LinearLayout info = AgentKit.column(activity);
                info.addView(AgentKit.title(activity, file.getName(), 12f),
                        new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
                info.addView(AgentKit.hint(activity,
                                LocalAi.formatSize(file.length())
                                        + (file.equals(selected) ? " · Current Selection" : "")),
                        new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
                row.addView(info, new LinearLayout.LayoutParams(0, MainActivity.WRAP, 1f));
                if (file.equals(selected)) {
                    row.addView(host.icon("ic_check", MainActivity.TEXT), host.iconSize(host.dp(18), 0));
                }
                row.addView(AgentKit.ghost(activity, "Delete", MainActivity.ERROR),
                        new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ai.selectModel(file);
                        host.toast("Selected " + file.getName());
                        if (holder[0] != null) {
                            holder[0].dismiss();
                        }
                        show();
                    }
                });
                Button delete = (Button) row.getChildAt(row.getChildCount() - 1);
                delete.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        host.confirm("Remove the model?",
                                file.getName() + "\nIt'll release you. "
                                        + LocalAi.formatSize(file.length()),
                                "Delete", true, new Runnable() {
                                    @Override
                                    public void run() {
                                        ai.deleteModel(file);
                                        if (holder[0] != null) {
                                            holder[0].dismiss();
                                        }
                                        show();
                                    }
                                });
                    }
                });
                LinearLayout.LayoutParams rowParams =
                        new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP);
                rowParams.setMargins(0, host.dp(6), 0, 0);
                content.addView(row, rowParams);
            }
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        LinearLayout footer = AgentKit.row(activity);
        footer.setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(16));
        Button close = AgentKit.secondary(activity, "Close");
        footer.addView(close, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        holder[0] = AgentKit.page(activity, "Local Model", scroll, footer);
        dialog = holder[0];
        holder[0].setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (dialog == holder[0]) {
                    dialog = null;
                }
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        verify.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.toast("Validation of models...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String result = ai.verifyInstalled();
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                AgentKit.confirm(activity,
                                        result.startsWith("Validation") ? "Model self-checked." : "Model Self-check Results",
                                        result, "Okay.", false, null);
                            }
                        });
                    }
                }, "verify-models").start();
            }
        });
        importButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                host.importModel();
            }
        });
        load.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ai.isLoaded()) {
                    ai.unload();
                    host.toast("Unmounted local model");
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    show();
                } else {
                    loadLocalModel(ai, holder[0]);
                }
            }
        });
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testLocalModel(ai, holder[0], testInput.getText().toString().trim());
            }
        });
    }

    private String marketStatusLine(LocalAi ai) {
        long usable = ai.modelDir().getUsableSpace();
        String storage = usable > 0 ? LocalAi.formatSize(usable) : "Unknown";
        if (ai.isDownloading()) {
            return "Downloading " + ai.downloadingName() + " " + ai.downloadingPercent()
                    + "% · Available " + storage;
        }
        int installed = 0;
        for (LocalAi.ModelSpec spec : LocalAi.catalog()) {
            File file = ai.installedFile(spec);
            if (file.isFile() && LocalAi.verifyGguf(file, spec) == null) {
                installed++;
            }
        }
        return "Installed " + installed + " A market model · Available Storage " + storage;
    }

    private void addMarketRow(LinearLayout parent, final LocalAi ai,
                              final LocalAi.ModelSpec spec, final Dialog[] holder,
                              final TextView marketStatus) {
        final File target = ai.installedFile(spec);
        final String problem = target.isFile()
                ? LocalAi.verifyGguf(target, spec) : null;
        final boolean installed = target.isFile() && problem == null;
        final boolean partial = ai.hasPartial(spec);
        final boolean downloadingSpec = ai.isDownloading(spec);
        final boolean selected = installed && target.equals(ai.selectedFile());

        LinearLayout row = AgentKit.row(activity);
        row.setBackground(host.rounded(MainActivity.CARD, 14));
        row.setPadding(host.dp(12), host.dp(11), host.dp(8), host.dp(11));
        row.setClickable(true);
        row.setFocusable(true);
        row.setLongClickable(true);
        row.addView(host.icon("ic_terminal", installed ? MainActivity.SUCCESS : MainActivity.MUTED),
                host.iconSize(host.dp(18), host.dp(10)));

        LinearLayout info = AgentKit.column(activity);
        info.addView(AgentKit.title(activity, spec.name, 12.5f),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        info.addView(AgentKit.hint(activity,
                        spec.description + "\nQ4_K_M · " + LocalAi.formatSize(spec.bytes)
                                + " · " + spec.license + " · Recommendations RAM " + spec.minRamMb + "MB"),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        String status;
        if (downloadingSpec) {
            status = "Downloading " + ai.downloadingPercent() + "%";
        } else if (installed && selected) {
            status = "Selected";
        } else if (installed) {
            status = "Installed";
        } else if (problem != null) {
            status = "File anomaly:" + problem;
        } else if (partial) {
            long partBytes = ai.partialBytes(spec);
            int partPercent = spec.bytes <= 0 ? 0
                    : (int) Math.min(99, partBytes * 100 / spec.bytes);
            status = "Not completed " + partPercent + "%(" + LocalAi.formatSize(partBytes) + ")";
        } else {
            status = "Not Downloaded";
        }
        TextView statusView = AgentKit.hint(activity, status);
        statusView.setTextColor(installed ? MainActivity.SUCCESS : problem != null ? MainActivity.ERROR : MainActivity.MUTED);
        info.addView(statusView, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        row.addView(info, new LinearLayout.LayoutParams(0, MainActivity.WRAP, 1f));

        final Button action = downloadingSpec
                ? AgentKit.secondary(activity, "Cancel")
                : installed && !selected ? AgentKit.primary(activity, "Selection")
                : installed ? AgentKit.secondary(activity, "Selected")
                : partial ? AgentKit.primary(activity, "Go on.")
                : AgentKit.primary(activity, "Download");
        if (installed && selected) {
            action.setEnabled(false);
        }
        row.addView(action, new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP));

        View.OnClickListener start = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ai.isDownloading(spec)) {
                    ai.cancelDownload();
                    host.toast(
                            "Canceling downloads, uncompleted files will be kept");
                    refreshLocalAiPage(holder);
                    return;
                }
                if (installed) {
                    ai.selectModel(target);
                    host.toast("Selected " + spec.name);
                    refreshLocalAiPage(holder);
                    return;
                }
                action.setText("Cancel");
                statusView.setText("Downloading 0%");
                statusView.setTextColor(MainActivity.MUTED);
                startMarketDownload(ai, spec, holder, marketStatus, action, statusView);
            }
        };
        row.setOnClickListener(start);
        action.setOnClickListener(start);
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showMarketMenu(ai, spec, v, holder);
                return true;
            }
        });

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP);
        rowParams.setMargins(0, host.dp(6), 0, 0);
        parent.addView(row, rowParams);
    }

    private void addPartialRow(LinearLayout parent, final LocalAi ai,
                               final LocalAi.ModelSpec spec, final Dialog[] holder,
                               final TextView marketStatus) {
        final File part = ai.partFile(spec);
        if (part == null || !part.isFile() || part.length() <= 0) {
            return;
        }
        long bytes = part.length();
        int percent = spec.bytes <= 0 ? 0
                : (int) Math.min(99, bytes * 100 / spec.bytes);
        LinearLayout row = AgentKit.row(activity);
        row.setBackground(host.rounded(MainActivity.CARD, 14));
        row.setPadding(host.dp(12), host.dp(11), host.dp(8), host.dp(11));
        row.addView(host.icon("ic_archive", MainActivity.MUTED), host.iconSize(host.dp(18), host.dp(10)));
        LinearLayout info = AgentKit.column(activity);
        info.addView(AgentKit.title(activity, spec.name + " · Download not completed", 12.5f),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        final TextView statusView = AgentKit.hint(activity,
                "Downloaded " + percent + "%(" + LocalAi.formatSize(bytes)
                        + " / " + LocalAi.formatSize(spec.bytes)
                        + ")· Can continue or delete");
        info.addView(statusView, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        row.addView(info, new LinearLayout.LayoutParams(0, MainActivity.WRAP, 1f));
        final Button resume = AgentKit.primary(activity, "Go on.");
        row.addView(resume, new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP));
        Button delete = AgentKit.ghost(activity, "Delete", MainActivity.ERROR);
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP);
        deleteParams.setMargins(host.dp(6), 0, 0, 0);
        row.addView(delete, deleteParams);
        resume.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ai.isDownloading(spec)) {
                    ai.cancelDownload();
                    host.toast(
                            "Canceling downloads, uncompleted files will be kept");
                    return;
                }
                resume.setText("Cancel");
                startMarketDownload(ai, spec, holder, marketStatus, resume, statusView);
            }
        });
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.confirm("Delete uncompleted download?",
                        spec.name + "\nDownloaded "
                                + LocalAi.formatSize(part.length())
                                + " will be deleted and then redownloaded.",
                        "Delete", true, new Runnable() {
                            @Override
                            public void run() {
                                part.delete();
                                host.toast(
                                        "Deleted Uncompleted Downloads");
                                refreshLocalAiPage(holder);
                            }
                        });
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showMarketMenu(ai, spec, v, holder);
                return true;
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP);
        params.setMargins(0, host.dp(6), 0, 0);
        parent.addView(row, params);
    }

    private void showMarketMenu(final LocalAi ai, final LocalAi.ModelSpec spec,
                                View anchor, final Dialog[] holder) {
        List<String> labels = new ArrayList<>();
        List<String> icons = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Diagnosis Download Source");
        icons.add("ic_terminal");
        colors.add(MainActivity.TEXT);
        actions.add(new Runnable() {
            @Override
            public void run() {
                showDownloadProbe(ai, spec);
            }
        });
        final File target = ai.installedFile(spec);
        final boolean installed = target != null && target.isFile()
                && LocalAi.verifyGguf(target, spec) == null;
        if (installed) {
            labels.add("Delete Downloaded Model (" + LocalAi.formatSize(target.length()) + ")");
            icons.add("ic_close");
            colors.add(MainActivity.ERROR);
            actions.add(new Runnable() {
                @Override
                public void run() {
                    host.confirm("Delete downloaded models?",
                            spec.name + "\nIt'll release you. " + LocalAi.formatSize(target.length())
                                    + "; If you are loading or using it, the selection is first unloaded and cleared.",
                            "Delete", true, new Runnable() {
                                @Override
                                public void run() {
                                    ai.deleteModel(target);
                                    host.toast(
                                            "Other Organiser" + spec.name);
                                    refreshLocalAiPage(holder);
                                }
                            });
                }
            });
        } else if (target != null && target.isFile()) {
            labels.add("Delete the anomaly model file (" + LocalAi.formatSize(target.length()) + ")");
            icons.add("ic_close");
            colors.add(MainActivity.ERROR);
            actions.add(new Runnable() {
                @Override
                public void run() {
                    ai.deleteModel(target);
                    host.toast("Removed an anomaly file");
                    refreshLocalAiPage(holder);
                }
            });
        }
        final File part = ai.partFile(spec);
        if (part != null && part.isFile() && part.length() > 0 && !ai.isDownloading(spec)) {
            labels.add("Delete uncompleted file (in" + LocalAi.formatSize(part.length()) + ")");
            icons.add("ic_close");
            colors.add(MainActivity.ERROR);
            actions.add(new Runnable() {
                @Override
                public void run() {
                    host.confirm("Delete uncompleted download?",
                            spec.name + "\nDownloaded " + LocalAi.formatSize(part.length())
                                    + " will be deleted and then redownloaded.",
                            "Delete", true, new Runnable() {
                                @Override
                                public void run() {
                                    part.delete();
                                    host.toast(
                                            "Unfinished files deleted");
                                    refreshLocalAiPage(holder);
                                }
                            });
                }
            });
        }
        if (ai.isDownloading(spec)) {
            labels.add("Cancel Download (retain unfinished files)");
            icons.add("ic_close");
            colors.add(MainActivity.ERROR);
            actions.add(new Runnable() {
                @Override
                public void run() {
                    ai.cancelDownload();
                    refreshLocalAiPage(holder);
                }
            });
            labels.add("Cancel and remove unfinished files");
            icons.add("ic_close");
            colors.add(MainActivity.ERROR);
            actions.add(new Runnable() {
                @Override
                public void run() {
                    host.confirm("Cancel and delete downloads?",
                            spec.name + "\nBoth the downloading and the unfinished files will be deleted.",
                            "Delete", true, new Runnable() {
                                @Override
                                public void run() {
                                    ai.cancelDownloadAndDelete();
                                    handler.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            refreshLocalAiPage(holder);
                                        }
                                    }, 900L);
                                }
                            });
                }
            });
        }
        host.showMenu(anchor, spec.name, labels.toArray(new String[0]),
                icons.toArray(new String[0]),
                toIntArray(colors), actions.toArray(new Runnable[0]), true);
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private void refreshLocalAiPage(final Dialog[] holder) {
        if (holder != null && holder[0] != null && holder[0].isShowing()) {
            holder[0].dismiss();
            show();
        }
    }

    private void showDownloadProbe(final LocalAi ai, final LocalAi.ModelSpec spec) {
        LinearLayout content = AgentKit.column(activity);
        content.setPadding(host.dp(18), 0, host.dp(18), 0);
        content.addView(AgentKit.hint(activity,
                        "Test " + spec.name + " Other OrganiserHTTP Status, redirection, header and front 128KB. "),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        final TextView status = AgentKit.title(activity, "Diagnosis...", 13f);
        status.setPadding(0, host.dp(12), 0, 0);
        content.addView(status, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        final TextView detailView = AgentKit.hint(activity, "Connecting...");
        detailView.setTextColor(MainActivity.TEXT);
        detailView.setTextIsSelectable(true);
        content.addView(detailView, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        AgentKit.sheet(activity, "Download source diagnosis", content);
        ai.probe(spec, new LocalAi.ProbeCallback() {
            @Override
            public void onResult(boolean ok, String detail) {
                status.setText(ok ? "Download Source Available" : "Download source abnormal");
                status.setTextColor(ok ? MainActivity.SUCCESS : MainActivity.ERROR);
                detailView.setText(detail);
            }
        });
    }

    private void startMarketDownload(final LocalAi ai, final LocalAi.ModelSpec spec,
                                     final Dialog[] pageHolder, final TextView marketStatus,
                                     final Button rowAction, final TextView rowStatus) {
        final Dialog[] dialogHolder = new Dialog[1];
        LinearLayout content = AgentKit.column(activity);
        content.setPadding(host.dp(18), 0, host.dp(18), 0);
        content.addView(AgentKit.hint(activity,
                        spec.name + " · Q4_K_M · " + LocalAi.formatSize(spec.bytes)
                                + " · " + spec.license
                                + "\nSupports break-up; autochecks after completion GGUF file header and size."),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        final TextView title = AgentKit.title(activity, "Ready to download....", 13f);
        title.setPadding(0, host.dp(14), 0, 0);
        content.addView(title, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        final TextView detail = AgentKit.hint(activity, "0%");
        content.addView(detail, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        final Button cancel = AgentKit.secondary(activity, "Cancel Download");
        final Button retry = AgentKit.primary(activity, "Try again / Continue");
        final Button close = AgentKit.secondary(activity, "Close");
        retry.setVisibility(View.GONE);
        close.setVisibility(View.GONE);
        LinearLayout actions = AgentKit.row(activity);
        actions.setPadding(0, host.dp(14), 0, 0);
        actions.addView(cancel, new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP));
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP);
        retryParams.setMargins(host.dp(8), 0, 0, 0);
        actions.addView(retry, retryParams);
        actions.addView(close, retryParams);
        content.addView(actions, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        dialogHolder[0] = AgentKit.sheet(activity, "Download " + spec.name, content);

        final long[] startedAt = {System.currentTimeMillis()};
        final boolean[] finished = {false};
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ai.cancelDownload();
                host.toast(
                        "Canceling, unfinished files will be kept for transmission");
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                refreshLocalAiPage(pageHolder);
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                refreshLocalAiPage(pageHolder);
            }
        });
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                startMarketDownload(ai, spec, pageHolder, marketStatus, rowAction, rowStatus);
            }
        });

        ai.download(spec, new LocalAi.DownloadCallback() {
            @Override
            public void onStatus(String message) {
                if (dialogHolder[0] != null && dialogHolder[0].isShowing()) {
                    detail.setText(message);
                }
            }

            @Override
            public void onProgress(int percent, long bytes, long total) {
                if (finished[0]) {
                    return;
                }
                if (dialogHolder[0] != null && dialogHolder[0].isShowing()) {
                    title.setText("Downloading " + percent + "%");
                    long elapsed = Math.max(1, System.currentTimeMillis() - startedAt[0]);
                    long speed = bytes * 1000L / elapsed;
                    String eta = "";
                    if (speed > 0 && total > bytes) {
                        long seconds = (total - bytes) / speed;
                        eta = " · Remaining " + (seconds / 60) + ":"
                                + String.format(Locale.US, "%02d", seconds % 60);
                    }
                    detail.setText(LocalAi.formatSize(bytes) + " / "
                            + LocalAi.formatSize(total) + " · "
                            + LocalAi.formatSize(speed) + "/s" + eta);
                }
                if (marketStatus != null) {
                    marketStatus.setText("Downloading " + spec.name + " " + percent
                            + "% · Close this window and download will continue");
                }
                if (rowStatus != null) {
                    rowStatus.setText("Downloading " + percent + "%");
                    rowStatus.setTextColor(MainActivity.MUTED);
                }
                if (rowAction != null) {
                    rowAction.setText("Cancel");
                }
            }

            @Override
            public void onDone(File file, String error) {
                finished[0] = true;
                if (error != null) {
                    if (dialogHolder[0] != null && dialogHolder[0].isShowing()) {
                        title.setText("Download not finished");
                        title.setTextColor(MainActivity.ERROR);
                        detail.setText(error);
                        cancel.setVisibility(View.GONE);
                        retry.setVisibility(View.VISIBLE);
                        close.setVisibility(View.VISIBLE);
                    }
                    host.toast(error);
                    if (marketStatus != null) {
                        marketStatus.setText("Download failed:" + error);
                    }
                    if (rowStatus != null) {
                        rowStatus.setText("Download failed");
                        rowStatus.setTextColor(MainActivity.ERROR);
                    }
                    if (rowAction != null) {
                        rowAction.setText("Try again");
                    }
                    return;
                }
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                if (file != null) {
                    ai.selectModel(file);
                    host.toast(
                            "The model download is completed and validated:" + spec.name);
                }
                if (rowStatus != null) {
                    rowStatus.setText("Selected");
                    rowStatus.setTextColor(MainActivity.SUCCESS);
                }
                if (rowAction != null) {
                    rowAction.setText("Selected");
                }
                if (marketStatus != null) {
                    marketStatus.setText("Installed " + spec.name + " · "
                            + LocalAi.formatSize(spec.bytes));
                }
                refreshLocalAiPage(pageHolder);
            }
        });
    }

    private void loadLocalModel(final LocalAi ai, final Dialog parent) {
        if (parent != null) {
            parent.dismiss();
        }
        final Dialog[] holder = new Dialog[1];
        LinearLayout content = AgentKit.column(activity);
        content.setPadding(host.dp(18), 0, host.dp(18), 0);
        content.addView(AgentKit.hint(activity,
                        "It takes seconds to load first. 0.5GB Model to read memory."),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        TextView status = AgentKit.title(activity, "Loading...", 13f);
        status.setPadding(0, host.dp(14), 0, 0);
        content.addView(status, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        holder[0] = AgentKit.sheet(activity, "Local Model", content);
        ai.loadSelected(new LocalAi.LoadCallback() {
            @Override
            public void onDone(boolean ok, String info, String error) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                if (ok) {
                    ai.warmInBackground();
                }
                host.toast(ok ? "Model loaded:" + info : error);
                show();
            }
        });
    }

    private void testLocalModel(final LocalAi ai, final Dialog parent, final String sample) {
        if (parent != null) {
            parent.dismiss();
        }
        final Dialog[] holder = new Dialog[1];
        LinearLayout content = AgentKit.column(activity);
        content.setPadding(host.dp(18), 0, host.dp(18), 0);
        content.addView(AgentKit.hint(activity, "Test local models with an example and not connected."),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        TextView status = AgentKit.title(activity, "In judgement....", 13f);
        status.setPadding(0, host.dp(14), 0, 0);
        content.addView(status, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        holder[0] = AgentKit.sheet(activity, "Test local models", content);
        ai.classify(sample.isEmpty() ? "Write me a list of to-dos and check the format." : sample,
                new LocalAi.ClassifyCallback() {
            @Override
            public void onResult(LocalAi.ClassifyResult result) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                if (result.error != null) {
                    host.toast(result.error);
                    show();
                    return;
                }
                LinearLayout resultContent = AgentKit.column(activity);
                resultContent.setPadding(host.dp(18), 0, host.dp(18), 0);
                resultContent.addView(AgentKit.hint(activity,
                                "Time-consuming " + result.elapsedMs + "ms · "
                                        + result.kind),
                        new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
                TextView raw = new TextView(activity);
                raw.setText(result.raw);
                raw.setTextColor(MainActivity.TEXT);
                raw.setTextSize(11.5f);
                raw.setTypeface(Typeface.MONOSPACE);
                raw.setLineSpacing(host.dp(2), 1f);
                raw.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10));
                raw.setBackground(host.rounded(MainActivity.CARD_TOOL, 12));
                raw.setTextIsSelectable(true);
                LinearLayout.LayoutParams rawParams =
                        new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP);
                rawParams.setMargins(0, host.dp(12), 0, 0);
                resultContent.addView(raw, rawParams);
                AgentKit.sheet(activity, "Test Results", resultContent);
            }
        });
    }

}
