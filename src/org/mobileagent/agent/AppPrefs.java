package org.mobileagent.agent;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class AppPrefs {
    static final String DEFAULT_BASE_URL = "https://api.example.com";
    static final String DEFAULT_MODEL = "example-model";
    static final String THINKING_OFF = "off";
    static final String THINKING_AUTO = "auto";
    static final String THINKING_ON = "on";

    private static final String FILE = "agent_settings";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_MODEL = "model";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL_CATALOG = "model_catalog";
    private static final String KEY_MODEL_PROFILES = "model_profiles";
    private static final String KEY_MODEL_SERVICES = "model_services";
    private static final String KEY_ACTIVE_SERVICE = "active_service";
    private static final String KEY_PROTOCOL_CACHE = "model_protocol_cache";
    private static final String KEY_ALLOW_DEVICE = "allow_device_control";
    private static final String KEY_AGENT_PROFILE = "agent_profile";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_MAX_TOKENS = "max_tokens";
    private static final String KEY_THINKING = "thinking";
    private static final String KEY_CONTEXT_CHARS = "context_chars";
    private static final String KEY_CONTEXT_TOKENS = "context_tokens";
    private static final String KEY_MAX_TOOL_ROUNDS = "max_tool_rounds";
    private static final String KEY_LAST_CONVERSATION = "last_conversation";
    private static final String KEY_PENDING_TASK_PROMPT = "pending_task_prompt";
    private static final String KEY_PENDING_SCREENSHOTS = "pending_screenshots";
    private static final String KEY_ALLOW_WEB = "allow_web_tools";
    private static final String KEY_ALLOW_HOOKS = "allow_hooks";
    private static final String KEY_LOCAL_MODEL = "local_model_path";
    private static final String KEY_INSTALL_AUTOMATION = "install_password_automation";
    private static final String KEY_AUTO_RESUME = "auto_resume_interrupted";
    private static final String KEY_BRIDGE_AUTO = "bridge_auto_reply";
    private static final String KEY_BRIDGE_LOCAL_ONLY = "bridge_local_only";
    private static final String KEY_BRIDGE_AUTO_ALL = "bridge_auto_all";
    private static final String KEY_BRIDGE_AUTO_APPS = "bridge_auto_apps";
    private static final String KEY_BRIDGE_SEEN_APPS = "bridge_seen_apps";
    private static final String KEY_DEBUG_BRIDGE = "debug_bridge_enabled";
    private static final String KEY_DEBUG_TOKEN = "debug_bridge_token";
    private static final String KEY_AUTO_TOOLS = "auto_tool_selection";
    private static final String KEY_SMART_CONTEXT = "smart_code_context";
    private static final String KEY_LITE_HARNESS = "lite_harness";
    private static final String KEY_ALLOW_OVER_LIMIT = "allow_over_limit";
    private static final String KEY_MODEL_PARAMS_VERSION = "model_params_version";
    private static final int MODEL_PARAMS_VERSION = 1;

    private final SharedPreferences prefs;

    AppPrefs(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        applyMaxModelDefaults();
        // Voice input was removed; also drop any stored voice endpoint/key.
        if (prefs.contains("voice_engine") || prefs.contains("voice_base_url")
                || prefs.contains("voice_api_key") || prefs.contains("voice_model")) {
            prefs.edit().remove("voice_engine").remove("voice_base_url")
                    .remove("voice_api_key").remove("voice_model").apply();
        }
        LocalConfig.install(context, prefs);
        ensureDefaultService();
    }

    private void applyMaxModelDefaults() {
        if (prefs.getInt(KEY_MODEL_PARAMS_VERSION, 0) >= MODEL_PARAMS_VERSION) {
            return;
        }
        prefs.edit()
                .putFloat(KEY_TEMPERATURE, 2f)
                .putInt(KEY_MAX_TOKENS, 65536)
                .putString(KEY_THINKING, THINKING_ON)
                .putInt(KEY_CONTEXT_TOKENS, 1000000)
                .putInt(KEY_MAX_TOOL_ROUNDS, 24)
                .putInt(KEY_MODEL_PARAMS_VERSION, MODEL_PARAMS_VERSION)
                .apply();
    }

    private void ensureDefaultService() {
        if (prefs.contains(KEY_MODEL_SERVICES)) {
            return;
        }
        String base = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL);
        String key = prefs.getString(KEY_API_KEY, "");
        if (base == null || base.trim().isEmpty()) {
            base = DEFAULT_BASE_URL;
        }
        ModelService service = ModelService.create("", base.trim(), key);
        service.id = "default";
        JSONArray array = new JSONArray();
        array.put(service.toJson());
        prefs.edit()
                .putString(KEY_MODEL_SERVICES, array.toString())
                .putString(KEY_ACTIVE_SERVICE, service.id)
                .apply();
    }

    String baseUrl() {
        String value = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL);
        return value == null || value.trim().isEmpty() ? DEFAULT_BASE_URL : value.trim();
    }

    String model() {
        String value = prefs.getString(KEY_MODEL, DEFAULT_MODEL);
        return value == null || value.trim().isEmpty() ? DEFAULT_MODEL : value.trim();
    }

    String apiKey() {
        String value = prefs.getString(KEY_API_KEY, "");
        return value == null ? "" : value.trim();
    }

    boolean hasKey() {
        if (!apiKey().isEmpty()) {
            return true;
        }
        for (ModelService service : services()) {
            if (service.apiKey != null && !service.apiKey.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    boolean allowDeviceControl() {
        return prefs.getBoolean(KEY_ALLOW_DEVICE, false);
    }

    void setAllowDeviceControl(boolean allow) {
        prefs.edit().putBoolean(KEY_ALLOW_DEVICE, allow).apply();
    }

    String agentProfile() {
        String value = prefs.getString(KEY_AGENT_PROFILE, "");
        return value == null ? "" : value.trim();
    }

    void setAgentProfile(String profile) {
        prefs.edit().putString(KEY_AGENT_PROFILE, profile == null ? "" : profile.trim()).apply();
    }

    float temperature() {
        float value = prefs.getFloat(KEY_TEMPERATURE, 2f);
        if (Float.isNaN(value)) {
            return 2f;
        }
        return Math.max(0f, Math.min(2f, value));
    }

    int maxTokens() {
        int value = prefs.getInt(KEY_MAX_TOKENS, 65536);
        return Math.max(2048, Math.min(65536, value));
    }

    void setMaxTokens(int value) {
        prefs.edit().putInt(KEY_MAX_TOKENS, Math.max(2048, Math.min(65536, value))).apply();
    }

    String thinkingMode() {
        String value = prefs.getString(KEY_THINKING, THINKING_ON);
        if (THINKING_AUTO.equals(value) || THINKING_ON.equals(value)) {
            return value;
        }
        return THINKING_OFF;
    }

    int contextTokens() {
        int value = prefs.getInt(KEY_CONTEXT_TOKENS, -1);
        if (value <= 0) {
            if (prefs.contains(KEY_CONTEXT_CHARS)) {
                value = prefs.getInt(KEY_CONTEXT_CHARS, 24000) / 2;
            } else {
                value = 1000000;
            }
        }
        return Math.max(8000, Math.min(1000000, value));
    }

    int maxToolRounds() {
        int value = prefs.getInt(KEY_MAX_TOOL_ROUNDS, 24);
        return Math.max(8, Math.min(24, value));
    }

    void saveModelParams(float temperature, int maxTokens, String thinking,
                         int contextTokens, int maxToolRounds) {
        prefs.edit()
                .putFloat(KEY_TEMPERATURE, temperature)
                .putInt(KEY_MAX_TOKENS, maxTokens)
                .putString(KEY_THINKING, thinking)
                .putInt(KEY_CONTEXT_TOKENS, contextTokens)
                .putInt(KEY_MAX_TOOL_ROUNDS, maxToolRounds)
                .apply();
    }

    void save(String baseUrl, String model, String apiKey) {
        prefs.edit()
                .putString(KEY_BASE_URL, baseUrl.trim())
                .putString(KEY_MODEL, model.trim())
                .putString(KEY_API_KEY, apiKey.trim())
                .apply();
    }

    void saveApiKey(String apiKey) {
        prefs.edit().putString(KEY_API_KEY, apiKey == null ? "" : apiKey.trim()).apply();
    }

    void saveProvider(String baseUrl, String apiKey) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        SharedPreferences.Editor editor = prefs.edit().putString(KEY_BASE_URL, base);
        if (apiKey != null) {
            editor.putString(KEY_API_KEY, apiKey.trim());
        }
        editor.apply();
        ModelService service = serviceForBase(base);
        if (service != null) {
            service.baseUrl = base;
            if (apiKey != null) {
                service.apiKey = apiKey.trim();
            }
            saveService(service);
        }
    }

    List<ModelService> services() {
        List<ModelService> result = new ArrayList<ModelService>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_MODEL_SERVICES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                ModelService service = ModelService.fromJson(array.optJSONObject(i));
                if (service != null && !result.contains(service)) {
                    result.add(service);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    ModelService service(String id) {
        String key = id == null ? "" : id.trim();
        if (key.isEmpty()) {
            return null;
        }
        for (ModelService service : services()) {
            if (key.equals(service.id)) {
                return service;
            }
        }
        return null;
    }

    ModelService serviceForBase(String baseUrl) {
        String wanted = normalizeForCompare(baseUrl);
        if (wanted.isEmpty()) {
            return null;
        }
        for (ModelService service : services()) {
            if (wanted.equals(normalizeForCompare(service.baseUrl))) {
                return service;
            }
        }
        return null;
    }

    private String normalizeForCompare(String baseUrl) {
        try {
            return ModelCatalog.normalizeBase(baseUrl);
        } catch (Exception e) {
            return baseUrl == null ? "" : baseUrl.trim();
        }
    }

    String apiKeyFor(String serviceId, String baseUrl) {
        ModelService service = service(serviceId);
        if (service == null) {
            service = serviceForBase(baseUrl);
        }
        if (service != null && service.apiKey != null && !service.apiKey.trim().isEmpty()) {
            return service.apiKey.trim();
        }
        return apiKey();
    }

    String activeServiceId() {
        String id = prefs.getString(KEY_ACTIVE_SERVICE, "");
        if (id != null && !id.trim().isEmpty() && service(id) != null) {
            return id.trim();
        }
        List<ModelService> all = services();
        if (!all.isEmpty()) {
            return all.get(0).id;
        }
        return "";
    }

    void setActiveServiceId(String id) {
        prefs.edit().putString(KEY_ACTIVE_SERVICE, id == null ? "" : id.trim()).apply();
    }

    void saveService(ModelService service) {
        if (service == null || service.baseUrl == null || service.baseUrl.trim().isEmpty()) {
            return;
        }
        List<ModelService> all = services();
        if (service.id == null || service.id.trim().isEmpty()) {
            String id = "svc" + Long.toString(System.currentTimeMillis(), 36);
            while (service(id) != null) {
                id = id + "x";
            }
            service.id = id;
        }
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (service.id.equals(all.get(i).id)) {
                all.set(i, service);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            all.add(service);
        }
        JSONArray array = new JSONArray();
        for (ModelService item : all) {
            array.put(item.toJson());
        }
        prefs.edit()
                .putString(KEY_MODEL_SERVICES, array.toString())
                .putString(KEY_ACTIVE_SERVICE, activeServiceId().isEmpty()
                        ? service.id : activeServiceId())
                .apply();
    }

    void deleteService(String id) {
        String key = id == null ? "" : id.trim();
        if (key.isEmpty()) {
            return;
        }
        List<ModelService> all = services();
        JSONArray array = new JSONArray();
        for (ModelService service : all) {
            if (!key.equals(service.id)) {
                array.put(service.toJson());
            }
        }
        String active = activeServiceId();
        prefs.edit().putString(KEY_MODEL_SERVICES, array.toString()).apply();
        if (key.equals(active)) {
            List<ModelService> remaining = services();
            setActiveServiceId(remaining.isEmpty() ? "" : remaining.get(0).id);
        }
    }

    String normalizedBase() {
        try {
            return ModelCatalog.normalizeBase(baseUrl());
        } catch (IllegalArgumentException e) {
            return baseUrl();
        }
    }

    private String catalogKey(String baseUrl) {
        try {
            return ModelCatalog.normalizeBase(baseUrl);
        } catch (IllegalArgumentException e) {
            String raw = baseUrl == null ? "" : baseUrl.trim();
            return raw.isEmpty() ? normalizedBase() : raw;
        }
    }

    List<String> catalogModels(String baseUrl) {
        List<String> result = new ArrayList<String>();
        try {
            JSONObject root = new JSONObject(prefs.getString(KEY_MODEL_CATALOG, "{}"));
            JSONObject entry = root.optJSONObject(catalogKey(baseUrl));
            JSONArray models = entry == null ? null : entry.optJSONArray("models");
            if (models != null) {
                for (int i = 0; i < models.length(); i++) {
                    String id = models.optString(i, "").trim();
                    if (!id.isEmpty() && !result.contains(id)) {
                        result.add(id);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    long catalogFetchedAt(String baseUrl) {
        try {
            JSONObject root = new JSONObject(prefs.getString(KEY_MODEL_CATALOG, "{}"));
            JSONObject entry = root.optJSONObject(catalogKey(baseUrl));
            return entry == null ? 0L : entry.optLong("fetchedAt", 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    void saveCatalog(String baseUrl, List<ModelProfile> models) {
        if (models == null || models.isEmpty()) {
            return;
        }
        try {
            JSONObject root = new JSONObject(prefs.getString(KEY_MODEL_CATALOG, "{}"));
            JSONArray ids = new JSONArray();
            for (ModelProfile model : models) {
                if (model != null && model.id != null && !model.id.trim().isEmpty()) {
                    ids.put(model.id.trim());
                }
            }
            JSONObject entry = new JSONObject();
            entry.put("fetchedAt", System.currentTimeMillis());
            entry.put("models", ids);
            root.put(catalogKey(baseUrl), entry);
            prefs.edit().putString(KEY_MODEL_CATALOG, root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    String cachedProtocol(String baseUrl, String model) {
        try {
            JSONObject root = new JSONObject(prefs.getString(KEY_PROTOCOL_CACHE, "{}"));
            String key = catalogKey(baseUrl) + "|"  + (model == null ? "" : model.trim());
            if (!root.has(key)) {
                return "";
            }
            return ModelProtocols.normalize(root.optString(key, ""));
        } catch (Exception e) {
            return "";
        }
    }

    void cacheProtocol(String baseUrl, String model, String protocol) {
        try {
            JSONObject root = new JSONObject(prefs.getString(KEY_PROTOCOL_CACHE, "{}"));
            root.put(catalogKey(baseUrl) + "|"  + (model == null ? "" : model.trim()),
                    ModelProtocols.normalize(protocol));
            prefs.edit().putString(KEY_PROTOCOL_CACHE, root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    void clearProtocolCache(String baseUrl, String model) {
        try {
            JSONObject root = new JSONObject(prefs.getString(KEY_PROTOCOL_CACHE, "{}"));
            String key = catalogKey(baseUrl) + "|"  + (model == null ? "" : model.trim());
            if (root.has(key)) {
                root.remove(key);
                prefs.edit().putString(KEY_PROTOCOL_CACHE, root.toString()).apply();
            }
        } catch (Exception ignored) {
        }
    }

    void clearProtocolCacheAll() {
        prefs.edit().remove(KEY_PROTOCOL_CACHE).apply();
    }

    ModelProfile modelProfile(String baseUrl, String model) {
        String id = model == null ? "" : model.trim();
        if (id.isEmpty()) {
            return ModelProfile.infer("");
        }
        try {
            JSONObject root = new JSONObject(prefs.getString(KEY_MODEL_PROFILES, "{}"));
            JSONObject object = root.optJSONObject(catalogKey(baseUrl) + "\n" + id);
            if (object != null) {
                return ModelProfile.fromJson(object, id);
            }
        } catch (Exception ignored) {
        }
        return ModelProfile.infer(id);
    }

    void saveModelProfile(String baseUrl, ModelProfile profile) {
        if (profile == null || profile.id == null || profile.id.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject root = new JSONObject(prefs.getString(KEY_MODEL_PROFILES, "{}"));
            root.put(catalogKey(baseUrl) + "\n" + profile.id.trim(), profile.toJson());
            prefs.edit().putString(KEY_MODEL_PROFILES, root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    void clearModelProfile(String baseUrl, String model) {
        String id = model == null ? "" : model.trim();
        if (id.isEmpty()) {
            return;
        }
        try {
            JSONObject root = new JSONObject(prefs.getString(KEY_MODEL_PROFILES, "{}"));
            root.remove(catalogKey(baseUrl) + "\n" + id);
            prefs.edit().putString(KEY_MODEL_PROFILES, root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    String lastConversationId() {
        String value = prefs.getString(KEY_LAST_CONVERSATION, "");
        return value == null ? "" : value.trim();
    }

    void setLastConversationId(String id) {
        prefs.edit().putString(KEY_LAST_CONVERSATION, id == null ? "" : id.trim()).apply();
    }

    void setPendingTaskPrompt(String prompt) {
        prefs.edit().putString(KEY_PENDING_TASK_PROMPT,
                prompt == null ? "" : prompt).apply();
    }

    String takePendingTaskPrompt() {
        String value = prefs.getString(KEY_PENDING_TASK_PROMPT, "");
        prefs.edit().remove(KEY_PENDING_TASK_PROMPT).apply();
        return value == null ? "" : value;
    }

    void addPendingScreenshot(String path) {
        String value = path == null ? "" : path.trim();
        if (value.isEmpty()) {
            return;
        }
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_PENDING_SCREENSHOTS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                if (value.equals(array.optString(i, ""))) {
                    return;
                }
            }
            array.put(value);
            while (array.length() > 12) {
                array.remove(0);
            }
            prefs.edit().putString(KEY_PENDING_SCREENSHOTS, array.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    List<String> takePendingScreenshots() {
        List<String> result = new ArrayList<String>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_PENDING_SCREENSHOTS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                String path = array.optString(i, "").trim();
                if (!path.isEmpty()) {
                    result.add(path);
                }
            }
            prefs.edit().remove(KEY_PENDING_SCREENSHOTS).apply();
        } catch (Exception ignored) {
        }
        return result;
    }

    String pendingTaskPrompt() {
        String value = prefs.getString(KEY_PENDING_TASK_PROMPT, "");
        return value == null ? "" : value;
    }

    void clearPendingTaskPrompt() {
        prefs.edit().remove(KEY_PENDING_TASK_PROMPT).apply();
    }

    boolean allowWebTools() {
        return prefs.getBoolean(KEY_ALLOW_WEB, false);
    }

    void setAllowWebTools(boolean allow) {
        prefs.edit().putBoolean(KEY_ALLOW_WEB, allow).apply();
    }

    /** Global gate for .agent/hooks.json; the model cannot silently enable shell hooks. */
    boolean allowHooks() {
        return prefs.getBoolean(KEY_ALLOW_HOOKS, false);
    }

    void setAllowHooks(boolean allow) {
        prefs.edit().putBoolean(KEY_ALLOW_HOOKS, allow).apply();
    }


    boolean allowInstallAutomation() {
        return prefs.getBoolean(KEY_INSTALL_AUTOMATION, false);
    }

    void setAllowInstallAutomation(boolean allow) {
        prefs.edit().putBoolean(KEY_INSTALL_AUTOMATION, allow).apply();
    }

    boolean autoResumeInterrupted() {
        return prefs.getBoolean(KEY_AUTO_RESUME, false);
    }

    void setAutoResumeInterrupted(boolean allow) {
        prefs.edit().putBoolean(KEY_AUTO_RESUME, allow).apply();
    }

    boolean autoToolSelection() {
        return prefs.getBoolean(KEY_AUTO_TOOLS, true);
    }

    void setAutoToolSelection(boolean allow) {
        prefs.edit().putBoolean(KEY_AUTO_TOOLS, allow).apply();
    }

    /** Lite Harness: Small context, small rotation, fixed core tool, off autoinjection/Rewind. */
    boolean liteHarness() {
        return prefs.getBoolean(KEY_LITE_HARNESS, true);
    }

    /**
     * Breaking through the round, budget and window ceilings is the user's cost
     * decision, not the harness's: when this is on, even lite harness stops clamping.
     */
    boolean allowOverLimit() {
        return prefs.getBoolean(KEY_ALLOW_OVER_LIMIT, false);
    }

    void setAllowOverLimit(boolean enabled) {
        prefs.edit().putBoolean(KEY_ALLOW_OVER_LIMIT, enabled).apply();
    }

    void setLiteHarness(boolean enabled) {
        prefs.edit().putBoolean(KEY_LITE_HARNESS, enabled).apply();
    }

    String localModelPath() {
        String value = prefs.getString(KEY_LOCAL_MODEL, "");
        return value == null ? "" : value.trim();
    }

    void setLocalModelPath(String path) {
        prefs.edit().putString(KEY_LOCAL_MODEL, path == null ? "" : path.trim()).apply();
    }

    /** Agent Automatically host the bridge (Auto-processing other responses in the backstage) APP Request sent. */
    boolean bridgeAutoEnabled() {
        return prefs.getBoolean(KEY_BRIDGE_AUTO, true);
    }

    /** File debug data bridge: Default closes, only exposed when user is clearly on/Actions. */
    boolean debugBridgeEnabled() {
        return prefs.getBoolean(KEY_DEBUG_BRIDGE, false);
    }

    void setDebugBridgeEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DEBUG_BRIDGE, enabled).apply();
        if (!enabled) {
            prefs.edit().remove(KEY_DEBUG_TOKEN).apply();
        }
    }

    String debugBridgeToken() {
        String token = prefs.getString(KEY_DEBUG_TOKEN, "");
        if (token == null || token.trim().isEmpty()) {
            token = java.util.UUID.randomUUID().toString().replace("-", "")
                    + java.util.UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString(KEY_DEBUG_TOKEN, token).apply();
        }
        return token;
    }

    String rotateDebugBridgeToken() {
        String token = java.util.UUID.randomUUID().toString().replace("-", "")
                + java.util.UUID.randomUUID().toString().replace("-", "");
        prefs.edit().putString(KEY_DEBUG_TOKEN, token).apply();
        return token;
    }

    void setBridgeAutoEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BRIDGE_AUTO, enabled).apply();
    }

    /** Whether the bridge request is based on a local model only (see figure II).true # Don't come back to the clouds # */
    boolean bridgeLocalOnly() {
        return prefs.getBoolean(KEY_BRIDGE_LOCAL_ONLY, false);
    }

    void setBridgeLocalOnly(boolean only) {
        prefs.edit().putBoolean(KEY_BRIDGE_LOCAL_ONLY, only).apply();
    }

    /** Whether to automatically process all applications for bridge requests (default closure, automatic list only). */
    boolean bridgeAutoAll() {
        return prefs.getBoolean(KEY_BRIDGE_AUTO_ALL, false);
    }

    void setBridgeAutoAll(boolean all) {
        prefs.edit().putBoolean(KEY_BRIDGE_AUTO_ALL, all).apply();
    }

    boolean bridgeAutoFor(String app) {
        if (app == null || app.trim().isEmpty()) {
            return false;
        }
        if (bridgeAutoAll()) {
            return true;
        }
        return bridgeAutoApps().contains(app.trim());
    }

    java.util.Set<String> bridgeAutoApps() {
        java.util.Set<String> values = prefs.getStringSet(KEY_BRIDGE_AUTO_APPS, null);
        return values == null ? new java.util.HashSet<String>()
                : new java.util.HashSet<String>(values);
    }

    void addBridgeAutoApp(String app) {
        if (app == null || app.trim().isEmpty()) {
            return;
        }
        java.util.Set<String> values = bridgeAutoApps();
        if (values.add(app.trim())) {
            prefs.edit().putStringSet(KEY_BRIDGE_AUTO_APPS, values).apply();
        }
        noteBridgeApp(app);
    }

    void removeBridgeAutoApp(String app) {
        if (app == null) {
            return;
        }
        java.util.Set<String> values = bridgeAutoApps();
        if (values.remove(app.trim())) {
            prefs.edit().putStringSet(KEY_BRIDGE_AUTO_APPS, values).apply();
        }
    }

    java.util.Set<String> bridgeSeenApps() {
        java.util.Set<String> values = prefs.getStringSet(KEY_BRIDGE_SEEN_APPS, null);
        return values == null ? new java.util.HashSet<String>()
                : new java.util.HashSet<String>(values);
    }

    void noteBridgeApp(String app) {
        if (app == null || app.trim().isEmpty()) {
            return;
        }
        java.util.Set<String> values = bridgeSeenApps();
        if (values.add(app.trim())) {
            prefs.edit().putStringSet(KEY_BRIDGE_SEEN_APPS, values).apply();
        }
    }

    /** Whether to automatically retrieve the relevant code clips at the start of the task (Lite Default closes, avoiding redeals per round. */
    boolean smartContextEnabled() {
        return prefs.getBoolean(KEY_SMART_CONTEXT, false);
    }

    void setSmartContextEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SMART_CONTEXT, enabled).apply();
    }

    JSONObject debugSettings() {
        return LocalConfig.effectiveSettings(prefs);
    }
}
