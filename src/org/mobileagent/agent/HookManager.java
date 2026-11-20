package org.mobileagent.agent;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * User-configurable lifecycle hooks. The config lives in the workspace
 * (.agent/hooks.json) and is gated by the global AppPrefs switch, so a
 * prompt-injected workspace edit alone cannot enable shell execution.
 */
final class HookManager {
    static final String CONFIG_PATH = ".agent/hooks.json";
    private static final String TAG = "AgentHooks";

    static final class Hook {
        final String command;
        final long timeoutMs;
        final Set<String> tools;

        Hook(String command, long timeoutMs, Set<String> tools) {
            this.command = command;
            this.timeoutMs = timeoutMs;
            this.tools = tools == null ? Collections.<String>emptySet() : tools;
        }

        boolean matches(String tool) {
            return tools.isEmpty() || tools.contains(tool);
        }
    }

    static final class PreResult {
        final boolean deny;
        final String reason;
        final JSONObject args;

        private PreResult(boolean deny, String reason, JSONObject args) {
            this.deny = deny;
            this.reason = reason == null ? "" : reason;
            this.args = args;
        }

        static PreResult allow(JSONObject args) {
            return new PreResult(false, "", args);
        }

        static PreResult deny(String reason) {
            return new PreResult(true, reason, null);
        }
    }

    final String error;
    private final Context context;
    private final Workspace workspace;
    private final List<Hook> preHooks = new ArrayList<>();
    private final List<Hook> postHooks = new ArrayList<>();
    private final List<Hook> stopHooks = new ArrayList<>();

    private HookManager(Context context, Workspace workspace, String error) {
        this.context = context;
        this.workspace = workspace;
        this.error = error;
    }

    static HookManager load(Context context, Workspace workspace, AppPrefs prefs) {
        if (prefs == null || !prefs.allowHooks()) {
            return null;
        }
        File file;
        try {
            file = workspace.resolve(CONFIG_PATH);
        } catch (Exception e) {
            return new HookManager(context, workspace, "Configure path invalid:" + message(e));
        }
        if (!file.isFile()) {
            return new HookManager(context, workspace, "Not found " + CONFIG_PATH);
        }
        String raw;
        try {
            raw = readText(file);
        } catch (Exception e) {
            return new HookManager(context, workspace, "Could not close temporary folder: %s" + message(e));
        }
        JSONObject json;
        try {
            json = ToolRegistry.parseObject(raw);
        } catch (Exception e) {
            return new HookManager(context, workspace, "JSON Could not close temporary folder: %s" + message(e));
        }
        if (!json.optBoolean("enabled", false)) {
            return new HookManager(context, workspace, "Configure Not Enabled (enabled=false)");
        }
        long defaultTimeout = clampSeconds(json.optLong("timeout_s", 5L)) * 1000L;
        HookManager manager = new HookManager(context, workspace, null);
        String invalid = manager.parse("pre_tool_use", json.optJSONArray("pre_tool_use"), defaultTimeout);
        if (invalid == null) {
            invalid = manager.parse("post_tool_use", json.optJSONArray("post_tool_use"), defaultTimeout);
        }
        if (invalid == null) {
            invalid = manager.parse("stop", json.optJSONArray("stop"), defaultTimeout);
        }
        if (invalid != null) {
            manager.preHooks.clear();
            manager.postHooks.clear();
            manager.stopHooks.clear();
            return new HookManager(context, workspace, invalid);
        }
        return manager;
    }

    private String parse(String event, JSONArray items, long defaultTimeout) {
        if (items == null) {
            return null;
        }
        for (int i = 0; i < items.length(); i++) {
            Object item = items.opt(i);
            String command = null;
            long timeout = defaultTimeout;
            Set<String> tools = new HashSet<>();
            if (item instanceof String) {
                command = ((String) item).trim();
            } else if (item instanceof JSONObject) {
                JSONObject object = (JSONObject) item;
                command = object.optString("command", "").trim();
                timeout = clampSeconds(object.optLong("timeout_s", defaultTimeout / 1000L)) * 1000L;
                JSONArray matcher = object.optJSONArray("tools");
                if (matcher != null) {
                    for (int t = 0; t < matcher.length(); t++) {
                        String tool = matcher.optString(t, "").trim();
                        if (!tool.isEmpty()) {
                            tools.add(tool);
                        }
                    }
                }
            }
            if (command == null || command.isEmpty()) {
                return "hooks.json It's... " + event + "[" + i + "] Missing command";
            }
            Hook hook = new Hook(command, timeout, tools);
            if ("pre_tool_use".equals(event)) {
                preHooks.add(hook);
            } else if ("post_tool_use".equals(event)) {
                postHooks.add(hook);
            } else {
                stopHooks.add(hook);
            }
        }
        return null;
    }

