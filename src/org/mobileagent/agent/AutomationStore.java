package org.mobileagent.agent;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Durable scheduled-task store: task definitions plus a bounded run history.
 * App-private so workspace edits/prompt injection cannot silently add schedules.
 */
final class AutomationStore {
    private static final String TAG = "AgentAutomation";
    private static final int MAX_TASKS = 50;
    private static final int MAX_RUNS = 200;
    private static AutomationStore INSTANCE;

    static synchronized AutomationStore get(Context context) {
        if (INSTANCE == null) {
            File dir = new File(context.getApplicationContext().getFilesDir(), "work");
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            INSTANCE = new AutomationStore(new File(dir, "automations.json"));
        }
        return INSTANCE;
    }

    static final class Task {
        String id = "";
        String name = "";
        String prompt = "";
        String workspace = "";
        boolean enabled = true;
        boolean readOnly = false;
        String scheduleType = "daily";
        int intervalMinutes = 60;
        int hour = 9;
        int minute = 0;
        int weekday = 2;
        int dayOfMonth = 1;
        long nextRunAt;
        long lastRunAt;
        String lastOutcome = "";
        String lastError = "";
        String lastConversationId = "";
        int runCount;

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("prompt", prompt)
                    .put("workspace", workspace)
                    .put("enabled", enabled)
                    .put("read_only", readOnly)
                    .put("schedule_type", scheduleType)
                    .put("interval_minutes", intervalMinutes)
                    .put("hour", hour)
                    .put("minute", minute)
                    .put("weekday", weekday)
                    .put("day_of_month", dayOfMonth)
                    .put("next_run_at", nextRunAt)
                    .put("last_run_at", lastRunAt)
                    .put("last_outcome", lastOutcome)
                    .put("last_error", lastError)
                    .put("last_conversation", lastConversationId)
                    .put("run_count", runCount);
        }

        Task copy() {
            Task task = new Task();
            task.id = id;
            task.name = name;
            task.prompt = prompt;
            task.workspace = workspace;
            task.enabled = enabled;
            task.readOnly = readOnly;
            task.scheduleType = scheduleType;
            task.intervalMinutes = intervalMinutes;
            task.hour = hour;
            task.minute = minute;
            task.weekday = weekday;
            task.dayOfMonth = dayOfMonth;
            task.nextRunAt = nextRunAt;
            task.lastRunAt = lastRunAt;
            task.lastOutcome = lastOutcome;
            task.lastError = lastError;
            task.lastConversationId = lastConversationId;
            task.runCount = runCount;
            return task;
        }

        static Task fromJson(JSONObject object) {
            Task task = new Task();
            task.id = object.optString("id", "");
            task.name = object.optString("name", "");
            task.prompt = object.optString("prompt", "");
            task.workspace = object.optString("workspace", "");
            task.enabled = object.optBoolean("enabled", true);
            task.readOnly = object.optBoolean("read_only", false);
            task.scheduleType = object.optString("schedule_type", "daily");
            task.intervalMinutes = object.optInt("interval_minutes", 60);
            task.hour = object.optInt("hour", 9);
            task.minute = object.optInt("minute", 0);
            task.weekday = object.optInt("weekday", 2);
            task.dayOfMonth = object.optInt("day_of_month", 1);
            task.nextRunAt = object.optLong("next_run_at", 0L);
            task.lastRunAt = object.optLong("last_run_at", 0L);
            task.lastOutcome = object.optString("last_outcome", "");
            task.lastError = object.optString("last_error", "");
            task.lastConversationId = object.optString("last_conversation", "");
            task.runCount = object.optInt("run_count", 0);
            return task;
        }

