package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReadPageTest {
    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : ".");
        File file = new File(dir, "read-page-test.txt");
        int count = 10000;
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file),
                StandardCharsets.UTF_8)) {
            for (int i = 1; i <= count; i++) {
                out.write("line-" + i + " " + repeat('x', 80) + "\n");
            }
        }

        Set<Integer> seen = new HashSet<>();
        int offset = 1;
        int pages = 0;
        String lastHeader = "";
        while (true) {
            AgentEngine.TextPage page = AgentEngine.readTextPage(file, offset, 2000, 4000);
            if (page.error != null) {
                throw new AssertionError("page error: " + page.error);
            }
            if (page.total != count) {
                throw new AssertionError("total=" + page.total + " expected " + count);
            }
            String[] lines = page.text.split("\n");
            if (lines.length == 0 || !lines[0].startsWith("[I'm sorry. ")) {
                throw new AssertionError("missing header: " + page.text.substring(0,
                        Math.min(120, page.text.length())));
            }
            lastHeader = lines[0];
            Matcher m = Pattern.compile("^\\[I'm sorry. (\\d+)-(\\d+) Okay. / Total (\\d+) Okay.")
                    .matcher(lines[0]);
            if (!m.find()) {
                throw new AssertionError("bad header: " + lines[0]);
            }
            int start = Integer.parseInt(m.group(1));
            int end = Integer.parseInt(m.group(2));
            if (start != offset) {
                throw new AssertionError("start " + start + " expected " + offset);
            }
            for (int i = 1; i < lines.length; i++) {
                Matcher row = Pattern.compile("^\\s*(\\d+)\\| line-(\\d+) ")
                        .matcher(lines[i]);
                if (row.find()) {
                    int n = Integer.parseInt(row.group(1));
                    if (!seen.add(n)) {
                        throw new AssertionError("duplicate line " + n);
                    }
                }
            }
            pages++;
            if (lines[0].contains("End of file")) {
                break;
            }
            offset = end + 1;
            if (pages > 2000) {
                throw new AssertionError("too many pages");
            }
        }
        if (seen.size() != count) {
            throw new AssertionError("read " + seen.size() + "/" + count + " lines");
        }
        if (!lastHeader.contains("Total " + count + " rows;to end of file")) {
            throw new AssertionError("last header: " + lastHeader);
        }

        AgentEngine.TextPage middle = AgentEngine.readTextPage(file, 5000, 100, 20000);
        if (middle.error != null || !middle.text.startsWith("[I'm sorry. 5000-5099 Okay. / Total 10000 Okay.")) {
            throw new AssertionError("middle page: " + middle.text.substring(0,
                    Math.min(120, middle.text.length())));
        }

        File longLine = new File(dir, "read-page-long.txt");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(longLine),
                StandardCharsets.UTF_8)) {
            out.write(repeat('y', 10000) + "\n");
        }
        AgentEngine.TextPage cut = AgentEngine.readTextPage(longLine, 1, 10, 4000);
        if (cut.error != null || !cut.text.contains("There's a line cut off from this page.")
                || !cut.text.contains("End of file")) {
            throw new AssertionError("long line not cut: " + cut.text.substring(0,
                    Math.min(160, cut.text.length())));
        }

        File empty = new File(dir, "read-page-empty.txt");
        empty.createNewFile();
        AgentEngine.TextPage emptyPage = AgentEngine.readTextPage(empty, 1, 10, 4000);
        if (emptyPage.error != null || !emptyPage.text.contains("File is empty")) {
            throw new AssertionError("empty file: " + emptyPage.text);
        }

        JSONArray history = new JSONArray();
        history.put(new JSONObject().put("role", "system").put("content", "system"));
        history.put(new JSONObject().put("role", "user").put("content", "read it"));
        JSONArray calls = new JSONArray();
        calls.put(new JSONObject().put("id", "c1").put("type", "function")
                .put("function", new JSONObject().put("name", "inspect")
                        .put("arguments", "{\"mode\":\"read\",\"path\":\"x\"}")));
        history.put(new JSONObject().put("role", "assistant")
                .put("content", JSONObject.NULL).put("tool_calls", calls));
        String payload = "{\"ok\":true,\"data\":\"" + repeat('z', 30000) + "\"}";
        history.put(new JSONObject().put("role", "tool")
                .put("tool_call_id", "c1").put("content", payload));
        history.put(new JSONObject().put("role", "user").put("content", "continue"));

        ContextCompiler.Result result = ContextCompiler.compile(history, new JSONArray(),
                32000, 2048, "", ContextCompiler.ESTIMATE);
        if (result.error != null) {
            throw new AssertionError("context error: " + result.error);
        }
        int toolChars = -1;
        JSONArray out = result.messages();
        for (int i = 0; i < out.length(); i++) {
            JSONObject msg = out.optJSONObject(i);
            if (msg != null && "tool".equals(msg.optString("role"))) {
                toolChars = msg.optString("content", "").length();
            }
        }
        if (toolChars < 25000) {
            throw new AssertionError("tool payload was cut to " + toolChars + " chars");
        }

        System.out.println("READ_PAGE_TEST_OK pages=" + pages
                + " total=" + seen.size() + " toolChars=" + toolChars);
    }

    private static String repeat(char c, int n) {
        StringBuilder builder = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            builder.append(c);
        }
        return builder.toString();
    }
}