    PreResult beforeTool(String tool, JSONObject args, String goal, int step,
                         String runId, String conversationId) {
        JSONObject current;
        try {
            current = args == null ? new JSONObject()
                    : ToolRegistry.parseObject(ToolRegistry.canonical(args));
        } catch (Exception e) {
            return PreResult.deny("hook Could not read tool parameters:" + message(e));
        }
        for (Hook hook : preHooks) {
            if (!hook.matches(tool)) {
                continue;
            }
            JSONObject input = baseInput("pre_tool_use", tool, current, goal, step,
                    runId, conversationId);
            HookRunner.Result result = HookRunner.run(context, workspace,
                    hook.command, input.toString(), hook.timeoutMs);
            if (result.timedOut) {
                return PreResult.deny("Hook Timeout (Perhaps)" + (hook.timeoutMs / 1000L) + " sec)");
            }
            if (result.exitCode != 0) {
                return PreResult.deny("Hook Exit Code " + result.exitCode + ": "
                        + firstLine(result.stderr.isEmpty() ? result.stdout : result.stderr));
            }
            String out = result.stdout.trim();
            if (out.isEmpty()) {
                continue;
            }
            JSONObject response = null;
            try {
                response = ToolRegistry.parseObject(out);
            } catch (Exception ignored) {
            }
            if (response == null) {
                if (out.startsWith("{")) {
                    return PreResult.deny("Hook Output is not legal JSON");
                }
                continue;
            }
            String decision = response.optString("decision", "").trim()
                    .toLowerCase(Locale.ROOT);
            if ("deny".equals(decision) || response.optBoolean("deny", false)) {
                return PreResult.deny(response.optString("reason", "Hook Reject this tool call"));
            }
            if (!decision.isEmpty() && !"allow".equals(decision) && !"modify".equals(decision)) {
                return PreResult.deny("Unknown Hook decision: " + decision);
            }
            JSONObject replacement = response.optJSONObject("args");
            if (replacement != null) {
                try {
                    current = ToolRegistry.parseObject(ToolRegistry.canonical(replacement));
                } catch (Exception e) {
                    return PreResult.deny("Hook Returned args Can not get message: %s %s" + message(e));
                }
            }
        }
        return PreResult.allow(current);
    }

    void afterTool(String tool, JSONObject args, String resultText, boolean ok, String error,
                   String goal, int step, String runId, String conversationId) {
        if (postHooks.isEmpty()) {
            return;
        }
        for (Hook hook : postHooks) {
            if (!hook.matches(tool)) {
                continue;
            }
            JSONObject input = baseInput("post_tool_use", tool, args, goal, step,
                    runId, conversationId);
            try {
                input.put("ok", ok);
                input.put("result", cut(resultText, 8000));
                input.put("error", error == null ? "" : cut(error, 1000));
            } catch (Exception ignored) {
            }
            HookRunner.Result result = HookRunner.run(context, workspace,
                    hook.command, input.toString(), hook.timeoutMs);
            if (result.timedOut || result.exitCode != 0) {
                Log.w(TAG, "post hook failed: " + hook.command + " exit="
                        + result.exitCode + " timeout=" + result.timedOut
                        + " err=" + firstLine(result.stderr));
            }
        }
    }

    void stopAsync(String outcome, int rounds, long tokens, String lastAnswer, String error,
                   String goal, String runId, String conversationId) {
        if (stopHooks.isEmpty()) {
            return;
        }
        final JSONObject input = new JSONObject();
        try {
            input.put("event", "stop");
            input.put("outcome", outcome == null ? "unknown" : outcome);
            input.put("rounds", rounds);
            input.put("tokens", tokens);
            input.put("last_answer", cut(lastAnswer, 4000));
            input.put("error", error == null ? "" : cut(error, 2000));
            input.put("goal", goal == null ? "" : cut(goal, 1000));
            input.put("run_id", runId == null ? "" : runId);
            input.put("conversation", conversationId == null ? "" : conversationId);
            input.put("workspace", workspace.root().getAbsolutePath());
        } catch (Exception ignored) {
        }
        final List<Hook> hooks = new ArrayList<>(stopHooks);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                for (Hook hook : hooks) {
                    HookRunner.Result result = HookRunner.run(context, workspace,
                            hook.command, input.toString(), hook.timeoutMs);
                    if (result.timedOut || result.exitCode != 0) {
                        Log.w(TAG, "stop hook failed: " + hook.command + " exit="
                                + result.exitCode + " timeout=" + result.timedOut
                                + " err=" + firstLine(result.stderr));
                    }
                }
            }
        }, "mobileagent-hook-stop");
        thread.setDaemon(true);
        thread.start();
    }

    private JSONObject baseInput(String event, String tool, JSONObject args, String goal,
                                 int step, String runId, String conversationId) {
        JSONObject input = new JSONObject();
        try {
            input.put("event", event);
            input.put("tool", tool);
            input.put("args", args == null ? new JSONObject() : args);
            input.put("workspace", workspace.root().getAbsolutePath());
            input.put("conversation", conversationId == null ? "" : conversationId);
            input.put("run_id", runId == null ? "" : runId);
            input.put("goal", goal == null ? "" : cut(goal, 1000));
            input.put("step", step);
        } catch (Exception ignored) {
        }
        return input;
    }

    private static long clampSeconds(long value) {
        return Math.max(1L, Math.min(15L, value));
    }

    private static String readText(File file) throws Exception {
        if (file.length() > 256 * 1024) {
            throw new java.io.IOException("hooks.json More than 256KB");
        }
        InputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            input.close();
        }
    }

    private static String cut(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        String clean = text.trim();
        int newline = clean.indexOf('\n');
        if (newline >= 0) clean = clean.substring(0, newline);
        return clean.length() <= 300 ? clean : clean.substring(0, 300) + "...";
    }

    private static String message(Exception error) {
        return error == null ? "unknown"
                : error.getMessage() == null ? error.getClass().getSimpleName()
                : error.getMessage();
    }
}