        String scheduleSummary() {
            if ("interval".equals(scheduleType)) {
                if (intervalMinutes % 1440 == 0) {
                    return "Every " + (intervalMinutes / 1440) + " Oh, my God.";
                }
                if (intervalMinutes % 60 == 0) {
                    return "Every " + (intervalMinutes / 60) + " Hours";
                }
                return "Every " + intervalMinutes + " min";
            }
            String time = String.format(Locale.US, "%02d:%02d", hour, minute);
            if ("weekly".equals(scheduleType)) {
                String[] names = {"", "Sunday", "Monday.", "Tuesday.", "Wednesday", "Thursday", "Friday.", "Saturday"};
                int index = Math.max(1, Math.min(7, weekday));
                return "Every" + names[index] + " " + time;
            }
            if ("monthly".equals(scheduleType)) {
                return "Monthly " + Math.max(1, Math.min(28, dayOfMonth)) + " Day " + time;
            }
            return "Chile " + time;
        }
    }

    static final class Run {
        String id = "";
        String taskId = "";
        String conversationId = "";
        long startedAt;
        long finishedAt;
        String outcome = "running";
        String answer = "";
        String error = "";
        long tokens;

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("id", id)
                    .put("task", taskId)
                    .put("conversation", conversationId)
                    .put("started", startedAt)
                    .put("finished", finishedAt)
                    .put("outcome", outcome)
                    .put("answer", answer)
                    .put("error", error)
                    .put("tokens", tokens);
        }

        Run copy() {
            Run run = new Run();
            run.id = id;
            run.taskId = taskId;
            run.conversationId = conversationId;
            run.startedAt = startedAt;
            run.finishedAt = finishedAt;
            run.outcome = outcome;
            run.answer = answer;
            run.error = error;
            run.tokens = tokens;
            return run;
        }

        static Run fromJson(JSONObject object) {
            Run run = new Run();
            run.id = object.optString("id", "");
            run.taskId = object.optString("task", "");
            run.conversationId = object.optString("conversation", "");
            run.startedAt = object.optLong("started", 0L);
            run.finishedAt = object.optLong("finished", 0L);
            run.outcome = object.optString("outcome", "running");
            run.answer = object.optString("answer", "");
            run.error = object.optString("error", "");
            run.tokens = object.optLong("tokens", 0L);
            return run;
        }
    }

    private final File file;
    private final List<Task> tasks = new ArrayList<>();
    private final List<Run> runs = new ArrayList<>();

    AutomationStore(File file) {
        this.file = file;
        load();
    }

    synchronized List<Task> tasks() {
        List<Task> copy = new ArrayList<>();
        for (Task task : tasks) {
            copy.add(task.copy());
        }
        return copy;
    }

    synchronized Task find(String id) {
        for (Task task : tasks) {
            if (task.id.equals(id)) {
                return task;
            }
        }
        return null;
    }

    synchronized boolean hasEnabled() {
        for (Task task : tasks) {
            if (task.enabled) {
                return true;
            }
        }
        return false;
    }

    synchronized Task create(String name, String prompt, String workspace,
                             String scheduleType, int intervalMinutes, int hour, int minute,
                             int weekday, int dayOfMonth, boolean readOnly) {
        Task task = new Task();
        task.id = "a-" + System.currentTimeMillis() + "-" + (tasks.size() + 1);
        task.name = name == null || name.trim().isEmpty() ? "Unnamed Automation" : name.trim();
        task.prompt = prompt == null ? "" : prompt.trim();
        task.workspace = workspace == null ? "" : workspace.trim();
        task.readOnly = readOnly;
        task.scheduleType = normalizeType(scheduleType);
        task.intervalMinutes = Math.max(5, Math.min(10080, intervalMinutes));
        task.hour = Math.max(0, Math.min(23, hour));
        task.minute = Math.max(0, Math.min(59, minute));
        task.weekday = Math.max(1, Math.min(7, weekday));
        task.dayOfMonth = Math.max(1, Math.min(28, dayOfMonth));
        task.enabled = true;
        task.nextRunAt = nextRun(task, System.currentTimeMillis());
        tasks.add(0, task);
        while (tasks.size() > MAX_TASKS) {
            tasks.remove(tasks.size() - 1);
        }
        save();
        return task;
    }

    synchronized void update(Task incoming) {
        if (incoming == null || incoming.id.isEmpty()) {
            return;
        }
        Task task = find(incoming.id);
        if (task == null) {
            return;
        }
        task.name = incoming.name;
        task.prompt = incoming.prompt;
        task.workspace = incoming.workspace;
        task.enabled = incoming.enabled;
        task.readOnly = incoming.readOnly;
        task.scheduleType = normalizeType(incoming.scheduleType);
        task.intervalMinutes = Math.max(5, Math.min(10080, incoming.intervalMinutes));
        task.hour = Math.max(0, Math.min(23, incoming.hour));
        task.minute = Math.max(0, Math.min(59, incoming.minute));
        task.weekday = Math.max(1, Math.min(7, incoming.weekday));
        task.dayOfMonth = Math.max(1, Math.min(28, incoming.dayOfMonth));
        task.nextRunAt = task.enabled ? nextRun(task, System.currentTimeMillis()) : 0L;
        save();
    }

    synchronized void delete(String id) {
        for (int i = tasks.size() - 1; i >= 0; i--) {
            if (tasks.get(i).id.equals(id)) {
                tasks.remove(i);
            }
        }
        for (int i = runs.size() - 1; i >= 0; i--) {
            if (runs.get(i).taskId.equals(id)) {
                runs.remove(i);
            }
        }
        save();
    }

    synchronized void setEnabled(String id, boolean enabled) {
        Task task = find(id);
        if (task == null) {
            return;
        }
        task.enabled = enabled;
        task.nextRunAt = enabled ? nextRun(task, System.currentTimeMillis()) : 0L;
        save();
    }

    synchronized String markRunStarted(String taskId, String conversationId) {
        Task task = find(taskId);
        if (task == null) {
            return "";
        }
        Run run = new Run();
        run.id = "r-" + System.currentTimeMillis() + "-" + (runs.size() + 1);
        run.taskId = taskId;
        run.conversationId = conversationId == null ? "" : conversationId;
        run.startedAt = System.currentTimeMillis();
        run.outcome = "running";
        runs.add(0, run);
        while (runs.size() > MAX_RUNS) {
            runs.remove(runs.size() - 1);
        }
        task.lastConversationId = run.conversationId;
        task.lastOutcome = "running";
        task.lastError = "";
        save();
        return run.id;
    }

    synchronized void finishRun(String taskId, String runId, String outcome,
                                String answer, String error, long tokens) {
        long now = System.currentTimeMillis();
        Run run = null;
        for (Run candidate : runs) {
            if (candidate.id.equals(runId)) {
                run = candidate;
                break;
            }
        }
        if (run != null) {
            run.finishedAt = now;
            run.outcome = outcome == null ? "unknown" : outcome;
            run.answer = cut(answer, 2000);
            run.error = cut(error, 2000);
            run.tokens = Math.max(0L, tokens);
        }
        Task task = find(taskId);
        if (task != null) {
            task.lastRunAt = now;
            task.lastOutcome = outcome == null ? "unknown" : outcome;
            task.lastError = cut(error, 2000);
            task.runCount++;
            task.nextRunAt = task.enabled ? nextRun(task, now) : 0L;
        }
        save();
    }

    synchronized List<Task> due(long now) {
        List<Task> due = new ArrayList<>();
        for (Task task : tasks) {
            if (task.enabled && task.nextRunAt > 0 && task.nextRunAt <= now) {
                due.add(task);
            }
        }
        Collections.sort(due, new Comparator<Task>() {
            @Override
            public int compare(Task a, Task b) {
                return Long.compare(a.nextRunAt, b.nextRunAt);
            }
        });
        return due;
    }

    /** Drop stale missed runs instead of firing a burst after the app was offline. */
    synchronized void normalizeSchedules(long now) {
        boolean changed = false;
        for (Task task : tasks) {
            if (!task.enabled) {
                if (task.nextRunAt != 0) {
                    task.nextRunAt = 0;
                    changed = true;
                }
                continue;
            }
            if (task.nextRunAt <= 0) {
                task.nextRunAt = nextRun(task, now);
                changed = true;
            } else if (task.nextRunAt < now - 6L * 60L * 60L * 1000L) {
                task.nextRunAt = nextRun(task, now);
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    synchronized List<Run> runsFor(String taskId, int limit) {
        List<Run> result = new ArrayList<>();
        for (Run run : runs) {
            if (run.taskId.equals(taskId)) {
                result.add(run.copy());
                if (result.size() >= Math.max(1, limit)) {
                    break;
                }
            }
        }
        return result;
    }

    static long nextRun(Task task, long now) {
        if (task == null) {
            return 0L;
        }
        if ("interval".equals(task.scheduleType)) {
            return now + Math.max(5, task.intervalMinutes) * 60000L;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.set(Calendar.HOUR_OF_DAY, task.hour);
        calendar.set(Calendar.MINUTE, task.minute);
        if ("weekly".equals(task.scheduleType)) {
            int target = Math.max(1, Math.min(7, task.weekday));
            while (calendar.get(Calendar.DAY_OF_WEEK) != target) {
                calendar.add(Calendar.DAY_OF_YEAR, 1);
            }
            if (calendar.getTimeInMillis() <= now) {
                calendar.add(Calendar.DAY_OF_YEAR, 7);
            }
            return calendar.getTimeInMillis();
        }
        if ("monthly".equals(task.scheduleType)) {
            applyDayOfMonth(calendar, task.dayOfMonth);
            if (calendar.getTimeInMillis() <= now) {
                calendar.add(Calendar.MONTH, 1);
                applyDayOfMonth(calendar, task.dayOfMonth);
            }
            return calendar.getTimeInMillis();
        }
        if (calendar.getTimeInMillis() <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        return calendar.getTimeInMillis();
    }

    private static void applyDayOfMonth(Calendar calendar, int desired) {
        int day = Math.max(1, Math.min(28, desired));
        calendar.set(Calendar.DAY_OF_MONTH,
                Math.min(day, calendar.getActualMaximum(Calendar.DAY_OF_MONTH)));
    }

    private static String normalizeType(String type) {
        if ("interval".equals(type) || "weekly".equals(type) || "monthly".equals(type)) {
            return type;
        }
        return "daily";
    }

    static String formatTime(long at) {
        if (at <= 0) {
            return "Not scheduled";
        }
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(at));
    }

    static String formatRunTime(long at) {
        if (at <= 0) {
            return "";
        }
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(at));
    }

    private void load() {
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            String raw = readText(file);
            JSONObject object = ToolRegistry.parseObject(raw);
            JSONArray taskArray = object.optJSONArray("tasks");
            if (taskArray != null) {
                for (int i = 0; i < taskArray.length(); i++) {
                    JSONObject item = taskArray.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    Task task = Task.fromJson(item);
                    if (!task.id.isEmpty() && !task.name.isEmpty()) {
                        tasks.add(task);
                    }
                }
            }
            JSONArray runArray = object.optJSONArray("runs");
            if (runArray != null) {
                for (int i = 0; i < runArray.length(); i++) {
                    JSONObject item = runArray.optJSONObject(i);
                    if (item != null) {
                        runs.add(Run.fromJson(item));
                    }
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "load failed", error);
        }
    }

    private void save() {
        try {
            JSONObject object = new JSONObject();
            object.put("version", 1);
            JSONArray taskArray = new JSONArray();
            for (Task task : tasks) {
                taskArray.put(task.toJson());
            }
            object.put("tasks", taskArray);
            JSONArray runArray = new JSONArray();
            for (Run run : runs) {
                runArray.put(run.toJson());
            }
            object.put("runs", runArray);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            OutputStream output = new FileOutputStream(temp);
            try {
                output.write(object.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
            } finally {
                output.close();
            }
            if (!temp.renameTo(file)) {
                file.delete();
                if (!temp.renameTo(file)) {
                    throw new java.io.IOException("automations.json rename failed");
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "save failed", error);
        }
    }

    private static String readText(File file) throws Exception {
        InputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[(int) Math.min(1024L * 1024L, Math.max(1024L, file.length()))];
            int read = input.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    private static String cut(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
