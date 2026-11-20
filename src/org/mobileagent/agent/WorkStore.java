package org.mobileagent.agent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Durable phone-side work ledger: tickets, triggers, effects and worker slots.
 *
 * <p>This is the Android adaptation of the Super-agent domain. It is intentionally
 * small: JSON on private storage, one writer, explicit stages and a bounded history.
 */
final class WorkStore {
    static final String RUNNING = "running";
    static final String WAITING = "waiting";
    static final String BLOCKED = "blocked";
    static final String READY = "ready";
    static final String FAILED = "failed";
    static final String STOPPED = "stopped";
    static final String ENDED = "ended";

    static final class Ticket {
        String id = "";
        String conversationId = "";
        String title = "";
        String status = RUNNING;
        String source = "user";
        String scope = "general";
        String runId = "";
        String lastError = "";
        String question = "";
        /** Automatically error-monitored full-on-site; normal task ticket empty. */
        String report = "";
        long createdAt;
        long updatedAt;
        long heartbeatAt;
        int step;
        int maxSteps;
        int workers;
        boolean attention;

        JSONObject toJson() throws Exception {
            JSONObject object = new JSONObject();
            object.put("id", id);
            object.put("conversation", conversationId);
            object.put("title", title);
            object.put("status", status);
            object.put("source", source);
            object.put("scope", scope);
            object.put("run", runId);
            object.put("error", lastError);
            object.put("question", question);
            object.put("report", report);
            object.put("created", createdAt);
            object.put("updated", updatedAt);
            object.put("heartbeat", heartbeatAt);
            object.put("step", step);
            object.put("maxSteps", maxSteps);
            object.put("workers", workers);
            object.put("attention", attention);
            return object;
        }

        static Ticket fromJson(JSONObject object) {
            Ticket ticket = new Ticket();
            ticket.id = object.optString("id");
            ticket.conversationId = object.optString("conversation");
            ticket.title = object.optString("title");
            ticket.status = object.optString("status", RUNNING);
            ticket.source = object.optString("source", "user");
            ticket.scope = object.optString("scope", "general");
            ticket.runId = object.optString("run");
            ticket.lastError = object.optString("error");
            ticket.question = object.optString("question");
            ticket.report = object.optString("report");
            ticket.createdAt = object.optLong("created");
            ticket.updatedAt = object.optLong("updated");
            ticket.heartbeatAt = object.optLong("heartbeat");
            ticket.step = object.optInt("step");
            ticket.maxSteps = object.optInt("maxSteps");
            ticket.workers = object.optInt("workers");
            ticket.attention = object.optBoolean("attention");
            return ticket;
        }

        boolean open() {
            return RUNNING.equals(status) || WAITING.equals(status)
                    || BLOCKED.equals(status) || READY.equals(status);
        }
    }

    static final class Trigger {
        String id = "";
        String source = "user";
        String conversationId = "";
        String text = "";
        String disposition = "recorded";
        long at;

        JSONObject toJson() throws Exception {
            JSONObject object = new JSONObject();
            object.put("id", id);
            object.put("source", source);
            object.put("conversation", conversationId);
            object.put("text", text);
            object.put("disposition", disposition);
            object.put("at", at);
            return object;
        }

        static Trigger fromJson(JSONObject object) {
            Trigger trigger = new Trigger();
            trigger.id = object.optString("id");
            trigger.source = object.optString("source", "user");
            trigger.conversationId = object.optString("conversation");
            trigger.text = object.optString("text");
            trigger.disposition = object.optString("disposition", "recorded");
            trigger.at = object.optLong("at");
            return trigger;
        }
    }

    static final class Effect {
        String id = "";
        String kind = "notice";
        String ticketId = "";
        String message = "";
        String status = "pending";
        long at;
        long dueAt;
        int attempts;

        JSONObject toJson() throws Exception {
            JSONObject object = new JSONObject();
            object.put("id", id);
            object.put("kind", kind);
            object.put("ticket", ticketId);
            object.put("message", message);
            object.put("status", status);
            object.put("at", at);
            object.put("due", dueAt);
            object.put("attempts", attempts);
            return object;
        }

