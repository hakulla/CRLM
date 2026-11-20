package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class Capabilities {
    static final String RULES_PATH = ".agent/rules.md";
    static final String MEMORY_PATH = ".agent/memory.md";
    static final String LESSONS_PATH = ".agent/lessons.md";
    static final String AGENTS_DIR = ".agent/agents";
    static final String SKILLS_DIR = ".agent/skills";
    static final String KNOWLEDGE_DIR = ".agent/knowledge";
    static final String EVOLUTION_DIR = ".agent/evolution";
    private static final int MAX_TEXT = 6000;

    static final class AgentProfile {
        static final int CURRENT_VERSION = 2;
        final String name;
        final String instructions;
        final Set<String> tools;
        final String filePath;
        final ToolScope scope;
        final String blockedReason;

        AgentProfile(String name, String instructions, Set<String> tools, String filePath) {
            this(name, instructions, tools == null ? ToolScope.inherit() : ToolScope.of(tools), filePath, "");
        }

        private AgentProfile(String name, String instructions, ToolScope scope, String filePath, String blockedReason) {
            this.name = name;
            this.instructions = instructions;
            this.scope = scope;
            this.tools = scope.isInherit() ? null : scope.names();
            this.filePath = filePath;
            this.blockedReason = blockedReason;
        }

        boolean hasToolRestriction() { return !scope.isInherit(); }
        ToolScope effectiveScope(ToolScope parent) {
            if (!blockedReason.isEmpty()) throw new IllegalStateException(blockedReason);
            return scope.resolve(parent);
        }

        static AgentProfile fromJson(JSONObject object, String fallbackName, String path) {
            if (object == null) throw new IllegalArgumentException("Missing profile JSON");
            int version = Conversation.versionOf(object);
            if (version < 1 || version > CURRENT_VERSION) throw new IllegalArgumentException("Unsupported agent profile version");
            if (object.has("name") && !(object.opt("name") instanceof String)
                    || object.has("instructions") && !(object.opt("instructions") instanceof String))
                throw new IllegalArgumentException("Invalid profile text");
            String name = object.optString("name", fallbackName);
            for (String key : new String[] { "mode", "toolMode" }) {
                if (object.has(key) && (!(object.opt(key) instanceof String)
                        || !java.util.Arrays.asList("inherit", "all", "none", "set").contains(object.optString(key))))
                    throw new IllegalArgumentException("Unknown profile mode");
            }
            if (object.has("mode") && object.has("toolMode") && !object.opt("mode").equals(object.opt("toolMode")))
                throw new IllegalArgumentException("Conflicting profile modes");
            Set<String> tools = new LinkedHashSet<>();
            JSONArray array = object.optJSONArray("tools");
            if (object.has("tools") && array == null) throw new IllegalArgumentException("Invalid profile tools");
            if (array != null) for (int i = 0; i < array.length(); i++) {
                Object value = array.opt(i);
                if (!(value instanceof String) || !tools.add((String) value))
                    throw new IllegalArgumentException("Invalid/duplicate profile tool");
            }
            ToolScope scope;
            if (object.has("toolScope")) {
                scope = ToolScope.fromJson(object.optJSONObject("toolScope"));
                if (array != null && !ToolScope.of(tools).fingerprint().equals(scope.fingerprint()))
                    throw new IllegalArgumentException("Conflicting profile scopes");
            } else if (object.has("toolMode") || object.has("mode")) {
                String mode = object.optString("toolMode", object.optString("mode", ""));
                try {
                    scope = ToolScope.fromJson(new JSONObject().put("version", 1).put("mode", mode).put("names", new JSONArray(tools)));
                } catch (Exception e) { throw new IllegalArgumentException("Invalid profile mode", e); }
            } else if (version < CURRENT_VERSION && tools.isEmpty()) scope = ToolScope.legacy0191();
            else if (array != null) scope = ToolScope.of(tools);
            else throw new IllegalArgumentException("Missing explicit profile scope");
            if (version < CURRENT_VERSION && (scope.isAll() || scope.isInherit())) scope = ToolScope.legacy0191();
            return new AgentProfile(name, object.optString("instructions", ""), scope, path, "");
        }
    }

    static final class SkillInfo {
        final String name;
        final String description;
        final String filePath;

        SkillInfo(String name, String description, String filePath) {
            this.name = name;
            this.description = description;
            this.filePath = filePath;
        }
    }

    static final class SearchHit {
        final String file;
        final String snippet;
        final int score;

        SearchHit(String file, String snippet, int score) {
            this.file = file;
            this.snippet = snippet;
            this.score = score;
        }
    }

    private final Workspace workspace;

    Capabilities(Workspace workspace) {
        this.workspace = workspace;
        ensureDefaults();
    }

    void ensureDefaults() {
        try {
            File root = workspace.root();
            File agent = new File(root, ".agent");
            mkdirs(agent);
            mkdirs(new File(agent, "agents"));
            mkdirs(new File(agent, "skills"));
            mkdirs(new File(agent, "rules"));
            mkdirs(new File(agent, "knowledge"));
            mkdirs(new File(agent, "evolution/proposals"));
            mkdirs(new File(agent, "evolution/reviews"));
            mkdirs(new File(agent, "evolution/done"));
            File rules = workspace.resolve(RULES_PATH);
            if (!rules.exists()) {
                write(rules, "# Agent Rules\n\n"
                        + "Write here.CRLM Rules to be observed, such as language, code style, prohibitions.\n");
            }
            File memory = workspace.resolve(MEMORY_PATH);
            if (!memory.exists()) {
                write(memory, "# Agent Memory\n\n");
            }
            seedBuiltinSkills(agent);
        } catch (Exception ignored) {
        }
    }

    private void seedBuiltinSkills(File agent) {
        try {
            File skills = new File(agent, "skills");
            File marker = new File(skills, ".builtin-v5");
            if (marker.exists()) {
                return;
            }
            writeSkillTemplate(skills, "plan-verify",
                    "Step-by-step planning, implementation and validation process for complex missions",
                    "# Plan and Verify\n\n"
                            + "Use for multi-step tasks.\n\n"
                            + "1. Restate the goal and constraints in one short paragraph.\n"
                            + "2. Call update_plan with 3-8 verifiable steps; mark exactly one in_progress.\n"
                            + "3. Do the smallest next step; after each step update plan status.\n"
                            + "4. Verify with tests, builds, grep or a concrete command; do not claim success without evidence.\n"
                            + "5. If blocked, record the blocker and ask one focused question.\n");
            writeSkillTemplate(skills, "debug-root-cause",
                    "Positioning bug Recurrence, evidence, root causes and restoration of validation process",
                    "# Debug Root Cause\n\n"
                            + "1. Reproduce the failure or collect the exact error.\n"
                            + "2. Locate the code path with grep/glob/read_file; do not guess.\n"
                            + "3. State a falsifiable hypothesis and test it.\n"
                            + "4. Apply the smallest fix with edit_file.\n"
                            + "5. Re-run the reproduction and report evidence.\n");
            writeSkillTemplate(skills, "code-review",
                    "Checking accuracy, security, border conditions and maintenance",
                    "# Code Review\n\n"
                            + "Review findings first, ordered by severity. Check:\n\n"
                            + "- correctness and failure paths\n"
                            + "- input validation and permissions\n"
                            + "- secrets, logging and privacy\n"
                            + "- concurrency and resource leaks\n"
                            + "- tests or a concrete verification plan\n\n"
                            + "Use file:line references when possible.\n");
            writeSkillTemplate(skills, "android-build",
                    "Use on-line Termux Tool chain construction, validation and installation APK",
                    "# Android Build\n\n"
                            + "1. Confirm the project has AndroidManifest.xml, src/ and res/.\n"
                            + "2. Run build_apk(project=\".\") if the workspace is the project root, otherwise projects/<name>.\n"
                            + "3. Read the build log and fix only the reported errors.\n"
                            + "4. Install only after a successful build; the system installer needs user confirmation.\n"
                            + "5. After updating the agent itself, re-enable the accessibility service and verify it.\n");
            writeSkillTemplate(skills, "build-failure-triage",
                    "Positioning root factors when construction failed by stage, error in compilation and real log",
                    "# Build Failure Triage\n\n"
                            + "1. Read the build result: exit code, [stage], and the first error:/file:line lines.\n"
                            + "2. Classify before acting: missing tool, javac error, d8 error, aapt2/resource error, signing/verify error, stuck process, or real timeout.\n"
                            + "3. Never conclude \"project too large/timeout\" without a timeout line or a hung process. A compile error is not a timeout.\n"
                            + "4. Fix only the reported file/line with edit_file; read the surrounding code first.\n"
                            + "5. Rebuild and report the stage timings and BUILD_OK. If the same error repeats, change approach instead of retrying.\n");
            writeSkillTemplate(skills, "ui-replication",
                    "Structure and interact from screenshot or video reversion interface",
                    "# UI Replication\n\n"
                            + "1. Extract key frames or screenshots and record layout, spacing, colors and typography.\n"
                            + "2. List screens and transitions before coding.\n"
                            + "3. Build reusable components and match hierarchy first, then polish.\n"
                            + "4. Test on the actual device with screenshots; compare side by side.\n"
                            + "5. Keep behavior real; do not add fake controls.\n");
            writeSkillTemplate(skills, "test-driven",
                    "Write an enforceable authentication first, then make minimum modifications and return",
                    "# Test Driven\n\n"
                            + "1. Turn the request into a concrete failing check: a test, build, grep or UI assertion.\n"
                            + "2. Reproduce the failure and capture the exact output.\n"
                            + "3. Make the smallest change that passes the check.\n"
                            + "4. Re-run the same check and one adjacent regression check.\n"
                            + "5. Report the commands and results; never claim green without output.\n");
            writeSkillTemplate(skills, "security-review",
                    "Review changes from key, privileges, injection, privacy and supply chain perspectives",
                    "# Security Review\n\n"
                            + "Check findings by severity:\n\n"
                            + "- secrets or tokens in code, logs, screenshots or conversations\n"
                            + "- input validation, path traversal and injection\n"
                            + "- least-privilege permissions and exported components\n"
                            + "- network endpoints, TLS and redirects\n"
                            + "- destructive operations without confirmation or rollback\n\n"
                            + "Give a minimal fix for each finding and re-check after the fix.\n");
            writeSkillTemplate(skills, "session-retro",
                    "Reassess targets, evidence, risks and next steps after mandate end",
                    "# Session Retro\n\n"
                            + "1. Restate the original goal and constraints.\n"
                            + "2. List what was actually completed with evidence (files, commands, screenshots, tests).\n"
                            + "3. Separate verified facts from assumptions.\n"
                            + "4. Record open problems, risks and rollback points.\n"
                            + "5. Propose at most three next actions, ordered by value.\n");
            writeSkillTemplate(skills, "harness-playbook",
                    "Build/Installation, tool failure, complex tasks, code survey, context and detailed self-check protocols",
                    "# Harness Playbook\n\n"
                            + "## Build and Install\n\n"
                            + "- Project requirements AndroidManifest.xml, src/**.java, res/values/strings.xml; Don't. Gradle/AndroidX. \n"
                            + "- build_apk(project=\"projects/<name>\"); Use after success install_apk installing;do not delete existing app.apk. \n"
                            + "- Install potential bullet system risk page/password;do not assume that it is installed if failure is reported correctly.\n\n"
                            + "## Build Failed Check\n\n"
                            + "- Read first. [stage], Exit code and number one. error:/file:line; Distinguishing between tools,javac, d8, aapt2, Signing, process hanging, real timeout.\n"
                            + "- No timeout evidence. Don't blame it.'It's too much work.'; Documents that only report at once/All right, finish the rerun.\n"
                            + "- The same error changed the method twice in a row and the user was told the blocking and evidence.\n\n"
                            + "## Tool failure and alternative\n\n"
                            + "- Read the wrong text first, then change the parameters./Change Tools/Downsizing; up to two alternatives.\n"
                            + "- Do not repeat the same tool with the same parameters; blockage is summed up by successive failures.\n\n"
                            + "## Complex tasks and plans\n\n"
                            + "- 3-8 One verifiable step, state is only pending/in_progress/completed ; the reason for the deviation is explained.\n"
                            + "- Every step of the way, with command, test,grep or screenshot validation; not authentication is explicit.\n\n"
                            + "## Code survey\n\n"
                            + "- First. glob/grep Positioning, re-use. read_file It's... offset/limit enumerating;wide spawn_subagent Back file:line. \n"
                            + "- Do not read big documents at once; do not repeat unchanged documents.\n\n"
                            + "## Context and token\n\n"
                            + "- The system will omit the old tool output/Old screenshot; re-call the tool if necessary, without assuming that the omission is still in place.\n"
                            + "- Intermediate rotation to write only one sentence; independent reading/Searches are launched in parallel with the same round; validation is stopped.\n"
                            + "- Command output used head/tail/grep (b) Interception;read_file Control limit; grep Control max_results. \n\n"
                            + "## Device Operations\n\n"
                            + "- list_apps → open_app → ui_dump → ui_tap/ui_set_text/ui_scroll; The screenshot needs to be read on the user.\n"
                            + "- The operation of paying, sending messages, unloading, re-establishing the plant must be clearly requested by the user; delete/Note rolls back before moving.\n\n"
                            + "## Self-inspection and self-modification\n\n"
                            + "- User report abnormally first self_check, Read the log again; run the same path after repair.\n"
                            + "- Modify app Description of changes before source code and confirmation by user; construction is on the mobile phone Termux The tool chain is complete.\n\n"
                            + "## Rewind and self-evolution\n\n"
                            + "- The end-of-mission system will be automatically reset; compliance [reverse experience], with related [recoverable proposal] prioritized.\n"
                            + "- Stable preference remember; Reusable process writing skills; long-term knowledge base; no one-time details and keys recorded.\n");
            writeSkillTemplate(skills, "agent-bridge",
                    "Self-study APK andCRLM Bridge protocol: manual/The choice is to send it automatically.Agent AutoRepack",
                    "# Agent Bridge\n\n"
                            + "All by Agent Development/Build APK We must all have a built-in. Agent Bridge, get the data that requires smart judgment./Punctuation toCRLM, Wait for the package to be returned and use the package drive App Logical.\n\n"
                            + "## Directory and protocol\n\n"
                            + "- Contents`/sdcard/MobileAgent/agent-bridge/{inbox,outbox,done}`. \n"
                            + "- App Write `inbox/<id>.json`: `{\"id\":\"...\",\"app\":\"Package Name\",\"kind\":\"...\",\"prompt\":\"...\",\"payload\":{...}}`; Write first. `.tmp` Change your name.\n"
                            + "- And then we'll broadcast. `org.mobileagent.agent.BRIDGE_REQUEST`(setPackage CRLM The name of the package)Agent Backstage services will be awakened.\n"
                            + "- App Round `outbox/<id>.json`, Read `text`/`source`; Give a retweeting hint over time, don't block it. UI. \n"
                            + "- Agent The request is automatically processed and the package is written back.**No user needs to open Agent Interface**. \n\n"
                            + "## Sending policy (important)\n\n"
                            + "- Default Manual: Only when sent by the user point button is suitable for an event that does not determine when a smart judgement is required.\n"
                            + "- Optional Auto:App Internal'AutoSend'tick box, default close; user tick to update by data/Time-sending.\n"
                            + "- Automatically send is to set minimum spacing, weight removal, time overtime maximums, avoid requests and duplicate consumption models.\n"
                            + "- Save the result when you get the package. App Configure/Announcements/interface and use the next logical; keep the local rule bottom when you fail.\n\n"
                            + "## Achieving reference\n\n"
                            + "- Psychic.app(projects/garden-air)It's... `BridgeIO` / `AgentBridge` / `AutoAgent` It's standard.\n"
                            + "- New App Directly reuse this client without creating a different protocol; request must be accompanied `app` The name of the package, easy. Agent Recording sources.\n");
            write(marker, "builtin skills v5\n");
        } catch (Exception ignored) {
        }
    }

    private void writeSkillTemplate(File skills, String name, String description, String body) {
        try {
            File dir = new File(skills, name);
            mkdirs(dir);
            File file = new File(dir, "SKILL.md");
            if (!file.exists()) {
                write(file, "# " + name + "\n\n" + description + "\n\n" + body);
            }
        } catch (Exception ignored) {
        }
    }

    String loadRules() {
        StringBuilder builder = new StringBuilder();
        appendRules(builder, readRelative(RULES_PATH, MAX_TEXT), ".agent/rules.md");
        appendRules(builder, readRelative("AGENTS.md", MAX_TEXT), "AGENTS.md");
        try {
            File dir = workspace.resolve(".agent/rules");
            File[] files = dir.listFiles();
            if (files != null) {
                java.util.Arrays.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".md")) {
                        appendRules(builder, readFile(file, MAX_TEXT),
                                ".agent/rules/" + file.getName());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        String text = builder.toString().trim();
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }

    private static void appendRules(StringBuilder builder, String text, String source) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        builder.append("## ").append(source).append('\n').append(text.trim());
    }

    String loadMemory() {
        try {
            String tail = ContextText.tail(workspace.resolve(MEMORY_PATH), 12000);
            return tail.isEmpty() ? readRelative(MEMORY_PATH, MAX_TEXT) : tail;
        } catch (Exception e) {
            return "";
        }
    }

    String rulesSection() {
        String rules = loadRules().trim();
        return rules.isEmpty() ? "" : "\n\n[User Rule\n" + trim(rules, MAX_TEXT);
    }

    String memorySection() {
        String memory = loadMemory().trim();
        if (memory.isEmpty()) {
            return "";
        }
        return "\n\n[Long-term memory (nearest entry, full text in " + MEMORY_PATH + ")]\n"
                + ContextText.compactLog(memory, 1800);
    }

    /**
     * Memory/Experience is an additional log, which cannot be filled in with full text.
     * Retain the initial long-term agreement + Recent entries, intermediate folding; models can be used when needed read_file View the full text.
     */

    boolean appendMemory(String note) {
        if (note == null || note.trim().isEmpty()) {
            return false;
        }
        try {
            String clean = note.trim().replace('\n', ' ').replace('\r', ' ');
            String existing = loadMemory();
            if (existing.contains(clean)) {
                return false;
            }
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    .format(new Date());
            File file = workspace.resolve(MEMORY_PATH);
            String line = "- [" + time + "] " + clean + "\n";
            FileOutputStream output = new FileOutputStream(file, true);
            try {
                output.write(line.getBytes(StandardCharsets.UTF_8));
            } finally {
                output.close();
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean removeMemoryLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return false;
        }
        try {
            File file = workspace.resolve(MEMORY_PATH);
            if (!file.isFile()) {
                return false;
            }
            String text = readFile(file, MAX_TEXT * 4);
            String[] lines = text.split("\n", -1);
            StringBuilder builder = new StringBuilder();
            boolean removed = false;
            for (String current : lines) {
                if (!removed && current.trim().equals(line.trim())) {
                    removed = true;
                    continue;
                }
                builder.append(current).append('\n');
            }
            if (!removed) {
                return false;
            }
            write(file, builder.toString());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    List<String> listAgentNames() {
        List<String> names = new ArrayList<>();
        for (AgentProfile profile : listAgents()) {
            names.add(profile.name);
        }
        return names;
    }

    String lessonsSection() {
        String text;
        try {
            text = ContextText.tail(workspace.resolve(LESSONS_PATH), 12000).trim();
        } catch (Exception e) {
            text = "";
        }
        if (text.isEmpty()) {
            text = readRelative(LESSONS_PATH, 6000).trim();
        }
        return text.isEmpty() ? "" : "\n\n[Repetition experience (must be complied with, and avoid repeating similar mistakes)\n"
                + ContextText.compactLog(text, 2000);
    }

    boolean appendLesson(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        try {
            String clean = text.trim().replace('\n', ' ').replace('\r', ' ');
            if (clean.length() > 240) {
                clean = clean.substring(0, 240) + "...";
            }
            String existing = readRelative(LESSONS_PATH, 20000);
            if (existing.contains(clean)) {
                return false;
            }
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    .format(new Date());
            File file = workspace.resolve(LESSONS_PATH);
            FileOutputStream output = new FileOutputStream(file, true);
            try {
                output.write(("- [" + time + "] " + clean + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            } finally {
                output.close();
            }
            if (existing.length() > 12000) {
                String current = readFile(file, 40000);
                write(file, "# Agent Lessons\n\n" + tail(current, 8000));
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    String upsertSkill(String name, String description, String body) {
        String displayName = name == null ? "" : name.trim();
        String content = body == null ? "" : body.trim();
        if (displayName.isEmpty() || content.isEmpty()) {
            return "";
        }
        try {
            String safe = safeName(displayName);
            File directory = workspace.resolve(SKILLS_DIR + "/" + safe);
            File file = new File(directory, "SKILL.md");
            if (!file.isFile()) {
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    return "";
                }
                String text = "# " + displayName + "\n\n"
                        + (description == null || description.trim().isEmpty()
                        ? "" : description.trim() + "\n\n") + content + "\n";
                write(file, text);
                return SKILLS_DIR + "/" + safe + "/SKILL.md";
            }
            String existing = readFile(file, 20000);
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String addition = "\n\n## Rewind Update " + date + "\n" + content + "\n";
            if (existing.contains(content)) {
                return SKILLS_DIR + "/" + safe + "/SKILL.md";
            }
            write(file, tail(existing, 14000) + addition);
            return SKILLS_DIR + "/" + safe + "/SKILL.md";
        } catch (Exception e) {
            return "";
        }
    }

    String upsertKnowledge(String name, String content) {
        String displayName = name == null ? "" : name.trim();
        String text = content == null ? "" : content.trim();
        if (displayName.isEmpty() || text.isEmpty()) {
            return "";
        }
        try {
            String fileName = safeName(displayName);
            if (!fileName.endsWith(".md") && !fileName.endsWith(".txt")) {
                fileName += ".md";
            }
            File file = workspace.resolve(KNOWLEDGE_DIR + "/" + fileName);
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (!file.isFile()) {
                write(file, "# " + displayName + "\n\n" + text + "\n");
            } else {
                String existing = readFile(file, 20000);
                if (!existing.contains(text)) {
                    write(file, tail(existing, 14000)
                            + "\n\n## Rewind Update " + date + "\n" + text + "\n");
                }
            }
            return KNOWLEDGE_DIR + "/" + fileName;
        } catch (Exception e) {
            return "";
        }
    }

    String upsertAgent(String name, String instructions) {
        String displayName = name == null ? "" : name.trim();
        String text = instructions == null ? "" : instructions.trim();
        if (displayName.isEmpty() || text.isEmpty()) {
            return "";
        }
        try {
            File file = workspace.resolve(AGENTS_DIR + "/" + safeName(displayName) + ".json");
            JSONObject object = new JSONObject();
            if (file.isFile()) {
                try {
                    object = new JSONObject(readFile(file, 50000));
                } catch (Exception ignored) {
                    object = new JSONObject();
                }
            }
            object.put("name", displayName);
            String old = object.optString("instructions", "");
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (old.contains(text)) {
                return AGENTS_DIR + "/" + file.getName();
            }
            object.put("instructions", old.trim().isEmpty() ? text
                    : old.trim() + "\n\n## Rewind Update " + date + "\n" + text);
            if (!object.has("tools")) {
                object.put("tools", new JSONArray());
            }
            write(file, object.toString(2));
            return AGENTS_DIR + "/" + file.getName();
        } catch (Exception e) {
            return "";
        }
    }

    String writeEvolutionProposal(String title, String problem,
                                  String evidence, String suggestion) {
        String name = title == null || title.trim().isEmpty()
                ? "Capacity improvements" : title.trim();
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(new Date());
            File file = workspace.resolve(EVOLUTION_DIR + "/proposals/"
                    + stamp + "-" + safeName(name) + ".md");
            write(file, "# Pending rehabilitation:" + name + "\n\n"
                    + "- Status:pending\n"
                    + "- Time:" + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    .format(new Date()) + "\n\n"
                    + "## Problem\n\n" + safe(problem) + "\n\n"
                    + "## Evidence\n\n" + safe(evidence) + "\n\n"
                    + "## Recommendations\n\n" + safe(suggestion) + "\n\n"
                    + "Move this document to .agent/evolution/done/, The modalities of the certification are also described in the report.\n");
            return workspace.relativePath(file);
        } catch (Exception e) {
            return "";
        }
    }

    String writeEvolutionReview(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "";
        }
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(new Date());
            File file = workspace.resolve(EVOLUTION_DIR + "/reviews/review-"
                    + stamp + ".md");
            write(file, content.trim() + "\n");
            return workspace.relativePath(file);
        } catch (Exception e) {
            return "";
        }
    }

    /** High-risk sedimentation is first entered into the isolation candidate area and is not directly injected into the follow-up system. */
    String writeCandidate(String kind, String name, String body, String source) {
        try {
            String display = name == null ? "" : name.trim();
            if (display.isEmpty() || body == null || body.trim().length() < 20) {
                return "";
            }
            File dir = new File(workspace.root(), EVOLUTION_DIR + "/candidates");
            mkdirs(dir);
            String file = safeName(kind + "-" + display) + "-"
                    + System.currentTimeMillis() + ".md";
            File target = new File(dir, file);
            String header = "# Candidates:" + display + "\n\n"
                    + "- Type:" + kind + "\n"
                    + "- Source:" + (source == null ? "unknown" : source) + "\n"
                    + "- Time:" + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    .format(new Date()) + "\n"
                    + "- Status: To be confirmed (not automatically effective)\n\n";
            write(target, header + body.trim() + "\n");
            return EVOLUTION_DIR + "/candidates/" + file;
        } catch (Exception e) {
            return "";
        }
    }

    String pendingEvolutionSection() {
        List<File> files = new ArrayList<>();
        File dir = new File(workspace.root(), EVOLUTION_DIR + "/proposals");
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isFile() && child.getName().endsWith(".md")) {
                    files.add(child);
                }
            }
        }
        if (files.isEmpty()) {
            return "";
        }
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return right.getName().compareTo(left.getName());
            }
        });
        StringBuilder builder = new StringBuilder("\n\n['A self-evolutionary proposal to be repaired'\n");
        int count = 0;
        for (File file : files) {
            if (count++ >= 3) {
                break;
            }
            String text = readFile(file, 2000);
            String first = "";
            for (String line : text.split("\n")) {
                if (line.trim().startsWith("#")) {
                    first = line.trim();
                    break;
                }
            }
            builder.append("- ").append(workspace.relativePath(file));
            if (!first.isEmpty()) {
                builder.append(": ").append(trim(first.replaceFirst("^#+\\s*", ""), 80));
            }
            builder.append('\n');
        }
        builder.append("Prioritize repair when relevant to current task; move files to after completion .agent/evolution/done/. \n");
        return builder.toString();
    }

    private static String safe(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.replaceAll("sk-[A-Za-z0-9_-]{8,}", "sk-***");
        clean = clean.replaceAll("(?i)(api[_ ]?key|token|password|Password|Password)\\s*[:: =]\\s*\\S+",
                "$1:***");
        return trim(clean, 4000);
    }

    private static String tail(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return "...\n" + text.substring(text.length() - max);
    }

    List<AgentProfile> listAgents() {
        List<AgentProfile> profiles = new ArrayList<>();
        try {
            File dir = workspace.resolve(AGENTS_DIR);
            File[] files = dir.listFiles();
            if (files == null) {
                return profiles;
            }
            for (File file : files) {
                if (!file.isFile() || !file.getName().endsWith(".json")) {
                    continue;
                }
                AgentProfile profile = readAgentFile(file);
                if (profile != null) {
                    profiles.add(profile);
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(profiles, new Comparator<AgentProfile>() {
            @Override
            public int compare(AgentProfile left, AgentProfile right) {
                return left.name.compareToIgnoreCase(right.name);
            }
        });
        return profiles;
    }

    AgentProfile loadAgent(String name) {
        if (name == null || name.trim().isEmpty() || "Default".equals(name.trim())) {
            return new AgentProfile("Default", "", null, "");
        }
        for (AgentProfile profile : listAgents()) {
            if (profile.name.equalsIgnoreCase(name.trim())) {
                return profile;
            }
        }
        return new AgentProfile("Default", "", null, "");
    }

    String createAgent(String name, String instructions) {
        String displayName = name == null ? "" : name.trim();
        if (displayName.isEmpty()) {
            return null;
        }
        try {
            String fileName = safeName(displayName) + ".json";
            File file = workspace.resolve(AGENTS_DIR + "/" + fileName);
            if (file.exists()) {
                return null;
            }
            JSONObject object = new JSONObject();
            object.put("name", displayName);
            object.put("instructions", instructions == null ? "" : instructions.trim());
            object.put("tools", new JSONArray());
            write(file, object.toString(2));
            return AGENTS_DIR + "/" + fileName;
        } catch (Exception e) {
            return null;
        }
    }

    String agentFilePath(String name) {
        for (AgentProfile profile : listAgents()) {
            if (profile.name.equalsIgnoreCase(name)) {
                return profile.filePath;
            }
        }
        return "";
    }

    List<SkillInfo> listSkills() {
        List<SkillInfo> skills = new ArrayList<>();
        try {
            File dir = workspace.resolve(SKILLS_DIR);
            File[] directories = dir.listFiles();
            if (directories == null) {
                return skills;
            }
            for (File folder : directories) {
                if (!folder.isDirectory()) {
                    continue;
                }
                File skillFile = new File(folder, "SKILL.md");
                if (!skillFile.isFile()) {
                    continue;
                }
                String content = readFile(skillFile, 2000);
                String description = "";
                String[] lines = content.split("\n");
                boolean titleSeen = false;
                for (String line : lines) {
                    String text = line.trim();
                    if (text.isEmpty()) {
                        continue;
                    }
                    if (!titleSeen && text.startsWith("#")) {
                        titleSeen = true;
                        continue;
                    }
                    description = text;
                    break;
                }
                skills.add(new SkillInfo(folder.getName(), description,
                        SKILLS_DIR + "/" + folder.getName() + "/SKILL.md"));
            }
        } catch (Exception ignored) {
        }
        Collections.sort(skills, new Comparator<SkillInfo>() {
            @Override
            public int compare(SkillInfo left, SkillInfo right) {
                return left.name.compareToIgnoreCase(right.name);
            }
        });
        return skills;
    }

    String readSkill(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "";
        }
        String safe = safeName(name.trim());
        try {
            File file = workspace.resolve(SKILLS_DIR + "/" + safe + "/SKILL.md");
            if (!file.isFile()) {
                return "";
            }
            return trim(readFile(file, 12000), 12000);
        } catch (Exception e) {
            return "";
        }
    }

    String createSkill(String name, String description) {
        String displayName = name == null ? "" : name.trim();
        if (displayName.isEmpty()) {
            return null;
        }
        try {
            String safe = safeName(displayName);
            File directory = workspace.resolve(SKILLS_DIR + "/" + safe);
            if (!directory.exists() && !directory.mkdirs()) {
                return null;
            }
            File file = new File(directory, "SKILL.md");
            if (!file.exists()) {
                String content = "# " + displayName + "\n\n"
                        + (description == null || description.trim().isEmpty()
                        ? "Write this here. Skill The use of this project and its use steps.\n" : description.trim() + "\n")
                        + "\n## Steps\n\n1. \n";
                write(file, content);
            }
            return SKILLS_DIR + "/" + safe + "/SKILL.md";
        } catch (Exception e) {
            return null;
        }
    }

    String createSkillWithBody(String name, String description, String body) {
        String displayName = name == null ? "" : name.trim();
        if (displayName.isEmpty() || body == null || body.trim().isEmpty()) {
            return null;
        }
        try {
            String safe = safeName(displayName);
            File directory = workspace.resolve(SKILLS_DIR + "/" + safe);
            if (directory.exists()) {
                return null;
            }
            if (!directory.mkdirs()) {
                return null;
            }
            File file = new File(directory, "SKILL.md");
            String content = "# " + displayName + "\n\n"
                    + (description == null || description.trim().isEmpty()
                    ? "" : description.trim() + "\n\n")
                    + body.trim() + "\n";
            write(file, content);
            return SKILLS_DIR + "/" + safe + "/SKILL.md";
        } catch (Exception e) {
            return null;
        }
    }

    String skillsSection() {
        List<SkillInfo> skills = listSkills();
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("\n\n[Available Skills]\n");
        int count = 0;
        for (SkillInfo skill : skills) {
            if (count++ >= 20) {
                break;
            }
            builder.append("- ").append(skill.name);
            if (!skill.description.isEmpty()) {
                builder.append(": ").append(trim(skill.description, 100));
            }
            builder.append('\n');
        }
        builder.append("Call if needed read_skill(name) Read the full step; scripts in skills can be used termux_run Implementation.\n");
        return builder.toString();
    }

    List<String> listKnowledgeFiles() {
        List<File> files = new ArrayList<>();
        collectKnowledge(new File(workspace.root(), KNOWLEDGE_DIR), files, 100);
        List<String> paths = new ArrayList<>();
        for (File file : files) {
            paths.add(workspace.relativePath(file));
        }
        Collections.sort(paths);
        return paths;
    }

    String knowledgeSection() {
        return "\n\nLocal knowledge base available search_knowledge(query) searching;knowledge files located "
                + KNOWLEDGE_DIR + ". \n";
    }

    String createKnowledge(String name, String content) {
        String displayName = name == null ? "" : name.trim();
        if (displayName.isEmpty()) {
            return null;
        }
        try {
            String fileName = safeName(displayName);
            if (!fileName.endsWith(".md") && !fileName.endsWith(".txt")) {
                fileName += ".md";
            }
            File file = workspace.resolve(KNOWLEDGE_DIR + "/" + fileName);
            if (!file.exists()) {
                write(file, "# " + displayName + "\n\n"
                        + (content == null || content.trim().isEmpty()
                        ? "Paste the knowledge document here.\n" : content.trim() + "\n"));
            }
            return KNOWLEDGE_DIR + "/" + fileName;
        } catch (Exception e) {
            return null;
        }
    }

    List<SearchHit> searchKnowledge(String query) {
        List<SearchHit> hits = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            return hits;
        }
        List<String> terms = queryTerms(query);
        if (terms.isEmpty()) {
            return hits;
        }
        List<File> files = new ArrayList<>();
        collectKnowledge(new File(workspace.root(), KNOWLEDGE_DIR), files, 100);
        long total = 0;
        for (File file : files) {
            if (total > 1024 * 1024) {
                break;
            }
            String text = readFile(file, 200000);
            total += text.length();
            String[] paragraphs = text.split("\\n\\s*\\n");
            for (String paragraph : paragraphs) {
                String clean = paragraph.replaceAll("\\s+", " ").trim();
                if (clean.isEmpty()) {
                    continue;
                }
                int score = score(clean, terms);
                if (score > 0) {
                    hits.add(new SearchHit(workspace.relativePath(file),
                            trim(clean, 500), score));
                }
            }
        }
        Collections.sort(hits, new Comparator<SearchHit>() {
            @Override
            public int compare(SearchHit left, SearchHit right) {
                return Integer.compare(right.score, left.score);
            }
        });
        if (hits.size() > 6) {
            return new ArrayList<>(hits.subList(0, 6));
        }
        return hits;
    }

    private void collectKnowledge(File directory, List<File> files, int limit) {
        if (directory == null || files.size() >= limit) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (files.size() >= limit) {
                return;
            }
            if (child.isDirectory()) {
                collectKnowledge(child, files, limit);
            } else if (child.isFile()
                    && (child.getName().endsWith(".md") || child.getName().endsWith(".txt"))) {
                files.add(child);
            }
        }
    }

    private static int score(String text, List<String> terms) {
        String lower = text.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            int index = 0;
            int count = 0;
            while ((index = lower.indexOf(term, index)) >= 0) {
                count++;
                index += term.length();
            }
            score += count * Math.max(1, term.length());
        }
        return score;
    }

    private static List<String> queryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        String lower = query.toLowerCase(Locale.ROOT).trim();
        if (!lower.isEmpty()) {
            terms.add(lower);
        }
        for (String part : lower.split("[\\s,, . .!!??; ;:: ]+")) {
            if (part.length() >= 2) {
                terms.add(part);
            }
        }
        if (containsChinese(lower)) {
            for (int i = 0; i + 2 <= lower.length(); i++) {
                terms.add(lower.substring(i, i + 2));
            }
        }
        return new ArrayList<>(terms);
    }

    private static boolean containsChinese(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) {
                return true;
            }
        }
        return false;
    }

    private AgentProfile readAgentFile(File file) {
        String fallback = file.getName().replace(".json", "");
        String path = workspace.relativePath(file);
        String name = fallback;
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString();
            JSONObject object = ToolRegistry.parseObject(text);
            name = object.optString("name", fallback);
            return AgentProfile.fromJson(object, fallback, path);
        } catch (Exception e) {
            // Keep the named profile visible and blocked; never silently fall back to default ALL.
            return new AgentProfile(name, "", ToolScope.none(), path, "Agent profile BLOCKED: " + e.getMessage());
        }
    }

    private String readRelative(String path, int max) {
        try {
            return readFile(workspace.resolve(path), max);
        } catch (Exception e) {
            return "";
        }
    }

    private static String readFile(File file, int max) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try {
            FileInputStream input = new FileInputStream(file);
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (total + read > max) {
                        output.write(buffer, 0, Math.max(0, max - total));
                        break;
                    }
                    output.write(buffer, 0, read);
                    total += read;
                }
                return output.toString("UTF-8");
            } finally {
                input.close();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static void write(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("cannot create dir");
        }
        FileOutputStream output = new FileOutputStream(file, false);
        try {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            output.close();
        }
    }

    private static void mkdirs(File directory) {
        if (directory != null && !directory.isDirectory()) {
            directory.mkdirs();
        }
    }

    private static String safeName(String value) {
        String clean = value.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff_-]", "_").trim();
        if (clean.isEmpty()) {
            clean = "item";
        }
        if (clean.length() > 60) {
            clean = clean.substring(0, 60);
        }
        return clean;
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n...[Interrupted]";
    }
}
