package org.mobileagent.agent;

import org.json.JSONObject;

import java.net.URL;

/** A configured OpenAI-compatible gateway. Multiple services can coexist. */
final class ModelService {
    String id = "";
    String name = "";
    String baseUrl = "";
    String apiKey = "";
    String protocol = ModelProtocols.AUTO;

    static ModelService create(String name, String baseUrl, String apiKey) {
        ModelService service = new ModelService();
        service.name = name == null ? "" : name.trim();
        service.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        service.apiKey = apiKey == null ? "" : apiKey.trim();
        service.protocol = ModelProtocols.AUTO;
        return service;
    }

    static ModelService fromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        ModelService service = new ModelService();
        service.id = object.optString("id", "").trim();
        service.name = object.optString("name", "").trim();
        service.baseUrl = object.optString("baseUrl", "").trim();
        service.apiKey = object.optString("apiKey", "").trim();
        service.protocol = ModelProtocols.normalize(object.optString("protocol", ModelProtocols.AUTO));
        if (service.baseUrl.isEmpty()) {
            return null;
        }
        if (service.id.isEmpty()) {
            service.id = service.baseUrl;
        }
        return service;
    }

    JSONObject toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("id", id);
            object.put("name", name);
            object.put("baseUrl", baseUrl);
            object.put("apiKey", apiKey);
            object.put("protocol", protocol);
        } catch (Exception ignored) {
        }
        return object;
    }

    String displayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        return host();
    }

    String host() {
        try {
            URL url = new URL(baseUrl);
            String path = url.getPath() == null ? "" : url.getPath();
            if (path.endsWith("/chat/completions")) {
                path = path.substring(0, path.length() - "/chat/completions".length());
            }
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return url.getHost() + path;
        } catch (Exception e) {
            return baseUrl == null || baseUrl.trim().isEmpty() ? "-" : baseUrl;
        }
    }

    String keyState() {
        return apiKey != null && !apiKey.trim().isEmpty() ? "Key Configured" : "Key Not configured";
    }

    ModelService copy() {
        ModelService service = create(name, baseUrl, apiKey);
        service.id = id;
        service.protocol = protocol;
        return service;
    }
}
