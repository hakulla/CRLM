package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;

final class Conversation {
    static final String DEFAULT_TITLE = "New Dialogue";
    static final String APPROVAL_AUTO = "auto";
    static final String APPROVAL_ASK = "ask";
    static final String APPROVAL_READONLY = "readonly";
    static final String PREPROCESS_OFF = "off";
    static final String PREPROCESS_RULES = "rules";
    static final String PREPROCESS_MODEL = "model";
    static final String SOURCE_CLOUD = "cloud";
    static final String SOURCE_LOCAL = "local";

    static final class Summary {
        final String id;
        final String title;
        final String workspace;
        final String model;
        final long updatedAt;
        final long createdAt;
        final boolean archived;

        Summary(String id, String title, String workspace, String model, long updatedAt) {
            this(id, title, workspace, model, updatedAt, false);
        }

        Summary(String id, String title, String workspace, String model, long updatedAt,
                boolean archived) {
            this(id, title, workspace, model, updatedAt, updatedAt, archived);
        }

        Summary(String id, String title, String workspace, String model, long updatedAt,
                long createdAt, boolean archived) {
            this.id = id;
            this.title = title;
            this.workspace = workspace;
            this.model = model;
            this.updatedAt = updatedAt;
            this.createdAt = createdAt > 0L ? createdAt : updatedAt;
            this.archived = archived;
        }
    }

    String id = "";
    String title = DEFAULT_TITLE;
    long createdAt;
    long updatedAt;
    String workspace = "";
    String baseUrl = AppPrefs.DEFAULT_BASE_URL;
    String model = AppPrefs.DEFAULT_MODEL;
    float temperature = 0.2f;
    int maxTokens = 4096;
    String thinking = AppPrefs.THINKING_OFF;
    int contextTokens = 12000;
    int maxToolRounds = 12;
    boolean paused;
    boolean archived;
    long pausedAt;
    boolean runActive;
    long runStartedAt;
    int autoResumeCount;
    long lastReviewAt;
    String agentProfile = "";
    String approvalMode = APPROVAL_AUTO;
    String preprocessMode = PREPROCESS_RULES;
    String modelSource = SOURCE_CLOUD;
    /** Which configured gateway this conversation uses; empty falls back to baseUrl lookup. */
    String modelService = "";
    /** Transient request capabilities loaded from the per-model profile; never persisted. */
    boolean capTools = true;
    boolean capVision = true;
    boolean capThinking = true;
    String capEffort = ModelProfile.DEFAULT_EFFORT;
    String protocol = ModelProtocols.AUTO;
    boolean protocolAuto = true;
    /** Historical records retained losslessly; never use these strings as execution approval. */
    JSONArray allowedTools = new JSONArray();
    String contextDigest = "";
    JSONArray plan = new JSONArray();
    JSONArray messages = new JSONArray();
    JSONArray entries = new JSONArray();
    JSONArray workingSet = new JSONArray();
    static final int CURRENT_VERSION = 4;
    /** Tool scope was introduced in version 3; older files migrate from the legacy 0.19.1 scope. */
    private static final int TOOL_SCOPE_VERSION = 3;
    int currentVersion = CURRENT_VERSION;
    ToolScope toolScope = ToolScope.inherit();
    // Preserve unrecognized fields, attachments and exact unchanged settings across migrations.
    private JSONObject original = new JSONObject();
    private JSONObject baseline;

