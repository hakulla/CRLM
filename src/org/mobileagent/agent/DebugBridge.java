package org.mobileagent.agent;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.List;

/**
 * File debug data bridge (default closes):
 * - agent-bridge/debug/state.json: Current Engine/Session/Planned/Questions to be answered JSON Quickshot
 * - agent-bridge/inbox/debug-*.json: Action Request (Present)kind=debug)
 * - agent-bridge/outbox/debug-*.json: Implementation results
 * - agent-bridge/debug/token: Debug the token
 *
 * It is a data face for debugging computers, not a function for ordinary users; the screenshot is used only for visual acceptance.
 */
final class DebugBridge {
    static final String PROTOCOL = "mobileagent-debug/1";
    private static final String WORKSPACE_MARKER = "DEBUG_API.md";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private DebugBridge() {
    }

    static File debugDir(Context context) {
        return new File(BridgeAutoService.bridgeDir(context), "debug");
    }

    static File stateFile(Context context) {
        return new File(debugDir(context), "state.json");
    }

    static File tokenFile(Context context) {
        return new File(debugDir(context), "token");
    }

    static void prepare(Context context) {
        try {
            AppPrefs prefs = new AppPrefs(context);
            if (!prefs.debugBridgeEnabled()) {
                writeDisabledMarker(context);
                return;
            }
            File dir = debugDir(context);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            writeText(tokenFile(context), prefs.debugBridgeToken());
            writeText(new File(dir, "README.md"), readme(context, true));
            writeText(new File(new Workspace(context).baseRoot(), WORKSPACE_MARKER),
                    marker(context, true));
            writeState(context);
        } catch (Throwable ignored) {
        }
    }

    static void writeDisabledMarker(Context context) {
        try {
            writeText(new File(new Workspace(context).baseRoot(), WORKSPACE_MARKER),
                    marker(context, false));
        } catch (Throwable ignored) {
        }
    }

    static void disable(Context context) {
        try {
            new File(debugDir(context), "token").delete();
            stateFile(context).delete();
            writeText(new File(debugDir(context), "README.md"), readme(context, false));
            writeDisabledMarker(context);
        } catch (Throwable ignored) {
        }
    }

    static boolean isTokenValid(Context context, String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        String expected = new AppPrefs(context).debugBridgeToken();
        return MessageDigest.isEqual(token.trim().getBytes(UTF8), expected.getBytes(UTF8));
    }

