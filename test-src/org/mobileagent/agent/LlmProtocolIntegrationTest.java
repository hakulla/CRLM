package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;

public final class LlmProtocolIntegrationTest {
    public static void main(String[] args) throws Exception {
        String key = System.getenv("NEWAPI_KEY");
        if (key == null || key.trim().isEmpty()) {
            System.out.println("LLM_PROTOCOL_INTEGRATION_SKIPPED");
            return;
        }
        run("coding-anthropic/claude-haiku-5.5", ModelProtocols.MESSAGES, key);
        run("gpt-6.1", ModelProtocols.RESPONSES, key);
        System.out.println("LLM_PROTOCOL_INTEGRATION_OK");
    }

    private static void run(String model, String protocol, String key) throws Exception {
        Conversation settings = new Conversation();
        settings.baseUrl = "https://api.example.com";
        settings.model = model;
        settings.maxTokens = 8192;
        settings.temperature = 0.2f;
        settings.thinking = AppPrefs.THINKING_ON;
        settings.capTools = true;
        settings.capVision = true;
        settings.capThinking = true;
        settings.capEffort = "high";
        settings.protocol = protocol;
        settings.protocolAuto = false;

        JSONArray messages = new JSONArray();
        messages.put(message("system", "Call the ping tool before answering."));
        messages.put(message("user", "Call the ping tool now, then tell me the result."));
        JSONArray tools = tools();
        LlmClient client = new LlmClient();
        final StringBuilder streamed = new StringBuilder();
        LlmClient.Result first = client.chatStream(messages, tools, key, settings,
                new LlmClient.StreamListener() {
                    @Override
                    public void onContent(String delta) {
                        if (delta != null) streamed.append(delta);
                    }

                    @Override
                    public void onReasoning(String delta) {
                    }
                });
        require(first.error == null, protocol + " first error: " + first.error);
        JSONArray calls = first.message.optJSONArray("tool_calls");
        require(calls != null && calls.length() > 0,
                protocol + " did not call tool; content="
                        + first.message.optString("content", ""));
        JSONObject call = calls.optJSONObject(0);
        String callId = call.optString("id", "");
        String name = call.optJSONObject("function").optString("name", "");
        require("ping".equals(name), protocol + " wrong tool: " + name);
        System.out.println(protocol + " first tool=" + callId + " streamed=" + streamed.length());

        messages.put(first.message);
        JSONObject tool = message("tool", "PONG");
        tool.put("tool_call_id", callId);
        messages.put(tool);
        LlmClient.Result second = client.chatStream(messages, tools, key, settings,
                new LlmClient.StreamListener() {
                    @Override
                    public void onContent(String delta) {
                    }

                    @Override
                    public void onReasoning(String delta) {
                    }
                });
        require(second.error == null, protocol + " second error: " + second.error);
        String text = second.message.optString("content", "");
        require(text.toLowerCase().contains("pong"),
                protocol + " did not receive tool result; content=" + text);
        System.out.println(protocol + " second text=" + text.replace('\n', ' '));
    }

    private static JSONArray tools() throws Exception {
        JSONArray tools = new JSONArray();
        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        JSONObject function = new JSONObject();
        function.put("name", "ping");
        function.put("description", "Returns PONG");
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", new JSONObject());
        function.put("parameters", schema);
        tool.put("function", function);
        tools.put(tool);
        return tools;
    }

    private static JSONObject message(String role, String content) throws Exception {
        JSONObject message = new JSONObject();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("FAILED: " + label);
        }
    }
}
