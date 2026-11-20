package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Stateless wire-format conversion between the agent's internal chat messages and vendor APIs. */
final class ProtocolCodec {
    private ProtocolCodec() {
    }

    static String systemText(JSONArray messages) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null || !"system".equals(message.optString("role", ""))) {
                continue;
            }
            String text = contentText(message.opt("content"));
            if (text.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(text);
        }
        return builder.toString();
    }

    static String contentText(Object content) {
        if (content == null || content == JSONObject.NULL) {
            return "";
        }
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof JSONObject) {
            JSONObject object = (JSONObject) content;
            String text = object.optString("text", "");
            if (!text.isEmpty()) {
                return text;
            }
            return object.optString("content", "");
        }
        if (content instanceof JSONArray) {
            JSONArray parts = (JSONArray) content;
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                Object part = parts.opt(i);
                if (part instanceof String) {
                    builder.append((String) part);
                } else if (part instanceof JSONObject) {
                    JSONObject object = (JSONObject) part;
                    String type = object.optString("type", "");
                    if ("text".equals(type) || "input_text".equals(type)
                            || "output_text".equals(type)) {
                        builder.append(object.optString("text", ""));
                    } else if ("image_url".equals(type) || "input_image".equals(type)) {
                        builder.append("[Picture]");
                    } else {
                        builder.append(object.optString("text", ""));
                    }
                }
            }
            return builder.toString();
        }
        return String.valueOf(content);
    }

    static JSONArray copyArray(JSONArray source) {
        if (source == null) {
            return new JSONArray();
        }
        try {
            return new JSONArray(source.toString());
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static JSONObject copyObject(JSONObject source) {
        if (source == null) {
            return new JSONObject();
        }
        try {
            return new JSONObject(source.toString());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ---------------- Anthropic Messages ----------------

    static JSONObject anthropicRequest(JSONArray messages, JSONArray tools, String model,
                                       int maxTokens, float temperature, boolean thinking,
                                       boolean capTools, String effort, boolean stream) {
        JSONObject body = new JSONObject();
        try {
            body.put("model", model);
            body.put("max_tokens", Math.max(1024, maxTokens));
            String system = systemText(messages);
            if (!system.isEmpty()) {
                body.put("system", system);
            }
            JSONArray converted = anthropicMessages(messages);
            if (converted.length() == 0) {
                converted.put(new JSONObject().put("role", "user")
                        .put("content", textBlocks("(Empty)")));
            }
            body.put("messages", converted);
            if (capTools && tools != null && tools.length() > 0) {
                body.put("tools", anthropicTools(tools));
                body.put("tool_choice", new JSONObject().put("type", "auto"));
            }
            if (thinking) {
                int budget = Math.max(1024, Math.min(4096, Math.max(1024, maxTokens - 512)));
                if (budget >= 1024) {
                    body.put("thinking", new JSONObject()
                            .put("type", "enabled")
                            .put("budget_tokens", budget));
                }
            }
            body.put("stream", stream);
        } catch (Exception ignored) {
        }
        return body;
    }

    private static JSONArray anthropicTools(JSONArray tools) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            JSONObject function = tool == null ? null : tool.optJSONObject("function");
            if (function == null) {
                continue;
            }
            String name = function.optString("name", "");
            if (name.isEmpty()) {
                continue;
            }
            JSONObject item = new JSONObject();
            try {
                item.put("name", name);
                item.put("description", function.optString("description", ""));
                JSONObject schema = function.optJSONObject("parameters");
                if (schema == null) {
                    schema = new JSONObject().put("type", "object")
                            .put("properties", new JSONObject());
                }
                item.put("input_schema", schema);
            } catch (Exception ignored) {
            }
            result.put(item);
        }
        return result;
    }

    private static JSONArray anthropicMessages(JSONArray messages) {
        JSONArray out = new JSONArray();
        JSONArray pendingToolResults = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) {
                continue;
            }
            String role = message.optString("role", "");
            if ("system".equals(role)) {
                continue;
            }
            if ("tool".equals(role)) {
                JSONObject block = new JSONObject();
                try {
                    block.put("type", "tool_result");
                    block.put("tool_use_id", message.optString("tool_call_id", ""));
                    String text = contentText(message.opt("content"));
                    block.put("content", text.isEmpty() ? "(Empty)" : text);
                } catch (Exception ignored) {
                }
                pendingToolResults.put(block);
                continue;
            }
            if (pendingToolResults.length() > 0) {
                addAnthropicMessage(out, "user", pendingToolResults);
                pendingToolResults = new JSONArray();
            }
            if ("assistant".equals(role)) {
                addAnthropicMessage(out, "assistant", anthropicAssistantBlocks(message));
            } else {
                addAnthropicMessage(out, "user",
                        anthropicContentBlocks(message.opt("content")));
            }
        }
        if (pendingToolResults.length() > 0) {
            addAnthropicMessage(out, "user", pendingToolResults);
        }
        if (out.length() == 0) {
            addAnthropicMessage(out, "user", textBlocks("(Empty)"));
        }
        JSONObject first = out.optJSONObject(0);
        if (first != null && "assistant".equals(first.optString("role", ""))) {
            JSONArray fixed = new JSONArray();
            JSONObject opening = new JSONObject();
            try {
                opening.put("role", "user");
                opening.put("content", textBlocks("(Go on.)"));
            } catch (Exception ignored) {
            }
            fixed.put(opening);
            for (int i = 0; i < out.length(); i++) {
                fixed.put(out.opt(i));
            }
            out = fixed;
        }
        return out;
    }

    private static void addAnthropicMessage(JSONArray out, String role, JSONArray blocks) {
        if (blocks == null || blocks.length() == 0) {
            return;
        }
        JSONObject last = out.length() == 0 ? null : out.optJSONObject(out.length() - 1);
        if (last != null && role.equals(last.optString("role", ""))) {
            JSONArray existing = last.optJSONArray("content");
            if (existing == null) {
                existing = new JSONArray();
                try {
                    last.put("content", existing);
                } catch (Exception ignored) {
                }
            }
            for (int i = 0; i < blocks.length(); i++) {
                existing.put(blocks.opt(i));
            }
            return;
        }
        JSONObject message = new JSONObject();
        try {
            message.put("role", role);
            message.put("content", blocks);
        } catch (Exception ignored) {
        }
        out.put(message);
    }

    private static JSONArray anthropicAssistantBlocks(JSONObject message) {
        JSONArray raw = message.optJSONArray("_anthropic_content");
        if (raw != null && raw.length() > 0) {
            return copyArray(raw);
        }
        JSONArray blocks = new JSONArray();
        String text = contentText(message.opt("content"));
        if (!text.isEmpty()) {
            blocks.put(textBlock(text));
        }
        JSONArray calls = message.optJSONArray("tool_calls");
        if (calls != null) {
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.optJSONObject(i);
                if (call == null) {
                    continue;
                }
                JSONObject function = call.optJSONObject("function");
                if (function == null) {
                    continue;
                }
                JSONObject block = new JSONObject();
                try {
                    block.put("type", "tool_use");
                    block.put("id", call.optString("id", ""));
                    block.put("name", function.optString("name", ""));
                    block.put("input", parseObject(function.optString("arguments", "")));
                } catch (Exception ignored) {
                }
                blocks.put(block);
            }
        }
        if (blocks.length() == 0) {
            blocks.put(textBlock("(Empty)"));
        }
        return blocks;
    }

    private static JSONArray anthropicContentBlocks(Object content) {
        JSONArray blocks = new JSONArray();
        if (content instanceof JSONArray) {
            JSONArray parts = (JSONArray) content;
            for (int i = 0; i < parts.length(); i++) {
                Object value = parts.opt(i);
                if (!(value instanceof JSONObject)) {
                    if (value != null && value != JSONObject.NULL) {
                        blocks.put(textBlock(String.valueOf(value)));
                    }
                    continue;
                }
                JSONObject part = (JSONObject) value;
                String type = part.optString("type", "");
                if ("image_url".equals(type) || "input_image".equals(type)) {
                    String url = imageUrl(part);
                    if (!url.isEmpty()) {
                        blocks.put(anthropicImageBlock(url));
                    }
                } else {
                    String text = part.optString("text", "");
                    if (!text.isEmpty()) {
                        blocks.put(textBlock(text));
                    }
                }
            }
        } else {
            String text = contentText(content);
            if (!text.isEmpty()) {
                blocks.put(textBlock(text));
            }
        }
        if (blocks.length() == 0) {
            blocks.put(textBlock("(Empty)"));
        }
        return blocks;
    }

    private static JSONObject textBlock(String text) {
        JSONObject block = new JSONObject();
        try {
            block.put("type", "text");
            block.put("text", text == null ? "" : text);
        } catch (Exception ignored) {
        }
        return block;
    }

    private static JSONArray textBlocks(String text) {
        JSONArray blocks = new JSONArray();
        blocks.put(textBlock(text));
        return blocks;
    }

    private static JSONObject anthropicImageBlock(String url) {
        JSONObject block = new JSONObject();
        JSONObject source = new JSONObject();
        try {
            if (url.startsWith("data:")) {
                int comma = url.indexOf(',');
                int semicolon = url.indexOf(';');
                String media = semicolon > 5 && comma > semicolon
                        ? url.substring(5, semicolon) : "image/jpeg";
                String data = comma > 0 ? url.substring(comma + 1) : "";
                source.put("type", "base64");
                source.put("media_type", media);
                source.put("data", data);
            } else {
                source.put("type", "url");
                source.put("url", url);
            }
            block.put("type", "image");
            block.put("source", source);
        } catch (Exception ignored) {
        }
        return block;
    }

    private static String imageUrl(JSONObject part) {
        Object image = part.opt("image_url");
        if (image instanceof JSONObject) {
            return ((JSONObject) image).optString("url", "");
        }
        if (image instanceof String) {
            return (String) image;
        }
        return part.optString("url", "");
    }

    static JSONObject anthropicMessage(JSONObject response, String stopReason) {
        JSONArray content = response == null ? null : response.optJSONArray("content");
        if (content == null && response != null && response.opt("content") instanceof String) {
            content = textBlocks(response.optString("content", ""));
        }
        return anthropicMessageFromBlocks(content == null ? new JSONArray() : content, stopReason);
    }

    static JSONObject anthropicMessageFromBlocks(JSONArray content, String stopReason) {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        JSONArray toolCalls = new JSONArray();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) {
                continue;
            }
            String type = block.optString("type", "");
            if ("text".equals(type)) {
                text.append(block.optString("text", ""));
            } else if ("thinking".equals(type)) {
                reasoning.append(block.optString("thinking", ""));
            } else if ("tool_use".equals(type)) {
                JSONObject call = new JSONObject();
                JSONObject function = new JSONObject();
                try {
                    call.put("id", block.optString("id", ""));
                    call.put("type", "function");
                    function.put("name", block.optString("name", ""));
                    Object input = block.opt("input");
                    function.put("arguments", input == null || input == JSONObject.NULL
                            ? "{}" : String.valueOf(input));
                    call.put("function", function);
                } catch (Exception ignored) {
                }
                toolCalls.put(call);
            }
        }
        JSONObject message = new JSONObject();
        try {
            message.put("role", "assistant");
            if (text.length() > 0) {
                message.put("content", text.toString());
            } else {
                message.put("content", toolCalls.length() > 0 ? JSONObject.NULL : "");
            }
            if (reasoning.length() > 0) {
                message.put("reasoning_content", reasoning.toString());
            }
            if (toolCalls.length() > 0) {
                message.put("tool_calls", toolCalls);
            }
            message.put("_anthropic_content", copyArray(content));
            if (stopReason != null) {
                message.put("_stop_reason", stopReason);
            }
        } catch (Exception ignored) {
        }
        return message;
    }

    static boolean anthropicThinkingSafe(JSONArray messages) {
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null || !"assistant".equals(message.optString("role", ""))) {
                continue;
            }
            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls == null || calls.length() == 0) {
                continue;
            }
            JSONArray raw = message.optJSONArray("_anthropic_content");
            boolean hasThinking = false;
            if (raw != null) {
                for (int b = 0; b < raw.length(); b++) {
                    JSONObject block = raw.optJSONObject(b);
                    if (block != null && ("thinking".equals(block.optString("type", ""))
                            || "redacted_thinking".equals(block.optString("type", "")))) {
                        hasThinking = true;
                        break;
                    }
                }
            }
            if (!hasThinking) {
                return false;
            }
        }
        return true;
    }

    // ---------------- OpenAI Responses ----------------

    static JSONObject responsesRequest(JSONArray messages, JSONArray tools, String model,
                                       int maxTokens, float temperature, boolean thinking,
                                       boolean capTools, String effort, boolean stream,
                                       boolean includeEncryptedReasoning) {
        JSONObject body = new JSONObject();
        try {
            body.put("model", model);
            String instructions = systemText(messages);
            if (!instructions.isEmpty()) {
                body.put("instructions", instructions);
            }
            JSONArray input = responsesInput(messages);
            if (input.length() == 0) {
                input.put(new JSONObject().put("role", "user")
                        .put("content", responsesTextBlocks("(Empty)")));
            }
            body.put("input", input);
            body.put("max_output_tokens", Math.max(256, maxTokens));
            body.put("store", false);
            if (capTools && tools != null && tools.length() > 0) {
                body.put("tools", responsesTools(tools));
                body.put("tool_choice", "auto");
            }
            if (thinking) {
                JSONObject reasoning = new JSONObject();
                reasoning.put("effort", responsesEffort(effort));
                reasoning.put("summary", "auto");
                body.put("reasoning", reasoning);
                if (includeEncryptedReasoning) {
                    body.put("include", new JSONArray().put("reasoning.encrypted_content"));
                }
            }
            body.put("stream", stream);
        } catch (Exception ignored) {
        }
        return body;
    }

    private static String responsesEffort(String effort) {
        String normalized = ModelProfile.normalizeEffort(effort);
        return "xhigh".equals(normalized) ? "high" : normalized;
    }

    private static JSONArray responsesTools(JSONArray tools) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            JSONObject function = tool == null ? null : tool.optJSONObject("function");
            if (function == null) {
                continue;
            }
            String name = function.optString("name", "");
            if (name.isEmpty()) {
                continue;
            }
            JSONObject item = new JSONObject();
            try {
                item.put("type", "function");
                item.put("name", name);
                item.put("description", function.optString("description", ""));
                JSONObject schema = function.optJSONObject("parameters");
                item.put("parameters", schema == null
                        ? new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()) : schema);
            } catch (Exception ignored) {
            }
            result.put(item);
        }
        return result;
    }

    private static JSONArray responsesInput(JSONArray messages) {
        JSONArray input = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) {
                continue;
            }
            String role = message.optString("role", "");
            if ("system".equals(role)) {
                continue;
            }
            if ("tool".equals(role)) {
                JSONObject item = new JSONObject();
                try {
                    item.put("type", "function_call_output");
                    item.put("call_id", message.optString("tool_call_id", ""));
                    String text = contentText(message.opt("content"));
                    item.put("output", text.isEmpty() ? "(Empty)" : text);
                } catch (Exception ignored) {
                }
                input.put(item);
                continue;
            }
            if ("assistant".equals(role)) {
                JSONArray raw = message.optJSONArray("_responses_output");
                if (raw != null && raw.length() > 0) {
                    for (int r = 0; r < raw.length(); r++) {
                        input.put(copyObject(raw.optJSONObject(r)));
                    }
                    continue;
                }
                String text = contentText(message.opt("content"));
                if (!text.isEmpty()) {
                    JSONObject item = new JSONObject();
                    try {
                        item.put("type", "message");
                        item.put("role", "assistant");
                        item.put("content", responsesOutputTextBlocks(text));
                    } catch (Exception ignored) {
                    }
                    input.put(item);
                }
                JSONArray calls = message.optJSONArray("tool_calls");
                if (calls != null) {
                    for (int c = 0; c < calls.length(); c++) {
                        JSONObject call = calls.optJSONObject(c);
                        JSONObject function = call == null ? null
                                : call.optJSONObject("function");
                        if (function == null) {
                            continue;
                        }
                        JSONObject item = new JSONObject();
                        try {
                            item.put("type", "function_call");
                            item.put("call_id", call.optString("id", ""));
                            item.put("name", function.optString("name", ""));
                            item.put("arguments", function.optString("arguments", "{}"));
                        } catch (Exception ignored) {
                        }
                        input.put(item);
                    }
                }
                continue;
            }
            JSONObject item = new JSONObject();
            try {
                item.put("role", "user");
                item.put("content", responsesInputBlocks(message.opt("content")));
            } catch (Exception ignored) {
            }
            input.put(item);
        }
        return input;
    }

    private static JSONArray responsesInputBlocks(Object content) {
        JSONArray blocks = new JSONArray();
        if (content instanceof JSONArray) {
            JSONArray parts = (JSONArray) content;
            for (int i = 0; i < parts.length(); i++) {
                Object value = parts.opt(i);
                if (!(value instanceof JSONObject)) {
                    if (value != null && value != JSONObject.NULL) {
                        blocks.put(responsesInputText(String.valueOf(value)));
                    }
                    continue;
                }
                JSONObject part = (JSONObject) value;
                String type = part.optString("type", "");
                if ("image_url".equals(type) || "input_image".equals(type)) {
                    String url = imageUrl(part);
                    if (!url.isEmpty()) {
                        try {
                            blocks.put(new JSONObject().put("type", "input_image")
                                    .put("image_url", url));
                        } catch (Exception ignored) {
                        }
                    }
                } else {
                    String text = part.optString("text", "");
                    if (!text.isEmpty()) {
                        blocks.put(responsesInputText(text));
                    }
                }
            }
        } else {
            String text = contentText(content);
            if (!text.isEmpty()) {
                blocks.put(responsesInputText(text));
            }
        }
        if (blocks.length() == 0) {
            blocks.put(responsesInputText("(Empty)"));
        }
        return blocks;
    }

    private static JSONObject responsesInputText(String text) {
        JSONObject block = new JSONObject();
        try {
            block.put("type", "input_text");
            block.put("text", text == null ? "" : text);
        } catch (Exception ignored) {
        }
        return block;
    }

    private static JSONArray responsesTextBlocks(String text) {
        JSONArray blocks = new JSONArray();
        blocks.put(responsesInputText(text));
        return blocks;
    }

    private static JSONArray responsesOutputTextBlocks(String text) {
        JSONArray blocks = new JSONArray();
        try {
            blocks.put(new JSONObject().put("type", "output_text")
                    .put("text", text == null ? "" : text));
        } catch (Exception ignored) {
        }
        return blocks;
    }

    static JSONObject responsesMessage(JSONObject response) {
        JSONArray output = response == null ? null : response.optJSONArray("output");
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        JSONArray toolCalls = new JSONArray();
        if (output == null && response != null) {
            String direct = response.optString("output_text", "");
            if (!direct.isEmpty()) {
                text.append(direct);
            }
        }
        if (output != null) {
            for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String type = item.optString("type", "");
                if ("message".equals(type)) {
                    JSONArray content = item.optJSONArray("content");
                    if (content != null) {
                        for (int c = 0; c < content.length(); c++) {
                            JSONObject part = content.optJSONObject(c);
                            if (part != null && "output_text".equals(
                                    part.optString("type", ""))) {
                                text.append(part.optString("text", ""));
                            }
                        }
                    }
                } else if ("function_call".equals(type)) {
                    JSONObject call = new JSONObject();
                    JSONObject function = new JSONObject();
                    try {
                        call.put("id", item.optString("call_id",
                                item.optString("id", "")));
                        call.put("type", "function");
                        function.put("name", item.optString("name", ""));
                        function.put("arguments", item.optString("arguments", "{}"));
                        call.put("function", function);
                    } catch (Exception ignored) {
                    }
                    toolCalls.put(call);
                } else if ("reasoning".equals(type)) {
                    appendReasoning(reasoning, item.optJSONArray("summary"));
                    appendReasoning(reasoning, item.optJSONArray("content"));
                }
            }
        }
        JSONObject message = new JSONObject();
        try {
            message.put("role", "assistant");
            message.put("content", text.length() > 0 ? text.toString()
                    : (toolCalls.length() > 0 ? JSONObject.NULL : ""));
            if (reasoning.length() > 0) {
                message.put("reasoning_content", reasoning.toString());
            }
            if (toolCalls.length() > 0) {
                message.put("tool_calls", toolCalls);
            }
            message.put("_responses_output", copyArray(output));
            String status = response == null ? "" : response.optString("status", "");
            if (!status.isEmpty()) {
                message.put("_stop_reason", status);
            }
            JSONObject incomplete = response == null ? null
                    : response.optJSONObject("incomplete_details");
            if (incomplete != null) {
                message.put("_incomplete_reason", incomplete.optString("reason", ""));
            }
        } catch (Exception ignored) {
        }
        return message;
    }

    private static void appendReasoning(StringBuilder builder, JSONArray parts) {
        if (parts == null) {
            return;
        }
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part != null) {
                String text = part.optString("text", "");
                if (!text.isEmpty()) {
                    builder.append(text);
                }
            }
        }
    }

    static JSONObject parseObject(String text) {
        try {
            return new JSONObject(text == null || text.trim().isEmpty() ? "{}" : text);
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
