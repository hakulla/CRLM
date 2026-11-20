package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Safe text access for org.json values that may be JSONObject.NULL. */
final class JsonText {
    private static final Pattern NULL_ONLY = Pattern.compile("(?is)\\s*(null\\s*)+");
    private static final Pattern NULL_RUN = Pattern.compile("(?i)(?:null){3,}");

    private JsonText() {
    }

    static String string(JSONObject object, String key) {
        if (object == null || key == null || !object.has(key)) {
            return "";
        }
        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        if (value instanceof String) {
            return (String) value;
        }
        return String.valueOf(value);
    }

    static boolean isNullOnly(String text) {
        if (text == null) {
            return true;
        }
        String value = text.trim();
        return value.isEmpty() || NULL_ONLY.matcher(value).matches();
    }

    static boolean isNullNoise(String text) {
        if (isNullOnly(text)) {
            return true;
        }
        String value = text.trim()
                .replace("...[Content has been cut]", "")
                .replace("[Content has been cut]", "")
                .trim();
        if (value.isEmpty()) {
            return true;
        }
        int count = 0;
        int index = 0;
        String lower = value.toLowerCase();
        while ((index = lower.indexOf("null", index)) >= 0) {
            count++;
            index += 4;
        }
        if (count < 2) {
            return false;
        }
        String remainder = lower.replace("null", "").replaceAll("\\s+", "");
        if (remainder.isEmpty() || remainder.matches("[nul]+")) {
            return true;
        }
        if (count >= 4 && value.length() > 32
                && count * 4L * 2 >= value.length()) {
            return true;
        }
        return count >= 4 && value.length() > 32
                && remainder.length() <= Math.max(8, value.length() / 20);
    }