    static Conversation fromPrefs(AppPrefs prefs) {
        Conversation conversation = new Conversation();
        conversation.baseUrl = prefs.baseUrl();
        conversation.model = prefs.model();
        conversation.temperature = prefs.temperature();
        conversation.maxTokens = prefs.maxTokens();
        conversation.thinking = prefs.thinkingMode();
        conversation.contextTokens = prefs.contextTokens();
        conversation.maxToolRounds = prefs.maxToolRounds();
        conversation.agentProfile = prefs.agentProfile();
        ModelProfile profile = prefs.modelProfile(conversation.baseUrl, conversation.model);
        ModelService service = prefs.serviceForBase(conversation.baseUrl);
        conversation.modelService = service == null ? prefs.activeServiceId() : service.id;
        conversation.capTools = profile.tools;
        conversation.capVision = profile.vision;
        conversation.capThinking = profile.thinking;
        conversation.capEffort = profile.effort;
        ModelProtocols.Resolved resolved = ModelProtocols.resolve(prefs,
                conversation.baseUrl, conversation.model);
        conversation.protocol = resolved.protocol;
        conversation.protocolAuto = resolved.automatic;
        if (!profile.thinking) {
            conversation.thinking = AppPrefs.THINKING_OFF;
        }
        return conversation;
    }

    void copySettingsFrom(Conversation other) {
        if (other == null) {
            return;
        }
        workspace = other.workspace;
        baseUrl = other.baseUrl;
        model = other.model;
        temperature = other.temperature;
        maxTokens = other.maxTokens;
        thinking = other.thinking;
        contextTokens = other.contextTokens;
        maxToolRounds = other.maxToolRounds;
        agentProfile = other.agentProfile;
        approvalMode = other.approvalMode;
        preprocessMode = other.preprocessMode;
        modelSource = other.modelSource;
        modelService = other.modelService;
        toolScope = other.toolScope;
    }

    Summary summary(String workspaceLabel) {
        return new Summary(id, title, workspaceLabel, model, updatedAt, createdAt, archived);
    }

