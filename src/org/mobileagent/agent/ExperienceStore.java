package org.mobileagent.agent;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local experience index: typed records + BM25 retrieval over goals, strategies and outcomes. */
final class ExperienceStore {
    static final class Record {
        final String id;
        final long at;
        final String goal;
        final String caps;
        final String strategies;
        final String outcome;
        final String error;
        final String lesson;
        final int rounds;
        final long tokens;

        Record(String id, long at, String goal, String caps, String strategies,
               String outcome, String error, String lesson, int rounds, long tokens) {
            this.id = id;
            this.at = at;
            this.goal = goal == null ? "" : goal;
            this.caps = caps == null ? "" : caps;
            this.strategies = strategies == null ? "" : strategies;
            this.outcome = outcome == null ? "" : outcome;
            this.error = error == null ? "" : error;
            this.lesson = lesson == null ? "" : lesson;
            this.rounds = rounds;
            this.tokens = tokens;
        }

        String text() {
            return goal + " " + strategies + " " + outcome + " " + error + " " + lesson;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("at", at).put("goal", goal)
                    .put("caps", caps).put("strategies", strategies)
                    .put("outcome", outcome).put("error", error).put("lesson", lesson)
                    .put("rounds", rounds).put("tokens", tokens);
        }

        static Record fromJson(JSONObject object) {
            return new Record(object.optString("id"), object.optLong("at"),
                    object.optString("goal"), object.optString("caps"),
                    object.optString("strategies"), object.optString("outcome"),
                    object.optString("error"), object.optString("lesson"),
                    object.optInt("rounds"), object.optLong("tokens"));
        }
    }

    private static final Pattern ASCII = Pattern.compile("[a-z0-9_]{2,}");
    private static final int MAX_RECORDS = 500;

    private final File dir;
    private final File file;
    private final List<Record> records = new ArrayList<>();
    private final Map<String, Integer> df = new HashMap<>();
    private final Set<String> ids = new HashSet<>();
    private double avgLength = 1.0;

    ExperienceStore(File workspaceRoot) {
        dir = new File(workspaceRoot, ".agent/experience");
        file = new File(dir, "experience.jsonl");
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        load();
    }

    synchronized void record(Record value) {
        if (value == null) {
            return;
        }
        if (value.id.isEmpty() || ids.contains(value.id)) {
            return;
        }
        records.add(value);
        ids.add(value.id);
        append(value);
        while (records.size() > MAX_RECORDS) {
            records.remove(0);
        }
        rebuildIndex();
        if (records.size() >= MAX_RECORDS) {
            rewrite();
        }
    }

    synchronized List<Record> recent(int limit) {
        List<Record> recent = new ArrayList<>(records);
        Collections.sort(recent, new Comparator<Record>() {
            @Override
            public int compare(Record a, Record b) {
                return Long.compare(b.at, a.at);
            }
        });
        return recent.subList(0, Math.min(limit, recent.size()));
    }