    /** Return text to the requesting party; the real structured state is state.json.  */
    static String handle(Context context, JSONObject request) {
        try {
            AppPrefs prefs = new AppPrefs(context);
            if (!prefs.debugBridgeEnabled()) {
                return "Debugging data bridge not opened";
            }
            if (request == null || !isTokenValid(context, request.optString("token", ""))) {
                return "Debug token invalid";
            }
            String cmd = request.optString("cmd", "").trim();
            AgentEngine engine = currentEngine(context);
            if ("snapshot".equals(cmd)) {
                writeState(context);
                return "ok";
            }
            if ("status".equals(cmd)) {
                JSONObject out = new JSONObject();
                out.put("protocol", PROTOCOL);
                out.put("running", engine != null && engine.isRunning());
                out.put("paused", engine != null && engine.isPaused());
                out.put("conversation", engine == null ? "" : engine.currentConversationId());
                return out.toString();
            }
            if ("pause".equals(cmd)) {
                if (engine == null || !engine.isRunning()) {
                    return "No running task currently available";
                }
                engine.requestPause();
                writeState(context);
                return "ok: pause requested";
            }
            if ("stop".equals(cmd)) {
                if (engine == null) {
                    return "No current engine";
                }
                engine.requestStop();
                writeState(context);
                return "ok: stop requested";
            }
            if ("resume".equals(cmd)) {
                if (engine == null) {
                    return "No current engine";
                }
                boolean ok = engine.resumeTask();
                writeState(context);
                return ok ? "ok: resumed" : "Current mission is not resumed";
            }
            if ("send".equals(cmd)) {
                String prompt = request.optString("prompt", "").trim();
                if (prompt.isEmpty()) {
                    return "Missing prompt";
                }
                if (engine == null) {
                    return "No current engine";
                }
                boolean ok = engine.startTask(prompt);
                writeState(context);
                return ok ? "ok: task started" : "Current Task Running, Not Sent";
            }
            if ("open".equals(cmd)) {
                String id = request.optString("conversation", "").trim();
                if (id.isEmpty()) {
                    return "Missing conversation";
                }
                boolean ok = MainActivity.debugOpenConversation(id);
                writeState(context);
                return ok ? "ok: opening" : "No interface available, cannot switch sessions";
            }
            if ("new".equals(cmd)) {
                boolean ok = MainActivity.debugNewConversation();
                writeState(context);
                return ok ? "ok: new conversation" : "No interface available, no session can be created";
            }
            if ("config".equals(cmd)) {
                if (engine == null || engine.currentConversation() == null) {
                    return "No Current Session";
                }
                Conversation conversation = engine.currentConversation();
                if (request.has("temperature")) {
                    conversation.temperature = (float) Math.max(0.0,
                            Math.min(2.0, request.optDouble("temperature", conversation.temperature)));
                }
                if (request.has("max_tokens")) {
                    conversation.maxTokens = Math.max(512,
                            Math.min(32768, request.optInt("max_tokens", conversation.maxTokens)));
                }
                String thinking = request.optString("thinking", "").trim().toLowerCase(Locale.ROOT);
                if ("off".equals(thinking) || "on".equals(thinking) || "auto".equals(thinking)) {
                    conversation.thinking = thinking;
                }
                if (request.has("context_tokens")) {
                    conversation.contextTokens = Math.max(8000,
                            Math.min(1000000, request.optInt("context_tokens", conversation.contextTokens)));
                }
                if (request.has("max_rounds")) {
                    conversation.maxToolRounds = Math.max(8,
                            Math.min(24, request.optInt("max_rounds", conversation.maxToolRounds)));
                }
                engine.saveCurrent();
                writeState(context);
                JSONObject out = new JSONObject();
                out.put("temperature", conversation.temperature);
                out.put("max_tokens", conversation.maxTokens);
                out.put("thinking", conversation.thinking);
                out.put("context_tokens", conversation.contextTokens);
                out.put("max_rounds", conversation.maxToolRounds);
                return "ok: " + out;
            }
            if ("prefs".equals(cmd)) {
                String action = request.optString("action", "state").trim();
                if ("flush".equals(action)) {
                    boolean ok = LocalConfig.flushNow();
                    return (ok ? "ok: " : "error: ") + LocalConfig.debugState();
                }
                if ("merge".equals(action)) {
                    int restored = LocalConfig.restoreMissing();
                    writeState(context);
                    return "ok: restored " + restored;
                }
                if ("forget".equals(action)) {
                    int removed = LocalConfig.forgetKeys(request.optJSONArray("keys"));
                    writeState(context);
                    return "ok: removed " + removed + " (mirror paused until process restart)";
                }
                if ("set".equals(action)) {
                    boolean ok = LocalConfig.setValue(request.optString("key", ""),
                            request.opt("value"));
                    if (ok) {
                        LocalConfig.flushAsync();
                        writeState(context);
                    }
                    return ok ? "ok" : "invalid key or value";
                }
                JSONObject out = LocalConfig.debugState();
                out.put("effective", prefs.debugSettings());
                return "ok: " + out;
            }
            if ("browser".equals(cmd)) {
                BrowserSession session = BrowserSession.get();
                String action = request.optString("action", "eval").trim();
                if ("open".equals(action)) {
                    String url = request.optString("url", "").trim();
                    if (url.isEmpty()) {
                        return "Missing url";
                    }
                    String error = session.open(context, url, 45000);
                    return error == null ? "ok: " + session.currentUrl()
                            : "Open failed:" + error;
                }
                if ("shot".equals(action)) {
                    byte[] png = session.screenshot();
                    if (png == null || png.length == 0) {
                        return "Could not close temporary folder: %s" + session.lastError();
                    }
                    File out = new File(debugDir(context), "browser-shot.png");
                    FileOutputStream stream = new FileOutputStream(out);
                    try {
                        stream.write(png);
                    } finally {
                        stream.close();
                    }
                    return "ok: " + out.getAbsolutePath() + " (" + png.length + " bytes)";
                }
                String js = request.optString("js", "");
                if (js.trim().isEmpty()) {
                    return "Missing js";
                }
                String result = session.evaluate(js);
                if (result != null && result.length() > 20000) {
                    result = result.substring(0, 20000) + "...";
                }
                return "ok: " + (result == null ? "" : result);
            }
            if ("rotate".equals(cmd)) {
                String token = prefs.rotateDebugBridgeToken();
                writeText(tokenFile(context), token);
                writeState(context);
                return "ok: token rotated; read " + tokenFile(context).getAbsolutePath();
            }
            return "Unknown cmd: " + cmd;
        } catch (Throwable e) {
            return "Debug command failed:" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static AgentEngine currentEngine(Context context) {
        AgentEngine ui = MainActivity.debugEngine();
        return ui != null ? ui : AgentEngine.get(context);
    }

    static void writeState(Context context) {
        try {
            AppPrefs prefs = new AppPrefs(context);
            if (!prefs.debugBridgeEnabled()) {
                return;
            }
            AgentEngine engine = currentEngine(context);
            JSONObject root = new JSONObject();
            root.put("protocol", PROTOCOL);
            root.put("generatedAt", System.currentTimeMillis());
            root.put("preferred", true);
            root.put("note", "Debug this file and send it first debug command; screenshots are only for visual acceptance, layout uiautomator dump. ");
            root.put("appVersion", versionName(context));
            root.put("debugEnabled", true);
            root.put("configMirror", LocalConfig.debugState());
            root.put("running", engine != null && engine.isRunning());
            root.put("paused", engine != null && engine.isPaused());
            root.put("currentConversation", engine == null ? "" : engine.currentConversationId());
            if (engine != null) {
                root.put("runStats", engine.debugRunStats());
            }
            if (engine != null) {
                Conversation conversation = engine.currentConversation();
                if (conversation != null) {
                    JSONObject meta = new JSONObject();
                    meta.put("id", conversation.id);
                    meta.put("title", conversation.title);
                    meta.put("model", conversation.model);
                    meta.put("temperature", conversation.temperature);
                    meta.put("maxTokens", conversation.maxTokens);
                    meta.put("contextTokens", conversation.contextTokens);
                    meta.put("maxToolRounds", conversation.maxToolRounds);
                    meta.put("thinking", conversation.thinking);
                    meta.put("paused", conversation.paused);
                    root.put("conversation", meta);
                }
                JSONArray plan = engine.getPlan();
                root.put("plan", plan == null ? new JSONArray() : new JSONArray(plan.toString()));
                List<AgentEngine.Entry> entries = engine.getEntries();
                JSONArray array = new JSONArray();
                int from = Math.max(0, entries.size() - 120);
                JSONObject pending = null;
                long pendingTime = 0L;
                for (int i = from; i < entries.size(); i++) {
                    AgentEngine.Entry entry = entries.get(i);
                    JSONObject item = new JSONObject();
                    item.put("type", entryType(entry.type));
                    item.put("time", entry.time);
                    item.put("text", redact(shorten(entry.text, 4000)));
                    if (entry.images != null && !entry.images.isEmpty()) {
                        JSONArray images = new JSONArray();
                        for (String image : entry.images) {
                            images.put(image);
                        }
                        item.put("images", images);
                    }
                    if (entry.files != null && !entry.files.isEmpty()) {
                        JSONArray files = new JSONArray();
                        for (String file : entry.files) {
                            files.put(file);
                        }
                        item.put("files", files);
                    }
                    array.put(item);
                    if (entry.type == AgentEngine.Entry.QUESTIONS) {
                        pending = item;
                        pendingTime = entry.time;
                    }
                }
                root.put("entries", array);
                if (pending != null) {
                    JSONObject question = new JSONObject(pending.toString());
                    question.put("answered", isAnswered(context, pendingTime));
                    root.put("latestQuestion", question);
                }
            }
            JSONArray conversations = new JSONArray();
            try {
                for (Conversation.Summary summary :
                        ConversationStore.shared(context).list(new Workspace(context))) {
                    JSONObject item = new JSONObject();
                    item.put("id", summary.id);
                    item.put("title", summary.title);
                    item.put("model", summary.model);
                    item.put("updatedAt", summary.updatedAt);
                    item.put("archived", summary.archived);
                    conversations.put(item);
                }
            } catch (Throwable ignored) {
            }
            root.put("conversations", conversations);
            File dir = debugDir(context);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            writeAtomic(stateFile(context), root.toString());
        } catch (Throwable ignored) {
        }
    }

    private static boolean isAnswered(Context context, long questionTime) {
        try {
            String raw = context.getSharedPreferences("mobileagent_ui", Context.MODE_PRIVATE)
                    .getString("question_answers", "");
            JSONObject all = new JSONObject(raw == null ? "{}" : raw);
            JSONArray answers = all.optJSONArray(String.valueOf(questionTime));
            if (answers == null || answers.length() == 0) {
                return false;
            }
            for (int i = 0; i < answers.length(); i++) {
                if (answers.optString(i, "").trim().isEmpty()) {
                    return false;
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String entryType(int type) {
        switch (type) {
            case AgentEngine.Entry.USER: return "user";
            case AgentEngine.Entry.ASSISTANT: return "assistant";
            case AgentEngine.Entry.TOOL: return "tool";
            case AgentEngine.Entry.ERROR: return "error";
            case AgentEngine.Entry.SYSTEM: return "system";
            case AgentEngine.Entry.REASONING: return "reasoning";
            case AgentEngine.Entry.QUESTIONS: return "question";
            case AgentEngine.Entry.IMAGE: return "image";
            default: return "event";
        }
    }

    private static String readme(Context context, boolean enabled) {
        File token = tokenFile(context);
        File state = stateFile(context);
        StringBuilder sb = new StringBuilder();
        sb.append("# CRLM Debug Data Bridge\n\n");
        sb.append(enabled ? "Status: Enabled\n\n" : "Status: Closed (in %2) App Settings → Agent Bridge → Debug Data Bridge Open)\n\n");
        sb.append("## Statement to debug the computer\n")
                .append("Use this data bridge to read status and execute actions. Do not use default screenshots, or do not use them for your own benefit.")
                .append("Do not enter through clicking/Input method sends messages; screenshots are used only for visual acceptance.\n\n");
        sb.append("## Read it.\n")
                .append("- Status snapshot:`").append(state.getAbsolutePath()).append("`\n")
                .append("  - Include current sessions, plans, running/Timeout,entries(Text/Picture path, list of questions to be answered, session list.\n")
                .append("- Page layout: Use `uiautomator dump`, Don't start with the screenshot.\n\n");
        sb.append("## Write\n")
                .append("- Put it on. JSON ♪ Write to `/sdcard/MobileAgent/agent-bridge/inbox/debug-<id>.json`: \n")
                .append("```json\n{\"kind\":\"debug\",\"token\":\"<token>\",\"cmd\":\"send\",\"prompt\":\"...\"}\n```\n")
                .append("- Actions:`send`(prompt), `pause`, `stop`, `resume`, ")
                .append("`open`(conversation), `new`, `snapshot`, `status`, `rotate`, `prefs`, `browser`. \n")
                .append("- `prefs` It's... action: `state` / `flush` / `merge`; `forget`, `set` Only for authentication saving and restoration.\n")
                .append("- `browser` It's... action: `open`(url)/ `eval`(js)/ `shot`(Intercepts the current inline browser page; only on a trial basis.\n")
                .append("- And it says, `/sdcard/MobileAgent/agent-bridge/outbox/debug-<id>.json`. \n")
                .append("- token file:`").append(token.getAbsolutePath()).append("`\n\n");
        sb.append("## Set mirror\n")
                .append("- All non-key settings automatically save to `/sdcard/MobileAgent/config/agent-settings.json`. \n")
                .append("- The file is used to complete the missing settings for start-up after overwrite installation (without unmounting), and the settings are in place.\n")
                .append("- API Key, Install passwords, debug badges, and only store them in the application private storage, without writing in the mirror.\n\n");
        sb.append("## Debug Limit\n")
                .append("- Do not modify system hibernation time (screen_off_timeout / stay_on_while_plugged_in); ")
                .append("Use only when needed `input keyevent KEYCODE_WAKEUP` Wake up.\n");
        sb.append("## Clear.\n")
                .append("- Only on debugging; Default closes. After off token/state It'll be deleted.\n")
                .append("- token/state is in shared storage, please be in after debugging App In there off.\n");
        return sb.toString();
    }

    private static String marker(Context context, boolean enabled) {
        StringBuilder sb = new StringBuilder();
        sb.append("# CRLM Debug the portal (prior to use)\n\n");
        sb.append(enabled
                ? "Debugging the data bridge is on. Debugging the book App Time priority is given to the data bridge, no default intercept/point input box.\n\n"
                : "Debugging data bridge is currently closed. If you need to debug it efficiently, in App Settings → Agent Bridge → Debug Data Bridge Open.\n\n");
        sb.append("- Status snapshot:`/sdcard/MobileAgent/agent-bridge/debug/state.json`\n")
                .append("- Command directory:`/sdcard/MobileAgent/agent-bridge/inbox/`(kind=debug)\n")
                .append("- token/Complete agreement:`/sdcard/MobileAgent/agent-bridge/debug/README.md`\n")
                .append("- Actions:send / pause / stop / resume / open / new / snapshot / status / prefs\n")
                .append("- Screenshots for visual acceptance only; layout `uiautomator dump`. \n")
                .append("- Do not modify system hibernation; use only when needed `input keyevent KEYCODE_WAKEUP`. \n");
        if (enabled) {
            sb.append("\nNote: After debugging App , avoid sharing the stored exposed session status.\n");
        }
        return sb.toString();
    }

    private static void writeAtomic(File target, String text) throws Exception {
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        writeText(tmp, text);
        if (target.exists() && !target.delete()) {
            // rename may still replace on many filesystems
        }
        if (!tmp.renameTo(target)) {
            writeText(target, text);
            tmp.delete();
        }
    }

    private static void writeText(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes(UTF8));
            out.flush();
        } finally {
            out.close();
        }
    }

    private static String versionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String value = text.replace("\u0000", "");
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static String redact(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replaceAll(
                "(?i)(ghp_|github_pat_|sk-|xox[baprs]-)[A-Za-z0-9_\\-]{8,}",
                "$1[REDACTED]");
        value = value.replaceAll(
                "(?i)(\"(?:api[_-]?key|token|password|secret|authorization)\"\\s*:\\s*\")[^\"]*(\")",
                "$1[REDACTED]$2");
        return value;
    }
}
