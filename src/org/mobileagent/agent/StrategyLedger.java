package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Strategy-level memory: what was tried, what worked, and what must not be repeated. */
final class StrategyLedger {
    static final class Attempt {
        final String strategy;
        final String tool;
        final String actionKey;
        final boolean ok;
        final String error;
        final boolean infoGain;
        final long at;

        Attempt(String strategy, String tool, String actionKey, boolean ok,
                String error, boolean infoGain) {
            this.strategy = strategy;
            this.tool = tool;
            this.actionKey = actionKey;
            this.ok = ok;
            this.error = error == null ? "" : error;
            this.infoGain = infoGain;
            this.at = System.currentTimeMillis();
        }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("strategy", strategy).put("tool", tool)
                    .put("action", actionKey).put("ok", ok).put("error", error)
                    .put("info", infoGain).put("at", at);
        }

        static Attempt fromJson(JSONObject object) {
            return new Attempt(object.optString("strategy"),
                    object.optString("tool"), object.optString("action"),
                    object.optBoolean("ok"), object.optString("error"),
                    object.optBoolean("info"));
        }
    }

    private static final class Stat {
        int attempts;
        int successes;
        int gains;
        int fails;
        int consecutiveFails;
        String lastError = "";
        long lastAt;
    }

    private final Map<String, Stat> stats = new LinkedHashMap<>();
    private final List<Attempt> recent = new ArrayList<>();
    private final Set<String> blocked = new HashSet<>();
    private final Set<String> blockedStrategies = new HashSet<>();
    private final Set<String> successfulActions = new HashSet<>();
    private final Map<String, Double> priors = new HashMap<>();

    StrategyLedger(JSONObject saved, Map<String, Double> priors) {
        if (saved != null) {
            load(saved);
        }
        if (priors != null) {
            this.priors.putAll(priors);
        }
    }

    synchronized void record(String strategy, String tool, String actionKey,
                             boolean ok, String error, boolean infoGain) {
        if (strategy == null || strategy.isEmpty()) {
            strategy = tool == null ? "unknown" : tool;
        }
        Stat stat = stats.get(strategy);
        if (stat == null) {
            stat = new Stat();
            stats.put(strategy, stat);
        }
        stat.attempts++;
        stat.lastAt = System.currentTimeMillis();
        if (infoGain) {
            stat.gains++;
        }
        if (ok) {
            stat.successes++;
            stat.consecutiveFails = 0;
            stat.lastError = "";
            if (actionKey != null && !actionKey.isEmpty()) {
                successfulActions.add(actionKey);
            }
        } else {
            stat.fails++;
            stat.consecutiveFails++;
            stat.lastError = error == null ? "" : error;
        }
        recent.add(new Attempt(strategy, tool == null ? "" : tool,
                actionKey == null ? "" : actionKey, ok, error, infoGain));
        while (recent.size() > 14) {
            recent.remove(0);
        }
    }

    synchronized void record(String strategy, String actionKey, boolean ok,
                             String error, boolean infoGain) {
        record(strategy, strategy, actionKey, ok, error, infoGain);
    }

    /** How many of the last N attempts produced new information. */
    synchronized int recentGains(int count) {
        int n = Math.max(0, Math.min(count, recent.size()));
        int gains = 0;
        for (int i = recent.size() - n; i < recent.size(); i++) {
            if (recent.get(i).infoGain) gains++;
        }
        return gains;
    }

    static boolean isVisualCapture(String actionKey) {
        return actionKey != null && (actionKey.startsWith("browser_screenshot:")
                || actionKey.startsWith("screenshot:")
                || actionKey.startsWith("view_image:"));
    }

    synchronized void block(String actionKey) {
        if (actionKey != null && !actionKey.isEmpty()) {
            blocked.add(actionKey);
        }
    }

    synchronized boolean isBlocked(String actionKey) {
        return actionKey != null && blocked.contains(actionKey);
    }

    synchronized void blockStrategy(String strategy) {
        if (strategy != null && !strategy.isEmpty()) {
            blockedStrategies.add(strategy);
        }
    }

    synchronized boolean isStrategyBlocked(String strategy) {
        return strategy != null && blockedStrategies.contains(strategy);
    }

    synchronized void resetFailures() {
        for (Stat stat : stats.values()) {
            stat.consecutiveFails = 0;
        }
        blocked.clear();
        blockedStrategies.clear();
        successfulActions.clear();
    }

    synchronized boolean alreadySucceeded(String actionKey) {
        return actionKey != null && successfulActions.contains(actionKey);
    }

    synchronized int consecutiveFails(String strategy) {
        Stat stat = stats.get(strategy);
        return stat == null ? 0 : stat.consecutiveFails;
    }

    synchronized List<String> alternatives(boolean webTask, CapabilityRegistry.Snapshot caps) {
        List<String> candidates = new ArrayList<>();
        if (webTask) {
            if (caps != null && caps.browserRead) {
                candidates.add("browser_read");
            }
            if (caps != null && caps.webview) {
                candidates.add("browser_screenshot");
                candidates.add("browser_eval");
            }
            candidates.add("web_search");
            candidates.add("static_fetch");
            candidates.add("webview_render");
            candidates.add("subagent");
        } else {
            candidates.add("web_search");
            candidates.add("subagent");
            candidates.add("read_file");
            candidates.add("termux_run");
            candidates.add("web_fetch");
        }
        Collections.sort(candidates, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                double pa = priors.containsKey(a) ? priors.get(a) : 0.5;
                double pb = priors.containsKey(b) ? priors.get(b) : 0.5;
                if (Math.abs(pa - pb) > 0.0001) {
                    return Double.compare(pb, pa);
                }
                return Integer.compare(consecutiveFails(a), consecutiveFails(b));
            }
        });
        List<String> result = new ArrayList<>();
        for (String candidate : candidates) {
            if (isBlocked(candidate) || isStrategyBlocked(candidate)
                    || consecutiveFails(candidate) >= 2) {
                continue;
            }
            result.add(candidate);
            if (result.size() >= 3) {
                break;
            }
        }
        if (result.isEmpty()) {
            result.addAll(candidates);
            if (result.size() > 2) {
                result = new ArrayList<>(result.subList(0, 2));
            }
        }
        return result;
    }

    synchronized String render() {
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (int i = recent.size() - 1; i >= 0 && shown < 4; i--, shown++) {
            Attempt attempt = recent.get(i);
            out.append(attempt.strategy).append(attempt.infoGain ? "✓"
                    : attempt.ok ? "·" : "✗");
            if (!attempt.ok && !attempt.error.isEmpty()) {
                out.append("(").append(trim(attempt.error, 46)).append(")");
            }
            if (shown < 3 && i > 0) {
                out.append("; ");
            }
        }
        if (out.length() == 0) {
            return "Not yet.";
        }
        return trim(out.toString(), 360);
    }

    synchronized String summary() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Stat> entry : stats.entrySet()) {
            if (out.length() > 0) {
                out.append(",");
            }
            out.append(entry.getKey()).append("=")
                    .append(entry.getValue().gains).append("/")
                    .append(entry.getValue().attempts);
        }
        return trim(out.toString(), 300);
    }

    synchronized String successStrategies() {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, Stat> entry : stats.entrySet()) {
            if (entry.getValue().gains > 0) {
                values.add(entry.getKey());
            }
        }
        return join(values, 160);
    }

    synchronized String failedStrategies() {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, Stat> entry : stats.entrySet()) {
            if (entry.getValue().attempts > 0 && entry.getValue().gains == 0) {
                values.add(entry.getKey());
            }
        }
        return join(values, 160);
    }

    synchronized JSONObject toJson() {
        try {
            JSONObject object = new JSONObject();
            JSONObject statObject = new JSONObject();
            for (Map.Entry<String, Stat> entry : stats.entrySet()) {
                Stat stat = entry.getValue();
                statObject.put(entry.getKey(), new JSONObject()
                        .put("attempts", stat.attempts)
                        .put("successes", stat.successes)
                        .put("gains", stat.gains)
                        .put("fails", stat.fails)
                        .put("consecutive", stat.consecutiveFails)
                        .put("error", stat.lastError)
                        .put("at", stat.lastAt));
            }
            JSONArray attempts = new JSONArray();
            for (Attempt attempt : recent) {
                attempts.put(attempt.toJson());
            }
            JSONArray blockArray = new JSONArray();
            for (String value : blocked) {
                blockArray.put(value);
            }
            object.put("stats", statObject);
            object.put("recent", attempts);
            object.put("blocked", blockArray);
            return object;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void load(JSONObject saved) {
        try {
            JSONObject statObject = saved.optJSONObject("stats");
            if (statObject != null) {
                java.util.Iterator<String> keys = statObject.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject value = statObject.optJSONObject(key);
                    if (value == null) {
                        continue;
                    }
                    Stat stat = new Stat();
                    stat.attempts = value.optInt("attempts");
                    stat.successes = value.optInt("successes");
                    stat.gains = value.optInt("gains");
                    stat.fails = value.optInt("fails");
                    stat.consecutiveFails = value.optInt("consecutive");
                    stat.lastError = value.optString("error");
                    stat.lastAt = value.optLong("at");
                    stats.put(key, stat);
                }
            }
            JSONArray attempts = saved.optJSONArray("recent");
            if (attempts != null) {
                for (int i = 0; i < attempts.length(); i++) {
                    JSONObject item = attempts.optJSONObject(i);
                    if (item != null) {
                        Attempt attempt = Attempt.fromJson(item);
                        recent.add(attempt);
                        if (attempt.ok && !attempt.actionKey.isEmpty()) {
                            successfulActions.add(attempt.actionKey);
                        }
                    }
                }
            }
            JSONArray blockArray = saved.optJSONArray("blocked");
            if (blockArray != null) {
                for (int i = 0; i < blockArray.length(); i++) {
                    String value = blockArray.optString(i);
                    if (value != null && !value.isEmpty()) {
                        blocked.add(value);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    static String strategyOf(String tool, String arguments) {
        if (tool == null) {
            return "unknown";
        }
        if ("termux_run".equals(tool)) {
            String command = optString(arguments, "command").toLowerCase(Locale.ROOT);
            if (command.contains("curl ") || command.contains("wget ")) {
                return "termux_web";
            }
            if (command.contains("ls ") || command.contains("find ")
                    || command.contains("cat ") || command.contains("head ")
                    || command.contains("tail ")) {
                return "termux_probe";
            }
            return "termux_run";
        }
        if ("web_fetch".equals(tool) || "web_search".equals(tool)
                || "read_file".equals(tool) || "list_files".equals(tool)
                || "spawn_subagent".equals(tool) || "build_apk".equals(tool)
                || "install_apk".equals(tool) || "web_browser_read".equals(tool)) {
            return tool;
        }
        if ("write_file".equals(tool) || "edit_file".equals(tool)
                || "apply_patch".equals(tool) || "move_file".equals(tool)
                || "delete_file".equals(tool)) {
            return "edit";
        }
        if (tool.startsWith("ui_") || "open_app".equals(tool)
                || "list_apps".equals(tool) || "screenshot".equals(tool)
                || "system_action".equals(tool) || "clipboard_read".equals(tool)
                || "clipboard_write".equals(tool)) {
            return "device";
        }
        return tool;
    }

    /** Observation tools: the same call may see new images every time/New interface, not heavy by parameter. */
    static boolean isObservationTool(String tool) {
        return "screenshot".equals(tool) || "ui_dump".equals(tool)
                || "clipboard_read".equals(tool);
    }

    /**
     * Observation tools are used to weigh results with 'result fingerprints': only images/The interface is really not changed to be repeated.
     * The government has not yet made a decision on the issue, but it has not yet made a decision on the issue.
     */
    static String actionKey(String tool, String arguments, String result) {
        String base = actionKey(tool, arguments);
        if (!isObservationTool(tool) || result == null) {
            return base;
        }
        return base + "#" + hash(result);
    }

    static String actionKey(String tool, String arguments) {
        if (tool == null) {
            return "unknown";
        }
        String strategy = strategyOf(tool, arguments);
        if ("termux_run".equals(tool)) {
            String command = optString(arguments, "command").toLowerCase(Locale.ROOT);
            return strategy + ":" + firstVerb(command);
        }
        if ("read_file".equals(tool) || "list_files".equals(tool)
                || "build_apk".equals(tool) || "install_apk".equals(tool)) {
            String path = optString(arguments, "path");
            if (path.isEmpty()) {
                path = optString(arguments, "project");
            }
            return strategy + ":" + path;
        }
        if ("web_fetch".equals(tool)) {
            return strategy + ":" + hash(optString(arguments, "url"));
        }
        if ("web_search".equals(tool)) {
            return strategy + ":" + hash(optString(arguments, "query"));
        }
        return strategy + ":" + hash(arguments);
    }

    private static String firstVerb(String command) {
        String[] parts = command.trim().split("\\s+");
        for (String part : parts) {
            String value = part.replaceAll("[^a-z0-9_./-]", "");
            if (!value.isEmpty() && !"cd".equals(value) && !"export".equals(value)
                    && !"&&".equals(value) && !";".equals(value)) {
                int slash = value.lastIndexOf('/');
                return slash >= 0 ? value.substring(slash + 1) : value;
            }
        }
        return "run";
    }

    private static String optString(String arguments, String key) {
        try {
            return new JSONObject(arguments == null ? "{}" : arguments).optString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String hash(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.isEmpty() ? "empty" : ToolRegistry.sha256(clean).substring(0, 16);
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static String join(List<String> values, int max) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) {
                out.append(",");
            }
            out.append(value);
        }
        return trim(out.toString(), max);
    }
}
