package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MessageProtocol {
    private MessageProtocol() {
    }

    static String role(JSONObject message) {
        if (message == null || message.isNull("role")) {
            return "";
        }
        Object role = message.opt("role");
        return role instanceof String ? (String) role : "";
    }

    static boolean hasCalls(JSONObject message) {
        JSONArray calls = message == null ? null : message.optJSONArray("tool_calls");
        return calls != null && calls.length() > 0;
    }

    static int safeKeepFrom(List<JSONObject> messages, int start, int desired) {
        int lower = Math.max(0, Math.min(start, messages.size()));
        int index = Math.max(lower, Math.min(desired, messages.size()));
        if (index == messages.size()) {
            return index;
        }
        while (index > lower && "tool".equals(role(messages.get(index)))) {
            index--;
        }
        return index;
    }

    static JSONArray repairLeadingOrphans(JSONArray request) {
        JSONArray copy = copy(request);
        int first = copy.length() > 0 && "system".equals(role(copy.optJSONObject(0))) ? 1 : 0;
        while (first < copy.length() && "tool".equals(role(copy.optJSONObject(first)))) {
            copy.remove(first);
        }
        return copy;
    }

    /** Heal a history whose turn was interrupted: every tool_call gets a result,
     *  and a tool result whose owner call went missing keeps its payload by getting a
     *  synthesized owner call. Dropping the real result and substituting a stub is what
     *  made the model believe a successful edit had failed, so it rebuilt and reinstalled
     *  forever. Returns a repaired copy. */
    static JSONArray repairDanglingCalls(JSONArray request) {
        JSONArray copy = hoistInterleavedHints(copy(request));
        JSONArray out = new JSONArray();
        Set<String> pending = new LinkedHashSet<>();
        Set<String> usedIds = new HashSet<>();
        Map<String, ArrayDeque<String>> aliases = new HashMap<>();
        int seq = 0;
        for (int i = 0; i < copy.length(); i++) {
            JSONObject message = copy.optJSONObject(i);
            if (message == null) {
                continue;
            }
            String role = role(message);
            if ("tool".equals(role)) {
                String id = message.isNull("tool_call_id") ? ""
                        : message.optString("tool_call_id", "").trim();
                if (id.isEmpty() && pending.size() == 1) {
                    id = pending.iterator().next();
                }
                if (!id.isEmpty() && pending.remove(id)) {
                    put(message, "tool_call_id", id);
                    out.put(message);
                } else {
                    String renamed = popAlias(aliases, id, pending);
                    if (renamed != null) {
                        pending.remove(renamed);
                        put(message, "tool_call_id", renamed);
                        out.put(message);
                        continue;
                    }
                    // Synth owner We have to close the call before we call it, or this one. assistant
                    // They'll be stuck behind the suspension. They'll be all round.'Tool call is not closed'. 
                    if (!pending.isEmpty()) {
                        for (String waiting : pending) {
                            out.put(stubResult(waiting));
                        }
                        pending.clear();
                    }
                    String owner = uniqueId(id.isEmpty() ? "call_recovered" : id,
                            usedIds, ++seq);
                    out.put(ownerCall(owner));
                    put(message, "tool_call_id", owner);
                    out.put(message);
                }
                continue;
            }
            if (!pending.isEmpty()) {
                for (String id : pending) {
                    out.put(stubResult(id));
                }
                pending.clear();
            }
            out.put(message);
            if ("assistant".equals(role)) {
                JSONArray calls = message.optJSONArray("tool_calls");
                if (calls != null) {
                    for (int c = 0; c < calls.length(); c++) {
                        JSONObject call = calls.optJSONObject(c);
                        if (call == null) {
                            call = new JSONObject();
                            put(call, "type", "function");
                            put(call, "id", "");
                            JSONObject fallback = new JSONObject();
                            put(fallback, "name", "unknown_tool");
                            put(fallback, "arguments", "{}");
                            put(call, "function", fallback);
                            putAt(calls, c, call);
                        }
                        String raw = call.isNull("id") ? ""
                                : call.optString("id", "").trim();
                        String id = uniqueId(raw.isEmpty() ? "call_auto" : raw,
                                usedIds, ++seq);
                        if (!id.equals(raw)) {
                            if (!raw.isEmpty()) {
                                ArrayDeque<String> queue = aliases.get(raw);
                                if (queue == null) {
                                    queue = new ArrayDeque<>();
                                    aliases.put(raw, queue);
                                }
                                queue.addLast(id);
                            }
                            put(call, "id", id);
                        }
                        JSONObject function = call.optJSONObject("function");
                        if (function == null) {
                            function = new JSONObject();
                            put(call, "function", function);
                        }
                        if (function.optString("name", "").trim().isEmpty()) {
                            put(function, "name", "unknown_tool");
                        }
                        if (function.optString("arguments", "").trim().isEmpty()) {
                            put(function, "arguments", "{}");
                        }
                        pending.add(id);
                    }
                }
            }
        }
        if (!pending.isEmpty()) {
            for (String id : pending) {
                out.put(stubResult(id));
            }
        }
        return out;
    }

    /**
     * 'It's been in history.'assistant Call → user System Hint → tool 'Results' in this order (the hint is in the tool implementer).
     * It's a retrofit, and it's a re-entry. It's a re-engineering logic that makes the real results an orphan, and then it's a synthesis.
     * Fake. owner Called, eventually checked and checked.'CallidEmpty or duplicate'. Here's the clue to the results of the group.
     * The semantics remain unchanged (the message is originally for the next round) and the sequence is back to the law.
     */
    private static JSONArray hoistInterleavedHints(JSONArray messages) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) {
                continue;
            }
            JSONArray calls = message.optJSONArray("tool_calls");
            if (!"assistant".equals(role(message)) || calls == null || calls.length() == 0) {
                out.put(message);
                continue;
            }
            Set<String> pending = new LinkedHashSet<>();
            for (int c = 0; c < calls.length(); c++) {
                JSONObject call = calls.optJSONObject(c);
                if (call != null && !call.isNull("id")) {
                    pending.add(call.optString("id", "").trim());
                }
            }
            if (pending.isEmpty()) {
                out.put(message);
                continue;
            }
            List<JSONObject> window = new java.util.ArrayList<>();
            int j = i + 1;
            while (j < messages.length() && !pending.isEmpty()) {
                JSONObject next = messages.optJSONObject(j);
                if (next == null) {
                    break;
                }
                String nextRole = role(next);
                if ("tool".equals(nextRole)) {
                    String id = next.isNull("tool_call_id") ? ""
                            : next.optString("tool_call_id", "").trim();
                    if (!pending.remove(id)) {
                        break;
                    }
                    window.add(next);
                    j++;
                } else if ("user".equals(nextRole) && isInterleavedHint(next)) {
                    window.add(next);
                    j++;
                } else {
                    break;
                }
            }
            out.put(message);
            if (pending.isEmpty() && hasHint(window)) {
                // The results are reordered: the results are in the order they are in, and the hints are moved back.
                for (JSONObject item : window) {
                    if (!isInterleavedHint(item)) {
                        out.put(item);
                    }
                }
                for (JSONObject item : window) {
                    if (isInterleavedHint(item)) {
                        out.put(item);
                    }
                }
            } else {
                for (JSONObject item : window) {
                    out.put(item);
                }
            }
            i = j - 1;
        }
        return out;
    }

    private static boolean hasHint(List<JSONObject> window) {
        for (JSONObject item : window) {
            if (isInterleavedHint(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInterleavedHint(JSONObject message) {
        if (message.optBoolean("_synthetic", false)) {
            return true;
        }
        String text = message.optString("content", "").trim();
        return text.startsWith("[System Hint]") || text.startsWith("[System correction]");
    }

    private static JSONObject stubResult(String callId) {
        JSONObject stub = new JSONObject();
        try {
            stub.put("role", "tool");
            stub.put("tool_call_id", callId);
            stub.put("content", "{\"ok\":false,\"error\":\"Tool call was interrupted and no return results were returned (historical restoration takes precedence)\"}");
        } catch (JSONException ignored) {
        }
        return stub;
    }

    /** Owner call synthesized for a tool result whose assistant call was lost.
     *  Keeping the real payload is what stops the "edit failed → rebuild → reinstall" loop. */
    private static JSONObject ownerCall(String callId) {
        JSONObject owner = new JSONObject();
        try {
            owner.put("role", "assistant");
            owner.put("content", "");
            JSONArray calls = new JSONArray();
            JSONObject call = new JSONObject();
            call.put("id", callId);
            call.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", "unknown_tool");
            fn.put("arguments", "{}");
            call.put("function", fn);
            calls.put(call);
            owner.put("tool_calls", calls);
        } catch (JSONException ignored) {
        }
        return owner;
    }

    static boolean hasPendingCalls(List<JSONObject> messages) {
        Set<String> pending = new HashSet<>();
        for (JSONObject message : messages) {
            if (message == null) {
                continue;
            }
            if ("assistant".equals(role(message))) {
                JSONArray calls = message.optJSONArray("tool_calls");
                if (calls != null) {
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject call = calls.optJSONObject(i);
                        if (call != null && !call.isNull("id")) {
                            pending.add(call.optString("id", ""));
                        }
                    }
                }
            } else if ("tool".equals(role(message))) {
                pending.remove(message.optString("tool_call_id", ""));
            }
        }
        return !pending.isEmpty();
    }

    static String validate(JSONArray request) {
        return validate(request, false);
    }

    static String validatePending(JSONArray request) {
        return validate(request, true);
    }

    private static String validate(JSONArray request, boolean allowPending) {
        if (request == null || request.length() == 0) {
            return "INVALID_MESSAGE_SEQUENCE: Request empty";
        }
        Set<String> seenCalls = new HashSet<>();
        Set<String> seenResults = new HashSet<>();
        Set<String> pending = new LinkedHashSet<>();
        boolean nonSystem = false;
        for (int i = 0; i < request.length(); i++) {
            JSONObject message = request.optJSONObject(i);
            if (message == null) {
                return invalid(i, "Message is not an object");
            }
            String role = role(message);
            if (!"system".equals(role) && !"user".equals(role)
                    && !"assistant".equals(role) && !"tool".equals(role)) {
                return invalid(i, "Unknown Role");
            }
            if ("system".equals(role)) {
                if (nonSystem || !pending.isEmpty()) {
                    return invalid(i, "System message is not prefixed");
                }
                continue;
            }
            nonSystem = true;
            if (!"tool".equals(role) && !pending.isEmpty()) {
                return invalid(i, "Tool Call is not closed, cannot insert new message");
            }
            if ("assistant".equals(role)) {
                JSONArray calls = message.optJSONArray("tool_calls");
                if (calls == null) {
                    continue;
                }
                for (int c = 0; c < calls.length(); c++) {
                    JSONObject call = calls.optJSONObject(c);
                    if (call == null || call.isNull("id")) {
                        return invalid(i, "Call Missingid");
                    }
                    String id = call.optString("id", "");
                    if (id.trim().isEmpty() || !seenCalls.add(id)) {
                        return invalid(i, "CallidEmpty or duplicate");
                    }
                    JSONObject function = call.optJSONObject("function");
                    if (function == null || function.isNull("name")
                            || function.optString("name", "").trim().isEmpty()) {
                        return invalid(i, "Call Missing Function Name");
                    }
                    pending.add(id);
                }
            } else if ("tool".equals(role)) {
                String id = message.isNull("tool_call_id") ? ""
                        : message.optString("tool_call_id", "");
                if (!pending.remove(id) || !seenResults.add(id)) {
                    return invalid(i, "Results of isolated, duplicated or cross-group tools");
                }
            }
        }
        return allowPending || pending.isEmpty() ? null
                : "INVALID_MESSAGE_SEQUENCE: Tool call has no result";
    }

    static JSONArray copy(JSONArray value) {
        try {
            return value == null ? new JSONArray() : new JSONArray(value.toString());
        } catch (Exception e) {
            throw new IllegalArgumentException("INVALID_MESSAGE_SEQUENCE: Can not get message: %s %s", e);
        }
    }

    private static String invalid(int index, String message) {
        return "INVALID_MESSAGE_SEQUENCE: I'm sorry." + index + "Article " + message;
    }

    private static void put(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (JSONException ignored) {
        }
    }

    private static void putAt(JSONArray target, int index, JSONObject value) {
        try {
            target.put(index, value);
        } catch (JSONException ignored) {
        }
    }

    /** Returns base if unused, otherwise base#n; always registers the result. */
    private static String uniqueId(String base, Set<String> used, int seq) {
        String candidate = base;
        int n = 1;
        while (used.contains(candidate)) {
            candidate = base + "#" + seq + "_" + n;
            n++;
        }
        used.add(candidate);
        return candidate;
    }

    private static String popAlias(Map<String, ArrayDeque<String>> aliases, String raw,
                                  Set<String> pending) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        ArrayDeque<String> queue = aliases.get(raw);
        if (queue == null) {
            return null;
        }
        while (!queue.isEmpty()) {
            String candidate = queue.pollFirst();
            if (pending.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
