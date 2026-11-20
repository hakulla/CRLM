package org.mobileagent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Single-file unified diff, validated completely before producing any output. */
final class PatchTool {
    static final class PatchException extends IllegalArgumentException {
        final String code;
        PatchException(String code) { super(code); this.code = code; }
    }
    static final class Result {
        final String text;
        final int hunks;
        Result(String text, int hunks) { this.text = text; this.hunks = hunks; }
    }
    static final class Options {
        // Only trailing horizontal whitespace on context; never indentation or deletions.
        final boolean allowTrailingContextWhitespace;
        Options(boolean allow) { allowTrailingContextWhitespace = allow; }
    }
    private static final Pattern HEADER = Pattern.compile(
            "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(?: .*)?$");
    private static final String NO_NL = "\\ No newline at end of file";
    private static PatchException error(String code) { return new PatchException(code); }
    private PatchTool() { }

    static Result apply(String text, String patch) throws PatchException {
        return apply(text, patch, "");
    }
    static Result apply(String text, String patch, String expectedPath) throws PatchException {
        return apply(text, patch, expectedPath, new Options(false));
    }
    static Result dryRun(String text, String patch, String expectedPath) throws PatchException {
        return apply(text, patch, expectedPath);
    }
    static Result apply(String text, String patch, String expectedPath, Options options) {
        if ((text != null && text.indexOf('\u0000') >= 0) || (patch != null && patch.indexOf('\u0000') >= 0))
            throw error("BINARY_CONTENT");
        Document doc = new Document(text == null ? "" : text);
        // A uniformly CRLF-transported diff has CRLF headers too; standard CRLF payload alone does not.
        boolean transported=patch != null && patch.indexOf('\n') >= 0;
        if (transported) for (int n=0; n<patch.length(); n++)
            if (patch.charAt(n) == '\n' && (n == 0 || patch.charAt(n-1) != '\r')) transported=false;
        if (transported) patch=patch.replace("\r\n","\n");
        List<Hunk> hunks = parse(patch, expectedPath);
        if (doc.lines.isEmpty()) {
            for (Hunk h : hunks) for (Op op : h.ops)
                if (op.tag == '+' && !op.noNL && op.text.endsWith("\r")) doc.eol = "\r\n";
        }
        List<Integer> positions = new ArrayList<>();
        int previousEnd = -1;
        int previousStart = -1;
        long finalLines=doc.lines.size();
        for (Hunk h : hunks) finalLines+=(long) h.newCount-h.oldCount;
        for (Hunk h : hunks) {
            if (h.bsdLeadingEmptyRange && finalLines <= 0) throw error("NEW_START");
            int pos = locate(doc, h, options != null && options.allowTrailingContextWhitespace);
            if (pos < previousEnd || pos == previousStart) throw error("OVERLAP");
            previousStart = pos;
            previousEnd = pos + h.oldCount;
            positions.add(pos);
        }
        List<Line> out = new ArrayList<>();
        int cursor = 0;
        for (int i = 0; i < hunks.size(); i++) {
            Hunk h = hunks.get(i);
            int pos = positions.get(i);
            while (cursor < pos) out.add(doc.lines.get(cursor++));
            for (Op op : h.ops) {
                if (op.tag != '+') {
                    Line original = doc.lines.get(cursor);
                    if (op.noNL != !original.newline || (op.noNL && cursor != doc.lines.size() - 1))
                        throw error("OLD_EOF_MARKER");
                    if (cursor == 0 && op.tag == '-' && op.text.startsWith("\uFEFF")) doc.bom = false;
                    cursor++;
                    if (op.tag == ' ') out.add(original); // Preserve original context bytes.
                } else {
                    String value = payload(op, doc);
                    if (out.isEmpty() && value.startsWith("\uFEFF")) {
                        doc.bom = true;
                        value = value.substring(1);
                    }
                    out.add(new Line(value, !op.noNL));
                }
            }
        }
        while (cursor < doc.lines.size()) out.add(doc.lines.get(cursor++));
        for (int i = 0; i + 1 < out.size(); i++) {
            if (!out.get(i).newline) throw error("NEW_EOF_MARKER");
        }
        StringBuilder result = new StringBuilder();
        if (doc.bom) result.append('\uFEFF');
        for (Line line : out) {
            result.append(line.text);
            if (line.newline) result.append(doc.eol);
        }
        return new Result(result.toString(), hunks.size());
    }
    private static final class Line {
        final String text;
        final boolean newline;
        Line(String text, boolean newline) { this.text = text; this.newline = newline; }
    }
    private static final class Document {
        final List<Line> lines = new ArrayList<>();
        boolean bom;
        String eol = "\n";
        Document(String text) {
            bom = text.startsWith("\uFEFF");
            if (bom) text = text.substring(1);
            boolean sawLF = false, sawCRLF = false;
            int start = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) != '\n') continue;
                boolean cr = i > start && text.charAt(i - 1) == '\r';
                sawCRLF |= cr;
                sawLF |= !cr;
                lines.add(new Line(text.substring(start, cr ? i - 1 : i), true));
                start = i + 1;
            }
            if (sawLF && sawCRLF) throw error("MIXED_EOL");
            if (sawCRLF) eol = "\r\n";
            if (start < text.length()) lines.add(new Line(text.substring(start), false));
            if (bom && text.isEmpty()) lines.add(new Line("", false));
        }
    }
    private static final class Op {
        final char tag;
        final String text;
        boolean noNL;
        Op(char tag, String text) { this.tag = tag; this.text = text; }
    }
    private static final class Hunk {
        final int oldStart, oldCount, newStart, newCount;
        final List<Op> ops = new ArrayList<>();
        boolean bsdLeadingEmptyRange;
        Hunk(int a, int b, int c, int d) { oldStart=a; oldCount=b; newStart=c; newCount=d; }
        int oldPos() { return oldCount == 0 ? oldStart : oldStart - 1; }
        int newPos() { return newCount == 0 ? newStart : newStart - 1; }
    }
    private static List<Hunk> parse(String patch, String expected) {
        if (patch == null || patch.isEmpty()) throw error("EMPTY_PATCH");
        String[] lines = patch.split("\n", -1);
        int end = lines.length;
        if (lines[end - 1].isEmpty()) end--;
        int i = 0;
        String gitOld = null, gitNew = null;
        if (i < end && clean(lines[i]).startsWith("diff --git ")) {
            String[] parts = clean(lines[i++]).substring(11).split(" ", -1);
            if (parts.length != 2) throw error("HEADER");
            gitOld = path(parts[0], true); gitNew = path(parts[1], true);
            if (i < end && clean(lines[i]).matches("index [0-9a-f]+\\.\\.[0-9a-f]+(?: [0-7]{6})?")) i++;
        }
        // Allow model only @@ hunk: From the caller. path Auto-Sync ---/+++ Head.
        if (i < end && clean(lines[i]).startsWith("@@")
                && expected != null && !expected.trim().isEmpty()) {
            String expectedPath = path(expected, false);
            List<String> rebuilt = new ArrayList<>();
            rebuilt.add("--- a/" + expectedPath);
            rebuilt.add("+++ b/" + expectedPath);
            for (int k = i; k < lines.length; k++) {
                rebuilt.add(lines[k]);
            }
            lines = rebuilt.toArray(new String[0]);
            end = lines.length;
            if (end > 0 && lines[end - 1].isEmpty()) end--;
            i = 2;
        }
        if (i + 1 >= end || !clean(lines[i]).startsWith("--- ")
                || !clean(lines[i + 1]).startsWith("+++ ")) throw error("PAIRED_HEADERS_REQUIRED");
        String oldPath = path(clean(lines[i++]).substring(4), true);
        String newPath = path(clean(lines[i++]).substring(4), true);
        if (!oldPath.equals(newPath) || (gitOld != null
                && (!gitOld.equals(oldPath) || !gitNew.equals(newPath)))) throw error("PATH_MISMATCH");
        if (expected != null && !expected.isEmpty() && !oldPath.equals(path(expected, false)))
            throw error("PATH_MISMATCH");
        List<Hunk> hunks = new ArrayList<>();
        long delta = 0;
        int prevEnd = -1, prevStart = -1;
        while (i < end) {
            Matcher m = HEADER.matcher(clean(lines[i++]));
            if (!m.matches()) throw error("UNEXPECTED_BODY");
            Hunk h = new Hunk(number(m.group(1)), count(m.group(2)),
                    number(m.group(3)), count(m.group(4)));
            if ((h.oldCount > 0 && h.oldStart == 0) || (h.newCount > 0 && h.newStart == 0)
                    || (long) h.oldPos() + h.oldCount > Integer.MAX_VALUE
                    || (long) h.newPos() + h.newCount > Integer.MAX_VALUE) throw error("RANGE");
            long expectedNew=(long) h.oldPos()+delta;
            // BSD diff -U0 spells a leading empty new range as +1,0 when new file is nonempty.
            h.bsdLeadingEmptyRange=expectedNew == 0 && h.newCount == 0 && h.newStart == 1;
            if (expectedNew != h.newPos() && !h.bsdLeadingEmptyRange) throw error("NEW_START");
            if (h.oldPos() < prevEnd || h.oldPos() == prevStart) throw error("OVERLAP");
            int oldN = 0, newN = 0;
            while (i < end) {
                String line = lines[i];
                if (clean(line).equals(NO_NL)) {
                    if (h.ops.isEmpty() || h.ops.get(h.ops.size()-1).noNL) throw error("EOF_MARKER");
                    h.ops.get(h.ops.size()-1).noNL = true;
                    i++;
                    continue;
                }
                if (oldN == h.oldCount && newN == h.newCount) break;
                if (line.isEmpty()) throw error("COUNT");
                char tag = line.charAt(0);
                if (tag != ' ' && tag != '-' && tag != '+') throw error("UNEXPECTED_BODY");
                h.ops.add(new Op(tag, line.substring(1)));
                if (tag != '+') oldN++;
                if (tag != '-') newN++;
                if (oldN > h.oldCount || newN > h.newCount) throw error("COUNT");
                i++;
            }
            if (oldN != h.oldCount || newN != h.newCount || h.ops.isEmpty()) throw error("COUNT");
            hunks.add(h);
            prevStart = h.oldPos(); prevEnd = prevStart + h.oldCount;
            delta += (long) h.newCount - h.oldCount;
        }
        if (hunks.isEmpty()) throw error("NO_HUNKS");
        return hunks;
    }
    private static int count(String s) { return s == null ? 1 : number(s); }
    private static int number(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { throw error("INTEGER_OVERFLOW"); }
    }
    private static String clean(String s) { return s.endsWith("\r") ? s.substring(0,s.length()-1) : s; }
    private static String payload(Op op, Document doc) {
        return doc.eol.equals("\r\n") && !op.noNL ? clean(op.text) : op.text;
    }
    private static String path(String s, boolean prefix) {
        int tab = s.indexOf('\t');
        if (tab >= 0) s = s.substring(0, tab);
        if (prefix && (s.startsWith("a/") || s.startsWith("b/"))) s = s.substring(2);
        while (s.startsWith("./")) s = s.substring(2);
        if (s.isEmpty() || s.equals("/dev/null") || s.indexOf('\\') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\u0000') >= 0 || s.indexOf('\r') >= 0) throw error("HEADER_PATH");
        for (String part : s.split("/", -1)) {
            if (part.equals("..") || part.equals(".")) throw error("HEADER_PATH");
        }
        return s;
    }
    private static int locate(Document doc, Hunk h, boolean tolerance) {
        if (h.oldCount == 0) {
            if (h.oldStart > doc.lines.size()) throw error("RANGE");
            return h.oldStart;
        }
        List<Integer> matches = matches(doc, h, false);
        if (matches.isEmpty() && tolerance) matches = matches(doc, h, true);
        if (matches.size() > 1) throw error("AMBIGUOUS_CONTEXT");
        if (matches.isEmpty()) throw error("CONTEXT_MISMATCH");
        return matches.get(0);
    }
    private static List<Integer> matches(Document doc, Hunk h, boolean tolerant) {
        List<Integer> result = new ArrayList<>();
        for (int pos = 0; pos <= doc.lines.size() - h.oldCount; pos++) {
            int index = pos;
            boolean ok = true;
            for (Op op : h.ops) {
                if (op.tag == '+') continue;
                String wanted = payload(op, doc);
                if (index == 0 && doc.bom && wanted.startsWith("\uFEFF")) wanted = wanted.substring(1);
                String actual = doc.lines.get(index++).text;
                if (tolerant && op.tag == ' ') { wanted = trailing(wanted); actual = trailing(actual); }
                if (!actual.equals(wanted)) { ok = false; break; }
            }
            if (ok) result.add(pos);
        }
        return result;
    }
    private static String trailing(String s) {
        int n = s.length();
        while (n > 0 && (s.charAt(n-1) == ' ' || s.charAt(n-1) == '\t')) n--;
        return s.substring(0,n);
    }
    static String selfTest() {
        try {
            String p = "--- a/t\n+++ b/t\n@@ -0,0 +1 @@\n+NEW\n";
            return "NEW\n".equals(apply("", p, "t").text) ? "OK" : "FAIL";
        } catch (Exception e) { return "FAIL:" + e.getMessage(); }
    }
}
