package org.mobileagent.agent;

import org.json.JSONObject;

import java.util.Locale;

/** Per-model capabilities. Stored locally and applied to every request for that model. */
final class ModelProfile {
    static final int DEFAULT_CONTEXT = 128000;
    static final int DEFAULT_MAX_OUTPUT = 8192;
    static final int CAPABILITY_VERSION = 2;
    static final String DEFAULT_EFFORT = "high";

    final String id;
    int contextTokens = DEFAULT_CONTEXT;
    int maxOutputTokens = DEFAULT_MAX_OUTPUT;
    boolean tools = true;
    boolean vision;
    boolean thinking;
    String effort = DEFAULT_EFFORT;
    String protocol = ModelProtocols.AUTO;
    int capabilityVersion = CAPABILITY_VERSION;

    ModelProfile(String id) {
        this.id = id == null ? "" : id;
    }

    static ModelProfile infer(String id) {
        ModelProfile profile = new ModelProfile(id);
        String key = profile.id.toLowerCase(Locale.ROOT);
        if (key.contains("gpt-6") || key.contains("claude") || key.contains("grok")) {
            profile.contextTokens = 400000;
        } else if (key.contains("glm")) {
            profile.contextTokens = 200000;
        } else if (key.equals("deepseek-flash") || key.contains("deepseek-v4")
                || key.equals("deepseek-chat")) {
            profile.contextTokens = 1000000;
            profile.maxOutputTokens = 65536;
        } else if (key.contains("deepseek-reasoner")) {
            profile.contextTokens = 128000;
        } else if (key.contains("deepseek") || key.contains("kimi")
                || key.contains("qwen")) {
            profile.contextTokens = 256000;
        }
        profile.vision = key.contains("gpt-6") || key.contains("claude")
                || key.contains("grok") || key.contains("qwen3.8")
                || key.contains("vision") || key.contains("-vl") || key.contains("omni")
                || key.contains("deepseek-flash") || key.contains("deepseek-v4")
                || key.equals("deepseek-chat");
        profile.thinking = key.contains("deepseek") || key.contains("qwen3")
                || key.contains("glm") || key.contains("kimi-k2")
                || key.contains("grok") || key.contains("claude")
                || key.contains("gpt-6") || key.contains("-r1") || key.contains("reason");
        return profile;
    }

    static boolean isKnownBroken(String id) {
        return id != null && id.toLowerCase(Locale.ROOT).endsWith("kimi-k2.7-code");
    }

    JSONObject toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("id", id);
            object.put("contextTokens", contextTokens);
            object.put("maxOutputTokens", maxOutputTokens);
            object.put("tools", tools);
            object.put("vision", vision);
            object.put("thinking", thinking);
            object.put("effort", effort);
            object.put("protocol", protocol);
            object.put("capabilityVersion", capabilityVersion);
        } catch (Exception ignored) {
        }
        return object;
    }

    static ModelProfile fromJson(JSONObject object, String fallbackId) {
        String id = object == null ? fallbackId : object.optString("id", fallbackId);
        ModelProfile profile = infer(id);
        if (object == null) {
            return profile;
        }
        profile.contextTokens = clampInt(object.optInt("contextTokens", profile.contextTokens),
                8000, 1000000);
        profile.maxOutputTokens = clampInt(
                object.optInt("maxOutputTokens", profile.maxOutputTokens), 512, 65536);
        profile.tools = object.optBoolean("tools", profile.tools);
        profile.vision = object.optBoolean("vision", profile.vision);
        profile.thinking = object.optBoolean("thinking", profile.thinking);
        String effort = object.optString("effort", profile.effort);
        profile.effort = normalizeEffort(effort);
        profile.protocol = ModelProtocols.normalize(
                object.optString("protocol", ModelProtocols.AUTO));
        if (object.optInt("capabilityVersion", 0) < CAPABILITY_VERSION) {
            ModelProfile inferred = infer(id);
            profile.contextTokens = Math.max(profile.contextTokens, inferred.contextTokens);
            profile.maxOutputTokens = Math.max(profile.maxOutputTokens,
                    inferred.maxOutputTokens);
            profile.vision = profile.vision || inferred.vision;
        }
        profile.capabilityVersion = CAPABILITY_VERSION;
        return profile;
    }

    static String normalizeEffort(String value) {
        if ("low".equals(value) || "medium".equals(value) || "high".equals(value)
                || "xhigh".equals(value)) {
            return value;
        }
        return DEFAULT_EFFORT;
    }

    static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    String contextLabel() {
        if (contextTokens >= 1000000 && contextTokens % 1000000 == 0) {
            return (contextTokens / 1000000) + "M";
        }
        if (contextTokens % 1000 == 0) {
            return (contextTokens / 1000) + "K";
        }
        return String.valueOf(contextTokens);
    }

    String outputLabel() {
        if (maxOutputTokens % 1000 == 0) {
            return (maxOutputTokens / 1000) + "K";
        }
        return String.valueOf(maxOutputTokens);
    }

    String summary() {
        StringBuilder builder = new StringBuilder();
        builder.append(contextLabel()).append(" Context · Output ").append(outputLabel());
        builder.append(tools ? " · Tools" : " · No tool");
        if (vision) {
            builder.append(" · Visual");
        }
        if (thinking) {
            builder.append(" · Think. ").append(effort);
        }
        if (!ModelProtocols.AUTO.equals(ModelProtocols.normalize(protocol))) {
            builder.append(" · ").append(ModelProtocols.shortLabel(protocol));
        }
        return builder.toString();
    }

    String shortSummary() {
        StringBuilder builder = new StringBuilder(contextLabel());
        if (tools) {
            builder.append(" · Tools");
        }
        if (vision) {
            builder.append(" · Visual");
        }
        if (thinking) {
            builder.append(" · Think.");
        }
        return builder.toString();
    }
}