    JSONObject toJson() throws Exception {
        if (currentVersion != CURRENT_VERSION) throw new IllegalArgumentException("Unsupported conversation version");
        validateModes();
        JSONObject current = knownJson();
        JSONObject object = ToolRegistry.parseObject(ToolRegistry.canonical(original));
        java.util.Iterator<String> keys = current.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (baseline == null || !original.has(key)
                    || !ToolRegistry.canonical(current.opt(key)).equals(ToolRegistry.canonical(baseline.opt(key))))
                object.put(key, current.opt(key));
        }
        object.put("version", currentVersion);
        object.put("toolScope", toolScope.toJson()); // persist migration freeze, never retain legacy ALL
        if (object.has("dataVersion")) object.put("dataVersion", currentVersion);
        // Legacy run bookkeeping moved to AgentState; drop the old keys instead of carrying them forever.
        object.remove("runId");
        object.remove("runState");
        object.remove("taskState");
        object.remove("verificationLedger");
        object.remove("verificationLedgerHash");
        return object;
    }

    private JSONObject knownJson() throws Exception {
        JSONObject object = new JSONObject();
        object.put("version", currentVersion);
        object.put("id", id);
        object.put("title", title);
        object.put("createdAt", createdAt);
        object.put("updatedAt", updatedAt);
        object.put("workspace", workspace);
        object.put("baseUrl", baseUrl);
        object.put("model", model);
        object.put("temperature", temperature);
        object.put("maxTokens", maxTokens);
        object.put("thinking", thinking);
        object.put("contextTokens", contextTokens);
        object.put("maxToolRounds", maxToolRounds);
        object.put("paused", paused);
        object.put("archived", archived);
        object.put("pausedAt", pausedAt);
        object.put("runActive", runActive);
        object.put("runStartedAt", runStartedAt);
        object.put("autoResumeCount", autoResumeCount);
        object.put("lastReviewAt", lastReviewAt);
        object.put("agentProfile", agentProfile);
        object.put("approvalMode", approvalMode);
        object.put("preprocessMode", preprocessMode);
        object.put("modelSource", modelSource);
        object.put("modelService", modelService);
        if (allowedTools == null || contextDigest == null) throw new IllegalArgumentException("Missing retained conversation data");
        object.put("allowedTools", allowedTools);
        object.put("contextDigest", contextDigest);
        if (plan == null || messages == null || entries == null || toolScope == null)
            throw new IllegalArgumentException("Missing conversation data; refusing empty replacement");
        object.put("plan", plan);
        object.put("messages", messages);
        object.put("entries", entries);
        object.put("workingSet", workingSet);
        object.put("toolScope", toolScope.toJson());
        return object;
    }

    static Conversation fromJson(JSONObject object) {
        if (object == null) throw new IllegalArgumentException("Missing conversation JSON");
        object = ToolRegistry.parseObject(ToolRegistry.canonical(object));
        int version = versionOf(object);
        if (version < 1 || version > CURRENT_VERSION) throw new IllegalArgumentException("Unsupported conversation version: " + version);
        validateTypes(object);
        Conversation conversation = new Conversation();
        conversation.original = object;
        conversation.id = object.optString("id", "");
        if (!conversation.id.matches("[A-Za-z0-9_-]{4,80}")) throw new IllegalArgumentException("Invalid conversation id");
        conversation.title = object.optString("title", DEFAULT_TITLE);
        conversation.createdAt = object.optLong("createdAt", 0L);
        conversation.updatedAt = object.optLong("updatedAt", conversation.createdAt);
        conversation.workspace = object.optString("workspace", "");
        conversation.baseUrl = object.optString("baseUrl", AppPrefs.DEFAULT_BASE_URL);
        conversation.model = object.optString("model", AppPrefs.DEFAULT_MODEL);
        conversation.temperature = (float) object.optDouble("temperature", 0.2);
        conversation.maxTokens = Math.max(2048, Math.min(65536,
                object.optInt("maxTokens", 4096)));
        conversation.thinking = object.optString("thinking", AppPrefs.THINKING_OFF);
        int loadedContext = object.has("contextTokens") ? object.optInt("contextTokens")
                : object.optInt("contextChars", 24000) / 2;
        conversation.contextTokens = Math.max(8000, Math.min(1000000, loadedContext));
        conversation.maxToolRounds = Math.max(8, Math.min(24,
                object.optInt("maxToolRounds", 12)));
        conversation.paused = object.optBoolean("paused", false);
        conversation.archived = object.optBoolean("archived", false);
        conversation.pausedAt = object.optLong("pausedAt", 0L);
        conversation.runActive = object.optBoolean("runActive", false);
        conversation.runStartedAt = object.optLong("runStartedAt", 0L);
        conversation.autoResumeCount = object.optInt("autoResumeCount", 0);
        conversation.lastReviewAt = object.optLong("lastReviewAt", 0L);
        conversation.agentProfile = object.optString("agentProfile", "");
        conversation.approvalMode = object.optString("approvalMode", APPROVAL_AUTO);
        conversation.preprocessMode = object.optString("preprocessMode", PREPROCESS_RULES);
        conversation.modelSource = object.optString("modelSource", SOURCE_CLOUD);
        conversation.modelService = object.optString("modelService", "").trim();
        conversation.validateModes();
        JSONArray allowedTools = object.optJSONArray("allowedTools");
        conversation.allowedTools = allowedTools == null ? new JSONArray() : allowedTools;
        conversation.contextDigest = object.optString("contextDigest", "");
        JSONArray plan = object.optJSONArray("plan");
        conversation.plan = plan == null ? new JSONArray() : plan;
        JSONArray messages = object.optJSONArray("messages");
        conversation.messages = messages == null ? new JSONArray() : messages;
        JSONArray entries = object.optJSONArray("entries");
        conversation.entries = entries == null ? new JSONArray() : entries;
        JSONArray workingSet = object.optJSONArray("workingSet");
        conversation.workingSet = workingSet == null ? new JSONArray() : workingSet;
        if (object.has("toolScope")) conversation.toolScope = ToolScope.fromJson(object.optJSONObject("toolScope"));
        else if (version < TOOL_SCOPE_VERSION) conversation.toolScope = ToolScope.legacy0191();
        else throw new IllegalArgumentException("Missing current-version toolScope");
        if (version < TOOL_SCOPE_VERSION && (conversation.toolScope.isAll() || conversation.toolScope.isInherit()))
            conversation.toolScope = ToolScope.legacy0191();
        try { conversation.baseline = ToolRegistry.parseObject(ToolRegistry.canonical(conversation.knownJson())); }
        catch (Exception e) { throw new IllegalArgumentException("Conversation migration failed", e); }
        return conversation;
    }

    static int versionOf(JSONObject object) {
        Object value = object.has("version") ? object.opt("version") : object.opt("dataVersion");
        if (value == null) return 1;
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue())
            throw new IllegalArgumentException("Invalid conversation version");
        int version = ((Number) value).intValue();
        if (object.has("dataVersion") && (!(object.opt("dataVersion") instanceof Number)
                || ((Number) object.opt("dataVersion")).doubleValue() != version))
            throw new IllegalArgumentException("Conflicting conversation versions");
        return version;
    }

    private void validateModes() {
        if (!java.util.Arrays.asList(APPROVAL_AUTO, APPROVAL_ASK, APPROVAL_READONLY).contains(approvalMode)
                || !java.util.Arrays.asList(PREPROCESS_OFF, PREPROCESS_RULES, PREPROCESS_MODEL).contains(preprocessMode)
                || !java.util.Arrays.asList(SOURCE_CLOUD, SOURCE_LOCAL).contains(modelSource)
                || !java.util.Arrays.asList(AppPrefs.THINKING_OFF, AppPrefs.THINKING_AUTO, AppPrefs.THINKING_ON).contains(thinking))
            throw new IllegalArgumentException("Unknown conversation mode; migration blocked");
    }

    private static void validateTypes(JSONObject object) {
        for (String key : ("id title workspace baseUrl model thinking agentProfile approvalMode preprocessMode modelSource modelService contextDigest").split(" "))
            if (object.has(key) && !(object.opt(key) instanceof String)) throw new IllegalArgumentException("Invalid string: " + key);
        for (String key : ("messages entries plan allowedTools workingSet").split(" "))
            if (object.has(key) && !(object.opt(key) instanceof JSONArray)) throw new IllegalArgumentException("Invalid array: " + key);
        for (String key : ("toolScope").split(" "))
            if (object.has(key) && !(object.opt(key) instanceof JSONObject)) throw new IllegalArgumentException("Invalid object: " + key);
        for (String key : ("paused runActive archived").split(" "))
            if (object.has(key) && !(object.opt(key) instanceof Boolean)) throw new IllegalArgumentException("Invalid boolean: " + key);
        for (String key : ("createdAt updatedAt pausedAt runStartedAt lastReviewAt maxTokens contextTokens contextChars maxToolRounds autoResumeCount").split(" ")) {
            if (!object.has(key)) continue;
            Object value = object.opt(key);
            if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid number: " + key);
            java.math.BigDecimal n = new java.math.BigDecimal(value.toString());
            boolean intValue = key.startsWith("max") || key.startsWith("context") || "autoResumeCount".equals(key);
            if (n.stripTrailingZeros().scale() > 0 || n.compareTo(java.math.BigDecimal.valueOf(intValue ? Integer.MAX_VALUE : Long.MAX_VALUE)) > 0
                    || n.compareTo(java.math.BigDecimal.valueOf(intValue ? Integer.MIN_VALUE : Long.MIN_VALUE)) < 0)
                throw new IllegalArgumentException("Unrepresentable integer: " + key);
        }
        if (object.has("temperature") && (!(object.opt("temperature") instanceof Number)
                || !Float.isFinite(((Number) object.opt("temperature")).floatValue())))
            throw new IllegalArgumentException("Invalid temperature");
    }

    static String titleFrom(String input) {
        String text = input == null ? "" : input.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return DEFAULT_TITLE;
        }
        return text.length() <= 28 ? text : text.substring(0, 28) + "...";
    }

}
