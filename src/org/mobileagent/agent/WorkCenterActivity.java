package org.mobileagent.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Phone-side task centre: tickets, attention, watchdog state and evolution approval. */
public final class WorkCenterActivity extends Activity {
    private static final int BG = Color.rgb(10, 10, 10);
    private static final int CARD = Color.rgb(22, 22, 22);
    private static final int LINE = Color.rgb(48, 48, 48);
    private static final int TEXT = Color.rgb(244, 244, 244);
    private static final int MUTED = Color.rgb(158, 158, 158);
    private static final int DIM = Color.rgb(105, 105, 105);
    private static final int ACCENT = Color.rgb(255, 107, 107);
    private static final int GREEN = Color.rgb(118, 214, 170);
    private static final int GOLD = Color.rgb(230, 184, 92);

    private WorkSupervisor supervisor;
    private AppPrefs prefs;
    private LinearLayout content;
    private LinearLayout tabRow;
    private String tab = "attention";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        supervisor = WorkSupervisor.get(this);
        prefs = new AppPrefs(this);
        setContentView(build());
        LanguageManager.apply(getWindow().getDecorView());
        LanguageManager.watch(getWindow().getDecorView());
        refresh();
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(16);
        root.setPadding(pad, dp(10), pad, 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Mission Centre");
        title.setTextColor(TEXT);
        title.setTextSize(24);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(this);
        close.setText("Close");
        close.setTextColor(MUTED);
        close.setTextSize(13);
        close.setPadding(dp(8), dp(6), dp(4), dp(6));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        header.addView(close);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("Paper status, heart rate care, evolutionary approval and competency snapshot");
        subtitle.setTextColor(DIM);
        subtitle.setTextSize(11);
        subtitle.setPadding(0, dp(2), 0, dp(12));
        root.addView(subtitle);

        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(tabRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addTab("Pending", "attention");
        addTab("Running", "running");
        addTab("History", "history");
        addTab("Evolution", "evolution");
        addTab("Trigger", "triggers");

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(10), 0, dp(20));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(0, dp(8), 0, dp(12));
        CheckBox web = new CheckBox(this);
        web.setText("Allow Networking Tool web_fetch / web_search(Clear web pages/Search for tasks is still automatically authorized)");
        web.setTextColor(TEXT);
        web.setTextSize(11);
        web.setChecked(prefs.allowWebTools());
        web.setOnCheckedChangeListener((button, checked) -> {
            prefs.setAllowWebTools(checked);
            toast(checked ? "Networking tool is on." : "Networking tool closed");
        });
        footer.addView(web);
        CheckBox auto = new CheckBox(this);
        auto.setText("Auto archive after task completion (not entering pending)");
        auto.setTextColor(TEXT);
        auto.setTextSize(11);
        auto.setChecked(supervisor.store.autoAccept);
        auto.setOnCheckedChangeListener((button, checked) -> {
            supervisor.store.setAutoAccept(checked);
            toast(checked ? "Auto-archive when finished" : "Pending trial after completion");
        });
        footer.addView(auto);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = button("Refresh", false);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(); }
        });
        actions.addView(refresh, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button app = button("Open Agent", true);
        app.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(WorkCenterActivity.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            }
        });
        LinearLayout.LayoutParams appParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        appParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(app, appParams);
        footer.addView(actions);
        root.addView(footer);
        return root;
    }

    private void addTab(String label, final String value) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(12);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tab = value;
                refresh();
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(6), 0);
        tabRow.addView(view, params);
    }

    private void refresh() {
        if (tabRow != null) {
            for (int i = 0; i < tabRow.getChildCount(); i++) {
                TextView tabView = (TextView) tabRow.getChildAt(i);
                boolean selected = (i == 0 && "attention".equals(tab))
                        || (i == 1 && "running".equals(tab))
                        || (i == 2 && "history".equals(tab))
                        || (i == 3 && "evolution".equals(tab))
                        || (i == 4 && "triggers".equals(tab));
                tabView.setTextColor(selected ? Color.WHITE : MUTED);
                tabView.setBackground(selected ? pill(Color.rgb(45, 45, 45)) : null);
            }
        }
        content.removeAllViews();
        if ("attention".equals(tab)) {
            renderTickets(supervisor.store.needsAttention(),
                    "There's no paper that you're dealing with.");
        } else if ("running".equals(tab)) {
            List<WorkStore.Ticket> running = supervisor.store.byStatus(WorkStore.RUNNING);
            running.addAll(supervisor.store.byStatus(WorkStore.WAITING));
            renderTickets(running, "No active task.");
        } else if ("history".equals(tab)) {
            List<WorkStore.Ticket> all = supervisor.store.tickets;
            int shown = 0;
            for (WorkStore.Ticket ticket : all) {
                if (ticket.attention) continue;
                if (WorkStore.RUNNING.equals(ticket.status)
                        || WorkStore.WAITING.equals(ticket.status)) continue;
                renderTicket(ticket);
                shown++;
                if (shown >= 20) break;
            }
            if (shown == 0) empty("There is no historical paper.");
        } else if ("evolution".equals(tab)) {
            renderEvolution();
        } else {
            renderTriggers();
        }
    }

    private void renderTickets(List<WorkStore.Ticket> tickets, String emptyText) {
        if (tickets.isEmpty()) {
            empty(emptyText);
            return;
        }
        for (WorkStore.Ticket ticket : tickets) renderTicket(ticket);
    }

    private void renderTicket(final WorkStore.Ticket ticket) {
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(ticket.title == null || ticket.title.isEmpty()
                ? "Unnamed Task" : ticket.title);
        title.setTextColor(TEXT);
        title.setTextSize(14);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView state = new TextView(this);
        state.setText(statusLabel(ticket.status));
        state.setTextSize(10);
        state.setTextColor(statusColor(ticket.status));
        state.setPadding(dp(8), dp(4), dp(8), dp(4));
        state.setBackground(pill(Color.rgb(36, 36, 36)));
        top.addView(state);
        card.addView(top);

        TextView meta = new TextView(this);
        meta.setText("Source " + ticket.source + " · Scope " + ticket.scope
                + " · Steps " + ticket.step + (ticket.maxSteps > 0 ? "/" + ticket.maxSteps : "")
                + " · " + age(ticket.heartbeatAt));
        meta.setTextColor(DIM);
        meta.setTextSize(10);
        meta.setPadding(0, dp(5), 0, 0);
        card.addView(meta);

        if (ticket.lastError != null && !ticket.lastError.isEmpty()) {
            TextView error = new TextView(this);
            error.setText(ticket.lastError);
            error.setTextColor(ticket.attention ? ACCENT : MUTED);
            error.setTextSize(11);
            error.setLineSpacing(dp(2), 1f);
            error.setPadding(0, dp(7), 0, 0);
            card.addView(error);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(10), 0, 0);
        final boolean errorReport = ticket.report != null && !ticket.report.isEmpty();
        Button open = button(errorReport ? "Dealing with errors" : "Open Agent", false);
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent intent = new Intent(WorkCenterActivity.this, MainActivity.class);
                // Existing MainActivity It's only possible. onNewIntent - We got orders. Just write. prefs This is not a trigger for new conversations;
                // So, we'll take the scene. Intent Bring it along. onCreate The same processing branch).
                // Note: Only FLAG_ACTIVITY_REORDER_TO_FRONT ♪ There is already ♪ MainActivity - The front desk.
                // But it won't be back. onNewIntent, Nor will it be rebuilt, and additional errors will be lost silently (in the form of direct back to the main interface).
                // CLEAR_TOP + SINGLE_TOP Ensure reuse and recall onNewIntent; prefs As a covert by onResume Consumption.
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                if (errorReport) {
                    intent.putExtra(MainActivity.EXTRA_NEW_TASK_PROMPT, ticket.report);
                    new AppPrefs(WorkCenterActivity.this).setPendingTaskPrompt(ticket.report);
                }
                startActivity(intent);
            }
        });
        actions.addView(open);
        if (errorReport) {
            Button detail = button("Check the scene.", false);
            detail.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showReport(ticket.report);
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(8), 0, 0, 0);
            actions.addView(detail, params);
        }
        if (ticket.attention) {
            Button handled = button("Tags processed", true);
            handled.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    supervisor.store.clearAttention(ticket.id);
                    toast("Marked for processing");
                    refresh();
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(8), 0, 0, 0);
            actions.addView(handled, params);
        }
        card.addView(actions);
        content.addView(card, cardParams());
    }

    private void showReport(String report) {
        TextView body = new TextView(this);
        body.setText(report == null ? "" : report);
        body.setTextColor(TEXT);
        body.setTextSize(11.5f);
        body.setLineSpacing(dp(2), 1f);
        body.setTextIsSelectable(true);
        body.setPadding(dp(12), dp(8), dp(12), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Wrong scene.")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }

    private void renderEvolution() {
        LinearLayout tools = card();
        TextView heading = new TextView(this);
        heading.setText("Capability snapshot and roll back");
        heading.setTextColor(TEXT);
        heading.setTextSize(13);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tools.addView(heading);
        TextView hint = new TextView(this);
        File latest = supervisor.evolution.latestSnapshot();
        hint.setText(latest == null ? "No snapshots. It is recommended that you create one before you approve evolution."
                : "Recent snapshot:" + latest.getName());
        hint.setTextColor(MUTED);
        hint.setTextSize(10);
        hint.setPadding(0, dp(4), 0, 0);
        tools.addView(hint);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(9), 0, 0);
        Button snapshot = button("Create Snapshot", false);
        snapshot.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                File result = supervisor.evolution.snapshotEvolution("manual");
                toast(result == null ? "Scatter failed." : "Created " + result.getName());
                refresh();
            }
        });
        actions.addView(snapshot);
        Button rollback = button("Roll back to the latest snapshot.", true);
        rollback.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmRollback(); }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(8), 0, 0, 0);
        actions.addView(rollback, params);
        tools.addView(actions);
        content.addView(tools, cardParams());

        List<File> proposals = supervisor.evolution.proposals();
        if (proposals.isEmpty()) {
            empty("There are currently no pending proposals for evolution.");
            return;
        }
        for (final File proposal : proposals) {
            LinearLayout card = card();
            TextView name = new TextView(this);
            name.setText(proposal.getName());
            name.setTextColor(TEXT);
            name.setTextSize(13);
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            card.addView(name);
            TextView meta = new TextView(this);
            meta.setText("Pending manual clearance · " + age(proposal.lastModified()));
            meta.setTextColor(GOLD);
            meta.setTextSize(10);
            meta.setPadding(0, dp(4), 0, 0);
            card.addView(meta);
            LinearLayout proposalActions = new LinearLayout(this);
            proposalActions.setOrientation(LinearLayout.HORIZONTAL);
            proposalActions.setPadding(0, dp(9), 0, 0);
            Button approve = button("Approval", true);
            approve.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (supervisor.evolution.approveProposal(proposal, true)) {
                        toast("The proposal has been approved. Worker Implementation");
                        refresh();
                    } else toast("Approval failed");
                }
            });
            proposalActions.addView(approve);
            Button reject = button("Reject", false);
            reject.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (supervisor.evolution.approveProposal(proposal, false)) {
                        toast("Proposal rejected");
                        refresh();
                    } else toast("Reject Failure");
                }
            });
            LinearLayout.LayoutParams proposalParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            proposalParams.setMargins(dp(8), 0, 0, 0);
            proposalActions.addView(reject, proposalParams);
            card.addView(proposalActions);
            content.addView(card, cardParams());
        }
    }

    private void renderTriggers() {
        int shown = 0;
        for (WorkStore.Trigger trigger : supervisor.store.triggers) {
            LinearLayout row = card();
            TextView type = new TextView(this);
            type.setText("[" + trigger.source + "]  " + age(trigger.at));
            type.setTextColor(DIM);
            type.setTextSize(9.5f);
            row.addView(type);
            TextView text = new TextView(this);
            text.setText(trigger.text);
            text.setTextColor(TEXT);
            text.setTextSize(11.5f);
            text.setLineSpacing(dp(2), 1f);
            text.setPadding(0, dp(4), 0, 0);
            row.addView(text);
            content.addView(row, cardParams());
            if (++shown >= 30) break;
        }
        if (shown == 0) empty("No trigger record yet.");
    }

    private void confirmRollback() {
        new AlertDialog.Builder(this)
                .setTitle("Rolling back?")
                .setMessage("Will memory / lessons / skills / agents / knowledge Restor to the latest snapshot. Changes that are not currently snapshotd will be lost.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Roll back", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        toast(supervisor.evolution.rollbackEvolution() ? "Rolling back" : "No quick roller photos.");
                        refresh();
                    }
                })
                .show();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(16));
        background.setStroke(1, LINE);
        card.setBackground(background);
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(9));
        return params;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : TEXT);
        button.setBackground(primary ? pill(Color.rgb(63, 91, 99)) : pill(Color.rgb(38, 38, 38)));
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        return button;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(999));
        return drawable;
    }

    private void empty(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(DIM);
        view.setTextSize(12);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(36), 0, dp(36));
        content.addView(view);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static String statusLabel(String status) {
        if (WorkStore.RUNNING.equals(status)) return "Under implementation";
        if (WorkStore.WAITING.equals(status)) return "Wait";
        if (WorkStore.BLOCKED.equals(status)) return "Blocked";
        if (WorkStore.READY.equals(status)) return "Pending trial";
        if (WorkStore.FAILED.equals(status)) return "Failed";
        if (WorkStore.STOPPED.equals(status)) return "Stopped";
        return "Closed";
    }

    private static int statusColor(String status) {
        if (WorkStore.RUNNING.equals(status)) return GREEN;
        if (WorkStore.BLOCKED.equals(status) || WorkStore.FAILED.equals(status)) return ACCENT;
        if (WorkStore.READY.equals(status)) return GOLD;
        return MUTED;
    }

    private static String age(long time) {
        if (time <= 0) return "No heart.";
        long seconds = Math.max(0, (System.currentTimeMillis() - time) / 1000L);
        if (seconds < 60) return seconds + " Second";
        if (seconds < 3600) return (seconds / 60) + " A minute ago.";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(new Date(time));
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
