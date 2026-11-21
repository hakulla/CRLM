package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The history of dialogue"Archiving Layer": Add earlier originals to .agent/archive/&lt;Session&gt;.jsonl, 
 * Only a index summary with a pointer in the context, if details are required context_search Check back.
 *
 * <p>The purpose is to keep the cost of each round in line."Current Working Set"It's not like you're talking to him.
 * The first one is not missing.UI It's... entries It is not affected in the first place.</p>
 */
final class ContextArchive {

    static final int DIGEST_MAX_CHARS = 2000;
    private static final int MAX_FACTS = 60;
    private static final int MAX_SNIPPET = 220;

    private ContextArchive() {
    }

    static File dir(File root) {
        return new File(root, ".agent/archive");
    }

    static File file(File root, String conversationId) {
        String safe = conversationId == null || conversationId.trim().isEmpty()
                ? "current" : conversationId.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(dir(root), safe + ".jsonl");
    }

    private static String label(String conversationId) {
        String safe = conversationId == null || conversationId.trim().isEmpty()
                ? "current" : conversationId.replaceAll("[^A-Za-z0-9._-]", "_");
        return ".agent/archive/" + safe + ".jsonl";
    }

    /**
     * - Put it on. [from, to) . To add the message to the archive, and return the index summary that can be inserted into the context (with line numbers and sha/Line-pointing pointer.
     */
    static String append(File root, String conversationId, JSONArray messages,
                         int from, int to) {
        File target = file(root, conversationId);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return "";
        }
        Map<String, String> callNames = new HashMap<>();
        Map<String, String> callPaths = new HashMap<>();
        StringBuilder facts = new StringBuilder();
        int firstLine = countLines(target) + 1;
        int line = firstLine;
        int archived = 0;
        try {
            Writer writer = new OutputStreamWriter(new FileOutputStream(target, true), "UTF-8");
            try {
                for (int i = Math.max(0, from); i < Math.min(to, messages.length()); i++) {
                    JSONObject message = messages.optJSONObject(i);
                    if (message == null) {
                        continue;
                    }
                    String role = MessageProtocol.role(message);
                    if ("system".equals(role)) {
                        continue;
                    }
                    if ("assistant".equals(role)) {
                        collectCalls(message, callNames, callPaths);
                    }
                    writer.write("{\"line\":" + line + ",\"msg\":" + message.toString() + "}\n");
                    String fact = factLine(role, message, callNames, callPaths, line);
                    if (fact != null && facts.length() < DIGEST_MAX_CHARS) {
                        facts.append(fact);
                    }
                    line++;
                    archived++;
                }
            } finally {
                writer.close();
            }
        } catch (Exception error) {
            return "";
        }
        if (archived == 0) {
            return "";
        }
        String header = "[Archive Index] " + archived + " earlier messages from "
                + label(conversationId) + " (lines " + firstLine + "-" + (line - 1)
                + ") were archived. The originals are not lost; use context_search when details are needed. "
                + "Do not guess the archived content.";
        StringBuilder digest = new StringBuilder(header).append('\n');
        String body = facts.toString();
        if (body.length() > DIGEST_MAX_CHARS) {
            body = body.substring(0, DIGEST_MAX_CHARS) + "...(More facts omitted)\n";
        }
        return digest.append(body).toString();
    }

    private static void collectCalls(JSONObject message, Map<String, String> names,
                                     Map<String, String> paths) {
        JSONArray calls = message.optJSONArray("tool_calls");
        if (calls == null) {
            return;
        }
        for (int c = 0; c < calls.length(); c++) {
            JSONObject call = calls.optJSONObject(c);
            if (call == null) {
                continue;
            }
            String id = call.optString("id", "");
            JSONObject function = call.optJSONObject("function");
            if (id.isEmpty() || function == null) {
                continue;
            }
            String name = function.optString("name", "");
            names.put(id, name);
            String args = function.optString("arguments", "");
            String path = "";
            try {
                path = new JSONObject(args).optString("path", "");
            } catch (Exception ignored) {
            }
            if (!path.isEmpty()) {
                paths.put(id, path);
            }
        }
    }

    private static String factLine(String role, JSONObject message, Map<String, String> names,
                                   Map<String, String> paths, int line) {
        if ("assistant".equals(role)) {
            return null;
        }
        if ("tool".equals(role)) {
            String id = message.optString("tool_call_id", "");
            String name = names.get(id);
            String path = paths.get(id);
            String text = message.optString("content", "");
            String outcome = summarize(text);
            StringBuilder fact = new StringBuilder("- [L").append(line).append("] ");
            if (name != null && !name.isEmpty()) {
                fact.append(name).append(' ');
            }
            if (path != null && !path.isEmpty()) {
                fact.append(shorten(path, 70)).append(' ');
            }
            fact.append(outcome).append('\n');
            return fact.toString();
        }
        String text = message.optString("content", "").trim();
        if (text.isEmpty()) {
            return null;
        }
        return "- [L" + line + "] User/System:" + shorten(oneLine(text), 90) + "\n";
    }

    /** The result of the tool is a sentence: give the first line of the line when success is achieved, and give the error when failure is made. */
    private static String summarize(String content) {
        if (content == null || content.isEmpty()) {
            return "(Empty results)";
        }
        String flat = oneLine(content);
        String lower = flat.toLowerCase(Locale.ROOT);
        boolean failed = lower.contains("\"ok\":false") || lower.startsWith("error") 
                || lower.contains("\"error\"");
        String head = flat;
        try {
            JSONObject object = new JSONObject(content);
            if (object.has("ok") && !object.optBoolean("ok", true)) {
                failed = true;
            }
            String error = object.optString("error", "");
            String data = object.optString("data", "");
            if (!error.isEmpty()) {
                failed = true;
                head = error;
            } else if (!data.isEmpty()) {
                head = data;
            }
        } catch (Exception ignored) {
        }
        return (failed ? "Failed:" : "") + shorten(oneLine(head), 110);
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** Archive retrieval: Recheck the original text by keyword and return the section with line numbers for the model to read on demand. */
    static String search(File root, String conversationId, String query, int limit) {
        String text = query == null ? "" : query.trim();
        if (text.isEmpty()) {
            return error("Missing query");
        }
        File target = file(root, conversationId);
        if (!target.isFile()) {
            return error("This session is not filed.");
        }
        String[] terms = text.toLowerCase(Locale.ROOT).split("\\s+");
        List<String> hits = new ArrayList<>();
        int total = 0;
        int scanned = 0;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(target), "UTF-8"));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    scanned++;
                    String lower = line.toLowerCase(Locale.ROOT);
                    boolean match = true;
                    for (String term : terms) {
                        if (!term.isEmpty() && !lower.contains(term)) {
                            match = false;
                            break;
                        }
                    }
                    if (!match) {
                        continue;
                    }
                    total++;
                    if (hits.size() < Math.max(1, Math.min(limit, 20))) {
                        hits.add(snippet(line, terms));
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            return error("Reading archive failed:" + e.getMessage());
        }
        StringBuilder out = new StringBuilder();
        out.append("[Archive Search] ").append(label(conversationId))
                .append(" Total ").append(scanned).append(" - The bar. - Hit. ").append(total)
                .append(" Article").append(hits.size() < total ? "(Just ahead " + hits.size() + " 'Live key words, please'" : "")
                .append(": \n");
        for (String hit : hits) {
            out.append(hit).append('\n');
        }
        return ok(out.toString());
    }

    private static String snippet(String jsonLine, String[] terms) {
        int lineNo = 0;
        String body = jsonLine;
        try {
            JSONObject wrapper = new JSONObject(jsonLine);
            lineNo = wrapper.optInt("line", 0);
            JSONObject message = wrapper.optJSONObject("msg");
            if (message != null) {
                String role = message.optString("role", "");
                String content = message.optString("content", "");
                String id = message.optString("tool_call_id", "");
                body = role + (id.isEmpty() ? "" : " " + id) + ": " + oneLine(content);
            }
        } catch (Exception ignored) {
        }
        int at = -1;
        String lower = body.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (!term.isEmpty()) {
                int found = lower.indexOf(term);
                if (found >= 0 && (at < 0 || found < at)) {
                    at = found;
                }
            }
        }
        int from = Math.max(0, at - 60);
        String cut = body.substring(Math.min(from, body.length()));
        if (at > 60) {
            cut = "..." + cut;
        }
        return "L" + lineNo + " " + shorten(cut, MAX_SNIPPET);
    }

    static int countLines(File file) {
        if (file == null || !file.isFile()) {
            return 0;
        }
        int lines = 0;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), "UTF-8"));
            try {
                while (reader.readLine() != null) {
                    lines++;
                }
            } finally {
                reader.close();
            }
        } catch (Exception ignored) {
        }
        return lines;
    }

    static long bytes(File root, String conversationId) {
        File target = file(root, conversationId);
        return target.isFile() ? target.length() : 0L;
    }

    /** Merge old summaries with new summaries: retain head-head sessions to sink and facts cut out as new to old. */
    static String merge(String previous, String digest) {
        String old = previous == null ? "" : previous.trim();
        String fresh = digest == null ? "" : digest.trim();
        if (fresh.isEmpty()) {
            return old;
        }
        if (old.isEmpty()) {
            return fresh.length() <= DIGEST_MAX_CHARS ? fresh : fresh.substring(0, DIGEST_MAX_CHARS);
        }
        String merged = old + "\n" + fresh;
        if (merged.length() <= DIGEST_MAX_CHARS) {
            return merged;
        }
        return merged.substring(merged.length() - DIGEST_MAX_CHARS);
    }

    private static String ok(String data) {
        try {
            return new JSONObject().put("ok", true).put("data", data).toString();
        } catch (Exception e) {
            return "{\"ok\":true,\"data\":\"\"}";
        }
    }

    private static String error(String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"Archive operation failed\"}";
        }
    }
}