    synchronized List<Record> recall(String goal, String error, String caps, int limit) {
        List<String> queryTokens = tokenize((goal == null ? "" : goal) + " "
                + (error == null ? "" : error));
        if (queryTokens.isEmpty()) {
            List<Record> recent = new ArrayList<>(records);
            Collections.sort(recent, new Comparator<Record>() {
                @Override
                public int compare(Record a, Record b) {
                    return Long.compare(b.at, a.at);
                }
            });
            return recent.subList(0, Math.min(limit, recent.size()));
        }
        final Map<Record, Double> scores = new HashMap<>();
        for (Record record : records) {
            List<String> tokens = tokenize(record.text());
            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                tf.put(token, tf.containsKey(token) ? tf.get(token) + 1 : 1);
            }
            double score = 0;
            for (String token : queryTokens) {
                Integer freq = tf.get(token);
                if (freq == null) {
                    continue;
                }
                Integer documents = df.get(token);
                double idf = Math.log(1.0 + ((records.size() - (documents == null ? 0 : documents)
                        + 0.5) / ((documents == null ? 0 : documents) + 0.5)));
                double lengthNorm = 1.0 - 0.75 + 0.75 * (tokens.size() / Math.max(1.0, avgLength));
                score += idf * (freq * 2.2) / (freq + 1.2 * lengthNorm);
            }
            if (caps != null && !caps.isEmpty() && caps.equals(record.caps)) {
                score += 1.5;
            }
            if (error != null && !error.isEmpty()
                    && record.error.toLowerCase(Locale.ROOT)
                    .contains(shortToken(error))) {
                score += 0.8;
            }
            if ("completed".equals(record.outcome)) {
                score += 0.3;
            }
            double ageDays = Math.max(0, (System.currentTimeMillis() - record.at) / 86400000.0);
            score += 0.5 / (1.0 + ageDays / 30.0);
            scores.put(record, score);
        }
        List<Record> ranked = new ArrayList<>(records);
        Collections.sort(ranked, new Comparator<Record>() {
            @Override
            public int compare(Record a, Record b) {
                return Double.compare(scores.getOrDefault(b, 0.0), scores.getOrDefault(a, 0.0));
            }
        });
        return ranked.subList(0, Math.min(limit, ranked.size()));
    }

    synchronized Map<String, Double> priors() {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (Record record : records) {
            if (!"completed".equals(record.outcome)) {
                continue;
            }
            for (String part : record.strategies.split(",")) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = part.substring(0, eq).trim();
                String[] values = part.substring(eq + 1).split("/");
                if (name.isEmpty() || values.length != 2) {
                    continue;
                }
                try {
                    int success = Integer.parseInt(values[0].trim());
                    int attempts = Integer.parseInt(values[1].trim());
                    int[] count = counts.get(name);
                    if (count == null) {
                        count = new int[2];
                        counts.put(name, count);
                    }
                    count[0] += success;
                    count[1] += attempts;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            int[] count = entry.getValue();
            double rate = count[1] == 0 ? 0.5 : (double) count[0] / count[1];
            result.put(entry.getKey(), 0.5 + 0.5 * rate);
        }
        return result;
    }

    static String render(List<Record> values) {
        if (values == null || values.isEmpty()) {
            return "Not yet.";
        }
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (Record record : values) {
            if (count > 0) {
                out.append("; ");
            }
            out.append(cut(record.goal, 60)).append(" → ").append(record.outcome);
            if (!record.strategies.isEmpty()) {
                out.append(" [").append(cut(record.strategies, 80)).append("]");
            }
            count++;
            if (out.length() > 260) {
                break;
            }
        }
        return cut(out.toString(), 320);
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher matcher = ASCII.matcher(lower);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        StringBuilder han = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                han.append(c);
            } else {
                addHanTokens(tokens, han);
                han.setLength(0);
            }
        }
        addHanTokens(tokens, han);
        return tokens;
    }

    private static void addHanTokens(List<String> tokens, StringBuilder value) {
        if (value.length() == 1) {
            tokens.add(value.toString());
        } else {
            for (int i = 0; i + 1 < value.length(); i++) {
                tokens.add(value.substring(i, i + 2));
            }
        }
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null && records.size() < MAX_RECORDS) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    Record record = Record.fromJson(new JSONObject(line));
                    if (!record.id.isEmpty() && ids.add(record.id)) {
                        records.add(record);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            close(reader);
        }
        rebuildIndex();
    }

    private void append(Record record) {
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file, true),
                    StandardCharsets.UTF_8);
            writer.write(record.toJson().toString());
            writer.write('\n');
        } catch (Exception ignored) {
        } finally {
            close(writer);
        }
    }

    private void rewrite() {
        File temp = new File(dir, "experience.jsonl.tmp");
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(temp),
                    StandardCharsets.UTF_8);
            int from = Math.max(0, records.size() - MAX_RECORDS);
            for (int i = from; i < records.size(); i++) {
                writer.write(records.get(i).toJson().toString());
                writer.write('\n');
            }
        } catch (Exception ignored) {
        } finally {
            close(writer);
        }
        try {
            if (file.exists()) {
                file.delete();
            }
            if (!temp.renameTo(file)) {
                temp.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private void rebuildIndex() {
        df.clear();
        long total = 0;
        for (Record record : records) {
            List<String> tokens = tokenize(record.text());
            total += tokens.size();
            Set<String> seen = new HashSet<>(tokens);
            for (String token : seen) {
                df.put(token, df.containsKey(token) ? df.get(token) + 1 : 1);
            }
        }
        avgLength = records.isEmpty() ? 1.0 : Math.max(1.0, (double) total / records.size());
    }

    private static String shortToken(String value) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return clean.length() <= 24 ? clean.toLowerCase(Locale.ROOT)
                : clean.substring(0, 24).toLowerCase(Locale.ROOT);
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}
