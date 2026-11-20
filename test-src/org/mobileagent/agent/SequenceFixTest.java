package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Authenticationassistant Call → System Hint → 'and the sequence will be fixed and the restoration will not create duplication.' id.  */
public final class SequenceFixTest {

    private static int checked;

    public static void main(String[] args) throws Exception {
        hintIsHoistedAfterResult();
        multiCallHintIsHoisted();
        realGapStillStubbed();
        duplicateCallsGetUniqueIds();
        realTranscriptRepairs();
        System.out.println("SequenceFixTest PASS (" + checked + " assertions)");
    }

    /** Individual call: the call and the result remain unchanged after the hint has been moved to the result. */
    private static void hintIsHoistedAfterResult() throws Exception {
        JSONArray history = new JSONArray();
        history.put(message("system", "SYS"));
        history.put(assistant("call_A"));
        history.put(hint("Successfully edited Foo.java"));
        history.put(tool("call_A", "{\"ok\":true}"));
        JSONArray fixed = MessageProtocol.repairDanglingCalls(history);
        check(MessageProtocol.validate(fixed) == null, "Once the historical restoration is done, the following should be verified:" + MessageProtocol.validate(fixed));
        check(countRole(fixed, "assistant") == 1, "No, I don't think we should. owner Call, actual assistant=" + countRole(fixed, "assistant"));
        int toolAt = indexOfRole(fixed, "tool");
        int hintAt = indexOfHint(fixed);
        check(toolAt >= 0 && hintAt > toolAt, "The hint should be after the result tool=" + toolAt + " hint=" + hintAt);
        check("call_A".equals(callId(fixed, 1)), "Original Call id Shouldn't be rewritten.");
    }

    /** Multiple calls: The result is all set before the reminder in the original order. */
    private static void multiCallHintIsHoisted() throws Exception {
        JSONArray history = new JSONArray();
        history.put(message("system", "SYS"));
        history.put(assistant("call_A", "call_B"));
        history.put(hint("Plans updated"));
        history.put(tool("call_B", "{\"ok\":true,\"which\":\"B\"}"));
        history.put(tool("call_A", "{\"ok\":true,\"which\":\"A\"}"));
        JSONArray fixed = MessageProtocol.repairDanglingCalls(history);
        check(MessageProtocol.validate(fixed) == null, "After the additional calls for historical restoration, the following should be verified:" + MessageProtocol.validate(fixed));
        check(countRole(fixed, "assistant") == 1, "Should not synthesize extra owner Call");
        int hintAt = indexOfHint(fixed);
        check(hintAt == 4, "The hint should be followed by two results, actual index=" + hintAt);
    }

    /** The real historical gap (which is followed by another round) remains undecided. */
    private static void realGapStillStubbed() throws Exception {
        JSONArray history = new JSONArray();
        history.put(message("system", "SYS"));
        history.put(assistant("call_A"));
        history.put(message("user", "Go on."));
        JSONArray fixed = MessageProtocol.repairDanglingCalls(history);
        check(MessageProtocol.validate(fixed) == null, "The gap should be filled:" + MessageProtocol.validate(fixed));
        check(countToolFor(fixed, "call_A") == 1, "The result of the placeholder should exist.");
    }

    /** Repeat/Empty id It has to be changed to the only one. id.  */
    private static void duplicateCallsGetUniqueIds() throws Exception {
        JSONArray history = new JSONArray();
        history.put(message("system", "SYS"));
        history.put(assistant("call_X"));
        history.put(tool("call_X", "1"));
        history.put(assistant("call_X"));
        history.put(tool("call_X", "2"));
        history.put(assistant(""));
        history.put(tool("", "3"));
        JSONArray fixed = MessageProtocol.repairDanglingCalls(history);
        String error = MessageProtocol.validate(fixed);
        check(error == null, "Repeat/Empty id The repairs shall be verified:" + error);
    }

    /** The real export record (which includes a 'tip between call and result') must be able to be repaired and verified. */
    private static void realTranscriptRepairs() throws Exception {
        String path = "/tmp/mobileagent/blocked-conv.json";
        if (!Files.exists(Paths.get(path))) {
            System.out.println("  (skipped real transcript: no file)");
            return;
        }
        String raw = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        JSONArray messages = new JSONObject(raw).getJSONArray("messages");
        JSONArray fixed = MessageProtocol.repairDanglingCalls(messages);
        String error = MessageProtocol.validate(fixed);
        check(error == null, "After the real history has been restored, the following should be verified:" + error);
        check(countRole(fixed, "assistant") <= countRole(messages, "assistant") + 2,
                "The amount of synthetic information should not be out of control.");
        System.out.println("  real transcript: messages=" + messages.length()
                + " repaired=" + fixed.length() + " assistants=" + countRole(fixed, "assistant"));
    }

    private static JSONObject message(String role, String content) throws Exception {
        JSONObject object = new JSONObject();
        object.put("role", role);
        object.put("content", content);
        return object;
    }

    private static JSONObject assistant(String... ids) throws Exception {
        JSONObject object = message("assistant", "");
        JSONArray calls = new JSONArray();
        for (String id : ids) {
            JSONObject call = new JSONObject();
            call.put("id", id);
            call.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", "edit");
            fn.put("arguments", "{}");
            call.put("function", fn);
            calls.put(call);
        }
        object.put("tool_calls", calls);
        return object;
    }

    private static JSONObject tool(String id, String content) throws Exception {
        JSONObject object = message("tool", content);
        object.put("tool_call_id", id);
        return object;
    }

    private static JSONObject hint(String text) throws Exception {
        JSONObject object = message("user", "[System Hint] " + text);
        object.put("_synthetic", true);
        object.put("_synthetic_source", "System correction");
        return object;
    }

    private static int countRole(JSONArray messages, String role) {
        int total = 0;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message != null && role.equals(MessageProtocol.role(message))) {
                total++;
            }
        }
        return total;
    }

    private static int countToolFor(JSONArray messages, String callId) {
        int total = 0;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message != null && "tool".equals(MessageProtocol.role(message))
                    && callId.equals(message.optString("tool_call_id", ""))) {
                total++;
            }
        }
        return total;
    }

    private static int indexOfRole(JSONArray messages, String role) {
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message != null && role.equals(MessageProtocol.role(message))) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfHint(JSONArray messages) {
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message != null && message.optBoolean("_synthetic", false)) {
                return i;
            }
        }
        return -1;
    }

    private static String callId(JSONArray messages, int index) {
        JSONObject message = messages.optJSONObject(index);
        JSONArray calls = message == null ? null : message.optJSONArray("tool_calls");
        JSONObject call = calls == null ? null : calls.optJSONObject(0);
        return call == null ? "" : call.optString("id", "");
    }

    private static void check(boolean ok, String label) {
        checked++;
        if (!ok) {
            throw new IllegalStateException("FAIL: " + label);
        }
    }

    private SequenceFixTest() {
    }
}
