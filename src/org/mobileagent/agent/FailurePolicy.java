package org.mobileagent.agent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Classifies tool failures so the agent stops hitting the same wall:
 * - permission errors ask the user because only they can grant access;
 * - capability/path/argument/network errors are internal problems: nudge the
 *   model to change strategy and keep working instead of asking the user.
 */
final class FailurePolicy {
    static final class Decision {
        final String question;
        final String summary;
        final Kind kind;
        final boolean needsUser;

        Decision(String question, String summary, Kind kind, boolean needsUser) {
            this.question = question;
            this.summary = summary;
            this.kind = kind;
            this.needsUser = needsUser;
        }
    }

    enum Kind { CAPABILITY, PERMISSION, TRANSIENT, INPUT, UNKNOWN }

    private final Map<String, Integer> counts = new HashMap<>();
    private final Set<String> asked = new HashSet<>();
    private final Set<String> nudged = new HashSet<>();
    private boolean askedAny;
    private int totalFailures;

    void reset() {
        counts.clear();
        asked.clear();
        nudged.clear();
        askedAny = false;
        totalFailures = 0;
    }

    Decision record(String tool, String error) {
        totalFailures++;
        Kind kind = classify(error);
        String key = (tool == null ? "" : tool) + "|" + normalize(error);
        Integer previous = counts.get(key);
        int count = previous == null ? 1 : previous + 1;
        counts.put(key, count);

        String reason;
        if (kind == Kind.PERMISSION) {
            if (askedAny) {
                return null;
            }
            askedAny = true;
            asked.add(key);
            reason = "Without permission or authorization, you're required to operate before you can continue.";
            String detail = shorten(error == null ? "" : error, 180);
            String question = "'" + (tool == null ? "Tools" : tool) + "'Failed:" + detail
                    + "\n" + reason + ". What do you want to do with it?";
            return new Decision(question, reason, kind, true);
        }
        if (kind == Kind.CAPABILITY) {
            reason = "Current tool/Capability is not available, but it needs to be achieved in another way.";
        } else if (kind == Kind.INPUT) {
            reason = "Same path/Parameter error has occurred " + count + " Number of times";
        } else if (kind == Kind.TRANSIENT) {
            reason = "Similar networks/Timeout error has occurred " + count + " Number of times";
        } else {
            reason = "Similar errors have occurred. " + count + " Number of times";
        }
        // Internal failures never ask the user. Nudge at most twice per error,
        // then let the normal round/time guards decide.
        if (nudged.add(key) && count <= 2) {
            return new Decision("", reason, kind, false);
        }
        if (totalFailures >= 3 && nudged.add("__total")) {
            return new Decision("", "This round has been repeated. " + totalFailures + " Subtool failed",
                    Kind.UNKNOWN, false);
        }
        return null;
    }

    static Kind classify(String error) {
        String text = error == null ? "" : error.toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return Kind.UNKNOWN;
        }
        if (text.contains("Unauthorized") || text.contains("Permission denied")
                || text.contains("permission denied") || text.contains("api key")
                || text.contains("401") || text.contains("403")
                || text.contains("Device control not active") || text.contains("Accessibility")
                || text.contains("Screen Read Unopened") || text.contains("Needing authorization.")) {
            return Kind.PERMISSION;
        }
        if (text.contains("Current agent No tool enabled") || text.contains("Unknown tool")
                || text.contains("command not found") || text.contains("Command not found")
                || text.contains("no ndk") || text.contains("No, I'm not. ndk")
                || (containsAny(text, "cmake", "ninja", "clang", "ndk", "compiler")
                && containsAny(text, "not found", "no such", "Not found", "Cannot initialise Evolution's mail component."))
                || text.contains("Tools not loaded") || text.contains("not supported")
                || text.contains("Not supporting that capability") || text.contains("Could not execute tool")) {
            return Kind.CAPABILITY;
        }
        if (text.contains("timeout") || text.contains("Timeout")
                || text.contains("Connection failed") || text.contains("Network")
                || text.contains("429") || text.contains("500") || text.contains("502")
                || text.contains("503") || text.contains("504")
                || text.contains("Try again later.") || text.contains("http 000")) {
            return Kind.TRANSIENT;
        }
        if (text.contains("Path") || text.contains("File does not exist")
                || text.contains("no such file") || text.contains("not found")
                || text.contains("Parameters") || text.contains("schema")
                || text.contains("Beyond") || text.contains("Missing")
                || text.contains("invalid") || text.contains("Cannot initialise Evolution's mail component.")) {
            return Kind.INPUT;
        }
        return Kind.UNKNOWN;
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String error) {
        if (error == null) {
            return "";
        }
        String text = error.toLowerCase(Locale.ROOT)
                .replaceAll("[0-9]+", "#")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    private static String shorten(String text, int max) {
        String value = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
