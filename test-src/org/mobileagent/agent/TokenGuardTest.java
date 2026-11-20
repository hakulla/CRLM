package org.mobileagent.agent;

import org.json.JSONObject;

import java.lang.reflect.Method;

public final class TokenGuardTest {
    public static void main(String[] args) throws Exception {
        Method readOnly = AgentEngine.class.getDeclaredMethod("isReadOnlyToolCall",
                String.class, String.class);
        readOnly.setAccessible(true);
        check(((Boolean) readOnly.invoke(null, "run",
                        "{\"command\":\"cd x && awk 'NR>=1' Conversation.java\"}")),
                "run awk source is read-only");
        check(((Boolean) readOnly.invoke(null, "run",
                        "{\"command\":\"cd x && grep -n Summary Conversation.java\"}")),
                "run grep source is read-only");
        check(!((Boolean) readOnly.invoke(null, "run",
                        "{\"command\":\"build_apk project=x\"}")),
                "build is not read-only");
        check(((Boolean) readOnly.invoke(null, "inspect",
                        "{\"mode\":\"read\",\"path\":\"Conversation.java\"}")),
                "inspect is read-only");
        check(!((Boolean) readOnly.invoke(null, "edit",
                        "{\"path\":\"Conversation.java\"}")),
                "edit is not read-only");

        Method shellGuard = AgentEngine.class.getDeclaredMethod("guardShellSourceRead",
                String.class);
        shellGuard.setAccessible(true);
        check(shellGuard.invoke(null,
                "cd /x && awk 'NR>=1 && NR<=20' Conversation.java") != null,
                "block awk source read");
        check(shellGuard.invoke(null,
                "cd /x && sed -i 's/a/b/' Conversation.java") != null,
                "block sed -i source edit");
        check(shellGuard.invoke(null,
                "cd /x && grep -n Summary Conversation.java") == null,
                "allow grep source search");
        check(shellGuard.invoke(null, "cat README.md") == null,
                "allow non-source cat");

        String compact = ToolDefs.compactSystemPrompt();
        String full = ToolDefs.systemPrompt();
        check(compact.length() < full.length(),
                "compact prompt is shorter: " + compact.length() + " vs " + full.length());

        JSONObject legacy = new JSONObject("{\"id\":\"guard_test\",\"version\":1,"
                + "\"title\":\"kept\",\"messages\":[],\"entries\":[]}");
        Conversation conversation = Conversation.fromJson(legacy);
        check(conversation.workingSet.length() == 0, "empty working set");
        conversation.workingSet.put(new JSONObject().put("kind", "Read")
                .put("path", "Conversation.java").put("detail", "Summary"));
        JSONObject json = conversation.toJson();
        check(json.getJSONArray("workingSet").length() == 1, "working set persisted");
        Conversation restored = Conversation.fromJson(json);
        check(restored.workingSet.length() == 1, "working set round trip");

        System.out.println("TOKEN_GUARD_TEST_OK");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("FAILED: " + label);
        }
    }
}