    static String stripNullRuns(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return NULL_RUN.matcher(text).replaceAll("").trim();
    }

    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String stripped = stripNullRuns(text);
        return isNullNoise(stripped) ? "" : stripped;
    }

    static String content(JSONObject message) {
        return contentText(message == null ? null : message.opt("content"));
    }

    static String contentText(Object content) {
        if (content == null || content == JSONObject.NULL) {
            return "";
        }
        if (content instanceof String) {
            return clean((String) content);
        }
        if (content instanceof JSONArray) {
            JSONArray parts = (JSONArray) content;
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null) {
                    continue;
                }
                String type = string(part, "type");
                if ("text".equals(type)) {
                    String text = clean(string(part, "text"));
                    if (!text.isEmpty()) {
                        if (builder.length() > 0) {
                            builder.append('\n');
                        }
                        builder.append(text);
                    }
                } else if ("image_url".equals(type)) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append("[Picture]");
                }
            }
            return builder.toString();
        }
        return clean(String.valueOf(content));
    }

    static JSONArray sanitizeMessages(JSONArray messages) {
        if (messages == null) {
            return new JSONArray();
        }
        JSONArray kept = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) {
                continue;
            }
            String role = string(message, "role");
            JSONArray calls = message.optJSONArray("tool_calls");
            boolean assistantWithCalls = "assistant".equals(role)
                    && calls != null && calls.length() > 0;
            Object content = message.opt("content");
            try {
                if (content == null || content == JSONObject.NULL) {
                    if (!assistantWithCalls) {
                        message.put("content", "");
                    }
                } else if (content instanceof String) {
                    String clean = clean((String) content);
                    if (clean.isEmpty()) {
                        message.put("content",
                                assistantWithCalls ? JSONObject.NULL : "");
                    } else if (!clean.equals(content)) {
                        message.put("content", clean);
                    }
                } else if (content instanceof JSONArray) {
                    JSONArray parts = (JSONArray) content;
                    JSONArray keptParts = new JSONArray();
                    for (int p = 0; p < parts.length(); p++) {
                        JSONObject part = parts.optJSONObject(p);
                        if (part == null) {
                            continue;
                        }
                        String type = string(part, "type");
                        if ("text".equals(type)) {
                            String clean = clean(string(part, "text"));
                            if (clean.isEmpty()) {
                                continue;
                            }
                            part.put("text", clean);
                        }
                        keptParts.put(part);
                    }
                    if (keptParts.length() == 0) {
                        message.put("content", "");
                    } else {
                        message.put("content", keptParts);
                    }
                }
                cleanReasoning(message, "reasoning_content");
                cleanReasoning(message, "reasoning");
            } catch (Exception ignored) {
            }
            kept.put(message);
        }
        return repairToolSequence(kept);
    }

    /**
     * OpenAI-compatible APIs require every assistant tool_call to be followed by a
     * matching tool message before the next user/assistant turn. A crash or old stop
     * could leave a dangling call and make every subsequent request fail with HTTP 400.
     *
     * <p>Two rules keep this from destroying real work: a call at the very end of the
     * history is left open (its result may still be in flight; the send-time repair adds a
     * placeholder to the request copy only), and a real result that arrives for a call that
     * already got a placeholder replaces it. Without those, a save taken between "call
     * issued" and "result appended" wrote Interrupted into the transcript and the real result
     * was later dropped as an orphan, so the model saw an interrupted tool for an edit that
     * actually succeeded.</p>
     */
    static JSONArray repairToolSequence(JSONArray messages) {
        JSONArray out = new JSONArray();
        Set<String> pending = new LinkedHashSet<>();
        Map<String, Integer> placeholderSlot = new HashMap<>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) {
                continue;
            }
            String role = string(message, "role");
            JSONArray calls = message.optJSONArray("tool_calls");
            if ("assistant".equals(role) && calls != null && calls.length() > 0) {
                closeGaps(out, pending, placeholderSlot);
                pending.clear();
                out.put(message);
                for (int c = 0; c < calls.length(); c++) {
                    JSONObject call = calls.optJSONObject(c);
                    if (call == null) {
                        continue;
                    }
                    String id = string(call, "id");
                    if (id.isEmpty()) {
                        id = "call_auto_" + i + "_" + c;
                        try {
                            call.put("id", id);
                        } catch (Exception ignored) {
                        }
                    }
                    pending.add(id);
                }
            } else if ("tool".equals(role)) {
                String id = string(message, "tool_call_id");
                if (id.isEmpty()) {
                    continue;
                }
                Integer slot = placeholderSlot.get(id);
                if (pending.remove(id)) {
                    if (slot != null) {
                        placeholderSlot.remove(id);
                        replaceAt(out, slot.intValue(), message);
                    } else {
                        out.put(message);
                        if (isPlaceholderResult(message)) {
                            placeholderSlot.put(id, Integer.valueOf(out.length() - 1));
                        }
                    }
                } else if (slot != null) {
                    // The place is written, and the real result is: top of the table, it cannot be thrown away as an isolated result.
                    placeholderSlot.remove(id);
                    replaceAt(out, slot.intValue(), message);
                }
            } else {
                closeGaps(out, pending, placeholderSlot);
                pending.clear();
                out.put(message);
            }
        }
        return out;
    }

    /** The call that is suspended in the middle must be filled; the place that is filled must be recorded and the real result replaced on arrival. */
    private static void closeGaps(JSONArray out, Set<String> pending,
                                  Map<String, Integer> placeholderSlot) {
        for (String id : pending) {
            out.put(placeholderResult(id));
            placeholderSlot.put(id, Integer.valueOf(out.length() - 1));
        }
    }

    private static final String PLACEHOLDER_MARKER = "[The historical record lacks the results of this tool ' s call and is automatically completed;";

    private static JSONObject placeholderResult(String callId) {
        JSONObject stub = new JSONObject();
        try {
            stub.put("role", "tool");
            stub.put("tool_call_id", callId);
            stub.put("content", PLACEHOLDER_MARKER + "Recall if you need a real result]");
        } catch (Exception ignored) {
        }
        return stub;
    }

    /** The result of the placeholder can be replaced by the subsequent real result, which identifies the fixed prefix. */
    private static boolean isPlaceholderResult(JSONObject message) {
        return string(message, "content").startsWith(PLACEHOLDER_MARKER);
    }

    private static void replaceAt(JSONArray out, int slot, JSONObject message) {
        if (slot < 0 || slot >= out.length()) {
            return;
        }
        try {
            out.put(slot, message);
        } catch (Exception ignored) {
        }
    }

    private static void cleanReasoning(JSONObject message, String key) {
        if (!message.has(key) || message.isNull(key)) {
            return;
        }
        Object raw = message.opt(key);
        try {
            if (!(raw instanceof String)) {
                message.remove(key);
                return;
            }
            // DeepSeek requires reasoning_content of tool-call turns to be passed
            // back verbatim; never trim or rewrite it.
            if (((String) raw).isEmpty()) {
                message.remove(key);
            }
        } catch (Exception ignored) {
        }
    }

    static JSONArray sanitizeEntries(JSONArray entries) {
        if (entries == null) {
            return new JSONArray();
        }
        JSONArray kept = new JSONArray();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            String text = clean(string(entry, "text"));
            if (text.isEmpty()) {
                continue;
            }
            try {
                entry.put("text", text);
            } catch (Exception ignored) {
            }
            kept.put(entry);
        }
        return kept;
    }

    static String sanitizeDigest(String digest) {
        if (digest == null || isNullOnly(digest)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        String[] lines = digest.split("\n", -1);
        for (String rawLine : lines) {
            String line = stripNullRuns(rawLine);
            String body = line.trim();
            if (body.startsWith("- ")) {
                int colon = body.indexOf(':');
                if (colon > 0 && isNullOnly(body.substring(colon + 1))) {
                    continue;
                }
            }
            if (line.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        String cleaned = builder.toString().trim();
        return isNullOnly(cleaned) ? "" : cleaned;
    }
}
