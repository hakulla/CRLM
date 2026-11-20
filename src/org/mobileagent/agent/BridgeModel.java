package org.mobileagent.agent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Model distribution requested by bridge: cloud Key When you have a cloud, you have to give priority to the cloud, and you have to ensure the quality of the recommendation; you have to return to the local area when the cloud is not available.
 */
final class BridgeModel {

    static final String SYSTEM = "You're on the phone. Agent, I'm on the machine. APP Deal with smart requests."
            + "Output final Chinese results for users only: simple, directly visible, highest 3 Article, every sentence."
            + "Don't export the thought, the analysis, the analysis.JSON The answer to this question is: 'Package, template placeholders, multiple versions,"
            + "Don't say it.'Final answer:()'This type of space is available.";

    private BridgeModel() {
    }

    /** Back {text, source}; Failed to return all null. source Like local / cloud.  */
    static String[] ask(Context ctx, String prompt, String payload) {
        String user = prompt == null ? "" : prompt;
        if (payload != null && !payload.trim().isEmpty() && !"{}".equals(payload.trim())) {
            user = user + "\n\nData:" + payload;
        }
        AppPrefs prefs = new AppPrefs(ctx);
        boolean localOnly = prefs.bridgeLocalOnly();
        boolean hasCloud = prefs.hasKey();
        String key = prefs.apiKey();
        android.util.Log.i("AgentBridge", "ask hasCloud=" + hasCloud + " localOnly=" + localOnly
                + " keyLen=" + (key == null ? 0 : key.trim().length()));
        if (hasCloud && !localOnly) {
            String cloud = clean(askCloud(ctx, user));
            if (usable(cloud)) {
                return new String[]{cloud, "cloud"};
            }
            String local = clean(askLocal(ctx, user));
            if (usable(local)) {
                return new String[]{local, "local(Cloud results not available)"};
            }
        } else {
            String local = clean(askLocal(ctx, user));
            if (usable(local)) {
                return new String[]{local, "local"};
            }
            if (hasCloud && !localOnly) {
                String cloud = clean(askCloud(ctx, user));
                if (usable(cloud)) {
                    return new String[]{cloud, "cloud(Local results not available)"};
                }
            }
        }
        return null;
    }

    /** Clear it. think Labels, empty seats, repeat lines, guaranteed APP And what we get is a direct and visible final result. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String t = text;
        int think = t.indexOf("<think>");
        if (think >= 0) {
            int end = t.indexOf("</think>", think);
            if (end >= 0) {
                t = t.substring(0, think) + " " + t.substring(end + 8);
            } else {
                t = t.substring(0, think);
            }
        }
        t = t.replace("<think>", " ").replace("</think>", " ");
        String[] lines = t.split("\n");
        List<String> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) {
                if (!kept.isEmpty() && !kept.get(kept.size() - 1).isEmpty()) {
                    kept.add("");
                }
                continue;
            }
            if (isPlaceholder(line)) {
                continue;
            }
            String key = line.replaceAll("\\s+", " ");
            if (!seen.add(key)) {
                continue;
            }
            kept.add(line);
        }
        while (!kept.isEmpty() && kept.get(kept.size() - 1).isEmpty()) {
            kept.remove(kept.size() - 1);
        }
        String result = join(kept).trim();
        return result.length() > 1600 ? result.substring(0, 1600) + "..." : result;
    }

    private static boolean isPlaceholder(String line) {
        String compact = line.replaceAll("\\s+", "");
        if (compact.equals("()") || compact.equals("()") || compact.equals("{}")) {
            return true;
        }
        return compact.matches("^(The final answer.|Answer|Answer!|Answer|Output)[:: ]?[()(){}]*$");
    }

    private static String join(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    /** Availability of results: non-empty, not empty space/Repeat/Template continues. Short code (e. g.) OK, 3)It must be released. */
    private static boolean usable(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (t.length() < 2) {
            return false;
        }
        int same = 0;
        for (int i = 1; i < t.length(); i++) {
            if (t.charAt(i) == t.charAt(i - 1)) {
                same++;
            }
        }
        if (same * 3 > t.length()) {
            return false;
        }
        // Small models sometimes put'Think./Template Extension'As an output of the answer, such text cannot be displayed directly to APP
        String lower = t.toLowerCase();
        if (t.contains("User Query") || t.contains("In accordance with the above rules") || t.contains("Generate Response")
                || t.contains("I need to generate.") || lower.contains("let me")
                || lower.contains("the user asks") || repetitive(t)) {
            return false;
        }
        return true;
    }

    /** Repeating test: same paragraph 8~24 Word Text Repeat 5 Over and above, the degradation output of small models can be generally determined. */
    private static boolean repetitive(String text) {
        for (int len = 8; len <= 24; len += 8) {
            for (int i = 0; i + len <= text.length(); i += len) {
                String probe = text.substring(i, i + len);
                int count = 0;
                int from = 0;
                while (count < 5) {
                    int index = text.indexOf(probe, from);
                    if (index < 0) {
                        break;
                    }
                    count++;
                    from = index + len;
                }
                if (count >= 5) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String askLocal(Context ctx, String user) {
        try {
            AppPrefs prefs = new AppPrefs(ctx);
            String path = prefs.localModelPath();
            if (path == null || path.isEmpty() || !LocalLlm.available()
                    || !LocalTemplates.supports(path)) {
                return null;
            }
            if (!new java.io.File(path).exists()) {
                return null;
            }
            LocalLlm llm = new LocalLlm();
            if (!llm.load(path, 4, 2048)) {
                llm.close();
                return null;
            }
            try {
                String prompt = SYSTEM + "\n\nMission:" + user
                        + "\nOnly the final answer that can be displayed directly, and not the process of interpretation or reflection.";
                String out = llm.generate(prompt, 200, 0.2f);
                return out == null ? null : out.trim();
            } finally {
                llm.close();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String askCloud(Context ctx, String user) {
        try {
            AppPrefs prefs = new AppPrefs(ctx);
            String key = prefs.apiKey();
            if (key == null || key.trim().isEmpty()) {
                android.util.Log.i("AgentBridge", "cloud skipped: no key");
                return null;
            }
            Conversation settings = Conversation.fromPrefs(prefs);
            settings.thinking = AppPrefs.THINKING_OFF;
            settings.temperature = 0.2f;
            settings.maxTokens = 800;
            JSONArray messages = new JSONArray();
            messages.put(message("system", SYSTEM));
            messages.put(message("user", user));
            LlmClient.Result result = new LlmClient().chat(messages, null, key, settings);
            if (result == null) {
                android.util.Log.i("AgentBridge", "cloud result=null");
                return null;
            }
            if (result.error != null) {
                android.util.Log.i("AgentBridge", "cloud error=" + result.error);
                return null;
            }
            if (result.message == null) {
                android.util.Log.i("AgentBridge", "cloud message=null");
                return null;
            }
            String content = JsonText.string(result.message, "content");
            return content == null ? null : content.trim();
        } catch (Throwable e) {
            android.util.Log.i("AgentBridge", "cloud exception=" + e.getClass().getSimpleName()
                    + ":" + e.getMessage());
            return null;
        }
    }

    private static JSONObject message(String role, String content) throws Exception {
        JSONObject object = new JSONObject();
        object.put("role", role);
        object.put("content", content);
        return object;
    }
}
