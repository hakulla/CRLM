package org.mobileagent.agent;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

final class LlmClient {
    private static final String PROTOCOL_UNSUPPORTED = "PROTOCOL_UNSUPPORTED:";
    private final AppPrefs prefs;

    LlmClient() {
        this(null);
    }

    LlmClient(AppPrefs prefs) {
        this.prefs = prefs;
    }

    interface StreamListener {
        void onContent(String delta);

        void onReasoning(String delta);
    }

    private volatile HttpURLConnection activeConnection;
    private volatile boolean cancelled;

    void cancel() {
        cancelled = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    private volatile String lastFinishReason = "";
    private volatile boolean lastTruncated;

    /** Is the last fluent response due to max_tokens Cut off. */
    boolean wasTruncated() {
        return lastTruncated;
    }

    static final class Result {
        final JSONObject message;
        final String error;
        final boolean retryable;
        final int promptTokens;
        final int completionTokens;
        final int cachedTokens;

        private Result(JSONObject message, String error, boolean retryable,
                       int promptTokens, int completionTokens, int cachedTokens) {
            this.message = message;
            this.error = error;
            this.retryable = retryable;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.cachedTokens = cachedTokens;
        }

        static Result success(JSONObject message) {
            return new Result(message, null, false, 0, 0, 0);
        }

        static Result success(JSONObject message, int promptTokens,
                              int completionTokens, int cachedTokens) {
            return new Result(message, null, false, promptTokens,
                    completionTokens, cachedTokens);
        }

        static Result failure(String error) {
            return new Result(null, error, false, 0, 0, 0);
        }

        static Result failure(String error, boolean retryable) {
            return new Result(null, error, retryable, 0, 0, 0);
        }
    }

    Result chat(JSONArray messages, JSONArray tools, String apiKey,
                Conversation settings) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return Result.failure("Not configured API Key");
        }
        if (settings == null) {
            return Result.failure("Session Settings Not Available");
        }
                if (hasImage(messages) && !settings.capVision) {
            // Images in history news do not block the current cycle: downgrade to text and avoid permanently misreporting the entire session after a map
            degradeImages(messages);
        }
        String protocol = resolveProtocol(settings);
        if (ModelProtocols.MESSAGES.equals(protocol)) {
            Result result = chatAnthropic(messages, tools, apiKey, settings);
            return handleProtocolResult(result, messages, tools, apiKey, settings,
                    false, null);
        }
        if (ModelProtocols.RESPONSES.equals(protocol)) {
            Result result = chatResponses(messages, tools, apiKey, settings);
            return handleProtocolResult(result, messages, tools, apiKey, settings,
                    false, null);
        }
        return chatCompletions(messages, tools, apiKey, settings);
    }

    Result chatStream(JSONArray messages, JSONArray tools, String apiKey,
                      Conversation settings, final StreamListener listener) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return Result.failure("Not configured API Key");
        }
        if (settings == null) {
            return Result.failure("Session Settings Not Available");
        }
                if (hasImage(messages) && !settings.capVision) {
            // Images in history news do not block the current cycle: downgrade to text and avoid permanently misreporting the entire session after a map
            degradeImages(messages);
        }
        if (hasImage(messages)) {
            return chat(messages, tools, apiKey, settings);
        }
        String protocol = resolveProtocol(settings);
        if (ModelProtocols.MESSAGES.equals(protocol)) {
            Result result = chatStreamAnthropic(messages, tools, apiKey, settings, listener);
            return handleProtocolResult(result, messages, tools, apiKey, settings,
                    true, listener);
        }
        if (ModelProtocols.RESPONSES.equals(protocol)) {
            Result result = chatStreamResponses(messages, tools, apiKey, settings, listener);
            return handleProtocolResult(result, messages, tools, apiKey, settings,
                    true, listener);
        }
        return chatStreamCompletions(messages, tools, apiKey, settings, listener);
    }

    private String resolveProtocol(Conversation settings) {
        String protocol = ModelProtocols.normalize(settings.protocol);
        if (!ModelProtocols.AUTO.equals(protocol)) {
            return protocol;
        }
        if (prefs != null) {
            ModelProtocols.Resolved resolved = ModelProtocols.resolve(prefs,
                    settings.baseUrl, settings.model);
            settings.protocol = resolved.protocol;
            settings.protocolAuto = resolved.automatic;
            return resolved.protocol;
        }
        settings.protocol = ModelProtocols.CHAT;
        settings.protocolAuto = true;
        return ModelProtocols.CHAT;
    }

    private Result handleProtocolResult(Result result, JSONArray messages, JSONArray tools,
                                        String apiKey, Conversation settings,
                                        boolean streaming, StreamListener listener) {
        if (result.error != null && result.error.startsWith(PROTOCOL_UNSUPPORTED)) {
            String detail = result.error.substring(PROTOCOL_UNSUPPORTED.length()).trim();
            String old = settings.protocol;
            if (settings.protocolAuto) {
                settings.protocol = ModelProtocols.CHAT;
                if (prefs != null) {
                    prefs.cacheProtocol(settings.baseUrl, settings.model, ModelProtocols.CHAT);
                }
                Log.i("AgentProtocol", "fallback " + old + " -> chat: " + detail);
                return streaming
                        ? chatStreamCompletions(messages, tools, apiKey, settings, listener)
                        : chatCompletions(messages, tools, apiKey, settings);
            }
            return Result.failure("Current service is not supported "
                    + ModelProtocols.label(old) + ": " + detail, false);
        }
        if (result.error == null && prefs != null && settings.protocolAuto
                && !ModelProtocols.CHAT.equals(settings.protocol)) {
            prefs.cacheProtocol(settings.baseUrl, settings.model, settings.protocol);
        }
        return result;
    }

    private Result chatAnthropic(JSONArray messages, JSONArray tools, String apiKey,
                                 Conversation settings) {
        cancelled = false;
        lastTruncated = false;
        lastFinishReason = "";
        Result last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            last = attemptAnthropic(messages, tools, apiKey, settings);
            if (cancelled) {
                return Result.failure("Request cancelled");
            }
            if (last.error == null || !last.retryable || attempt == 3) {
                return last;
            }
            sleepBeforeRetry(attempt);
        }
        return last == null ? Result.failure("Request failed", true) : last;
    }

    private Result attemptAnthropic(JSONArray messages, JSONArray tools, String apiKey,
                                    Conversation settings) {
        HttpURLConnection connection = null;
        try {
            boolean thinking = AppPrefs.THINKING_ON.equals(settings.thinking)
                    && settings.capThinking && settings.maxTokens >= 2048
                    && ProtocolCodec.anthropicThinkingSafe(messages);
            JSONObject body = ProtocolCodec.anthropicRequest(messages, tools,
                    settings.model, settings.maxTokens, settings.temperature,
                    thinking, settings.capTools, settings.capEffort, false);
            connection = openPost(ModelCatalog.anthropicEndpoint(settings.baseUrl),
                    body, apiKey, true, false);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String error = readAll(connection.getErrorStream(), 65536);
                return protocolFailure(code, error);
            }
            String response = readAll(connection.getInputStream(), 4 * 1024 * 1024);
            JSONObject root = new JSONObject(response);
            if (root.has("error") && !root.isNull("error")) {
                    return Result.failure(errorMessage(root));
                }
            String stop = root.optString("stop_reason", "");
            lastFinishReason = stop;
            lastTruncated = "max_tokens".equals(stop);
            JSONObject message = ProtocolCodec.anthropicMessage(root, stop);
            if (emptyMessage(message)) {
                return Result.failure("Model does not return content", true);
            }
            int[] usage = anthropicUsage(root.optJSONObject("usage"));
            return Result.success(message, usage[0], usage[1], usage[2]);
        } catch (IOException e) {
            if (cancelled) {
                return Result.failure("Request cancelled");
            }
            return Result.failure("Network connection failed. Check the network and interface address", true);
        } catch (Exception e) {
            return Result.failure("Anthropic Request failed:" + e.getClass().getSimpleName());
        } finally {
            closeConnection(connection);
        }
    }

    private Result chatStreamAnthropic(JSONArray messages, JSONArray tools, String apiKey,
                                       Conversation settings, final StreamListener listener) {
        cancelled = false;
        lastTruncated = false;
        lastFinishReason = "";
        HttpURLConnection connection = null;
        try {
            boolean thinking = AppPrefs.THINKING_ON.equals(settings.thinking)
                    && settings.capThinking && settings.maxTokens >= 2048
                    && ProtocolCodec.anthropicThinkingSafe(messages);
            JSONObject body = ProtocolCodec.anthropicRequest(messages, tools,
                    settings.model, settings.maxTokens, settings.temperature,
                    thinking, settings.capTools, settings.capEffort, true);
            connection = openPost(ModelCatalog.anthropicEndpoint(settings.baseUrl),
                    body, apiKey, true, true);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String error = readAll(connection.getErrorStream(), 65536);
                return protocolFailure(code, error);
            }

            Map<Integer, AnthropicBlock> active = new TreeMap<Integer, AnthropicBlock>();
            Map<Integer, JSONObject> finished = new TreeMap<Integer, JSONObject>();
            int promptTokens = 0;
            int completionTokens = 0;
            int cachedTokens = 0;
            String stopReason = "";
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                JSONObject event;
                try {
                    event = new JSONObject(data);
                } catch (Exception ignored) {
                    continue;
                }
                String type = event.optString("type", "");
                if ("error".equals(type)) {
                    return Result.failure(errorMessage(event));
                }
                if ("message_start".equals(type)) {
                    JSONObject usage = event.optJSONObject("message");
                    usage = usage == null ? null : usage.optJSONObject("usage");
                    int[] counted = anthropicUsage(usage);
                    promptTokens = counted[0];
                    cachedTokens = counted[2];
                    continue;
                }
                if ("content_block_start".equals(type)) {
                    int index = event.optInt("index", active.size());
                    AnthropicBlock block = new AnthropicBlock();
                    JSONObject start = event.optJSONObject("content_block");
                    if (start != null) {
                        block.type = start.optString("type", "");
                        block.id = start.optString("id", "");
                        block.name = start.optString("name", "");
                        String text = start.optString("text", "");
                        if (!text.isEmpty()) {
                            block.text.append(text);
                        }
                    }
                    active.put(index, block);
                    continue;
                }
                if ("content_block_delta".equals(type)) {
                    int index = event.optInt("index", 0);
                    AnthropicBlock block = active.get(index);
                    if (block == null) {
                        block = new AnthropicBlock();
                        active.put(index, block);
                    }
                    JSONObject delta = event.optJSONObject("delta");
                    if (delta == null) {
                        continue;
                    }
                    String deltaType = delta.optString("type", "");
                    if ("text_delta".equals(deltaType)) {
                        String text = delta.optString("text", "");
                        if (!text.isEmpty()) {
                            block.text.append(text);
                            if (listener != null) {
                                listener.onContent(text);
                            }
                        }
                    } else if ("thinking_delta".equals(deltaType)) {
                        String think = delta.optString("thinking", "");
                        if (!think.isEmpty()) {
                            block.thinking.append(think);
                            if (listener != null) {
                                listener.onReasoning(think);
                            }
                        }
                    } else if ("signature_delta".equals(deltaType)) {
                        block.signature.append(delta.optString("signature", ""));
                    } else if ("input_json_delta".equals(deltaType)) {
                        block.input.append(delta.optString("partial_json", ""));
                    }
                    continue;
                }
                if ("content_block_stop".equals(type)) {
                    int index = event.optInt("index", -1);
                    AnthropicBlock block = active.remove(index);
                    if (block != null) {
                        JSONObject done = block.finish();
                        if (done != null) {
                            finished.put(index, done);
                        }
                    }
                    continue;
                }
                if ("message_delta".equals(type)) {
                    JSONObject delta = event.optJSONObject("delta");
                    if (delta != null) {
                        String stop = delta.optString("stop_reason", "");
                        if (!stop.isEmpty()) {
                            stopReason = stop;
                        }
                    }
                    JSONObject usage = event.optJSONObject("usage");
                    if (usage != null) {
                        completionTokens = usage.optInt("output_tokens", completionTokens);
                        int[] counted = anthropicUsage(usage);
                        if (counted[0] > 0) {
                            promptTokens = counted[0];
                        }
                        if (counted[2] > 0) {
                            cachedTokens = counted[2];
                        }
                    }
                    continue;
                }
                if ("message_stop".equals(type)) {
                    break;
                }
            }
            for (Map.Entry<Integer, AnthropicBlock> entry : active.entrySet()) {
                JSONObject done = entry.getValue().finish();
                if (done != null) {
                    finished.put(entry.getKey(), done);
                }
            }
            JSONArray content = new JSONArray();
            for (JSONObject block : finished.values()) {
                content.put(block);
            }
            JSONObject message = ProtocolCodec.anthropicMessageFromBlocks(content, stopReason);
            if (emptyMessage(message)) {
                return Result.failure("Response stream was closed early", true);
            }
            lastFinishReason = stopReason;
            lastTruncated = "max_tokens".equals(stopReason);
            return Result.success(message, promptTokens, completionTokens, cachedTokens);
        } catch (IOException e) {
            if (cancelled) {
                return Result.failure("Request cancelled");
            }
            return Result.failure("Network connection is down. Check the network.", true);
        } catch (Exception e) {
            if (cancelled) {
                return Result.failure("Request cancelled");
            }
            return Result.failure("Anthropic Stream request failed:"
                    + e.getClass().getSimpleName());
        } finally {
            closeConnection(connection);
        }
    }

    private Result chatResponses(JSONArray messages, JSONArray tools, String apiKey,
                                 Conversation settings) {
        cancelled = false;
        lastTruncated = false;
        lastFinishReason = "";
        Result last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            last = attemptResponses(messages, tools, apiKey, settings);
            if (cancelled) {
                return Result.failure("Request cancelled");
            }
            if (last.error == null || !last.retryable || attempt == 3) {
                return last;
            }
            sleepBeforeRetry(attempt);
        }
        return last == null ? Result.failure("Request failed", true) : last;
    }

    private Result attemptResponses(JSONArray messages, JSONArray tools, String apiKey,
                                    Conversation settings) {
        boolean includeReasoning = true;
        for (int pass = 0; pass < 2; pass++) {
            HttpURLConnection connection = null;
            try {
                boolean thinking = AppPrefs.THINKING_ON.equals(settings.thinking)
                        && settings.capThinking;
                JSONObject body = ProtocolCodec.responsesRequest(messages, tools,
                        settings.model, settings.maxTokens, settings.temperature,
                        thinking, settings.capTools, settings.capEffort, false,
                        includeReasoning && thinking);
                connection = openPost(ModelCatalog.responsesEndpoint(settings.baseUrl),
                        body, apiKey, false, false);
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) {
                    String error = readAll(connection.getErrorStream(), 65536);
                    String lower = error == null ? "" : error.toLowerCase(Locale.ROOT);
                    if (pass == 0 && code == 400 && thinking
                            && (lower.contains("include") || lower.contains("encrypted"))) {
                        includeReasoning = false;
                        continue;
                    }
                    return protocolFailure(code, error);
                }
                String response = readAll(connection.getInputStream(), 4 * 1024 * 1024);
                JSONObject root = new JSONObject(response);
                if (root.has("error") && !root.isNull("error")) {
                    return Result.failure(errorMessage(root));
                }
                String status = root.optString("status", "");
                JSONObject incomplete = root.optJSONObject("incomplete_details");
                String reason = incomplete == null ? "" : incomplete.optString("reason", "");
                lastFinishReason = status;
                lastTruncated = "incomplete".equals(status)
                        && ("max_output_tokens".equals(reason)
                        || "max_tokens".equals(reason));
                JSONObject message = ProtocolCodec.responsesMessage(root);
                if (emptyMessage(message)) {
                    return Result.failure("Model does not return content", true);
                }
                int[] usage = responsesUsage(root.optJSONObject("usage"));
                return Result.success(message, usage[0], usage[1], usage[2]);
            } catch (IOException e) {
                if (cancelled) {
                    return Result.failure("Request cancelled");
                }
                return Result.failure("Network connection failed. Check the network and interface address", true);
            } catch (Exception e) {
                return Result.failure("Responses Request failed:" + e.getClass().getSimpleName());
            } finally {
                closeConnection(connection);
            }
        }
        return Result.failure("Responses Request failed", true);
    }

    private Result chatStreamResponses(JSONArray messages, JSONArray tools, String apiKey,
                                       Conversation settings, final StreamListener listener) {
        cancelled = false;
        lastTruncated = false;
        lastFinishReason = "";
        HttpURLConnection connection = null;
        try {
            boolean thinking = AppPrefs.THINKING_ON.equals(settings.thinking)
                    && settings.capThinking;
            JSONObject body = ProtocolCodec.responsesRequest(messages, tools,
                    settings.model, settings.maxTokens, settings.temperature,
                    thinking, settings.capTools, settings.capEffort, true, thinking);
            connection = openPost(ModelCatalog.responsesEndpoint(settings.baseUrl),
                    body, apiKey, false, true);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String error = readAll(connection.getErrorStream(), 65536);
                return protocolFailure(code, error);
            }

            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            JSONArray doneItems = new JSONArray();
            JSONObject finalResponse = null;
            int promptTokens = 0;
            int completionTokens = 0;
            int cachedTokens = 0;
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                JSONObject event;
                try {
                    event = new JSONObject(data);
                } catch (Exception ignored) {
                    continue;
                }
                String type = event.optString("type", "");
                if ("response.output_text.delta".equals(type)) {
                    String delta = event.optString("delta", "");
                    if (!delta.isEmpty()) {
                        content.append(delta);
                        if (listener != null) {
                            listener.onContent(delta);
                        }
                    }
                } else if ("response.reasoning_summary_text.delta".equals(type)
                        || "response.reasoning_text.delta".equals(type)) {
                    String delta = event.optString("delta", "");
                    if (!delta.isEmpty()) {
                        reasoning.append(delta);
                        if (listener != null) {
                            listener.onReasoning(delta);
                        }
                    }
                } else if ("response.output_item.done".equals(type)) {
                    JSONObject item = event.optJSONObject("item");
                    if (item != null) {
                        doneItems.put(ProtocolCodec.copyObject(item));
                    }
                } else if ("response.completed".equals(type)
                        || "response.incomplete".equals(type)) {
                    finalResponse = event.optJSONObject("response");
                    break;
                } else if ("response.failed".equals(type)) {
                    JSONObject response = event.optJSONObject("response");
                    return Result.failure(response == null
                            ? "Responses Request failed" : errorMessage(response));
                } else if ("error".equals(type)) {
                    return Result.failure(errorMessage(event));
                }
            }

            JSONObject message;
            if (finalResponse != null) {
                message = ProtocolCodec.responsesMessage(finalResponse);
                int[] usage = responsesUsage(finalResponse.optJSONObject("usage"));
                promptTokens = usage[0];
                completionTokens = usage[1];
                cachedTokens = usage[2];
                String status = finalResponse.optString("status", "");
                JSONObject incomplete = finalResponse.optJSONObject("incomplete_details");
                String reason = incomplete == null ? "" : incomplete.optString("reason", "");
                lastFinishReason = status;
                lastTruncated = "incomplete".equals(status)
                        && ("max_output_tokens".equals(reason) || "max_tokens".equals(reason));
            } else {
                JSONObject synthetic = new JSONObject();
                synthetic.put("output", doneItems);
                synthetic.put("status", "completed");
                message = ProtocolCodec.responsesMessage(synthetic);
                lastFinishReason = "completed";
            }
            if (ProtocolCodec.contentText(message.opt("content")).isEmpty()
                    && content.length() > 0) {
                message.put("content", content.toString());
            }
            if (message.optString("reasoning_content", "").isEmpty()
                    && reasoning.length() > 0) {
                message.put("reasoning_content", reasoning.toString());
            }
            if (emptyMessage(message)) {
                return Result.failure("Response stream was closed early", true);
            }
            return Result.success(message, promptTokens, completionTokens, cachedTokens);
        } catch (IOException e) {
            if (cancelled) {
                return Result.failure("Request cancelled");
            }
            return Result.failure("Network connection is down. Check the network.", true);
        } catch (Exception e) {
            if (cancelled) {
                return Result.failure("Request cancelled");
            }
            return Result.failure("Responses Stream request failed:"
                    + e.getClass().getSimpleName());
        } finally {
            closeConnection(connection);
        }
    }

    private static final class AnthropicBlock {
        String type = "";
        String id = "";
        String name = "";
        final StringBuilder text = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final StringBuilder signature = new StringBuilder();
        final StringBuilder input = new StringBuilder();

        JSONObject finish() {
            JSONObject block = new JSONObject();
            try {
                if ("thinking".equals(type)) {
                    block.put("type", "thinking");
                    block.put("thinking", thinking.toString());
                    if (signature.length() > 0) {
                        block.put("signature", signature.toString());
                    }
                } else if ("tool_use".equals(type)) {
                    block.put("type", "tool_use");
                    block.put("id", id);
                    block.put("name", name);
                    block.put("input", ProtocolCodec.parseObject(input.toString()));
                } else {
                    if (text.length() == 0) {
                        return null;
                    }
                    block.put("type", "text");
                    block.put("text", text.toString());
                }
            } catch (Exception ignored) {
            }
            return block;
        }
    }

    private HttpURLConnection openPost(String endpoint, JSONObject body, String apiKey,
                                       boolean anthropic, boolean stream) throws Exception {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        Log.i("AgentVision", "attempt protocol=" + (anthropic ? "messages" : "responses")
                + " model=" + body.optString("model") + " payload=" + payload.length);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        activeConnection = connection;
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(180000);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(payload.length);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
        if (anthropic) {
            connection.setRequestProperty("x-api-key", apiKey);
            connection.setRequestProperty("anthropic-version", "2023-06-01");
        } else {
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        OutputStream output = connection.getOutputStream();
        try {
            output.write(payload);
        } finally {
            output.close();
        }
        return connection;
    }

    private void closeConnection(HttpURLConnection connection) {
        if (activeConnection == connection) {
            activeConnection = null;
        }
        if (connection != null) {
            connection.disconnect();
        }
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(700L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Result protocolFailure(int code, String errorBody) {
        String message = httpMessage(code, errorBody);
        if (protocolUnsupported(code, errorBody)) {
            return Result.failure(PROTOCOL_UNSUPPORTED + " " + message, false);
        }
        return Result.failure(message, isRetryableStatus(code));
    }

    private static boolean protocolUnsupported(int code, String errorBody) {
        if (code == 404 || code == 405 || code == 501) {
            return true;
        }
        if (code != 400 && code != 422) {
            return false;
        }
        String lower = errorBody == null ? "" : errorBody.toLowerCase(Locale.ROOT);
        return lower.contains("unknown endpoint") || lower.contains("not supported")
                || lower.contains("unsupported endpoint") || lower.contains("invalid url")
                || lower.contains("no route") || lower.contains("not found");
    }

    private static int[] anthropicUsage(JSONObject usage) {
        if (usage == null) {
            return new int[]{0, 0, 0};
        }
        return new int[]{
                usage.optInt("input_tokens", 0),
                usage.optInt("output_tokens", 0),
                usage.optInt("cache_read_input_tokens", 0)
        };
    }

    private static int[] responsesUsage(JSONObject usage) {
        if (usage == null) {
            return new int[]{0, 0, 0};
        }
        int prompt = usage.optInt("input_tokens", usage.optInt("prompt_tokens", 0));
        int completion = usage.optInt("output_tokens", usage.optInt("completion_tokens", 0));
        int cached = 0;
        JSONObject details = usage.optJSONObject("input_tokens_details");
        if (details == null) {
            details = usage.optJSONObject("prompt_tokens_details");
        }
        if (details != null) {
            cached = details.optInt("cached_tokens", 0);
        }
        return new int[]{prompt, completion, cached};
    }

    private static boolean emptyMessage(JSONObject message) {
        if (message == null) {
            return true;
        }
        JSONArray calls = message.optJSONArray("tool_calls");
        return ProtocolCodec.contentText(message.opt("content")).isEmpty()
                && (calls == null || calls.length() == 0)
                && message.optString("reasoning_content", "").isEmpty();
    }

    private static String errorMessage(JSONObject root) {
        if (root == null) {
            return "Model returned error";
        }
        JSONObject error = root.optJSONObject("error");
        if (error != null) {
            String message = error.optString("message", "");
            if (!message.isEmpty()) {
                return message;
            }
        }
        String message = root.optString("message", "");
        return message.isEmpty() ? root.toString() : message;
    }

    private Result chatCompletions(JSONArray messages, JSONArray tools, String apiKey,
                                   Conversation settings) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return Result.failure("Not configured API Key");
        }
        if (settings == null) {
            return Result.failure("Session Settings Not Available");
        }
                if (hasImage(messages) && !settings.capVision) {
            // Images in history news do not block the current cycle: downgrade to text and avoid permanently misreporting the entire session after a map
            degradeImages(messages);
        }
        cancelled = false;
        Result last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            last = attemptChat(messages, tools, apiKey, settings);
            if (cancelled) {
                return Result.failure("Request cancelled");
            }
            if (last.error == null || !last.retryable || attempt == 3) {
                return last;
            }
            try {
                Thread.sleep(700L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        return last == null ? Result.failure("Request failed", true) : last;
    }

    private Result attemptChat(JSONArray messages, JSONArray tools, String apiKey,
                               Conversation settings) {

        HttpURLConnection connection = null;
        try {
            String endpoint = normalizeEndpoint(settings.baseUrl);
            normalizeImageParts(messages);
            JSONObject body = new JSONObject();
            body.put("model", settings.model);
            body.put("messages", messages);
            if (tools != null && tools.length() > 0 && settings.capTools) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }
            body.put("stream", false);
            body.put("temperature", settings.temperature);
            body.put("max_tokens", settings.maxTokens);
            applyThinking(body, endpoint, settings);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            Log.i("AgentVision", "attempt model=" + settings.model + " msgs="
                    + messages.length() + " images=" + imageCount(messages)
                    + " payload=" + payload.length);
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            activeConnection = connection;
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(120000);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);

            OutputStream output = connection.getOutputStream();
            try {
                output.write(payload);
            } finally {
                output.close();
            }

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String error = readAll(connection.getErrorStream(), 65536);
                return Result.failure(httpMessage(code, error), isRetryableStatus(code));
            }

            String response = readAll(connection.getInputStream(), 1024 * 1024);
            return parse(response);
        } catch (IOException e) {
            if (cancelled) {
                return Result.failure("Request cancelled");
            }
            return Result.failure("Network connection failed. Check the network and interface address", true);
        } catch (Exception e) {
            return Result.failure("Request failed:" + e.getClass().getSimpleName());
        } finally {
            if (activeConnection == connection) {
                activeConnection = null;
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Result chatStreamCompletions(JSONArray messages, JSONArray tools, String apiKey,
                                         Conversation settings, final StreamListener listener) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return Result.failure("Not configured API Key");
        }
        if (settings == null) {
            return Result.failure("Session Settings Not Available");
        }
                if (hasImage(messages) && !settings.capVision) {
            // Images in history news do not block the current cycle: downgrade to text and avoid permanently misreporting the entire session after a map
            degradeImages(messages);
        }
        cancelled = false;
        lastTruncated = false;
        lastFinishReason = "";
        // Vision requests use the non-streaming endpoint. The vision guide does
        // not guarantee streaming image support, and some providers silently
        // drop image parts from SSE requests.
        if (hasImage(messages)) {
            return attemptChat(messages, tools, apiKey, settings);
        }
        HttpURLConnection connection = null;
        try {
            String endpoint = normalizeEndpoint(settings.baseUrl);
            normalizeImageParts(messages);
            JSONObject body = new JSONObject();
            body.put("model", settings.model);
            body.put("messages", messages);
            if (tools != null && tools.length() > 0 && settings.capTools) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }
            body.put("stream", true);
            body.put("temperature", settings.temperature);
            body.put("max_tokens", settings.maxTokens);
            try {
                String host = new URL(endpoint).getHost();
                if (host.endsWith("deepseek.com") || host.endsWith("openai.com")
                        || host.endsWith("example.com")
                        || host.contains("openrouter.ai") || host.contains("siliconflow")) {
                    JSONObject streamOptions = new JSONObject();
                    streamOptions.put("include_usage", true);
                    body.put("stream_options", streamOptions);
                }
            } catch (Exception ignored) {
            }
            applyThinking(body, endpoint, settings);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            Log.i("AgentVision", "attempt model=" + settings.model + " msgs="
                    + messages.length() + " images=" + imageCount(messages)
                    + " payload=" + payload.length);
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            activeConnection = connection;
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(120000);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);

            OutputStream output = connection.getOutputStream();
            try {
                output.write(payload);
            } finally {
                output.close();
            }

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String error = readAll(connection.getErrorStream(), 65536);
                return Result.failure(httpMessage(code, error), isRetryableStatus(code));
            }

            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            Map<Integer, JSONObject> toolCalls = new TreeMap<>();
            Map<Integer, StringBuilder> args = new TreeMap<>();
            int streamPromptTokens = 0;
            int streamCompletionTokens = 0;
            int streamCachedTokens = 0;
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }
                JSONObject chunk;
                try {
                    chunk = new JSONObject(data);
                } catch (Exception ignored) {
                    continue;
                }
                if (chunk.has("error")) {
                    JSONObject error = chunk.optJSONObject("error");
                    String message = error == null ? ""
                            : JsonText.string(error, "message");
                    return Result.failure(message.isEmpty() ? "Model returned error" : message);
                }
                JSONObject usage = chunk.optJSONObject("usage");
                if (usage != null) {
                    int[] counted = usageOf(usage);
                    streamPromptTokens = counted[0];
                    streamCompletionTokens = counted[1];
                    streamCachedTokens = counted[2];
                }
                JSONArray choices = chunk.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    continue;
                }
                JSONObject choice = choices.optJSONObject(0);
                String finish = choice == null ? "" : JsonText.string(choice, "finish_reason");
                if (!finish.isEmpty()) {
                    lastFinishReason = finish;
                    if ("length".equals(finish)) {
                        lastTruncated = true;
                    }
                }
                JSONObject delta = choice == null ? null : choice.optJSONObject("delta");
                if (delta == null) {
                    continue;
                }
                String text = JsonText.string(delta, "content");
                if (!text.isEmpty()) {
                    content.append(text);
                    if (listener != null) {
                        listener.onContent(text);
                    }
                }
                String think = JsonText.string(delta, "reasoning_content");
                if (think.isEmpty()) {
                    think = JsonText.string(delta, "reasoning");
                }
                if (!think.isEmpty()) {
                    reasoning.append(think);
                    if (listener != null) {
                        listener.onReasoning(think);
                    }
                }
                JSONArray calls = delta.optJSONArray("tool_calls");
                if (calls != null) {
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject call = calls.optJSONObject(i);
                        if (call == null) {
                            continue;
                        }
                        int index = call.optInt("index", i);
                        JSONObject target = toolCalls.get(index);
                        if (target == null) {
                            target = new JSONObject();
                            target.put("index", index);
                            target.put("type", "function");
                            JSONObject function = new JSONObject();
                            function.put("name", "");
                            function.put("arguments", "");
                            target.put("function", function);
                            toolCalls.put(index, target);
                            args.put(index, new StringBuilder());
                        }
                        if (call.has("id")) {
                            target.put("id", call.optString("id", ""));
                        }
                        JSONObject function = call.optJSONObject("function");
                        if (function != null) {
                            String name = function.optString("name", "");
                            if (!name.isEmpty()) {
                                target.optJSONObject("function").put("name", name);
                            }
                            String argument = function.optString("arguments", "");
                            if (!argument.isEmpty()) {
                                args.get(index).append(argument);
                            }
                        }
                    }
                }
            }

            if (!cancelled && content.length() == 0 && toolCalls.isEmpty()
                    && reasoning.length() == 0 && lastFinishReason.isEmpty()) {
                return Result.failure("Response stream was closed early", true);
            }

            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            message.put("content", content.length() == 0
                    ? JSONObject.NULL : content.toString());
            if (reasoning.length() > 0) {
                message.put("reasoning_content", reasoning.toString());
            }
            if (!toolCalls.isEmpty()) {
                JSONArray array = new JSONArray();
                for (Map.Entry<Integer, JSONObject> entry : toolCalls.entrySet()) {
                    JSONObject call = entry.getValue();
                    call.remove("index");
                    call.optJSONObject("function").put("arguments",
                            args.get(entry.getKey()).toString());
                    array.put(call);
                }
                message.put("tool_calls", array);
            }
            return Result.success(message, streamPromptTokens, streamCompletionTokens,
                    streamCachedTokens);
        } catch (IOException e) {
            if (cancelled) {
                return Result.failure("Request cancelled");
            }
            return Result.failure("Network connection is down. Check the network.", true);
        } catch (Exception e) {
            if (cancelled) {
                return Result.failure("Request cancelled");
            }
            return Result.failure("Stream request failed:" + e.getClass().getSimpleName());
        } finally {
            if (activeConnection == connection) {
                activeConnection = null;
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isRetryableStatus(int code) {
        return code == 408 || code == 429 || code >= 500;
    }

    private static Result parse(String response) {
        try {
            JSONObject root = new JSONObject(response);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return Result.failure("Model does not return results");
            }
            JSONObject choice = choices.optJSONObject(0);
            JSONObject message = choice == null ? null : choice.optJSONObject("message");
            if (message == null) {
                return Result.failure("The model did not return messages");
            }
            Object content = message.opt("content");
            if (content == null || content == JSONObject.NULL
                    || content instanceof String) {
                String clean = JsonText.string(message, "content");
                JSONArray calls = message.optJSONArray("tool_calls");
                message.put("content", clean.isEmpty()
                        ? (calls != null && calls.length() > 0 ? JSONObject.NULL : "")
                        : clean);
            }
            return Result.success(message, usageOf(root.optJSONObject("usage"))[0],
                    usageOf(root.optJSONObject("usage"))[1],
                    usageOf(root.optJSONObject("usage"))[2]);
        } catch (Exception e) {
            return Result.failure("Model returns content undefeated");
        }
    }

    private static int[] usageOf(JSONObject usage) {
        if (usage == null) {
            return new int[]{0, 0, 0};
        }
        int prompt = usage.optInt("prompt_tokens", 0);
        int completion = usage.optInt("completion_tokens", 0);
        int cached = usage.optInt("prompt_cache_hit_tokens", 0);
        JSONObject details = usage.optJSONObject("prompt_tokens_details");
        if (details != null) {
            cached = Math.max(cached, details.optInt("cached_tokens", 0));
        }
        return new int[]{prompt, completion, cached};
    }

    private static String normalizeEndpoint(String baseUrl) throws IllegalArgumentException {
        return ModelCatalog.chatEndpoint(baseUrl);
    }

    private static void applyThinking(JSONObject body, String endpoint,
                                      Conversation settings) throws Exception {
        String thinkingMode = settings.thinking;
        String host = new URL(endpoint).getHost();
        if (host.endsWith("deepseek.com")) {
            if (!AppPrefs.THINKING_AUTO.equals(thinkingMode)) {
                JSONObject thinking = new JSONObject();
                thinking.put("type", AppPrefs.THINKING_ON.equals(thinkingMode)
                        ? "enabled" : "disabled");
                body.put("thinking", thinking);
            }
            if (AppPrefs.THINKING_ON.equals(thinkingMode)) {
                body.put("reasoning_effort", ModelProfile.normalizeEffort(settings.capEffort));
            }
            return;
        }
        if (AppPrefs.THINKING_ON.equals(thinkingMode) && settings.capThinking) {
            body.put("reasoning_effort", ModelProfile.normalizeEffort(settings.capEffort));
        }
    }

    private static String httpMessage(int code, String errorBody) {
        String detail = errorDetail(errorBody);
        if (code == 401 || code == 403) {
            return "API Key Invalid or disempowered";
        }
        if (code == 402) {
            return "Insufficient account balance";
        }
        if (code == 429) {
            return "Excessive requests or limits";
        }
        if (code >= 500) {
            return "Model services are temporarily unavailable (HTTP " + code + ")";
        }
        String base = "Request failed (%1)HTTP " + code + ")";
        return detail.isEmpty() ? base : base + ": " + detail;
    }

    private static String errorDetail(String body) {
        if (body == null || body.trim().isEmpty()) {
            return "";
        }
        try {
            JSONObject root = new JSONObject(body);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                String message = error.optString("message", "");
                if (!message.isEmpty()) {
                    return shorten(message);
                }
            }
            String message = root.optString("message", "");
            if (!message.isEmpty()) {
                return shorten(message);
            }
        } catch (Exception ignored) {
        }
        return shorten(body);
    }

    private static String shorten(String value) {
        String clean = value.replaceAll("\\s+", " ").trim();
        if (clean.length() > 160) {
            clean = clean.substring(0, 160) + "...";
        }
        return clean;
    }

    private static String readAll(InputStream input, int maxBytes) throws IOException {
        if (input == null) {
            return "";
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total + read > maxBytes) {
                int keep = Math.max(0, maxBytes - total);
                output.write(buffer, 0, keep);
                break;
            }
            output.write(buffer, 0, read);
            total += read;
        }
        input.close();
        return output.toString("UTF-8");
    }
    private static int imageCount(JSONArray messages) {
        int count = 0;
        if (messages == null) return 0;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            JSONArray parts = message.optJSONArray("content");
            if (parts == null) continue;
            for (int p = 0; p < parts.length(); p++) {
                JSONObject part = parts.optJSONObject(p);
                if (part != null && "image_url".equals(part.optString("type", ""))) count++;
            }
        }
        return count;
    }

    private static boolean hasImage(JSONArray messages) {
        if (messages == null) return false;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            JSONArray parts = message.optJSONArray("content");
            if (parts == null) continue;
            for (int p = 0; p < parts.length(); p++) {
                JSONObject part = parts.optJSONObject(p);
                if (part != null && "image_url".equals(part.optString("type", ""))) return true;
            }
        }
        return false;
    }

    /**
     * Replaces the image widget in the message with a biting of text when the current model does not support visualization.
     * Such a drawing would not allow the entire session to fail permanently in the subsequent cycle; the model would be told that it was not visible.
     */
    private static void degradeImages(JSONArray messages) {
        if (messages == null) return;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            JSONArray parts = message.optJSONArray("content");
            if (parts == null) continue;
            for (int p = 0; p < parts.length(); p++) {
                JSONObject part = parts.optJSONObject(p);
                if (part == null || !"image_url".equals(part.optString("type", ""))) continue;
                try {
                    JSONObject text = new JSONObject();
                    text.put("type", "text");
                    text.put("text", "[Picture omitted: Current model does not open visual capability to view the picture, please change to a model that supports the picture or describe it directly in text]");
                    parts.put(p, text);
                } catch (Exception ignored) { }
            }
        }
    }

    private static void normalizeImageParts(JSONArray messages) {
        if (messages == null) return;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            JSONArray parts = message.optJSONArray("content");
            if (parts == null) continue;
            for (int p = 0; p < parts.length(); p++) {
                JSONObject part = parts.optJSONObject(p);
                if (part == null || !"image_url".equals(part.optString("type", ""))) continue;
                JSONObject image = part.optJSONObject("image_url");
                if (image != null && !image.has("detail")) {
                    try { image.put("detail", "auto"); } catch (Exception ignored) { }
                }
            }
        }
    }

}