        static Effect fromJson(JSONObject object) {
            Effect effect = new Effect();
            effect.id = object.optString("id");
            effect.kind = object.optString("kind", "notice");
            effect.ticketId = object.optString("ticket");
            effect.message = object.optString("message");
            effect.status = object.optString("status", "pending");
            effect.at = object.optLong("at");
            effect.dueAt = object.optLong("due");
            effect.attempts = object.optInt("attempts");
            return effect;
        }
    }

    final File file;
    final List<Ticket> tickets = new ArrayList<>();
    final List<Trigger> triggers = new ArrayList<>();
    final List<Effect> effects = new ArrayList<>();
    boolean autoAccept;
    int maxWorkers = 2;
    long lastSaveAt;

    WorkStore(Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), "work"));
    }

    /** Filesystem boundary for JVM tests. */
    WorkStore(File dir) {
        if (!dir.isDirectory()) dir.mkdirs();
        file = new File(dir, "work.json");
        load();
    }

    synchronized Ticket beginTicket(String conversationId, String title, String input, String runId) {
        long now = System.currentTimeMillis();
        Ticket ticket = null;
        for (Ticket candidate : tickets) {
            if (candidate.open() && conversationId != null
                    && conversationId.equals(candidate.conversationId)) {
                ticket = candidate;
                break;
            }
        }
        if (ticket == null) {
            ticket = new Ticket();
            ticket.id = "t-" + now + "-" + (tickets.size() + 1);
            ticket.createdAt = now;
            ticket.source = "user";
            tickets.add(0, ticket);
            while (tickets.size() > 120) tickets.remove(tickets.size() - 1);
        }
        ticket.conversationId = conversationId == null ? "" : conversationId;
        ticket.title = cleanTitle(title, input);
        ticket.status = RUNNING;
        String inferred = inferScope(input);
        if ("general".equals(inferred) && ticket.scope != null
                && !ticket.scope.isEmpty()) {
            inferred = ticket.scope;
        }
        ticket.scope = inferred;
        ticket.runId = runId == null ? "" : runId;
        ticket.lastError = "";
        ticket.question = "";
        ticket.attention = false;
        ticket.updatedAt = now;
        ticket.heartbeatAt = now;
        ticket.step = 0;
        ticket.maxSteps = 0;
        recordTriggerLocked("user", conversationId, input, ticket.id);
        saveLocked();
        return ticket;
    }

    synchronized void heartbeat(String ticketId, String runId, int step, int maxSteps) {
        Ticket ticket = find(ticketId);
        if (ticket == null) return;
        long now = System.currentTimeMillis();
        ticket.heartbeatAt = now;
        ticket.updatedAt = now;
        if (runId != null && !runId.isEmpty()) ticket.runId = runId;
        ticket.step = Math.max(0, step);
        if (maxSteps > 0) ticket.maxSteps = maxSteps;
        saveThrottled();
    }

    synchronized void block(String ticketId, String status, String message) {
        Ticket ticket = find(ticketId);
        if (ticket == null) return;
        long now = System.currentTimeMillis();
        ticket.status = FLAGGED(status) ? status : BLOCKED;
        ticket.attention = true;
        ticket.lastError = shortText(message);
        ticket.updatedAt = now;
        ticket.heartbeatAt = now;
        effectLocked("attention", ticket.id, message);
        saveLocked();
    }

    /**
     * (a) Automatically drop a separate instrument for red-word errors made during the dialogue, without covering the current task ticket;
     * Users can view the site and open dialogue processes in the mission centre.
     */
    synchronized Ticket reportError(String conversationId, String title,                                    String summary, String report) {
        long now = System.currentTimeMillis();
        String cleanSummary = shortText(summary);
        for (Ticket existing : tickets) {
            if ("error".equals(existing.source)
                    && conversationId != null
                    && conversationId.equals(existing.conversationId)
                    && cleanSummary.equals(existing.lastError)
                    && now - existing.updatedAt < 60_000L) {
                existing.updatedAt = now;
                existing.heartbeatAt = now;
                existing.question = report == null ? "" : report;
                existing.report = report == null ? "" : report;
                effectLocked("attention", existing.id, cleanSummary);
                saveLocked();
                return existing;
            }
        }
        Ticket ticket = new Ticket();
        ticket.id = "err-" + now + "-" + (tickets.size() + 1);
        ticket.conversationId = conversationId == null ? "" : conversationId;
        ticket.title = cleanTitle(title, summary);
        ticket.status = BLOCKED;
        ticket.source = "error";
        ticket.scope = "error";
        ticket.lastError = cleanSummary;
        ticket.question = report == null ? "" : report;
        ticket.report = report == null ? "" : report;
        ticket.attention = true;
        ticket.createdAt = now;
        ticket.updatedAt = now;
        ticket.heartbeatAt = now;
        tickets.add(0, ticket);
        while (tickets.size() > 120) tickets.remove(tickets.size() - 1);
        effectLocked("attention", ticket.id, cleanSummary);
        saveLocked();
        return ticket;
    }

    synchronized boolean hasErrorReport(String conversationId, String summary) {
        String clean = shortText(summary);
        for (Ticket ticket : tickets) {
            if ("error".equals(ticket.source)
                    && conversationId != null
                    && conversationId.equals(ticket.conversationId)
                    && clean.equals(ticket.lastError)) {
                return true;
            }
        }
        return false;
    }

    synchronized void end(String ticketId, String status, String message) {
        Ticket ticket = find(ticketId);
        if (ticket == null) return;
        long now = System.currentTimeMillis();
        boolean archive = autoAccept && READY.equals(status);
        ticket.status = archive ? ENDED : status == null || status.isEmpty() ? ENDED : status;
        ticket.attention = !archive && (BLOCKED.equals(ticket.status)
                || FAILED.equals(ticket.status) || READY.equals(ticket.status));
        if (message != null && !message.isEmpty()) ticket.lastError = shortText(message);
        ticket.updatedAt = now;
        ticket.heartbeatAt = now;
        saveLocked();
    }

    synchronized void clearAttention(String ticketId) {
        Ticket ticket = find(ticketId);
        if (ticket == null) return;
        ticket.attention = false;
        if (BLOCKED.equals(ticket.status) || FAILED.equals(ticket.status)
                || READY.equals(ticket.status)) {
            ticket.status = ENDED;
        }
        ticket.updatedAt = System.currentTimeMillis();
        saveLocked();
    }

    synchronized void trigger(String source, String conversationId, String text) {
        recordTriggerLocked(source, conversationId, text, "");
        saveLocked();
    }

    synchronized void effect(String kind, String ticketId, String message) {
        effectLocked(kind, ticketId, message);
        saveLocked();
    }

    synchronized void effectSent(String effectId) {
        for (Effect effect : effects) {
            if (effect.id.equals(effectId)) {
                effect.status = "sent";
                effect.attempts++;
                break;
            }
        }
        saveLocked();
    }

    synchronized void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Ticket ticket : tickets) {
            if (!RUNNING.equals(ticket.status)) continue;
            if (ticket.heartbeatAt > 0 && now - ticket.heartbeatAt > 180_000L) {
                ticket.status = BLOCKED;
                ticket.attention = true;
                ticket.lastError = "More than 3 No progress was made in the minutes and the task may be stuck on the tool or the network.";
                ticket.updatedAt = now;
                effectLocked("attention", ticket.id, ticket.lastError);
                changed = true;
            }
        }
        if (changed) saveLocked();
    }

    synchronized Ticket find(String ticketId) {
        if (ticketId == null) return null;
        for (Ticket ticket : tickets) {
            if (ticketId.equals(ticket.id)) return ticket;
        }
        return null;
    }

    synchronized Ticket latestOpen() {
        for (Ticket ticket : tickets) {
            if (ticket.open()) return ticket;
        }
        return null;
    }

    synchronized List<Ticket> needsAttention() {
        List<Ticket> result = new ArrayList<>();
        for (Ticket ticket : tickets) {
            if (ticket.attention) result.add(ticket);
        }
        sortNewest(result);
        return result;
    }

    synchronized List<Ticket> byStatus(String status) {
        List<Ticket> result = new ArrayList<>();
        for (Ticket ticket : tickets) {
            if (status == null || status.equals(ticket.status)) result.add(ticket);
        }
        sortNewest(result);
        return result;
    }

    synchronized String networkScope(String ticketId) {
        Ticket ticket = find(ticketId);
        return ticket == null ? "" : ticket.scope;
    }

    synchronized void setAutoAccept(boolean value) {
        autoAccept = value;
        saveLocked();
    }

    synchronized void setMaxWorkers(int value) {
        maxWorkers = Math.max(1, Math.min(4, value));
        saveLocked();
    }

    synchronized Trigger recordWorkerEvent(String ticketId, String message) {
        Trigger trigger = new Trigger();
        trigger.id = "tr-" + System.currentTimeMillis();
        trigger.source = "worker";
        trigger.text = shortText(message);
        trigger.disposition = ticketId == null ? "recorded" : ticketId;
        trigger.at = System.currentTimeMillis();
        triggers.add(0, trigger);
        trimTriggers();
        saveLocked();
        return trigger;
    }

    synchronized String startWorker(String ticketId, String task) {
        Ticket ticket = find(ticketId);
        if (ticket == null) {
            int active = 0;
            for (Ticket candidate : tickets) if (candidate.open()) active++;
            if (active >= maxWorkers) return null;
            recordWorkerEvent("", task);
            return "w-" + System.currentTimeMillis() + "-" + active;
        }
        if (ticket.workers >= maxWorkers) return null;
        ticket.workers++;
        ticket.updatedAt = System.currentTimeMillis();
        recordWorkerEvent(ticketId, "worker started: " + task);
        saveLocked();
        return "w-" + System.currentTimeMillis() + "-" + ticket.workers;
    }

    synchronized void endWorker(String ticketId) {
        Ticket ticket = find(ticketId);
        if (ticket == null) return;
        ticket.workers = Math.max(0, ticket.workers - 1);
        ticket.updatedAt = System.currentTimeMillis();
        saveLocked();
    }

    private void recordTriggerLocked(String source, String conversationId, String text, String disposition) {
        Trigger trigger = new Trigger();
        trigger.id = "tr-" + System.currentTimeMillis() + "-" + (triggers.size() + 1);
        trigger.source = source == null ? "system" : source;
        trigger.conversationId = conversationId == null ? "" : conversationId;
        trigger.text = shortText(text);
        trigger.disposition = disposition == null ? "recorded" : disposition;
        trigger.at = System.currentTimeMillis();
        triggers.add(0, trigger);
        trimTriggers();
    }

    private void trimTriggers() {
        while (triggers.size() > 200) triggers.remove(triggers.size() - 1);
    }

    private void effectLocked(String kind, String ticketId, String message) {
        Effect effect = new Effect();
        effect.id = "ef-" + System.currentTimeMillis() + "-" + (effects.size() + 1);
        effect.kind = kind == null ? "notice" : kind;
        effect.ticketId = ticketId == null ? "" : ticketId;
        effect.message = shortText(message);
        effect.at = System.currentTimeMillis();
        effect.dueAt = effect.at;
        effects.add(0, effect);
        while (effects.size() > 100) effects.remove(effects.size() - 1);
    }

    private static boolean FLAGGED(String status) {
        return BLOCKED.equals(status) || FAILED.equals(status) || READY.equals(status)
                || WAITING.equals(status) || ENDED.equals(status) || STOPPED.equals(status);
    }

    static String inferScope(String input) {
        String text = input == null ? "" : input.toLowerCase(Locale.ROOT);
        if (containsAny(text, "Research", "Research", "research", "github", "git", "Web", "Document",
                "Link", "url", "http", "superagent", "super-agent", "harness", "deepseek",
                "Papers", "Official", "repo", "repository", "Search", "Search it.", "Check it out.",
                "Website", "Page", "Browser", "web", "page", "browser", "search", "fetch")) return "research";
        if (containsAny(text, "Build", "Install", "Pack up.", "Compile", "Signature", "build", "apk", "install")) return "build";
        if (containsAny(text, "Cell phone.", "Interface", "Screenshot", "Click", "Apply", "device", "screen", "tap")) return "device";
        return "general";
    }

    static boolean isResearchScope(String scope) {
        return "research".equals(scope);
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static String cleanTitle(String title, String input) {
        String value = title == null ? "" : title.trim();
        if (value.isEmpty() || Conversation.DEFAULT_TITLE.equals(value)) {
            value = input == null ? "" : input.trim().replace('\n', ' ');
            if (value.length() > 48) value = value.substring(0, 48) + "...";
        }
        return value;
    }

    private static String shortText(String text) {
        if (text == null) return "";
        String value = text.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() > 500 ? value.substring(0, 500) + "..." : value;
    }

    private static void sortNewest(List<Ticket> list) {
        Collections.sort(list, new Comparator<Ticket>() {
            @Override
            public int compare(Ticket left, Ticket right) {
                return Long.compare(right.updatedAt, left.updatedAt);
            }
        });
    }

    private void saveThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastSaveAt < 1500L) return;
        saveLocked();
    }

    synchronized void save() {
        saveLocked();
    }

    private void saveLocked() {
        try {
            JSONObject root = new JSONObject();
            JSONArray ticketArray = new JSONArray();
            for (Ticket ticket : tickets) ticketArray.put(ticket.toJson());
            root.put("tickets", ticketArray);
            JSONArray triggerArray = new JSONArray();
            for (Trigger trigger : triggers) triggerArray.put(trigger.toJson());
            root.put("triggers", triggerArray);
            JSONArray effectArray = new JSONArray();
            for (Effect effect : effects) effectArray.put(effect.toJson());
            root.put("effects", effectArray);
            root.put("autoAccept", autoAccept);
            root.put("maxWorkers", maxWorkers);
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            File temp = new File(file.getParentFile(), "work.json.tmp");
            FileOutputStream output = new FileOutputStream(temp, false);
            try { output.write(bytes); } finally { output.close(); }
            if (!temp.renameTo(file)) {
                FileOutputStream direct = new FileOutputStream(file, false);
                try { direct.write(bytes); } finally { direct.close(); }
                temp.delete();
            }
            lastSaveAt = System.currentTimeMillis();
        } catch (Exception ignored) {
        }
    }

    private void load() {
        if (!file.isFile()) return;
        try {
            FileInputStream input = new FileInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            input.close();
            JSONObject root = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            JSONArray ticketArray = root.optJSONArray("tickets");
            if (ticketArray != null) {
                for (int i = 0; i < ticketArray.length(); i++) {
                    JSONObject object = ticketArray.optJSONObject(i);
                    if (object != null) tickets.add(Ticket.fromJson(object));
                }
            }
            JSONArray triggerArray = root.optJSONArray("triggers");
            if (triggerArray != null) {
                for (int i = 0; i < triggerArray.length(); i++) {
                    JSONObject object = triggerArray.optJSONObject(i);
                    if (object != null) triggers.add(Trigger.fromJson(object));
                }
            }
            JSONArray effectArray = root.optJSONArray("effects");
            if (effectArray != null) {
                for (int i = 0; i < effectArray.length(); i++) {
                    JSONObject object = effectArray.optJSONObject(i);
                    if (object != null) effects.add(Effect.fromJson(object));
                }
            }
            autoAccept = root.optBoolean("autoAccept", false);
            maxWorkers = Math.max(1, Math.min(4, root.optInt("maxWorkers", 2)));
        } catch (Exception ignored) {
        }
    }
}
