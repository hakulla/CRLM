package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Archiving layers: originals are dropped, index abstracts are drawn with a pointer, searchable hits, consolidation is capped. */
public final class ArchiveTest {

    private static int checked;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("archive-test").toFile();
        JSONArray messages = sample();
        String digest = ContextArchive.append(root, "c-test", messages, 0, messages.length());
        check(digest.contains("[Archive Index]"), "Abstracts should have an archive index header:" + digest);
        check(digest.contains("I've already put the earlier one 5 Message"), "The summary should indicate the number of entries (in thousands of United States dollars)system Not in archive:" + digest);
        check(digest.contains("inspect") && digest.contains("projects/a/BUILD.md"),
                "The facts should be accompanied by a toolname and path:" + digest);
        check(digest.contains("Failed") , "The result of the failure should be marked as a failure:" + digest);

        File archive = ContextArchive.file(root, "c-test");
        check(archive.isFile(), "Archived files should exist");
        String[] lines = new String(Files.readAllBytes(archive.toPath()),
                StandardCharsets.UTF_8).split("\n");
        check(lines.length == 5, "The archive should have 5 Lines (excluding system), Actual " + lines.length);
        JSONObject first = new JSONObject(lines[0]);
        check(first.optInt("line", 0) == 1, "Archive line number should be from 1 Start");

        String hit = ContextArchive.search(root, "c-test", "timeout", 8);
        check(hit.contains("\"ok\":true"), "The search should be successful:" + hit);
        check(hit.contains("L2"), "- It's the first time. 2 Article:" + hit);
        check(hit.contains("Hit. 1 Article"), "There should be only one hit:" + hit);

        String miss = ContextArchive.search(root, "c-test", "Keywords that do not existzzz", 8);
        check(miss.contains("Hit. 0 Article"), "The key should not be given. 0 Hit:" + miss);

        JSONArray more = sample();
        String second = ContextArchive.append(root, "c-test", more, 0, more.length());
        check(second.contains("I'm sorry. 6-10 Okay."), "The second archive should be followed by a line number:" + second);

        String merged = ContextArchive.merge(repeated("Old facts ", 400), second);
        check(merged.length() <= ContextArchive.DIGEST_MAX_CHARS,
                "The merger should not exceed the ceiling, actual " + merged.length());
        String tail = second.trim();
        tail = tail.substring(Math.max(0, tail.length() - 40));
        check(merged.endsWith(tail), "Mergers should keep the latest tail");

        System.out.println("ArchiveTest PASS (" + checked + " assertions)");
    }

    private static JSONArray sample() throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(message("system", "SYS"));
        messages.put(assistant("call_1", "inspect", "{\"path\":\"projects/a/BUILD.md\"}"));
        messages.put(tool("call_1", "{\"ok\":true,\"data\":\"[I'm sorry. 1-5 Okay.] Total 639 Okay. timeout 60s\"}"));
        messages.put(message("user", "Go on."));
        messages.put(assistant("call_2", "run", "{\"command\":\"bash build.sh\"}"));
        messages.put(tool("call_2", "{\"ok\":false,\"error\":\"BUILD_FAILED stage=javac\"}"));
        return messages;
    }

    private static JSONObject message(String role, String content) throws Exception {
        JSONObject object = new JSONObject();
        object.put("role", role);
        object.put("content", content);
        return object;
    }

    private static JSONObject assistant(String id, String name, String args) throws Exception {
        JSONObject object = message("assistant", "");
        JSONArray calls = new JSONArray();
        JSONObject call = new JSONObject();
        call.put("id", id);
        call.put("type", "function");
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("arguments", args);
        call.put("function", fn);
        calls.put(call);
        object.put("tool_calls", calls);
        return object;
    }

    private static JSONObject tool(String id, String content) throws Exception {
        JSONObject object = message("tool", content);
        object.put("tool_call_id", id);
        return object;
    }

    private static String repeated(String text, int times) {
        StringBuilder build = new StringBuilder();
        for (int i = 0; i < times; i++) {
            build.append(text).append(i).append('\n');
        }
        return build.toString();
    }

    private static void check(boolean ok, String label) {
        checked++;
        if (!ok) {
            throw new IllegalStateException("FAIL: " + label);
        }
    }

    private ArchiveTest() {
    }
}
