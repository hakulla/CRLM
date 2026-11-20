package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ProtocolCodecTest {
    public static void main(String[] args) throws Exception {
        check(ModelProtocols.MESSAGES.equals(ModelProtocols.infer(
                "coding-anthropic/claude-haiku-5.5")), "infer claude");
        check(ModelProtocols.RESPONSES.equals(ModelProtocols.infer(
                "gpt-6.1")), "infer gpt");
        check(ModelProtocols.RESPONSES.equals(ModelProtocols.infer("o3-mini")), "infer o3");
        check(ModelProtocols.CHAT.equals(ModelProtocols.infer("deepseek-v4-pro")),
                "infer deepseek");

        JSONArray messages = new JSONArray();
        messages.put(message("system", "SYS"));
        messages.put(message("user", "hello"));
        JSONObject assistant = message("assistant", "working");
        JSONArray calls = new JSONArray();
        JSONObject call = new JSONObject();
        call.put("id", "call_1");
        call.put("type", "function");
        JSONObject function = new JSONObject();
        function.put("name", "inspect");
        function.put("arguments", "{\"path\":\"a\"}");
        call.put("function", function);
        calls.put(call);
        assistant.put("tool_calls", calls);
        messages.put(assistant);
        JSONObject tool = message("tool", "ok");
        tool.put("tool_call_id", "call_1");
        messages.put(tool);
        messages.put(message("user", "continue"));

        JSONArray tools = new JSONArray();
        JSONObject toolDef = new JSONObject();
        toolDef.put("type", "function");
        JSONObject toolFunction = new JSONObject();
        toolFunction.put("name", "inspect");
        toolFunction.put("description", "read a file");
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", new JSONObject());
        toolFunction.put("parameters", schema);
        toolDef.put("function", toolFunction);
        tools.put(toolDef);

        JSONObject anthropic = ProtocolCodec.anthropicRequest(messages, tools,
                "claude", 8192, 0.2f, false, true, "high", false);
        check("SYS".equals(anthropic.optString("system")), "anthropic system");
        JSONArray am = anthropic.optJSONArray("messages");
        check(am.length() == 3, "anthropic merged messages: " + am.length());
        check("user".equals(am.optJSONObject(0).optString("role")), "anthropic first user");
        check("assistant".equals(am.optJSONObject(1).optString("role")),
                "anthropic assistant");
        JSONArray assistantBlocks = am.optJSONObject(1).optJSONArray("content");
        check("tool_use".equals(assistantBlocks.optJSONObject(1).optString("type")),
                "anthropic tool_use");
        JSONObject lastUser = am.optJSONObject(2);
        boolean hasResult = false;
        boolean hasContinue = false;
        JSONArray lastBlocks = lastUser.optJSONArray("content");
        for (int i = 0; i < lastBlocks.length(); i++) {
            JSONObject block = lastBlocks.optJSONObject(i);
            if ("tool_result".equals(block.optString("type"))) {
                hasResult = "call_1".equals(block.optString("tool_use_id"));
            }
            if ("text".equals(block.optString("type"))
                    && "continue".equals(block.optString("text"))) {
                hasContinue = true;
            }
        }
        check(hasResult && hasContinue, "anthropic tool_result + text merged");
        check(anthropic.optJSONArray("tools").optJSONObject(0).optJSONObject("input_schema") != null,
                "anthropic tool schema");

        JSONObject responses = ProtocolCodec.responsesRequest(messages, tools,
                "gpt", 8192, 0.2f, false, true, "high", false, false);
        check("SYS".equals(responses.optString("instructions")), "responses instructions");
        JSONArray input = responses.optJSONArray("input");
        boolean hasFunctionCall = false;
        boolean hasFunctionOutput = false;
        for (int i = 0; i < input.length(); i++) {
            JSONObject item = input.optJSONObject(i);
            if ("function_call".equals(item.optString("type"))) {
                hasFunctionCall = "call_1".equals(item.optString("call_id"));
            }
            if ("function_call_output".equals(item.optString("type"))) {
                hasFunctionOutput = "call_1".equals(item.optString("call_id"));
            }
        }
        check(hasFunctionCall && hasFunctionOutput, "responses tool loop items");
        check("inspect".equals(responses.optJSONArray("tools").optJSONObject(0)
                .optString("name")), "responses flat tool schema");

        JSONObject anthropicResponse = new JSONObject();
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "thinking")
                .put("thinking", "plan").put("signature", "sig"));
        content.put(new JSONObject().put("type", "text").put("text", "hi"));
        content.put(new JSONObject().put("type", "tool_use")
                .put("id", "t1").put("name", "ping")
                .put("input", new JSONObject().put("x", 1)));
        anthropicResponse.put("content", content);
        JSONObject anthropicMessage = ProtocolCodec.anthropicMessage(anthropicResponse,
                "tool_use");
        check("hi".equals(anthropicMessage.optString("content")), "anthropic text");
        check("plan".equals(anthropicMessage.optString("reasoning_content")),
                "anthropic reasoning");
        check("t1".equals(anthropicMessage.optJSONArray("tool_calls").optJSONObject(0)
                .optString("id")), "anthropic call id");
        check(anthropicMessage.optJSONArray("_anthropic_content").length() == 3,
                "anthropic raw content");
        check(!ProtocolCodec.anthropicThinkingSafe(messages),
                "thinking unsafe for legacy tool call");
        check(!ProtocolCodec.anthropicThinkingSafe(messagesWithToolCallOnly()),
                "thinking unsafe without signature");
        JSONArray safeHistory = new JSONArray();
        safeHistory.put(message("user", "do"));
        safeHistory.put(anthropicMessage);
        check(ProtocolCodec.anthropicThinkingSafe(safeHistory),
                "thinking safe with signed blocks");

        JSONArray replay = new JSONArray();
        replay.put(message("user", "do"));
        replay.put(anthropicMessage);
        JSONObject toolResult = message("tool", "PONG");
        toolResult.put("tool_call_id", "t1");
        replay.put(toolResult);
        JSONObject replayRequest = ProtocolCodec.anthropicRequest(replay, tools,
                "claude", 8192, 0.2f, true, true, "high", false);
        JSONArray replayAssistant = replayRequest.optJSONArray("messages")
                .optJSONObject(1).optJSONArray("content");
        check("thinking".equals(replayAssistant.optJSONObject(0).optString("type")),
                "anthropic replay thinking");
        check(replayRequest.has("thinking"), "anthropic thinking enabled");

        JSONObject responsesResponse = new JSONObject();
        responsesResponse.put("status", "completed");
        JSONArray output = new JSONArray();
        output.put(new JSONObject().put("type", "reasoning")
                .put("summary", new JSONArray().put(new JSONObject()
                        .put("type", "summary_text").put("text", "think"))));
        output.put(new JSONObject().put("type", "message").put("role", "assistant")
                .put("content", new JSONArray().put(new JSONObject()
                        .put("type", "output_text").put("text", "hello"))));
        output.put(new JSONObject().put("type", "function_call")
                .put("call_id", "c1").put("name", "ping").put("arguments", "{}"));
        responsesResponse.put("output", output);
        JSONObject responsesMessage = ProtocolCodec.responsesMessage(responsesResponse);
        check("hello".equals(responsesMessage.optString("content")), "responses text");
        check("think".equals(responsesMessage.optString("reasoning_content")),
                "responses reasoning");
        check("c1".equals(responsesMessage.optJSONArray("tool_calls").optJSONObject(0)
                .optString("id")), "responses call id");
        check(responsesMessage.optJSONArray("_responses_output").length() == 3,
                "responses raw output");

        JSONArray responsesReplay = new JSONArray();
        responsesReplay.put(message("user", "do"));
        responsesReplay.put(responsesMessage);
        JSONObject responseTool = message("tool", "PONG");
        responseTool.put("tool_call_id", "c1");
        responsesReplay.put(responseTool);
        JSONObject responseReplayRequest = ProtocolCodec.responsesRequest(responsesReplay,
                tools, "gpt", 8192, 0.2f, true, true, "high", false, true);
        JSONArray replayInput = responseReplayRequest.optJSONArray("input");
        boolean replayHasReasoning = false;
        boolean replayHasCall = false;
        boolean replayHasOutput = false;
        for (int i = 0; i < replayInput.length(); i++) {
            JSONObject item = replayInput.optJSONObject(i);
            if ("reasoning".equals(item.optString("type"))) replayHasReasoning = true;
            if ("function_call".equals(item.optString("type"))) replayHasCall = true;
            if ("function_call_output".equals(item.optString("type"))) replayHasOutput = true;
        }
        check(replayHasReasoning && replayHasCall && replayHasOutput,
                "responses raw replay items");
        check(responseReplayRequest.optJSONObject("reasoning") != null,
                "responses reasoning enabled");

        System.out.println("PROTOCOL_CODEC_TEST_OK");
    }

    private static JSONArray messagesWithToolCallOnly() throws Exception {
        JSONArray messages = new JSONArray();
        JSONObject assistant = message("assistant", "working");
        JSONArray calls = new JSONArray();
        JSONObject call = new JSONObject();
        call.put("id", "call_1");
        call.put("type", "function");
        call.put("function", new JSONObject().put("name", "inspect")
                .put("arguments", "{}"));
        calls.put(call);
        assistant.put("tool_calls", calls);
        messages.put(assistant);
        return messages;
    }

    private static JSONObject message(String role, String content) throws Exception {
        JSONObject message = new JSONObject();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("FAILED: " + label);
        }
    }
}
