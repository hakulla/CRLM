package org.mobileagent.agent;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class EvolutionEngine {
    private static final String TAG = "AgentEvolve";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Set<String> IN_FLIGHT = Collections.synchronizedSet(new HashSet<String>());
    private static final Map<String, Request> PENDING =
            Collections.synchronizedMap(new HashMap<String, Request>());

    interface Callback {
        void onDone(Result result);
    }

    static final class Result {
        final boolean skipped;
        final String summary;
        final String reportPath;
        final String error;

        Result(boolean skipped, String summary, String reportPath, String error) {
            this.skipped = skipped;
            this.summary = summary;
            this.reportPath = reportPath;
            this.error = error;
        }
    }

    private static final class Counts {
        int memory;
        int lessons;
        int skills;
        int agents;
        int knowledge;
        int fixes;
        int removed;

        int total() {
            return memory + lessons + skills + agents + knowledge + fixes + removed;
        }
    }

    private static final class Request {
        final Context context;
        final AppPrefs prefs;
        final Workspace workspace;
        final Conversation settings;
        final String conversationId;
        final String digest;
        final String existing;
        final Callback callback;

        Request(Context context, AppPrefs prefs, Workspace workspace,
                Conversation settings, String conversationId, String digest,
                String existing, Callback callback) {
            this.context = context;
            this.prefs = prefs;
            this.workspace = workspace;
            this.settings = settings;
            this.conversationId = conversationId;
            this.digest = digest;
            this.existing = existing;
            this.callback = callback;
        }
    }

    private EvolutionEngine() {
    }

    static void review(Context context, AppPrefs prefs, Workspace workspace,
                       Conversation settings, String conversationId, String digest,
                       String existingSummary, Callback callback) {
        if (digest == null || digest.trim().isEmpty()) {
            callback.onDone(new Result(true, "Rewind: No revolving dialogue.", "", null));
            return;
        }
        Request request = new Request(context.getApplicationContext(), prefs, workspace,
                snapshot(settings), conversationId, digest,
                existingSummary == null ? "" : existingSummary, callback);
        synchronized (PENDING) {
            if (IN_FLIGHT.contains(conversationId)) {
                PENDING.put(conversationId, request);
                Log.i(TAG, "queued review conversation=" + conversationId);
                return;
            }
            IN_FLIGHT.add(conversationId);
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                runRequest(request);
            }
        });
    }

    private static void runRequest(Request request) {
        Result result;
        try {
            result = EvolutionEngine.runReview(request.context, request.prefs,
                    request.workspace, request.settings, request.digest, request.existing);
        } catch (Throwable error) {
            Log.w(TAG, "review failed", error);
            result = new Result(false, "", "",
                    error.getMessage() == null
                            ? error.getClass().getSimpleName() : error.getMessage());
        }
        try {
            request.callback.onDone(result);
        } catch (Throwable error) {
            Log.w(TAG, "review callback failed", error);
        }
        Request next;
        synchronized (PENDING) {
            next = PENDING.remove(request.conversationId);
            if (next == null) {
                IN_FLIGHT.remove(request.conversationId);
            }
        }
        if (next != null) {
            final Request pending = next;
            EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    runRequest(pending);
                }
            });
        }
    }

    private static Conversation snapshot(Conversation source) {
        Conversation settings = new Conversation();
        if (source != null) {
            settings.baseUrl = source.baseUrl;
            settings.model = source.model;
            settings.modelSource = source.modelSource;
        }
        settings.temperature = 0.1f;
        settings.maxTokens = 1500;
        settings.thinking = AppPrefs.THINKING_OFF;
        return settings;
    }

    private static Result runReview(Context context, AppPrefs prefs, Workspace workspace,
                                    Conversation settings, String digest, String existing) {
        Capabilities caps = new Capabilities(workspace);
        String localName = localLabel(context);
        // Operational outcomes are the evidence base for evolution; keep one
        // structured record store instead of re-deriving lessons from chat text.
        String recentExperience = ExperienceStore.render(
                new ExperienceStore(workspace.root()).recent(8));
        String contextEvidence = existing == null ? "" : existing;
        if (!recentExperience.isEmpty()) {
            contextEvidence = contextEvidence
                    + "\n\n[Recent running experience (auto-record)]\n" + recentExperience;
        }
        String triage = localTriage(context, digest, contextEvidence);
        Log.i(TAG, "triage=" + triage);
        if ("SKIP".equals(triage)) {
            return new Result(true, "Rewind: Local judgment does not require deposition." + localName + ")",
                    "", null);
        }
        if ("DEPOSIT".equals(triage)) {
            // Local small models are not stable in sediment mass; clouds are left to cloud-based writing, and clouds are not available to cover local depths.
            triage = "ESCALATE";
        }
        WorkSupervisor supervisor = WorkSupervisor.get(context);
        supervisor.evolution.snapshotEvolution("before-review");
        boolean cloudAllowed = prefs.hasKey()
                && !Conversation.SOURCE_LOCAL.equals(settings.modelSource);
        if (cloudAllowed) {
            Result cloud = cloudReview(prefs, caps, settings, digest, contextEvidence,
                    cloudLabel(settings));
            if (cloud != null) {
                supervisor.evolution.recordReview(cloud.summary, settings.id);
                return cloud;
            }
        }
        Result local = localDeposit(context, caps, digest,
                prefs.hasKey() ? localName + ", Clouds are not available" : localName);
        if (local != null) {
            supervisor.evolution.recordReview(local.summary, settings.id);
            return local;
        }
        return new Result(false, "", "",
                "Local models are not available or failed" + (cloudAllowed ? ", The cloud-repeated disk failed."
                        : "; Local mode forbids automatic clouding, not active cloud repeat"));
    }

    private static String localTriage(Context context, String digest, String existing) {
        LocalAi ai = LocalAi.get(context);
        if (!ai.backendAvailable() || ai.selectedFile() == null) {
            return "";
        }
        String system = "You're a cell phone.Agent. Based on the session summary, you will be asked whether you need to redisclose after the mission has ended.\n"
                + "Just one word, don't explain:\n"
                + "SKIP = We're talking, we're asking questions, we're not having a new preference./New facts/Error, no reusable process, no sedimentation\n"
                + "ESCALATE = Any other: worth sinking, error./User correction, reusable process, capability gap, or you're not sure\n"
                + "Failed tools, failed construction, user correction, claimed completion without validation, all the same ESCALATE. \n"
                + "Example:\n"
                + "The user asks for the weather today,Agent Answer the question. -> SKIP\n"
                + "The user says that the answer is to come to a conclusion.Agent Remember. -> ESCALATE\n"
                + "Positioning three times after construction failure aapt2 Resource error and fix -> ESCALATE\n"
                + "Answer:";
        String user = "[Session Summary]\n" + trim(digest, 1600)
                + "\n\n[♪ We've been settled ♪\n" + trim(existing, 700);
        String raw = localGenerate(ai, buildPrompt(system, user), 128);
        Log.i(TAG, "triage raw=" + (raw == null ? "null"
                : raw.replace('\n', ' ').substring(0, Math.min(120, raw.length()))));
        if (raw == null) {
            return "";
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.contains("ESCALATE") || raw.contains("Upgrade")
                || raw.contains("Need to be stronger.")) {
            return "ESCALATE";
        }
        if (upper.contains("SKIP") || raw.contains("No need.")
                || raw.contains("Skip") || raw.contains("No, I don't.")) {
            return "SKIP";
        }
        if (upper.contains("DEPOSIT")) {
            return "ESCALATE";
        }
        return "";
    }

    private static Result localDeposit(Context context, Capabilities caps,
                                       String digest, String source) {
        LocalAi ai = LocalAi.get(context);
        if (!ai.backendAvailable() || ai.selectedFile() == null) {
            return null;
        }
        String system = "You're a cell phone.Agent. According to the session summary, most of the data is extracted from the data. 3 Long-term memory and 3 Lessons learned.\n"
                + "For each line, only one format is to be produced:\n"
                + "MEMORY: Stable user preferences or project facts, no more80Word\n"
                + "LESSON: Next error that should be avoided or steps that should be taken, no more than100Word\n"
                + "Without a sediment, just output. NONE. Do not output an explanation, a title or an empty line.\n";
        String raw = localGenerate(ai, buildPrompt(system, trim(digest, 1600)), 220);
        if (raw == null) {
            return null;
        }
        List<String> memory = new ArrayList<>();
        List<String> lessons = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String clean = cleanLine(line);
            if (clean.startsWith("MEMORY:")) {
                String value = cleanLine(clean.substring(7));
                if (!value.isEmpty() && memory.size() < 3 && isStableMemory(value)) {
                    memory.add(value);
                }
            } else if (clean.startsWith("LESSON:")) {
                String value = cleanLine(clean.substring(7));
                if (!value.isEmpty() && lessons.size() < 3 && isUsefulLesson(value)) {
                    lessons.add(value);
                }
            }
        }
        if (memory.isEmpty() && lessons.isEmpty()) {
            return null;
        }
        Counts counts = new Counts();
        for (String line : memory) {
            if (caps.appendMemory(line)) {
                counts.memory++;
            }
        }
        for (String line : lessons) {
            if (caps.appendLesson(line)) {
                counts.lessons++;
            }
        }
        String report = "# Rewind Report\n\n- Source:" + source
                + "\n- Time:" + now() + "\n- Summary:\n\n" + trim(digest, 1500)
                + "\n\n## Actions\n\n- Memory +" + counts.memory
                + "\n- Experience +" + counts.lessons + "\n";
        String path = caps.writeEvolutionReview(report);
        if (counts.total() == 0) {
            return new Result(true, "Rewind: Local checks completed, no new sedimentation. ("
                    + source + ")", path, null);
        }
        return new Result(false, "Rewind completed (%1)" + source + "): Memory +" + counts.memory
                + ", Experience +" + counts.lessons + ". ", path, null);
    }

    private static String localLabel(Context context) {
        try {
            java.io.File file = LocalAi.get(context).selectedFile();
            if (file != null) {
                String name = file.getName();
                if (name.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
                    name = name.substring(0, name.length() - 5);
                }
                if (name.length() > 42) {
                    name = name.substring(0, 42) + "...";
                }
                return "Local " + name;
            }
        } catch (Throwable ignored) {
        }
        return "Local Model";
    }

    private static String cloudLabel(Conversation settings) {
        String model = settings == null ? "" : settings.model;
        return "Clouds " + (model == null || model.trim().isEmpty()
                ? "Model" : model.trim());
    }

    private static boolean isStableMemory(String value) {
        if (value.length() < 8 || value.length() > 80) {
            return false;
        }
        if (containsAny(value, "This time.", "This time.", "Just now.", "Success", "Implemented", "Today")) {
            return false;
        }
        return containsAny(value, "User", "Later", "Remember.", "Default", "Prefer", "Item",
                "Path", "Build", "Model", "Environment", "Don't.", "Always", "Workspace");
    }

    private static boolean isUsefulLesson(String value) {
        if (value.length() < 15) {
            return false;
        }
        if (containsAny(value, "Potential problems", "Watch it.", "Make sure it's right.")) {
            return false;
        }
        return containsAny(value, "Avoid", "Next time.", "Reactions", "Don't.", "Result",
                "Because...", "First", "I have to.", "Change", "Inspection");
    }

    private static boolean containsAny(String text, String... words) {
        if (text == null) {
            return false;
        }
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static Result cloudReview(AppPrefs prefs, Capabilities caps,
                                      Conversation settings, String digest,
                                      String existing, String source) {
        String system = "You are.CRLM The reset and self-evolution engine."
                + "Only if it's of long-term value. JSON, Don't. markdown Code block:\n"
                + "{\"verdict\":\"noneordeposit\",\"reason\":\"One word.\","
                + "\"memory\":[\"Stable preferences/Facts, most of them.3Article, every article<=80Word\"],"
                + "\"lessons\":[\"Mistake lessons/Next time, tops.4Article, every article<=100Word\"],"
                + "\"skills\":[{\"name\":\"Short-line English Name\",\"description\":\"One word.\",\"body\":\"MarkdownStep,<=1200Word\"}],"
                + "\"agents\":[{\"name\":\"Name\",\"instructions\":\"The role and boundaries,<=600Word\"}],"
                + "\"knowledge\":[{\"name\":\"Filename\",\"content\":\"<=2000Word\"}],"
                + "\"remove_memory\":[\"Full of old memories in conflict with the latest demands\"],"
                + "\"fixes\":[{\"title\":\"Short Title\",\"problem\":\"Problem\",\"evidence\":\"Evidence\",\"suggestion\":\"Recommendations for rehabilitation\"}]}\n"
                + "Rules:\n"
                + "1. Reuseable, stable, verifiable content only; no one-time task details, keys, passwords or privacy are recorded.\n"
                + "2. Skills/SonAgent/Knowledge is only exported when there is a real value for reuse; it is better not to be wasted.\n"
                + "3. fixes Output only when a repeat error, user correction or capability gap occurs; problem specific to tool/Documentation/Command.\n"
                + "4. Do not repeat re-export content that duplicates existing sediment; outdated memories are only inserted when there is a clear conflict remove_memory. \n"
                + "5. Unnecessary output {\"verdict\":\"none\"}. \n";
        String user = "[Session Summary]\n" + trim(digest, 2600)
                + "\n\n[♪ We've been settled ♪\n" + trim(existing, 1800);
        JSONArray messages = new JSONArray();
        try {
            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", system);
            messages.put(systemMessage);
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", user);
            messages.put(userMessage);
        } catch (Exception e) {
            return null;
        }
        LlmClient client = new LlmClient();
        LlmClient.Result result = client.chat(messages, null, prefs.apiKey(), settings);
        if (result == null || result.error != null || result.message == null) {
            Log.w(TAG, "cloud review failed: "
                    + (result == null ? "null" : result.error));
            return null;
        }
        String content = JsonText.string(result.message, "content");
        JSONObject json = parseJson(content);
        if (json == null) {
            Log.w(TAG, "cloud review json parse failed");
            return null;
        }
        String verdict = json.optString("verdict", "deposit").toLowerCase(Locale.ROOT);
        Counts counts = "none".equals(verdict) ? new Counts()
                : applyJson(caps, json, "cloud:" + settings.model);
        String report = "# Rewinding report (close)\n\n- Time:" + now()
                + "\n- verdict: " + verdict
                + "\n- reason: " + cleanLine(json.optString("reason", ""))
                + "\n\n## Summary of Session\n\n" + trim(digest, 2000)
                + "\n\n## Model Output\n\n```json\n" + trim(content, 4000) + "\n```\n"
                + "\n## Action Statistics\n\n- Memory +" + counts.memory
                + "\n- Experience +" + counts.lessons
                + "\n- Skills +" + counts.skills
                + "\n- SonAgent +" + counts.agents
                + "\n- Knowledge +" + counts.knowledge
                + "\n- Pending rehabilitation +" + counts.fixes
                + "\n- Remove Memory " + counts.removed + "\n";
        String path = caps.writeEvolutionReview(report);
        if (counts.total() == 0) {
            return new Result(true, "Rewind: Checked, no sedimentation required." + source + ")",
                    path, null);
        }
        StringBuilder summary = new StringBuilder("Retrodisc complete:");
        if (counts.memory > 0) summary.append("Memory +").append(counts.memory).append(", ");
        if (counts.lessons > 0) summary.append("Experience +").append(counts.lessons).append(", ");
        if (counts.skills > 0) summary.append("Skills +").append(counts.skills).append(", ");
        if (counts.agents > 0) summary.append("SonAgent +").append(counts.agents).append(", ");
        if (counts.knowledge > 0) summary.append("Knowledge +").append(counts.knowledge).append(", ");
        if (counts.fixes > 0) summary.append("Pending rehabilitation +").append(counts.fixes).append(", ");
        if (counts.removed > 0) summary.append("Remove Memory ").append(counts.removed).append(", ");
        summary.setLength(summary.length() - 1);
        summary.append(". (").append(source).append(")");
        if (counts.fixes > 0) {
            summary.append("The rehabilitation proposal has been documented and the next task will be prioritized.");
        }
        if (!path.isEmpty()) {
            summary.append("Reporting:").append(path);
        }
        return new Result(false, summary.toString(), path, null);
    }

    private static Counts applyJson(Capabilities caps, JSONObject json, String source) {
        Counts counts = new Counts();
        for (String line : strings(json.optJSONArray("memory"), 3)) {
            if (caps.appendMemory(line)) {
                counts.memory++;
            }
        }
        for (String line : strings(json.optJSONArray("lessons"), 4)) {
            if (caps.appendLesson(line)) {
                counts.lessons++;
            }
        }
        JSONArray skills = json.optJSONArray("skills");
        if (skills != null) {
            for (int i = 0; i < skills.length() && i < 2; i++) {
                JSONObject skill = skills.optJSONObject(i);
                if (skill == null) {
                    continue;
                }
                String name = cleanLine(skill.optString("name", ""));
                String body = trim(JsonText.string(skill, "body"), 3000);
                if (name.isEmpty() || body.length() < 20) {
                    continue;
                }
                if (!caps.writeCandidate("skill", name,
                        cleanLine(skill.optString("description", "")) + "\n\n" + body,
                        source).isEmpty()) {
                    counts.skills++;
                }
            }
        }
        JSONArray agents = json.optJSONArray("agents");
        if (agents != null) {
            for (int i = 0; i < agents.length() && i < 2; i++) {
                JSONObject agent = agents.optJSONObject(i);
                if (agent == null) {
                    continue;
                }
                String name = cleanLine(agent.optString("name", ""));
                String instructions = trim(JsonText.string(agent, "instructions"), 2000);
                if (!name.isEmpty() && instructions.length() >= 20
                        && !caps.writeCandidate("agent", name, instructions,
                        source).isEmpty()) {
                    counts.agents++;
                }
            }
        }
        JSONArray knowledge = json.optJSONArray("knowledge");
        if (knowledge != null) {
            for (int i = 0; i < knowledge.length() && i < 2; i++) {
                JSONObject item = knowledge.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String name = cleanLine(item.optString("name", ""));
                String content = trim(JsonText.string(item, "content"), 3000);
                if (!name.isEmpty() && content.length() >= 20
                        && !caps.writeCandidate("knowledge", name, content,
                        source).isEmpty()) {
                    counts.knowledge++;
                }
            }
        }
        for (String line : strings(json.optJSONArray("remove_memory"), 3)) {
            if (caps.removeMemoryLine(line)) {
                counts.removed++;
            }
        }
        JSONArray fixes = json.optJSONArray("fixes");
        if (fixes != null) {
            for (int i = 0; i < fixes.length() && i < 3; i++) {
                JSONObject fix = fixes.optJSONObject(i);
                if (fix == null) {
                    continue;
                }
                String title = cleanLine(fix.optString("title", "Capacity improvements"));
                String problem = trim(JsonText.string(fix, "problem"), 1200);
                String evidence = trim(JsonText.string(fix, "evidence"), 1200);
                String suggestion = trim(JsonText.string(fix, "suggestion"), 1200);
                if (problem.length() >= 6
                        && !caps.writeEvolutionProposal(title, problem, evidence,
                        suggestion).isEmpty()) {
                    counts.fixes++;
                }
            }
        }
        return counts;
    }

    private static List<String> strings(JSONArray array, int max) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (int i = 0; i < array.length() && values.size() < max; i++) {
            String value = cleanLine(array.optString(i, ""));
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private static String buildPrompt(String system, String user) {
        return "<|im_start|>system\n" + system + "<|im_end|>\n"
                + "<|im_start|>user\n" + user + "\n<|im_end|>\n"
                + "<|im_start|>assistant\n";
    }

    private static String localGenerate(LocalAi ai, String prompt, int maxTokens) {
        try {
            LocalAi.ChatResult result = ai.streamChat(prompt, maxTokens, 0.0f, null);
            if (result == null || result.error != null || result.text == null) {
                return null;
            }
            String text = result.text;
            int end = text.indexOf("<|im_end|>");
            if (end >= 0) {
                text = text.substring(0, end);
            }
            text = text.replaceAll("(?s)\u003cthink\u003e.*?\u003c/think\u003e", " ");
            text = text.trim();
            return text.isEmpty() ? null : text;
        } catch (Throwable error) {
            Log.w(TAG, "local generate failed", error);
            return null;
        }
    }

    private static JSONObject parseJson(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return new JSONObject(text.substring(start, end + 1));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String cleanLine(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.replace('\n', ' ').replace('\r', ' ').trim();
        clean = clean.replaceAll("sk-[A-Za-z0-9_-]{8,}", "sk-***");
        clean = clean.replaceAll("(?i)(api[_ ]?key|token|password|Password|Password)\\s*[:: =]\\s*\\S+",
                "$1:***");
        if (clean.startsWith("- ")) {
            clean = clean.substring(2).trim();
        }
        if (clean.length() > 240) {
            clean = clean.substring(0, 240) + "...";
        }
        return clean;
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        String clean = text.trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
    }
}
