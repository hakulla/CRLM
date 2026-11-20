package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The tool call and the result match repair must follow two bottom lines: the call that is in progress and the result is not returned can be written
 * 'Interrupted'The result of the false results; the actual result must be replaced by the seating result and cannot be discarded as isolated information.
 * If you break either, the model will see a tool that works well.'Interrupted', And then we'll repeat our work.
 */
public final class ToolSequenceRepairTest {

    private static int checked;

    public static void main(String[] args) throws Exception {
        inFlightTailStaysOpen();
        realResultReplacesStub();
        midListGapStillStubbed();
        orphanResultDropped();
        repairedHistoryValidates();
        System.out.println("ToolSequenceRepairTest PASS (" + checked + " assertions)");
    }

    /** The resulting calls are left open and are occupied by temporary repairs before they are sent, which cannot be written into history. */
    private static void inFlightTailStaysOpen() throws Exception {
        JSONArray history = new JSONArray();
        history.put(message("system", "SYS"));
        history.put(message("user", "Change it."));
        history.put(assistant("call_A"));
        JSONArray fixed = JsonText.repairToolSequence(history);
        check(fixed.length() == 3, "Call in flight should not be replaced by 4 Results of false statements, actual " + fixed.length());
        check("assistant".equals(MessageProtocol.role(fixed.optJSONObject(2))), "The end is still the same. assistant Call");
    }

    /** The result of the placeholder is left behind, the real result is retained. */
    private static void realResultReplacesStub() throws Exception {
        JSONArray history = new JSONArray();
        history.put(message("system", "SYS"));
        history.put(assistant("call_A"));
        history.put(tool("call_A", "[The historical record lacks the results of this tool call and is automatically completed; if you need a real result, re-call the tool]"));
        history.put(tool("call_A", "{\"ok\":true,\"data\":\"Edited Foo.java\"}"));
        JSONArray fixed = JsonText.repairToolSequence(history);
        check(countTool(fixed, "call_A") == 1, "The same call can only be made with one result, actually. "
                + countTool(fixed, "call_A"));
        check(contentOf(fixed, "call_A").contains("Edited Foo.java"),
                "The real results cannot be squeezed out by sub-optimal positions, actually:" + contentOf(fixed, "call_A"));
    }

    /** The real historical gap (recalled later) still needs to be filled. API Direct 400.  */
    private static void midListGapStillStubbed() throws Exception {
        JSONArray history = new JSONArray();
        history.put(message("system", "SYS"));
        history.put(assistant("call_A"));
        history.put(message("user", "Go on."));
        JSONArray fixed = JsonText.repairToolSequence(history);
        check(countTool(fixed, "call_A") == 1, "I'll need a seat for the suspended air conditioner left behind.");
        check(MessageProtocol.role(fixed.optJSONObject(2)).equals("tool"), "You need to keep up with the call.");
    }

    /** The result of the lack of a corresponding tool for calling is still discarded. */
    private static void orphanResultDropped() throws Exception {
        JSONArray history = new JSONArray();
        history.put(message("system", "SYS"));
        history.put(tool("call_GHOST", "No one called."));
        history.put(message("user", "Hello."));
        JSONArray fixed = JsonText.repairToolSequence(history);
        check(countTool(fixed, "call_GHOST") == 0, "The result of the isolation tool should be discarded");
    }

    /** The completed history must be verified by agreement after provisional completion before it is sent, otherwise the next round will be blocked. */
    private static void repairedHistoryValidates() throws Exception {
        JSONArray history = new JSONArray();
        history.put(message("system", "SYS"));
        history.put(assistant("call_A"));
        history.put(message("user", "Go on."));
        history.put(assistant("call_B"));
        JSONArray stored = JsonText.repairToolSequence(history);
        check(MessageProtocol.validate(MessageProtocol.repairDanglingCalls(stored)) == null,
                "Archiving Rehabilitation + After completion before sending, pass validate()");
        check(countTool(stored, "call_B") == 0, "The last call in flight doesn't go into the archive.");
    }

    private static JSONObject message(String role, String content) throws Exception {
        JSONObject object = new JSONObject();
        object.put("role", role);
        object.put("content", content);
        return object;
    }

    private static JSONObject assistant(String callId) throws Exception {
        JSONObject object = message("assistant", "");
        JSONArray calls = new JSONArray();
        JSONObject call = new JSONObject();
        call.put("id", callId);
        call.put("type", "function");
        JSONObject function = new JSONObject();
        function.put("name", "edit");
        function.put("arguments", "{}");
        call.put("function", function);
        calls.put(call);
        object.put("tool_calls", calls);
        return object;
    }

    private static JSONObject tool(String callId, String content) throws Exception {
        JSONObject object = message("tool", content);
        object.put("tool_call_id", callId);
        return object;
    }

    private static int countTool(JSONArray messages, String callId) {
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

    private static String contentOf(JSONArray messages, String callId) {
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message != null && "tool".equals(MessageProtocol.role(message))
                    && callId.equals(message.optString("tool_call_id", ""))) {
                return message.optString("content", "");
            }
        }
        return "";
    }

    private static void check(boolean ok, String label) {
        checked++;
        if (!ok) {
            throw new IllegalStateException("FAIL: " + label);
        }
    }

    private ToolSequenceRepairTest() {
    }
}
