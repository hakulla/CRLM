package org.mobileagent.agent;

import java.util.Locale;

/** Chooses which wire protocol to use for a model. */
final class ModelProtocols {
    static final String AUTO = "auto";
    static final String CHAT = "chat";
    static final String RESPONSES = "responses";
    static final String MESSAGES = "messages";

    static final class Resolved {
        final String protocol;
        final boolean automatic;

        Resolved(String protocol, boolean automatic) {
            this.protocol = protocol;
            this.automatic = automatic;
        }
    }

    private ModelProtocols() {
    }

    static String normalize(String value) {
        String lower = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (RESPONSES.equals(lower) || MESSAGES.equals(lower) || CHAT.equals(lower)) {
            return lower;
        }
        return AUTO;
    }

    static String label(String value) {
        String protocol = normalize(value);
        if (RESPONSES.equals(protocol)) {
            return "OpenAI Responses";
        }
        if (MESSAGES.equals(protocol)) {
            return "Anthropic Messages";
        }
        if (CHAT.equals(protocol)) {
            return "OpenAI Chat";
        }
        return "Automatic (by Model)";
    }

    static String shortLabel(String value) {
        String protocol = normalize(value);
        if (RESPONSES.equals(protocol)) {
            return "Responses";
        }
        if (MESSAGES.equals(protocol)) {
            return "Messages";
        }
        if (CHAT.equals(protocol)) {
            return "Chat";
        }
        return "Automatic";
    }

    static String infer(String model) {
        String key = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (key.contains("claude") || key.contains("anthropic")) {
            return MESSAGES;
        }
        if (key.contains("gpt-6") || key.contains("gpt-5") || key.contains("gpt5")
                || key.contains("codex") || key.startsWith("o1")
                || key.startsWith("o3") || key.startsWith("o4")
                || key.contains("/o1") || key.contains("/o3") || key.contains("/o4")) {
            return RESPONSES;
        }
        return CHAT;
    }

    static Resolved resolve(AppPrefs prefs, String baseUrl, String model) {
        ModelProfile profile = prefs.modelProfile(baseUrl, model);
        String profileProtocol = normalize(profile.protocol);
        if (!AUTO.equals(profileProtocol)) {
            return new Resolved(profileProtocol, false);
        }
        ModelService service = prefs.serviceForBase(baseUrl);
        String serviceProtocol = service == null ? AUTO : normalize(service.protocol);
        if (!AUTO.equals(serviceProtocol)) {
            return new Resolved(serviceProtocol, false);
        }
        String cached = prefs.cachedProtocol(baseUrl, model);
        if (!cached.isEmpty() && !AUTO.equals(normalize(cached))) {
            return new Resolved(normalize(cached), true);
        }
        return new Resolved(infer(model), true);
    }
}
