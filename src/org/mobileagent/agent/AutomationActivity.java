package org.mobileagent.agent;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Automation management: scheduled tasks, run now, history and enable/disable. */
public class AutomationActivity extends Activity {
    private AutomationStore store;
    private LinearLayout list;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            render();
            handler.postDelayed(this, 2000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(MainActivity.BG);
        getWindow().setNavigationBarColor(MainActivity.BG);
        store = AutomationStore.get(this);
        setContentView(buildUi());
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LanguageManager.apply(getWindow().getDecorView());
        LanguageManager.watch(getWindow().getDecorView());
        render();
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, 2000L);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private View buildUi() {
        LinearLayout root = AgentKit.column(this);
        root.setBackgroundColor(MainActivity.BG);

        LinearLayout header = AgentKit.row(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        Button back = AgentKit.secondary(this, "Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(back, new LinearLayout.LayoutParams(WRAP, WRAP));

        TextView title = AgentKit.title(this, "Automation", 20f);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));

        Button create = AgentKit.primary(this, "New");
        create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editTask(null);
            }
        });
        header.addView(create, new LinearLayout.LayoutParams(WRAP, WRAP));

        TextView intro = AgentKit.hint(this,
                "Autostart as planned Agent The work, each execution generates an independent session, where the results can be viewed."
                        + "Automation requires the operation of a notification service; all service will be automatically terminated after the closure of the mission.");
        intro.setPadding(dp(16), 0, dp(16), dp(8));
        root.addView(intro, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout stopRow = AgentKit.row(this);
        stopRow.setPadding(dp(16), 0, dp(16), dp(8));
        Button stop = AgentKit.secondary(this, "Stop Current Run");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutomationService.stopCurrent(AutomationActivity.this);
                Toast.makeText(AutomationActivity.this,
                        "Requested to stop current automation tasks", Toast.LENGTH_SHORT).show();
            }
        });
        stopRow.addView(stop, new LinearLayout.LayoutParams(WRAP, WRAP));
        root.addView(stopRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        list = AgentKit.column(this);
        list.setPadding(dp(12), 0, dp(12), dp(20));
        scroll.addView(list, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        return root;
    }

    private void render() {
        if (list == null) {
            return;
        }
        list.removeAllViews();
        List<AutomationStore.Task> tasks = store.tasks();
        if (tasks.isEmpty()) {
            list.addView(AgentKit.hint(this,
                    "No automation. 'New' at the top right corner, set up mission instructions and implementation plans."),
                    new LinearLayout.LayoutParams(MATCH, WRAP));
            return;
        }
        for (final AutomationStore.Task task : tasks) {
            list.addView(taskCard(task), new LinearLayout.LayoutParams(MATCH, WRAP));
            list.addView(AgentKit.spacer(this, 8));
        }
    }

    private View taskCard(final AutomationStore.Task task) {
        LinearLayout card = AgentKit.card(this);
        LinearLayout top = AgentKit.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = AgentKit.title(this, task.name, 14f);
        top.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView state = AgentKit.hint(this, task.enabled ? "Enabled" : "Disabled");
        state.setTextColor(task.enabled ? MainActivity.SUCCESS : MainActivity.DIM);
        top.addView(state, new LinearLayout.LayoutParams(WRAP, WRAP));
        card.addView(top, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView summary = AgentKit.hint(this, task.scheduleSummary()
                + " · Next time. " + AutomationStore.formatTime(task.nextRunAt));
        card.addView(summary, new LinearLayout.LayoutParams(MATCH, WRAP));

        String last = "No running record yet";
        if (task.lastRunAt > 0) {
            last = "Last time. " + AutomationStore.formatRunTime(task.lastRunAt)
                    + " · " + outcomeLabel(task.lastOutcome);
            if (task.lastError != null && !task.lastError.trim().isEmpty()) {
                last += " · " + shorten(task.lastError, 60);
            }
        }
        TextView lastView = AgentKit.hint(this, last);
        lastView.setTextColor("failed".equals(task.lastOutcome)
                ? MainActivity.ERROR : MainActivity.MUTED);
        card.addView(lastView, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout actions = AgentKit.row(this);
        actions.setPadding(0, dp(8), 0, 0);
        Button run = AgentKit.primary(this, "Run Now");
        actions.addView(run, new LinearLayout.LayoutParams(WRAP, WRAP));
        Button toggle = AgentKit.secondary(this, task.enabled ? "Disable" : "Enable");
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        toggleParams.leftMargin = dp(6);
        actions.addView(toggle, toggleParams);
        Button history = AgentKit.secondary(this, "Records");
        LinearLayout.LayoutParams historyParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        historyParams.leftMargin = dp(6);
        actions.addView(history, historyParams);
        Button edit = AgentKit.secondary(this, "Edit");
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        editParams.leftMargin = dp(6);
        actions.addView(edit, editParams);
        card.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));

        Button delete = AgentKit.ghost(this, "Delete", MainActivity.ERROR);
        delete.setPadding(0, dp(8), 0, 0);
        card.addView(delete, new LinearLayout.LayoutParams(WRAP, WRAP));

        run.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AutomationService.runNow(AutomationActivity.this, task.id);
                Toast.makeText(AutomationActivity.this,
                        "Starts running:" + task.name, Toast.LENGTH_SHORT).show();
            }
        });
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                store.setEnabled(task.id, !task.enabled);
                if (task.enabled) {
                    AutomationService.stopCurrent(AutomationActivity.this);
                } else {
                    AutomationService.startIfEnabled(AutomationActivity.this);
                }
                render();
            }
        });
        history.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRuns(task);
            }
        });
        edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editTask(task);
            }
        });
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AgentKit.confirm(AutomationActivity.this, "Delete Automation",
                        "Confirm Delete" + task.name + "'And their running records?",
                        "Delete", true, new Runnable() {
                            @Override
                            public void run() {
                                store.delete(task.id);
                                AutomationService.startIfEnabled(AutomationActivity.this);
                                render();
                            }
                        });
            }
        });
        return card;
    }

    private void showRuns(AutomationStore.Task task) {
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(16), dp(4), dp(16), dp(16));
        List<AutomationStore.Run> runs = store.runsFor(task.id, 20);
        if (runs.isEmpty()) {
            content.addView(AgentKit.hint(this, "No running records yet."),
                    new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        for (final AutomationStore.Run run : runs) {
            LinearLayout card = AgentKit.card(this);
            TextView title = AgentKit.title(this,
                    AutomationStore.formatRunTime(run.startedAt) + " · "
                            + outcomeLabel(run.outcome), 12f);
            title.setTextColor("failed".equals(run.outcome)
                    ? MainActivity.ERROR : MainActivity.TEXT);
            card.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));
            String detail = run.answer == null || run.answer.trim().isEmpty()
                    ? run.error : run.answer;
            card.addView(AgentKit.hint(this, shorten(detail, 240)),
                    new LinearLayout.LayoutParams(MATCH, WRAP));
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (run.conversationId == null || run.conversationId.isEmpty()) {
                        return;
                    }
                    Intent open = new Intent(AutomationActivity.this, MainActivity.class);
                    open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    open.putExtra(MainActivity.EXTRA_CONVERSATION_ID, run.conversationId);
                    startActivity(open);
                }
            });
            content.addView(card, new LinearLayout.LayoutParams(MATCH, WRAP));
            content.addView(AgentKit.spacer(this, 6));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        AgentKit.page(this, "Run Record · " + task.name, scroll);
    }

    private void editTask(AutomationStore.Task existing) {
        final AutomationStore.Task target = existing == null ? new AutomationStore.Task() : existing;
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(16), dp(4), dp(16), dp(14));

        content.addView(AgentKit.label(this, "Name"),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        final EditText name = AgentKit.field(this, target.name,
                "For example: daily code check", false);
        content.addView(name, new LinearLayout.LayoutParams(MATCH, WRAP));

        content.addView(AgentKit.label(this, "Task Directive"),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        final EditText prompt = AgentKit.field(this, target.prompt,
                "Description Agent What is done every time; independent sessions are generated.", true);
        content.addView(prompt, new LinearLayout.LayoutParams(MATCH, WRAP));

        content.addView(AgentKit.label(this, "Workspace (empty)=Root directory)"),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        final EditText workspace = AgentKit.field(this, target.workspace,
                "For example: projects/mobile-agent", false);
        content.addView(workspace, new LinearLayout.LayoutParams(MATCH, WRAP));

        content.addView(AgentKit.label(this, "Implementation plan"),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        final Spinner type = spinner(new String[]{
                "Every N min/Hours/Oh, my God.", "Chile HH:mm", "Weekly HH:mm", "Monthly HH:mm"
        });
        content.addView(type, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout intervalRow = AgentKit.row(this);
        intervalRow.setPadding(0, dp(6), 0, 0);
        final EditText intervalValue = AgentKit.field(this,
                String.valueOf(intervalValue(target.intervalMinutes)), "N", false);
        intervalValue.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalRow.addView(intervalValue, new LinearLayout.LayoutParams(0, WRAP, 1f));
        final Spinner intervalUnit = spinner(new String[]{"min", "Hours", "Oh, my God."});
        LinearLayout.LayoutParams unitParams = new LinearLayout.LayoutParams(0, WRAP, 1f);
        unitParams.leftMargin = dp(6);
        intervalRow.addView(intervalUnit, unitParams);
        content.addView(intervalRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout clockRow = AgentKit.row(this);
        clockRow.setPadding(0, dp(6), 0, 0);
        final EditText hour = numberField(target.hour, "Time");
        final EditText minute = numberField(target.minute, "min");
        clockRow.addView(hour, new LinearLayout.LayoutParams(0, WRAP, 1f));
        clockRow.addView(minute, new LinearLayout.LayoutParams(0, WRAP, 1f));
        content.addView(clockRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        final Spinner weekday = spinner(new String[]{
                "Sunday", "Monday.", "Tuesday.", "Wednesday", "Thursday", "Friday.", "Saturday"
        });
        weekday.setSelection(Math.max(0, Math.min(6, target.weekday - 1)));
        content.addView(weekday, new LinearLayout.LayoutParams(MATCH, WRAP));

        final EditText dayOfMonth = numberField(target.dayOfMonth, "Number of months (in thousands)1-28)");
        content.addView(dayOfMonth, new LinearLayout.LayoutParams(MATCH, WRAP));

        final CheckBox readOnly = new CheckBox(this);
        readOnly.setText("Read-only mode (cannot write)/Command/Networking)");
        readOnly.setTextColor(MainActivity.TEXT);
        readOnly.setTextSize(12f);
        readOnly.setChecked(target.readOnly);
        content.addView(readOnly, new LinearLayout.LayoutParams(MATCH, WRAP));

        final CheckBox enabled = new CheckBox(this);
        enabled.setText("Enable (to point auto-run)");
        enabled.setTextColor(MainActivity.TEXT);
        enabled.setTextSize(12f);
        enabled.setChecked(existing == null || target.enabled);
        content.addView(enabled, new LinearLayout.LayoutParams(MATCH, WRAP));

        int typeIndex = "interval".equals(target.scheduleType) ? 0
                : "weekly".equals(target.scheduleType) ? 2
                : "monthly".equals(target.scheduleType) ? 3 : 1;
        type.setSelection(typeIndex);
        int unitIndex = target.intervalMinutes % 1440 == 0 ? 2
                : target.intervalMinutes % 60 == 0 ? 1 : 0;
        intervalUnit.setSelection(unitIndex);

        final Runnable updateVisibility = new Runnable() {
            @Override
            public void run() {
                int selected = type.getSelectedItemPosition();
                setVisible(intervalRow, selected == 0);
                setVisible(clockRow, selected != 0);
                setVisible(weekday, selected == 2);
                setVisible(dayOfMonth, selected == 3);
            }
        };
        type.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                updateVisibility.run();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        updateVisibility.run();

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        LinearLayout footer = AgentKit.row(this);
        footer.setPadding(dp(16), dp(8), dp(16), dp(14));
        Button cancel = AgentKit.secondary(this, "Cancel");
        footer.addView(cancel, new LinearLayout.LayoutParams(0, WRAP, 1f));
        Button save = AgentKit.primary(this, "Save");
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, WRAP, 1f);
        saveParams.leftMargin = dp(8);
        footer.addView(save, saveParams);
        final Dialog dialog = AgentKit.page(this,
                existing == null ? "New Automation" : "Editorial automation", scroll, footer);

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String taskName = name.getText().toString().trim();
                String taskPrompt = prompt.getText().toString().trim();
                if (taskName.isEmpty() || taskPrompt.isEmpty()) {
                    Toast.makeText(AutomationActivity.this,
                            "Name and task command cannot be empty", Toast.LENGTH_SHORT).show();
                    return;
                }
                int selected = type.getSelectedItemPosition();
                int minutes = parseInt(intervalValue.getText().toString(), 60)
                        * (intervalUnit.getSelectedItemPosition() == 2 ? 1440
                        : intervalUnit.getSelectedItemPosition() == 1 ? 60 : 1);
                String scheduleType = selected == 0 ? "interval"
                        : selected == 2 ? "weekly" : selected == 3 ? "monthly" : "daily";
                int taskHour = parseInt(hour.getText().toString(), 9);
                int taskMinute = parseInt(minute.getText().toString(), 0);
                int taskWeekday = weekday.getSelectedItemPosition() + 1;
                int taskDay = parseInt(dayOfMonth.getText().toString(), 1);
                if (existing == null) {
                    AutomationStore.Task created = store.create(taskName, taskPrompt,
                            workspace.getText().toString(), scheduleType, minutes,
                            taskHour, taskMinute, taskWeekday, taskDay, readOnly.isChecked());
                    created.enabled = enabled.isChecked();
                    store.update(created);
                } else {
                    target.name = taskName;
                    target.prompt = taskPrompt;
                    target.workspace = workspace.getText().toString().trim();
                    target.scheduleType = scheduleType;
                    target.intervalMinutes = minutes;
                    target.hour = taskHour;
                    target.minute = taskMinute;
                    target.weekday = taskWeekday;
                    target.dayOfMonth = taskDay;
                    target.readOnly = readOnly.isChecked();
                    target.enabled = enabled.isChecked();
                    store.update(target);
                }
                if (enabled.isChecked()) {
                    AutomationService.start(AutomationActivity.this);
                } else {
                    AutomationService.startIfEnabled(AutomationActivity.this);
                }
                dialog.dismiss();
                render();
                Toast.makeText(AutomationActivity.this, "Automation saved",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private EditText numberField(int value, String hint) {
        EditText field = AgentKit.field(this, String.valueOf(value), hint, false);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        return field;
    }

    private <T> Spinner spinner(T[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<T> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private static void setVisible(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text == null ? "" : text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int intervalValue(int minutes) {
        if (minutes % 1440 == 0) return Math.max(1, minutes / 1440);
        if (minutes % 60 == 0) return Math.max(1, minutes / 60);
        return Math.max(5, minutes);
    }

    private String outcomeLabel(String outcome) {
        if ("completed".equals(outcome)) return "Completed";
        if ("paused".equals(outcome)) return "Pause";
        if ("stopped".equals(outcome)) return "Stop";
        if ("failed".equals(outcome)) return "Failed";
        if ("running".equals(outcome)) return "Running";
        return outcome == null || outcome.isEmpty() ? "Unknown" : outcome;
    }

    private static String shorten(String text, int max) {
        if (text == null) return "";
        String clean = text.replace('\n', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    private int dp(float value) {
        return AgentKit.dp(this, value);
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
}
