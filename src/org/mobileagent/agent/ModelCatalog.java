package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** OpenAI-compatible gateway helpers: base URL, /models discovery and model metadata. */
final class ModelCatalog {
    static final String DEFAULT_BASE = "https://api.example.com";

    static final class FetchResult {
        final List<ModelProfile> models;
        final int hidden;
        final long fetchedAt;
        final String error;

        private FetchResult(List<ModelProfile> models, int hidden, long fetchedAt,
                            String error) {
            this.models = models;
            this.hidden = hidden;
            this.fetchedAt = fetchedAt;
            this.error = error;
        }

        static FetchResult success(List<ModelProfile> models, int hidden) {
            return new FetchResult(models, hidden, System.currentTimeMillis(), null);
        }

        static FetchResult failure(String error) {
            return new FetchResult(new ArrayList<ModelProfile>(), 0, 0L, error);
        }

        boolean ok() {
            return error == null;
        }
    }

    private ModelCatalog() {
    }

    static String normalizeBase(String raw) {
        String url = raw == null ? "" : raw.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/chat/completions")) {
            url = url.substring(0, url.length() - "/chat/completions".length());
        }
        if (url.endsWith("/models")) {
            url = url.substring(0, url.length() - "/models".length());
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.startsWith("https://")) {
            throw new IllegalArgumentException("Interface addresses must be used https://");
        }
        URL parsed;
        try {
            parsed = new URL(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed interface address");
        }
        if (parsed.getHost() == null || parsed.getHost().trim().isEmpty()) {
            throw new IllegalArgumentException("The interface address lacks hostname");
        }
        String path = parsed.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            path = "/v1";
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            path = "/v1";
        }
        String port = parsed.getPort() > 0 ? ":" + parsed.getPort() : "";
        return parsed.getProtocol() + "://" + parsed.getHost() + port + path;
    }

    static String chatEndpoint(String raw) {
        return normalizeBase(raw) + "/chat/completions";
    }

    static String anthropicEndpoint(String raw) {
        return normalizeBase(raw) + "/messages";
    }

    static String responsesEndpoint(String raw) {
        return normalizeBase(raw) + "/responses";
    }

    static FetchResult fetch(String baseUrl, String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return FetchResult.failure("Not configured API Key");
        }
        String base;
        try {
            base = normalizeBase(baseUrl);
        } catch (IllegalArgumentException e) {
            return FetchResult.failure(e.getMessage());
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(base + "/models").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            connection.setRequestProperty("User-Agent", "MobileAgent");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String body = readLimited(code >= 400
                        ? connection.getErrorStream() : connection.getInputStream(),
                        256 * 1024);
                return FetchResult.failure(httpError(code, body));
            }
            String body = readLimited(connection.getInputStream(), 1024 * 1024);
            return parseModels(body);
        } catch (java.net.SocketTimeoutException e) {
            return FetchResult.failure("Connect to model service timeout, check the network or interface address");
        } catch (java.net.UnknownHostException e) {
            return FetchResult.failure("Could not parse model service domain name. Check the network and interface address");
        } catch (java.io.IOException e) {
            return FetchResult.failure("Cannot connect to model service. Check the network and interface address");
        } catch (Exception e) {
            return FetchResult.failure("Could not close temporary folder: %s" + e.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static FetchResult parseModels(String body) {
        String text = body == null ? "" : body.trim();
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1).trim();
        }
        if (!text.startsWith("{")) {
            return FetchResult.failure("The interface returns not a model list (possibly a web page or login page), but a model list."
                    + "Please confirm address to /v1 Until then.");
        }
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (Exception e) {
            return FetchResult.failure("The content returned by the model service cannot be identified");
        }
        JSONArray data = root.optJSONArray("data");
        if (data == null) {
            return FetchResult.failure("No model list in the contents returned by the model service");
        }
        Map<String, ModelProfile> unique = new LinkedHashMap<String, ModelProfile>();
        int hidden = 0;
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String id = item.optString("id", "").trim();
            if (id.isEmpty()) {
                continue;
            }
            if (ModelProfile.isKnownBroken(id)) {
                hidden++;
                continue;
            }
            ModelProfile profile = ModelProfile.infer(id);
            applyHints(profile, item);
            if (!unique.containsKey(id)) {
                unique.put(id, profile);
            }
        }
        if (unique.isEmpty()) {
            return FetchResult.failure("Model service returned empty list");
        }
        List<ModelProfile> models = new ArrayList<ModelProfile>(unique.values());
        java.util.Collections.sort(models, new java.util.Comparator<ModelProfile>() {
            @Override
            public int compare(ModelProfile a, ModelProfile b) {
                return a.id.toLowerCase(Locale.ROOT).compareTo(
                        b.id.toLowerCase(Locale.ROOT));
            }
        });
        return FetchResult.success(models, hidden);
    }

    private static void applyHints(ModelProfile profile, JSONObject item) {
        int context = firstPositive(item, "context_length", "context_window",
                "max_context_length", "context_size");
        if (context > 0) {
            profile.contextTokens = ModelProfile.clampInt(context, 8000, 1000000);
        }
        int output = firstPositive(item, "max_output_tokens", "max_completion_tokens",
                "max_tokens");
        if (output > 0) {
            profile.maxOutputTokens = ModelProfile.clampInt(output, 512, 65536);
        }
        JSONObject capabilities = item.optJSONObject("capabilities");
        if (capabilities != null) {
            profile.vision = capabilities.optBoolean("vision",
                    capabilities.optBoolean("supports_vision", profile.vision));
            profile.tools = capabilities.optBoolean("tools",
                    capabilities.optBoolean("supports_tools", profile.tools));
            profile.thinking = capabilities.optBoolean("thinking",
                    capabilities.optBoolean("supports_reasoning", profile.thinking));
        }
        profile.vision = item.optBoolean("supports_vision", profile.vision);
        profile.tools = item.optBoolean("supports_tools", profile.tools);
        profile.thinking = item.optBoolean("supports_reasoning", profile.thinking);
        JSONArray parameters = item.optJSONArray("supported_parameters");
        if (parameters != null) {
            for (int i = 0; i < parameters.length(); i++) {
                String value = parameters.optString(i, "").toLowerCase(Locale.ROOT);
                if ("vision".equals(value) || "image".equals(value)
                        || "image_url".equals(value)) {
                    profile.vision = true;
                } else if ("tools".equals(value) || "tool_choice".equals(value)
                        || "function_calling".equals(value)) {
                    profile.tools = true;
                } else if ("reasoning".equals(value) || "reasoning_effort".equals(value)
                        || "thinking".equals(value)) {
                    profile.thinking = true;
                }
            }
        }
    }

    private static int firstPositive(JSONObject item, String... keys) {
        for (String key : keys) {
            int value = item.optInt(key, 0);
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    private static String httpError(int code, String body) {
        if (code == 401 || code == 403) {
            return "API Key Invalid or disempowered";
        }
        if (code == 404) {
            return "Malformed interface address (%1)HTTP 404), Please confirm that you have signed /v1 Until then.";
        }
        if (code == 429) {
            return "Request too often or limited, please try again later";
        }
        if (code >= 500) {
            return "Model services are temporarily unavailable (HTTP " + code + ")";
        }
        String detail = errorDetail(body);
        return detail.isEmpty() ? "Failed to fetch model list (%1)HTTP " + code + ")"
                : "Failed to fetch model list (%1)HTTP " + code + "): " + detail;
    }

    private static String errorDetail(String body) {
        if (body == null || body.trim().isEmpty()) {
            return "";
        }
        try {
            JSONObject object = new JSONObject(body);
            JSONObject error = object.optJSONObject("error");
            if (error != null) {
                String message = error.optString("message", "");
                if (!message.isEmpty()) {
                    return limit(message, 160);
                }
            }
            String message = object.optString("message", "");
            if (!message.isEmpty()) {
                return limit(message, 160);
            }
        } catch (Exception ignored) {
        }
        return limit(body.replace('\n', ' ').trim(), 160);
    }

    private static String limit(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static String readLimited(InputStream input, int maxBytes) throws Exception {
        if (input == null) {
            return "";
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            int keep = Math.min(read, maxBytes - total);
            if (keep > 0) {
                output.write(buffer, 0, keep);
                total += keep;
            }
            if (total >= maxBytes) {
                break;
            }
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
