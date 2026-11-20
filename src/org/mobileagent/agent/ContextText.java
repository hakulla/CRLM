package org.mobileagent.agent;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The text in the context is definitive compression and end reading; all returns do not exceed the declared character limit. */
final class ContextText {
    private ContextText() {
    }

    static String compactLog(String text, int maxChars) {
        String clean = text == null ? "" : text.trim();
        int cap = Math.max(64, maxChars);
        if (clean.length() <= cap) {
            return clean;
        }
        String[] lines = clean.split("\n");
        if (lines.length <= 2) {
            return cut(clean, cap);
        }
        List<String> kept = new ArrayList<>();
        int budget = Math.max(16, cap - 64);
        for (int i = 0; i < 2 && i < lines.length; i++) {
            if (lines[i].length() + 1 > budget) {
                break;
            }
            kept.add(lines[i]);
            budget -= lines[i].length() + 1;
        }
        List<String> tail = new ArrayList<>();
        for (int i = lines.length - 1; i >= 2 && budget > 0; i--) {
            String line = lines[i];
            if (line.length() + 1 > budget) {
                break;
            }
            tail.add(line);
            budget -= line.length() + 1;
        }
        Collections.reverse(tail);
        while (true) {
            int omitted = lines.length - kept.size() - tail.size();
            String marker = omitted > 0 ? "(Earlier. " + omitted + " (Folded)\n" : "";
            StringBuilder builder = new StringBuilder(marker);
            for (String line : kept) {
                builder.append(line).append('\n');
            }
            for (String line : tail) {
                builder.append(line).append('\n');
            }
            String result = builder.toString().trim();
            if (result.length() <= cap) {
                return result;
            }
            if (!tail.isEmpty()) {
                // Discards older tail entries first, and the latest entries must be kept.
                tail.remove(0);
                continue;
            }
            return cut(result, cap);
        }
    }

    static String cut(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "...";
    }

    /** Read the end of the file and start with the previous line break to avoid breaking half UTF-8/Record. */
    static String tail(File file, int maxBytes) {
        if (file == null || !file.isFile() || maxBytes <= 0) {
            return "";
        }
        RandomAccessFile input = null;
        try {
            input = new RandomAccessFile(file, "r");
            long size = input.length();
            int length = (int) Math.min(size, maxBytes);
            long start = size - length;
            input.seek(start);
            byte[] data = new byte[length];
            int read = input.read(data);
            int offset = 0;
            if (start > 0) {
                while (offset < read && data[offset] != '\n') {
                    offset++;
                }
                if (offset < read) {
                    offset++;
                }
            }
            return new String(data, offset, Math.max(0, read - offset), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    static String truncateWithHighlights(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        int cap = Math.max(32, max);
        int head = cap / 2;
        int highlights = cap / 3;
        int tail = cap - head - highlights - 32;
        String kept = text.substring(0, head);
        String removed = text.substring(head, Math.max(head, text.length() - Math.max(tail, 0)));
        StringBuilder builder = new StringBuilder(kept);
        builder.append("\n...[Interrupt ").append(text.length() - head - Math.max(tail, 0))
                .append(" Character]...\n");
        String important = importantLines(removed, Math.max(0, highlights));
        if (!important.isEmpty()) {
            builder.append(important).append('\n');
        }
        if (tail > 0) {
            builder.append(text.substring(text.length() - tail));
        }
        String result = builder.toString();
        return result.length() <= cap ? result : result.substring(0, Math.max(0, cap - 1)) + "...";
    }

    static String importantLines(String text, int maxChars) {
        if (text == null || text.isEmpty() || maxChars <= 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String raw : text.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.length() > 300 || !seen.add(line)) {
                continue;
            }
            String lower = line.toLowerCase();
            boolean important = lower.contains("error") || lower.contains("exception")
                    || lower.contains("failed") || lower.contains("failure")
                    || lower.contains("fatal") || lower.contains("caused by")
                    || lower.contains("build_failed") || line.contains("Error")
                    || line.contains("Failed") || line.contains("Unusual")
                    || line.contains("Cannot") || line.contains("Cannot initialise Evolution's mail component.");
            if (!important) {
                continue;
            }
            if (builder.length() + line.length() > maxChars) {
                break;
            }
            builder.append("  | ").append(line).append('\n');
        }
        return builder.toString().trim();
    }
}
