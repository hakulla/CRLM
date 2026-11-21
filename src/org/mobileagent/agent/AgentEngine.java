package org.mobileagent.agent;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AgentEngine {
    interface Listener {
        void onAgentUpdate();
    }

    interface ApprovalHandler {
        int requestApproval(String tool, String arguments);
    }

    interface MemoryCallback {
        void onMemories(List<String> suggestions, String error);
    }

    interface ReviewCallback {
        void onReview(String review, String path, String error);
    }

    interface SkillDraftCallback {
        void onDraft(String name, String description, String body, String error);
    }

    static final int APPROVAL_DENY = 0;
    static final int APPROVAL_ONCE = 1;
    static final int APPROVAL_ALWAYS = 2;

    static final class ContextInfo {
        final int usedTokens;
        final int budgetTokens;
        final int droppedMessages;
        final int percent;

        ContextInfo(int usedTokens, int budgetTokens, int droppedMessages, int reserved) {
            this.usedTokens = usedTokens;
            this.budgetTokens = budgetTokens;
            this.droppedMessages = droppedMessages;
            // The output reserve is not history space: counting it made a brand new
            // conversation read as 99% full.
            int contentBudget = Math.max(1, budgetTokens - reserved);
            int contentUsed = Math.max(0, usedTokens - reserved);
            this.percent = Math.max(0, Math.min(100,
                    Math.round(contentUsed * 100f / contentBudget)));
        }
    }

    private static final class PreparedContext {
        final JSONArray messages;
        final JSONArray tools;
        final Conversation settings;
        final int tokens;
        final int dropped;
        /** Output Preset + Safety surplus: they are not historical space. */
        final int reserved;
        final String error;

        PreparedContext(JSONArray messages, JSONArray tools, Conversation settings,
                        int tokens, int dropped, int reserved, String error) {
            this.messages = messages;
            this.tools = tools;
            this.settings = settings;
            this.tokens = tokens;
            this.dropped = dropped;
            this.reserved = reserved;
            this.error = error;
        }
    }


    static final class Entry {
        static final int USER = 0;
        static final int ASSISTANT = 1;
        static final int TOOL = 2;
        static final int ERROR = 3;
        static final int SYSTEM = 4;
        static final int REASONING = 5;
        /** Other Organisertext Yes. JSON Array [{question, options[], multi}].  */
        static final int QUESTIONS = 6;
        /** Screenshot/Photo card:images is the absolute path in the working area, clicking on the dialogue page to open the preview. */
        static final int IMAGE = 7;

        final int type;
        volatile String text;
        final long time;
        final List<String> images;
        /** Workspace-relative paths of files picked from the system file manager. */
        final List<String> files;

        Entry(int type, String text, long time) {
            this(type, text, time, null, null);
        }

        Entry(int type, String text, long time, List<String> images) {
            this(type, text, time, images, null);
        }

        Entry(int type, String text, long time, List<String> images, List<String> files) {
            this.type = type;
            this.text = text;
            this.time = time;
            this.images = images == null ? new ArrayList<String>() : new ArrayList<>(images);
            this.files = files == null ? new ArrayList<String>() : new ArrayList<>(files);
        }
    }

    /** A system-picked file copied into the workspace so tools can read it. */
    static final class FileAttachment {
        final String path;
        final String name;
        final long size;
        final String mime;

        FileAttachment(String path, String name, long size, String mime) {
            this.path = path == null ? "" : path;
            this.name = name == null || name.trim().isEmpty() ? "file" : name.trim();
            this.size = Math.max(0L, size);
            this.mime = mime == null ? "" : mime;
        }
    }

    static final class ImageAttachment {
        final String path;
        final byte[] jpeg;
        final String mime;

        ImageAttachment(String path, byte[] jpeg, String mime) {
            this.path = path == null ? "" : path;
            this.jpeg = jpeg;
            this.mime = mime == null || mime.isEmpty() ? "image/jpeg" : mime;
        }
    }

    private static final int MAX_READ_BYTES = 64 * 1024;
    /** text-reading single-page budget;actually accessed contextBudget(), The result of the page is complete access to the request. */
    private static final int MAX_READ_PAGE_CHARS = 30000;
    /** Suggested text files above this size search/sed Positioning to avoid the scanned toll of the entire document. */
    private static final long MAX_TEXT_READ_BYTES = 64L * 1024 * 1024;
    /** view_image Single picture ceiling: approximately 4MB, Avoid base64 After the blast. */
    private static final long MAX_IMAGE_VIEW_BYTES = 4L * 1024 * 1024;
    private static final int MAX_SEARCH_OUTPUT = 24000;
    private static final int MAX_IMAGE_TOTAL_BYTES = 4 * 1024 * 1024;
    private static final long MAX_DOWNLOAD_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_WRITE_BYTES = 8 * 1024 * 1024;
    /** Single File HTML/Saltline It's always more than that. 512KB; Edit upper limit is set for memory reading by file as a whole. */
    private static final int MAX_EDIT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_LIST_FILES = 200;
    private static final int MAX_ENTRIES = 1000;
    private static final int MAX_TOOL_OUTPUT = 12000;
    /** One run of the hard ceiling: at the top, it must be suspended and the user point 'continues' opens a new round. */
    private static final int HARD_MAX_ROUNDS = 24;
    /** The user opens the 'Face-through ceiling' without a wheeled subspan (cost-sharing). */
    private static final int OVER_LIMIT_ROUNDS = Integer.MAX_VALUE / 4;
    /** In the absence of any acceptance steps, only so many more rounds are given to export conclusions. */
    private static final int CONVERGE_ROUNDS = 2;
    private static final long MAX_RUN_MS = 10L * 60L * 1000L;
    /** The consecutive reading wheel reaches that value and avoids infinity of exploration. */
        private static final int NO_PROGRESS_READ_ROUNDS = 12;
    private static final int SOURCE_READ_TOTAL_LIMIT = 60;
    private static final int SOURCE_READ_PER_FILE_LIMIT = 12;
    private static final int SOURCE_PAGE_LINES = 200;
    /** Same tool to call (name)+(Aggregations) A cumulative maximum number of times. */
    private static final int REPEAT_CALL_LIMIT = 3;
    private static final int LITE_CONTEXT_TOKENS = 12000;
    private static final int LITE_MAX_OUTPUT = 4096;
    private static final int LITE_ROUNDS = 12;
    /** Soft ceilings every time there's a real information gain +4, Until the hard limit. */
    private static final int ROUND_EXTENSION = 4;
    private static final long LITE_TASK_BUDGET = 120000L;
    private static final long TASK_BUDGET_EXTENSION = 80000L;
    private static final long MAX_TASK_BUDGET = 360000L;
    private static final int MAX_LOADED_ADAPTERS = 6;
    private static final int AUTO_ADAPTER_USES = 8;
    private static final int MANUAL_ADAPTER_USES = 4;
    private static final int MAX_TOOL_SEARCHES = 6;
    private static final int MAX_TOOL_LOADS = 12;
    /** Autorecovering of interruptions only handles the latest 10 Mission in a minute. Avoid reopening. App Burn it. token.  */
    private static final long AUTO_RESUME_MAX_AGE_MS = 10L * 60L * 1000L;
    private static final Set<String> READ_ONLY_SHELL = new HashSet<>(Arrays.asList(
            "ls", "cat", "head", "tail", "grep", "egrep", "fgrep", "sed", "awk", "wc",
            "find", "stat", "file", "du", "df", "md5sum", "sha1sum", "sha256sum", "diff",
            "cmp", "echo", "printf", "pwd", "id", "uname", "which", "command", "test",
            "true", "false", "date", "sort", "uniq", "cut", "tr", "nl", "readlink",
            "realpath", "basename", "dirname", "tree", "strings", "hexdump", "od", "xxd",
            "time", "env", "sleep"));
    // Maximum number of times a model is automatically corrected when returning empty content (no text, no tools to call)
    private static final int EMPTY_REPLY_RETRY_LIMIT = 2;
    private static final int MAX_CORRECTIONS = 6;
    /** Visually restored tasks: each'Modify + Intergraph comparison'Count it. Up. 6 Forces to stop after wheel. */
    private static final int VISUAL_MAX_ITERATIONS = 6;
    private static final int VISUAL_MAX_EDITS_PER_ITERATION = 3;
    /** Up to 1 agent for a single assignment to prevent simultaneous distribution/Recursively magnified. */
    private static final int MAX_SUBAGENT_CALLS = 6;
    private static final Set<String> SUBAGENT_TOOL_NAMES = new HashSet<>(Arrays.asList(
            "list_files", "read_file", "glob", "grep", "search_knowledge",
            "list_skills", "read_skill", "web_fetch", "web_search"));
    private static final Set<String> CORE_TOOL_NAMES = new LinkedHashSet<>(Arrays.asList(
            "inspect", "edit", "run", "ask", "delegate", "update_plan",
            "tool_search", "tool_load"));
    private static final Set<String> LEGACY_CORE_ALIASES = new HashSet<>(Arrays.asList(
            "read_file", "list_files", "grep", "glob", "write_file", "edit_file",
            "shell_run", "ask_user", "spawn_subagent"));
    private static final String SUBAGENT_PROMPT =
            "You're the Lord. Agent You're the only investigator you've sent. Your job is to gather evidence and draw conclusions."
                    + "Cannot modify files, cannot execute commands, cannot operate interfaces; web-based research is available first web_search Find the link. Use it again. web_fetch Read the text.\n"
                    + "Rules:\n"
                    + "1. The budget for the step is tight and must be in place once: the task is to give clear documentation and read directly; if the path is not clear, the path must be in place. list/glob Column directory location, do not over and over again guess paths.\n"
                    + "2. Up to two readings of the same document (first page) + (a) The paragraph is required, and the same paragraph is not read over and is immediately concluded with sufficient evidence.\n"
                    + "3. Each conclusion must be supported by evidence, citing relative paths and line numbers (see table 2).file:line). \n"
                    + "4. Do not request confirmation from users; state clearly what is missing when no or insufficient information is available and write the identified part first.\n"
                    + "5. The final conclusion is in the 800 In the words: first give the answer, then give the key evidence, and finally add the uncertainty.";
    private static volatile AgentEngine instance;

    private final Context appContext;
    private final AppPrefs prefs;
    private final Workspace baseWorkspace;
    private Workspace workspace;
    private final ConversationStore conversations;
    private final LlmClient llm;
    private final McpManager mcp;
    private final WorkSupervisor supervisor;
    /** Engine-local registry: one schema/effect/resource/executor binding per built-in tool. */
    private final ToolRegistry tools;
    private volatile List<ScreenCaptureService.CaptureResult> roundShots = new ArrayList<>();
    private volatile String workTicketId = "";
    /** One-lined implementer; the card is replaced in its entirety when it dies, ensuring that the follow-up mission is not blocked by the old thread. */
    private volatile ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Thread workerThread;
    /** Live shell child process, so Stop can kill it instead of waiting out its timeout. */
    private volatile Process activeShellProcess;
    /** The number of acceptance steps was last extended; no more rounds were purchased without new acceptances. */
    private int lastExtensionCompleted = 0;
    /** Has this round been down?'Forced closure'Instruction. */
    private boolean converged = false;
    private final boolean headless;
    private volatile String lastRunOutcome = "";
    private volatile long lastRunTokens;
    private volatile String lastRunErrorText = "";
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> webCacheReads = new HashSet<>();
    private boolean readOnlyNudged;
    private int sourceReadCalls;
    /** Operational programme: reflections.../Implementation tool.../Generating responses..., For presentation in the bottom status bar. */
    private volatile String runPhase = "";
    /** Phases: summary of the target audience or reflection of the tool, presented in single row after the stage. */
    private volatile String runDetail = "";
    private final Map<String, Integer> sourceReadCounts = new HashMap<>();
    private boolean sourceReadNudged;
    private AgentState runState;
    private StrategyLedger strategyLedger;
    private ExperienceStore experienceStore;
    private CapabilityRegistry.Snapshot capabilities;
    private StateMonitor stateMonitor;
    private StateMonitor.Decision lastStateDecision;
    private final Object saveQueueLock = new Object();
    private boolean saveQueued;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Operating intergenerational: Forced termination or the start of a new mission, leaving the old thread silently and without changing the shared status. */
    private volatile int runGeneration;
    private final Object entriesLock = new Object();
    private final Object runLock = new Object();
    private final List<Entry> entries = new ArrayList<>();
    /** Errors reported to mission centre during current cycle to avoid double-checking of each round with the same error. */
    private final Set<String> reportedErrors = new HashSet<>();
    private final FailurePolicy failurePolicy = new FailurePolicy();
    private final List<JSONObject> messages = new ArrayList<>();
    private volatile Listener listener;
    private volatile ApprovalHandler approvalHandler;
    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile boolean pauseRequested;
    /** Whether this round is due to the number of rounds/Budget/The window ceiling is suspended for the interface to give a ' break-up cap' portal directly. */
    private volatile boolean limitPaused;
    /** Invalidates delayed run watchdogs when a run ends or is replaced. */
    private int watchdogToken;
    private volatile int injectedSeq;
    /**
     * The system hint that you inject during the tool 's implementation must be queued.assistant Call → user Hint → tool 'and the result.'
     * In this order, the entire round of requests is made illegal (calling)idEmpty or duplicate / The use of the tool is still not closed.
     * And... edit After success update_plan The alarm was injected within the implementer, and it was in that order.
     */
    private int inFlightCalls;
    private final List<String> deferredSynthetics = new ArrayList<>();
    /** Only the second reading of the same document is a pointer: repeat reading is one of the greatest waste sources of long talk. */
    private static final class ReadMark {
        String sha = "";
        final Set<String> ranges = new HashSet<>();
    }
    /** The result of the event beyond this length is left with only a handle on the context. */
    private static final int HANDLE_THRESHOLD_CHARS = 6000;
    /** The history exceeds this number for archiving compression. */
    private static final int COMPACT_MIN_MESSAGES = 80;
    /** Number of recent messages kept after compression (approximately) 10 A tool round. */
    private static final int COMPACT_KEEP_MESSAGES = 40;
    private final Map<String, ReadMark> readMarks = new HashMap<>();
    private int messagesVersion;
    private ContextInfo cachedContextInfo;
    private int cachedContextVersion = -1;
    private Capabilities activeCapabilities;
    private Set<String> activeToolNames;
    private ToolScope activeToolScope = ToolScope.none();
    /** Agent JSON (a) To retain the original scope when clearly limiting the tool;null Expressing Default Core + Current round adapter. */
    private ToolScope profileScope;
    private boolean profileRestricted;
    private final LinkedHashMap<String, Integer> loadedAdapters = new LinkedHashMap<>();
    private HookManager hooks;
    private boolean stopHooksFired;
    private String runOutcome = "";
    private int toolSearchCount;
    private int toolLoadCount;
    private VerificationLedger runLedger = new VerificationLedger();
    private TaskBudget taskBudget;
    private String currentRunId = "";
    private List<McpManager.Tool> activeMcpTools = new ArrayList<>();
    private JSONArray cachedToolDefinitions;
    private String cachedToolDefinitionsKey = "";
    private Conversation conversation;
    private volatile int currentStep;
    private volatile int maxSteps;
    private long runPromptTokens;
    private long runCompletionTokens;
    private long runCachedTokens;
    private int runToolCalls;
    private String runThinking;
    private String runInput = "";
    private boolean visualTask;
    private boolean visualBaselineCaptured;
    private int visualIterations;
    private int visualEditsSinceShot;
    private boolean visualStopAnnounced;
    /** The current wheel tool policy track for identification run↔browser_open This cross-tool cycle. */
    private final List<String> loopTrail = new ArrayList<>();
    private int browserFailures;
    private int browserOpensSinceProgress;
    private String browserFailClass = "";
    private volatile String loopStopReason = "";
    private int subagentCalls;
    private boolean thinkingPruneNoted;
    private boolean thinkingFallbackTried;
    private final Object persistLock = new Object();
    private boolean recoveryChecked;
    private static final int MAX_AUTO_RESUME = 2;

    static AgentEngine get(Context context) {
        if (instance == null) {
            synchronized (AgentEngine.class) {
                if (instance == null) {
                    instance = new AgentEngine(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private AgentEngine(Context context) {
        appContext = context.getApplicationContext();
        prefs = new AppPrefs(appContext);
        llm = new LlmClient(prefs);
        baseWorkspace = new Workspace(appContext);
        conversations = ConversationStore.shared(appContext);
        conversation = conversations.loadLastOrCreate(prefs, baseWorkspace);
        workspace = baseWorkspace.sub(conversation.workspace);
        headless = false;
        prefs.setLastConversationId(conversation.id);
        loadConversationMemory();
        mcp = new McpManager(appContext);
        supervisor = WorkSupervisor.get(appContext);
        tools = ToolRegistry.withBuiltins();
        bindBuiltinExecutors();
        if (!headless) {
            backfillRecentErrors();
        }
    }

    /** A private engine for scheduled automations: own conversation, no UI or app-wide service side effects. */
    static AgentEngine forAutomation(Context context, Conversation target) {
        if (target == null) {
            throw new IllegalArgumentException("Missing automation conversation");
        }
        return new AgentEngine(context.getApplicationContext(), target, true);
    }

    /** A UI session engine bound to one conversation; several can run at the same time. */
    static AgentEngine forSession(Context context, Conversation target) {
        if (target == null) {
            throw new IllegalArgumentException("Missing session conversation");
        }
        return new AgentEngine(context.getApplicationContext(), target, false);
    }

    private AgentEngine(Context context, Conversation target, boolean headlessMode) {
        appContext = context;
        prefs = new AppPrefs(appContext);
        llm = new LlmClient(prefs);
        baseWorkspace = new Workspace(appContext);
        conversations = ConversationStore.shared(appContext);
        conversation = target;
        workspace = baseWorkspace.sub(target.workspace);
        headless = headlessMode;
        loadConversationMemory();
        mcp = new McpManager(appContext);
        supervisor = WorkSupervisor.get(appContext);
        tools = ToolRegistry.withBuiltins();
        bindBuiltinExecutors();
        if (!headless) {
            backfillRecentErrors();
        }
    }

    /**
     * The single place mapping a built-in tool name to its real implementation.
     * Schemas/effects/resources stay owned by ToolRegistry; this only supplies
     * the engine-side handler so there is no second name dispatcher.
     */
    private void bindBuiltinExecutors() {
        tools.bindExecutor("update_plan", (args, r) -> updatePlan(args.optJSONArray("steps")));
        tools.bindExecutor("self_check", (args, r) -> okJson(selfCheck()));
        tools.bindExecutor("glob", (args, r) -> globFiles(args.optString("pattern", ""),
                args.optString("path", ""), args.optInt("max_results", 100)));
        tools.bindExecutor("grep", (args, r) -> grepFiles(args.optString("query", ""),
                args.optString("path", ""), args.optString("glob", ""),
                args.optBoolean("ignore_case", false), args.optInt("context", 0),
                args.optInt("max_results", 30)));
        tools.bindExecutor("edit_file", (args, r) -> editFile(args.optString("path", ""),
                args.optString("old_text", ""), args.optString("new_text", ""),
                args.optBoolean("replace_all", false)));
        tools.bindExecutor("move_file", (args, r) -> moveFile(args.optString("from", ""),
                args.optString("to", ""), args.optBoolean("overwrite", false)));
        tools.bindExecutor("delete_file", (args, r) -> deleteFile(args.optString("path", "")));
        tools.bindExecutor("spawn_subagent", (args, r) -> spawnSubagent(
                args.optString("task", ""), args.optInt("max_steps", 8)));
        tools.bindExecutor("list_checkpoints", (args, r) -> listCheckpoints(args.optInt("limit", 20)));
        tools.bindExecutor("restore_checkpoint", (args, r) -> restoreCheckpoint(args.optString("id", "")));
        tools.bindExecutor("clipboard_read", (args, r) -> clipboardRead());
        tools.bindExecutor("clipboard_write", (args, r) -> clipboardWrite(args.optString("text", "")));
        tools.bindExecutor("web_fetch", (args, r) -> webFetch(args.optString("url", ""),
                workTicketId != null && supervisor.networkAllowed(workTicketId)));
        tools.bindExecutor("download_file", (args, r) -> downloadFileTool(
                args.optString("url", ""), args.optString("path", ""),
                args.optString("sha256", ""), args.optLong("size", 0L)));
        tools.bindExecutor("web_search", (args, r) -> webSearch(args.optString("query", "")));
        tools.bindExecutor("image_search", (args, r) -> imageSearchTool(args));
        tools.bindExecutor("remember", (args, r) -> remember(args.optString("note", "")));
        tools.bindExecutor("list_skills", (args, r) -> listSkills());
        tools.bindExecutor("read_skill", (args, r) -> readSkill(args.optString("name", "")));
        tools.bindExecutor("search_knowledge", (args, r) -> searchKnowledge(args.optString("query", "")));
        tools.bindExecutor("repo_index", (args, r) -> repoIndex(args.optBoolean("rebuild", false)));
        tools.bindExecutor("repo_search", (args, r) -> repoSearch(args.optString("query", ""),
                args.optString("path", ""), args.optInt("max_results", 6)));
        tools.bindExecutor("repo_map", (args, r) -> repoMap(args.optString("path", ""),
                args.optInt("max_files", 80)));
        tools.bindExecutor("apply_patch", (args, r) -> applyPatch(args.optString("path", ""),
                args.optString("patch", ""), args.optBoolean("dry_run", false)));
        tools.bindExecutor("mcp_discover", (args, r) -> mcpDiscover(args.optString("query", ""),
                args.optString("server", "")));
        tools.bindExecutor("list_files", (args, r) -> listFiles(args.optString("path", "")));
        tools.bindExecutor("read_file", (args, r) -> readTool(args));
        tools.bindExecutor("write_file", (args, r) -> writeFile(args.optString("path", ""),
                args.optString("content", ""), args.optBoolean("append", false)));
        tools.bindExecutor("list_apps", (args, r) -> listApps(args.optString("query", "")));
        tools.bindExecutor("open_app", (args, r) -> openApp(args.optString("query", "")));
        tools.bindExecutor("return_to_app", (args, r) -> returnToApp());
        tools.bindExecutor("ui_dump", (args, r) -> uiDump());
        tools.bindExecutor("screenshot", (args, r) -> screenshot());
        tools.bindExecutor("view_image", (args, r) -> viewImage(args));
        tools.bindExecutor("browser_open", (args, r) -> browserOpen(args));
        tools.bindExecutor("browser_screenshot", (args, r) -> browserScreenshot(args));
        tools.bindExecutor("browser_eval", (args, r) -> browserEval(args));
        tools.bindExecutor("ask_user", (args, r) -> askQuestions(args));
        tools.bindExecutor("inspect", (args, r) -> {
            String result = inspectTool(args);
            recordInspectWorkingSet(args, result);
            return result;
        });
        tools.bindExecutor("edit", (args, r) -> {
            String result = editTool(args);
            if (toolResultOk(result)) {
                String path = args.optString("path", "");
                recordWorkingSet("Edit", path, args.optString("intent", ""));
                                    clearSourceReadQuota(path);
                injectEditPlanNudge(path);
            }
            return result;
        });
                tools.bindExecutor("run", (args, r) -> {
            String result = shellRun(args.optString("command", ""),
                    args.optInt("timeout_s", 60),
                    args.optInt("max_lines", 0),
                    args.optBoolean("summary_only", false));
            recordRunWorkingSet(args, result);
            return result;
        });
        tools.bindExecutor("ask", (args, r) -> askQuestions(args));
        tools.bindExecutor("delegate", (args, r) -> spawnSubagent(args.optString("task", ""),
                args.optInt("max_steps", 8)));
        tools.bindExecutor("tool_search", (args, r) -> toolSearch(args.optString("query", ""),
                args.optInt("limit", 6)));
        tools.bindExecutor("tool_load", (args, r) -> toolLoad(args.optString("name", "")));
        tools.bindExecutor("context_search", (args, r) -> ContextArchive.search(workspace.baseRoot(),
                conversation == null ? "current" : conversation.id,
                args.optString("query", ""), args.optInt("limit", 8)));
        tools.bindExecutor("shell_run", (args, r) -> shellRun(args.optString("command", ""),
                args.optInt("timeout_s", 60)));
        tools.bindExecutor("termux_run", (args, r) -> termuxRun(args.optString("command", ""),
                args.optInt("timeout_s", 180)));
        tools.bindExecutor("build_apk", (args, r) -> buildApk(args.optString("project", "")));
        tools.bindExecutor("install_apk", (args, r) -> installApk(args.optString("path", "")));
        tools.bindExecutor("ui_tap", (args, r) -> uiTap(args));
        tools.bindExecutor("ui_set_text", (args, r) -> uiSetText(args.optString("target", ""),
                args.optString("text", "")));
        tools.bindExecutor("ui_scroll", (args, r) -> uiScroll(args.optString("direction", "down")));
        tools.bindExecutor("system_action", (args, r) -> systemAction(args.optString("action", "")));
    }

    private String readTool(JSONObject args) throws Exception {
        String path = args.optString("path", "");
        if (path != null && path.startsWith(".agent/web/")) {
            if (!webCacheReads.add(path)) {
                return errorJson("The full-text cache of the web page has been read on the current round; no more readings."
                        + "Please answer the user directly based on the preview in the dialogue or let the user specify a specific chapter.");
            }
            if (args.optInt("limit", 200) > 80) args.put("limit", 80);
        }
        return readFile(path, args.optInt("offset", 1), args.optInt("limit", 200));
    }

    private String screenshot() {
        if (!prefs.allowDeviceControl()) {
            return errorJson("Device control is not open, no intercept");
        }
        ScreenCaptureService.CaptureResult shot = ScreenCaptureService.captureBlocking();
        if (shot.error != null) {
            return errorJson(shot.error);
        }
        roundShots.add(shot);
        return okJson("Screenshot saved: " + shot.path);
    }

    /** Read pictures files in the workspace as image_url Implanting the model and achieving 'see-the-art' */
    private String viewImage(JSONObject args) {
        String path = args.optString("path", "").trim();
        if (path.isEmpty()) {
            return errorJson("Missing pictures path path");
        }
        try {
            File file = workspace.resolve(path);
            if (!file.isFile()) {
                return errorJson("Picture does not exist:" + path);
            }
            long size = file.length();
            if (size > MAX_IMAGE_VIEW_BYTES) {
                return errorJson("Images are too big (" + size + " bytes, upper limit " + MAX_IMAGE_VIEW_BYTES
                        + "); Please compress or intercept the local area first.");
            }
            byte[] bytes = readBytes(file, (int) size);
            String mime = imageMimeOf(path);
            if (mime == null) {
                return errorJson("Unsupported Photo Formatting (only) png/jpg/jpeg/webp/gif): " + path);
            }
            ScreenCaptureService.CaptureResult shot =
                    ScreenCaptureService.CaptureResult.success(bytes, file.getAbsolutePath());
            roundShots.add(shot);
            return okJson("Attached to the model:" + path + "(" + size + " bytes)");
        } catch (Exception e) {
            return errorJson("Could not close temporary folder: %s" + e.getMessage());
        }
    }

    /** Internal browser: Open Page (URL Or work area/Local of Absolute Path HTML), returns the title and text summary. */
    private String browserOpen(JSONObject args) {
        String url = args.optString("url", "").trim();
        if (url.isEmpty()) {
            return errorJson("Missing url(Support http(s):// Or local html Path)");
        }
        int timeout = args.optInt("timeout_ms", 45000);
        if (timeout < 3000) timeout = 3000;
        if (timeout > 90000) timeout = 90000;
        String target = url;
        if (!url.contains("://")) {
            try {
                File file = workspace.resolve(url);
                if (file.isFile()) {
                    target = "file://" + file.getAbsolutePath();
                }
            } catch (Exception ignored) {
            }
        }
        try {
            BrowserSession session = BrowserSession.get();
            String error = session.open(appContext, target, timeout);
            if (error != null) {
                String lower = error.toLowerCase(Locale.ROOT);
                if (lower.contains("err_name_not_resolved")
                        || lower.contains("err_connection_refused")
                        || lower.contains("err_file_not_found")) {
                    return errorJson(error + "\nLocal static page please direct the workspace relative path"
                            + "(It's gonna turn. file://); Local HTTP Service is only for use. http://127.0.0.1:Port,"
                            + "And first. run Implementation curl -I Confirms that the port is listening. Do not open the same address again.");
                }
                return errorJson(error);
            }
            String raw = session.extractText();
            String title = "";
            String text = raw == null ? "" : raw;
            try {
                JSONObject obj = new JSONObject(raw);
                title = obj.optString("title", "");
                text = obj.optString("text", "");
            } catch (Exception ignored) {
            }
            if (text.length() > BrowserSession.maxText()) {
                text = text.substring(0, BrowserSession.maxText()) + "\n...(Interrupted)";
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("url", session.currentUrl());
            out.put("title", title);
            out.put("text", text);
            String console = session.consoleDump();
            if (!console.isEmpty()) {
                out.put("console", console);
            }
            out.put("hint", "Call if you need to see the rendering. browser_screenshot, The screenshot is automatically attached to the model.");
            if (isLocalHtml(session.currentUrl())) {
                attachBrowserScreenshot(session, out);
                try {
                    String diag = session.evaluate("(function(){var c=document.querySelector('canvas');"
                            + "var e=window.__errors||window.__ERRORS||[];"
                            + "return JSON.stringify({canvas:!!c,width:c?c.width:0,"
                            + "height:c?c.height:0,three:!!window.THREE,"
                            + "errors:Array.isArray(e)?e.slice(0,5):[]});})()");
                    if (diag != null && !diag.trim().isEmpty()) {
                        out.put("diagnostics", diag);
                    }
                } catch (Throwable ignored) {
                }
            }
            return out.toString();
        } catch (Exception e) {
            return errorJson("Failed to open page:" + e.getMessage());
        }
    }

    private static boolean isLocalHtml(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("file://") || lower.endsWith(".html") || lower.endsWith(".htm");
    }

    /** Local HTML When you open it, you automatically intercept a picture and avoid the repetition of the model. browser_open I can't see the picture. */
    private void attachBrowserScreenshot(BrowserSession session, JSONObject out) {
        try {
            byte[] png = session.screenshot();
            if (png == null || png.length == 0) {
                out.put("screenshot_error", session.lastError());
                return;
            }
            String path = "screenshots/browser-open-" + System.currentTimeMillis() + ".png";
            File file = workspace.resolve(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream stream = new FileOutputStream(file)) {
                stream.write(png);
            }
            roundShots.add(ScreenCaptureService.CaptureResult.success(png, file.getAbsolutePath()));
            out.put("screenshot", path);
            out.put("screenshot_note", "Auto-separated pages and attached to models; next steps directly to screenshots or continue to change, do not repeat browser_open. ");
            if (mostlyDarkPng(png)) {
                out.put("screenshot_warning", "Auto-interception is almost completely black: the page may not be rendered."
                        + "First. browser_eval Inspection window.THREE/window.__errors, Do not repeat the screenshot.");
            }
        } catch (Exception e) {
            try {
                out.put("screenshot_error", e.getMessage());
            } catch (Exception ignored) {
            }
        }
    }

    /** Internal browser: Intercept the current page and attach it to the model as a picture. */
    private String browserScreenshot(JSONObject args) {
        try {
            BrowserSession session = BrowserSession.get();
            if (session.currentUrl() == null || session.currentUrl().isEmpty()) {
                return errorJson("The browser has not opened the page. Please call first. browser_open");
            }
            byte[] png = session.screenshot();
            if (png == null || png.length == 0) {
                return errorJson("Could not close temporary folder: %s" + session.lastError());
            }
            String path = "screenshots/browser-" + System.currentTimeMillis() + ".png";
            File out = workspace.resolve(path);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream stream = new FileOutputStream(out)) {
                stream.write(png);
            }
            ScreenCaptureService.CaptureResult shot =
                    ScreenCaptureService.CaptureResult.success(png, out.getAbsolutePath());
            roundShots.add(shot);
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("path", path);
            result.put("url", session.currentUrl());
            result.put("bytes", png.length);
            result.put("message", "Internal browser page intercepted and attached to model:" + path);
            if (mostlyDarkPng(png)) {
                result.put("warning", "The screenshot is almost completely black: the page is probably not replayed (see figure).Three.js/JS Not executed or canvas empty."
                        + "Call first. browser_eval Inspection window.THREE, window.__errors and canvas Dimensions,"
                        + "Do not repeat the screenshot; fix the page and then intercept it.");
            }
            return result.toString();
        } catch (Exception e) {
            return errorJson("Could not close temporary folder: %s" + e.getMessage());
        }
    }

    private String browserEval(JSONObject args) {
        String js = args == null ? "" : args.optString("js", "").trim();
        if (js.isEmpty()) {
            return errorJson("Missing js(To execute on current page JavaScript)");
        }
        try {
            BrowserSession session = BrowserSession.get();
            if (session.currentUrl() == null || session.currentUrl().isEmpty()) {
                return errorJson("The browser has not opened the page. Please call first. browser_open");
            }
            String result = session.evaluate(js);
            return okJson(shorten(result, 12000));
        } catch (Exception e) {
            return errorJson("Execute Web Page JS Failed:" + e.getMessage());
        }
    }

    private static boolean mostlyDarkPng(byte[] png) {
        if (png == null || png.length == 0) {
            return false;
        }
        try {
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(
                    png, 0, png.length);
            if (bitmap == null) {
                return false;
            }
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int stepX = Math.max(1, width / 48);
            int stepY = Math.max(1, height / 48);
            int total = 0;
            int dark = 0;
            for (int y = 0; y < height; y += stepY) {
                for (int x = 0; x < width; x += stepX) {
                    int color = bitmap.getPixel(x, y);
                    int r = (color >> 16) & 0xff;
                    int g = (color >> 8) & 0xff;
                    int b = color & 0xff;
                    total++;
                    if (r < 10 && g < 10 && b < 10) {
                        dark++;
                    }
                }
            }
            bitmap.recycle();
            return total > 0 && dark * 100L / total >= 97L;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String imageMimeOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return null;
    }

    private static String sniffImageMime(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        if ((bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8) {
            return "image/jpeg";
        }
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    Workspace workspace() {
        return workspace;
    }

    Workspace baseWorkspace() {
        return baseWorkspace;
    }

    AppPrefs prefs() {
        return prefs;
    }

    void setApprovalHandler(ApprovalHandler handler) {
        this.approvalHandler = handler;
    }

    String approvalMode() {
        return conversation == null ? Conversation.APPROVAL_AUTO
                : conversation.approvalMode;
    }

    void setApprovalMode(String mode) {
        if (conversation == null) {
            return;
        }
        if (!Conversation.APPROVAL_ASK.equals(mode)
                && !Conversation.APPROVAL_READONLY.equals(mode)) {
            mode = Conversation.APPROVAL_AUTO;
        }
        conversation.approvalMode = mode;
        saveCurrent();
        notifyUpdate();
    }

    List<String> alwaysAllowedTools() {
        List<String> names = new ArrayList<>();
        if (conversation == null || conversation.allowedTools == null) {
            return names;
        }
        for (int i = 0; i < conversation.allowedTools.length(); i++) {
            String name = allowedToolName(conversation.allowedTools.optString(i, ""));
            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    void clearAlwaysAllowedTools() {
        if (conversation == null) {
            return;
        }
        conversation.allowedTools = new JSONArray();
        saveCurrent();
        notifyUpdate();
    }

    void removeAlwaysAllowedTool(String name) {
        if (conversation == null || conversation.allowedTools == null) {
            return;
        }
        JSONArray kept = new JSONArray();
        for (int i = 0; i < conversation.allowedTools.length(); i++) {
            String current = conversation.allowedTools.optString(i, "");
            if (!name.equals(allowedToolName(current))) {
                kept.put(current);
            }
        }
        conversation.allowedTools = kept;
        saveCurrent();
        notifyUpdate();
    }

    /** 'Always allowed'Effective by tool;old name|hash Records remain compatible. */
    void allowToolAlways(String name) {
        if (conversation == null || name == null || !name.matches("[A-Za-z][A-Za-z0-9_]*")) {
            return;
        }
        if (conversation.allowedTools == null) {
            conversation.allowedTools = new JSONArray();
        }
        for (int i = 0; i < conversation.allowedTools.length(); i++) {
            if (name.equals(allowedToolName(conversation.allowedTools.optString(i, "")))) {
                return;
            }
        }
        conversation.allowedTools.put(name);
        saveCurrent();
        notifyUpdate();
    }

    void setAlwaysAllowedTools(List<String> names) {
        if (conversation == null) {
            return;
        }
        JSONArray array = new JSONArray();
        if (names != null) {
            for (String name : names) {
                if (name != null && name.matches("[A-Za-z][A-Za-z0-9_]*")
                        && !containsAllowedTool(array, name)) {
                    array.put(name);
                }
            }
        }
        conversation.allowedTools = array;
        saveCurrent();
        notifyUpdate();
    }

    private static boolean containsAllowedTool(JSONArray array, String name) {
        for (int i = 0; i < array.length(); i++) {
            if (name.equals(array.optString(i, ""))) {
                return true;
            }
        }
        return false;
    }

    private static String allowedToolName(String entry) {
        if (entry == null) {
            return "";
        }
        int bar = entry.indexOf('|');
        return bar > 0 ? entry.substring(0, bar) : entry;
    }

    private boolean isAlwaysAllowed(String name, String args) {
        if (conversation == null || conversation.allowedTools == null || name == null) {
            return false;
        }
        String exact = name + "|" + argHash(args);
        for (int i = 0; i < conversation.allowedTools.length(); i++) {
            String entry = conversation.allowedTools.optString(i, "");
            if (name.equals(entry) || (name + "|*").equals(entry) || exact.equals(entry)) {
                return true;
            }
        }
        return false;
    }

    private void rememberAllowedTool(String name, String args) {
        allowToolAlways(name);
    }

    private static String argHash(String args) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((args == null ? "{}" : args)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                builder.append(String.format("%02x", bytes[i] & 0xff));
            }
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(String.valueOf(args).hashCode());
        }
    }

    String modelSource() {
        return conversation == null ? Conversation.SOURCE_CLOUD
                : conversation.modelSource;
    }

    boolean isLocalModel() {
        return Conversation.SOURCE_LOCAL.equals(modelSource());
    }

    boolean requiresApiKey() {
        return !isLocalModel();
    }

    void setModelSource(String source) {
        if (conversation == null) {
            return;
        }
        conversation.modelSource = Conversation.SOURCE_LOCAL.equals(source)
                ? Conversation.SOURCE_LOCAL : Conversation.SOURCE_CLOUD;
        saveCurrent();
        notifyUpdate();
    }

    String preprocessMode() {
        return conversation == null ? Conversation.PREPROCESS_RULES
                : conversation.preprocessMode;
    }

    void setPreprocessMode(String mode) {
        if (conversation == null) {
            return;
        }
        if (!Conversation.PREPROCESS_OFF.equals(mode)
                && !Conversation.PREPROCESS_MODEL.equals(mode)) {
            mode = Conversation.PREPROCESS_RULES;
        }
        conversation.preprocessMode = mode;
        saveCurrent();
        notifyUpdate();
    }

    void addLocalNote(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        addEntry(Entry.SYSTEM, text.trim(), false);
    }

    JSONArray getPlan() {
        return conversation == null ? new JSONArray() : conversation.plan;
    }

    Conversation currentConversation() {
        return conversation;
    }

    String currentTitle() {
        return conversation == null ? Conversation.DEFAULT_TITLE : conversation.title;
    }

    String currentWorkspacePath() {
        return conversation == null ? "" : conversation.workspace;
    }

    List<Conversation.Summary> listConversations() {
        return conversations.list(baseWorkspace);
    }

    void saveCurrent() {
        Conversation current = conversation;
        if (current == null) {
            return;
        }
        List<JSONObject> messageSnapshot;
        synchronized (messages) {
            messageSnapshot = new ArrayList<>(messages);
        }
        JSONArray messageJson = new JSONArray();
        for (JSONObject message : messageSnapshot) {
            messageJson.put(message);
        }
        messageJson = JsonText.sanitizeMessages(messageJson);
        List<Entry> entrySnapshot;
        synchronized (entriesLock) {
            entrySnapshot = new ArrayList<>(entries);
        }
        JSONArray entryJson = new JSONArray();
        for (Entry entry : entrySnapshot) {
            entryJson.put(entryToJson(entry));
        }
        synchronized (persistLock) {
            current.messages = messageJson;
            current.entries = entryJson;
            current.updatedAt = System.currentTimeMillis();
            conversations.save(current);
        }
    }

    /** The interface line is dedicated to consolidating requests for a short period of time and to perpetuating them backstage. */
    void saveCurrentAsync() {
        synchronized (saveQueueLock) {
            if (saveQueued) {
                return;
            }
            saveQueued = true;
        }
        saveExecutor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (saveQueueLock) {
                    saveQueued = false;
                }
                try {
                    saveCurrent();
                } catch (RuntimeException e) {
                    Log.e("MobileAgent", "async save failed", e);
                }
            }
        });
    }

    void setAgentProfile(String profile) {
        if (conversation != null) {
            conversation.agentProfile = profile == null ? "" : profile.trim();
            saveCurrent();
            notifyUpdate();
        }
    }

    private void loadConversationMemory() {
        synchronized (messages) {
            messages.clear();
            if (conversation != null) {
                for (int i = 0; i < conversation.messages.length(); i++) {
                    JSONObject message = conversation.messages.optJSONObject(i);
                    if (message != null) {
                        messages.add(message);
                    }
                }
            }
        }
        synchronized (entriesLock) {
            entries.clear();
            if (conversation != null) {
                for (int i = 0; i < conversation.entries.length(); i++) {
                    JSONObject object = conversation.entries.optJSONObject(i);
                    Entry entry = entryFromJson(object);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            }
        }
        messagesChanged();
    }

    private void messagesChanged() {
        messagesVersion++;
        cachedContextInfo = null;
        cachedContextVersion = -1;
    }

    private void repairMessagesInMemory() {
        synchronized (messages) {
            JSONArray current = new JSONArray();
            for (JSONObject message : messages) {
                current.put(message);
            }
            JSONArray repaired = JsonText.sanitizeMessages(current);
            messages.clear();
            for (int i = 0; i < repaired.length(); i++) {
                JSONObject message = repaired.optJSONObject(i);
                if (message != null) {
                    messages.add(message);
                }
            }
        }
        messagesChanged();
    }

    private static JSONObject entryToJson(Entry entry) {
        try {
            JSONObject object = new JSONObject();
            object.put("type", entry.type);
            object.put("text", entry.text);
            object.put("time", entry.time);
            if (entry.images != null && !entry.images.isEmpty()) {
                JSONArray images = new JSONArray();
                for (String path : entry.images) {
                    if (path != null && !path.isEmpty()) {
                        images.put(path);
                    }
                }
                if (images.length() > 0) {
                    object.put("images", images);
                }
            }
            if (entry.files != null && !entry.files.isEmpty()) {
                JSONArray files = new JSONArray();
                for (String path : entry.files) {
                    if (path != null && !path.isEmpty()) {
                        files.put(path);
                    }
                }
                if (files.length() > 0) {
                    object.put("files", files);
                }
            }
            return object;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static Entry entryFromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        int type = object.optInt("type", Entry.SYSTEM);
        String text = JsonText.clean(JsonText.string(object, "text"));
        if (text.isEmpty()) {
            return null;
        }
        List<String> images = new ArrayList<>();
        JSONArray imageArray = object.optJSONArray("images");
        if (imageArray != null) {
            for (int i = 0; i < imageArray.length(); i++) {
                String path = JsonText.clean(imageArray.optString(i, ""));
                if (!path.isEmpty()) {
                    images.add(path);
                }
            }
        }
        List<String> files = new ArrayList<>();
        JSONArray fileArray = object.optJSONArray("files");
        if (fileArray != null) {
            for (int i = 0; i < fileArray.length(); i++) {
                String path = JsonText.clean(fileArray.optString(i, ""));
                if (!path.isEmpty()) {
                    files.add(path);
                }
            }
        }
        return new Entry(type, text, object.optLong("time", System.currentTimeMillis()),
                images, files);
    }

    void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    Listener current = AgentEngine.this.listener;
                    if (current != null) {
                        current.onAgentUpdate();
                    }
                }
            });
        }
    }

    List<Entry> getEntries() {
        synchronized (entriesLock) {
            return new ArrayList<>(entries);
        }
    }

    boolean isRunning() {
        return running;
    }

    boolean isPaused() {
        return !running && conversation != null && conversation.paused;
    }

    /** Whether the current pause is rotated/Budget/Window cap trigger (ask, The user pauses and does not count. */
    boolean limitPaused() {
        return limitPaused;
    }

    String currentConversationId() {
        return conversation == null ? "" : conversation.id;
    }

    int currentStep() {
        return currentStep;
    }

    int maxSteps() {
        return maxSteps;
    }

    /** Debugging bridge: Current/budget for the most recent round,token, (c) Tool and closing state. */
    private String persistAgentError(Throwable error) {
        try {
            java.io.StringWriter writer = new java.io.StringWriter();
            java.io.PrintWriter printer = new java.io.PrintWriter(writer);
            error.printStackTrace(printer);
            printer.flush();
            String stack = writer.toString();
            File dir = new File(workspace.baseRoot(), ".agent/diagnostics");
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            File target = new File(dir, "last-agent-error.txt");
            File tmp = new File(dir, "last-agent-error.txt.tmp");
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            try {
                out.write((System.currentTimeMillis() + "\n" + stack).getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
            if (target.exists()) target.delete();
            tmp.renameTo(target);
            return stack;
        } catch (Throwable ignored) {
            return "";
        }
    }

    JSONObject debugRunStats() {
        JSONObject out = new JSONObject();
        try {
            out.put("step", currentStep);
            out.put("maxSteps", maxSteps);
            out.put("promptTokens", runPromptTokens);
            out.put("completionTokens", runCompletionTokens);
            out.put("toolCalls", runToolCalls);
            out.put("running", isRunning());
            out.put("paused", isPaused());
            out.put("thinking", runThinking == null ? "" : runThinking);
            out.put("outcome", runOutcome == null ? "" : runOutcome);
            out.put("contextBudget", contextBudget());
            out.put("loopStop", loopStopReason == null ? "" : loopStopReason);
            out.put("subagentCalls", subagentCalls);
            out.put("visualTask", visualTask);
            out.put("visualIterations", visualIterations);
            out.put("visualEditsSinceShot", visualEditsSinceShot);
            out.put("visualStopped", visualStopAnnounced);
            if (runState != null) {
                out.put("goal", runState.goal == null ? "" : runState.goal);
                out.put("lastError", runState.lastError == null ? "" : runState.lastError);
            }
            if (conversation != null) {
                out.put("model", conversation.model);
                out.put("temperature", conversation.temperature);
                out.put("maxTokens", conversation.maxTokens);
                out.put("contextTokens", conversation.contextTokens);
                out.put("maxToolRounds", conversation.maxToolRounds);
                out.put("conversationThinking", conversation.thinking);
                out.put("capThinking", conversation.capThinking);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    boolean newConversation() {
        synchronized (runLock) {
            if (running) {
                return false;
            }
        }
        return newConversationNow();
    }

    private boolean newConversationNow() {
        saveCurrent();
        Conversation created = conversations.create(prefs, conversation);
        workspace = baseWorkspace.sub(created.workspace);
        applyConversation(created);
        return true;
    }

    boolean openConversation(String id) {
        synchronized (runLock) {
            if (running) {
                return conversation != null && conversation.id.equals(id);
            }
        }
        return openConversationNow(id);
    }

    Conversation loadConversationForView(String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        if (conversation != null && conversation.id.equals(id)) {
            return conversation;
        }
        return conversations.load(id);
    }

    private boolean openConversationNow(String id) {
        if (conversation != null && conversation.id.equals(id)) {
            return true;
        }
        saveCurrent();
        Conversation loaded = conversations.load(id);
        if (loaded == null) {
            return false;
        }
        workspace = baseWorkspace.sub(loaded.workspace);
        applyConversation(loaded);
        return true;
    }

    private void schedulePause(String note) {
        pauseRequested = true;
        llm.cancel();
        if (isLocalModel()) {
            LocalAi.get(appContext).cancelChat();
        }
        if (conversation != null && conversation.runActive) {
            conversation.runActive = false;
            saveCurrent();
        }
        addEntry(Entry.SYSTEM, note, false);
        notifyUpdate();
        armStopWatchdog();
    }

    /**
     * Pause/The following is a list of the most recent cases of the police and police officers who have been arrested by the police:
     * Force end of current wheel from the interface side pocket to avoid the buttons always showing'Pauseing/Stopping'. 
     */
    void forceFinishIfStuck() {
        boolean stuck;
        synchronized (runLock) {
            stuck = running && (pauseRequested || stopRequested);
        }
        if (!stuck) {
            return;
        }
        boolean wasPause = pauseRequested;
        synchronized (runLock) {
            runGeneration++;
        }
        if (wasPause && conversation != null) {
            conversation.paused = true;
            conversation.pausedAt = System.currentTimeMillis();
        }
        addEntry(Entry.SYSTEM, wasPause
                ? "The current round has been suspended (responding request is being closed and can continue)."
                : "The current round has been stopped (regarding request is being closed and can continue).", false);
        finishRun(true);
    }

    boolean deleteConversation(String id) {
        if (id == null || id.trim().isEmpty()) {
            return false;
        }
        synchronized (runLock) {
            if (running && conversation != null && conversation.id.equals(id)) {
                return false;
            }
        }
        saveCurrent();
        Conversation previous = conversation;
        conversations.delete(id);
        if (previous != null && previous.id.equals(id)) {
            List<Conversation.Summary> remaining = conversations.list(baseWorkspace);
            Conversation next = null;
            if (!remaining.isEmpty()) {
                next = conversations.load(remaining.get(0).id);
            }
            if (next == null) {
                next = conversations.create(prefs, previous);
            }
            workspace = baseWorkspace.sub(next.workspace);
            applyConversation(next);
        } else {
            notifyUpdate();
        }
        return true;
    }

    boolean renameConversation(String id, String title) {
        synchronized (runLock) {
            if (running && conversation != null && conversation.id.equals(id)) {
                return false;
            }
        }
        String clean = title == null ? "" : title.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) {
            return false;
        }
        if (clean.length() > 60) {
            clean = clean.substring(0, 60);
        }
        if (conversation != null && conversation.id.equals(id)) {
            conversation.title = clean;
            saveCurrent();
            notifyUpdate();
            return true;
        }
        Conversation target = conversations.load(id);
        if (target == null) {
            return false;
        }
        target.title = clean;
        target.updatedAt = System.currentTimeMillis();
        conversations.save(target);
        notifyUpdate();
        return true;
    }

    boolean setConversationArchived(String id, boolean archived) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        if (conversation != null && conversation.id.equals(id)) {
            conversation.archived = archived;
            saveCurrent();
            notifyUpdate();
            return true;
        }
        Conversation target = conversations.load(id);
        if (target == null) {
            return false;
        }
        target.archived = archived;
        conversations.save(target);
        notifyUpdate();
        return true;
    }

    boolean updateConversationSettings(String workspacePath, String baseUrl, String model,
                                       float temperature, int maxTokens, String thinking,
                                       int contextTokens, int maxToolRounds,
                                       String agentProfile) {
        return updateConversationSettings(workspacePath, baseUrl, model, temperature,
                maxTokens, thinking, contextTokens, maxToolRounds, agentProfile,
                conversation == null ? "" : conversation.modelService);
    }

    boolean updateConversationSettings(String workspacePath, String baseUrl, String model,
                                       float temperature, int maxTokens, String thinking,
                                       int contextTokens, int maxToolRounds,
                                       String agentProfile, String modelService) {
        synchronized (runLock) {
            if (running) {
                return false;
            }
        }
        if (conversation == null) {
            return false;
        }
        Workspace nextWorkspace = baseWorkspace.sub(workspacePath);
        if (!nextWorkspace.root().isDirectory()) {
            return false;
        }
        String nextBase = baseUrl == null ? "" : baseUrl.trim();
        String nextModel = model == null ? "" : model.trim();
        if (nextBase.isEmpty() || nextModel.isEmpty()) {
            return false;
        }
        conversation.workspace = nextWorkspace.subPath();
        conversation.baseUrl = nextBase;
        conversation.model = nextModel;
        conversation.temperature = temperature;
        conversation.maxTokens = Math.max(2048, Math.min(65536, maxTokens));
        conversation.thinking = AppPrefs.THINKING_ON.equals(thinking)
                ? AppPrefs.THINKING_ON
                : AppPrefs.THINKING_AUTO.equals(thinking)
                ? AppPrefs.THINKING_AUTO : AppPrefs.THINKING_OFF;
        conversation.contextTokens = Math.max(8000, Math.min(1000000, contextTokens));
        conversation.maxToolRounds = Math.max(8, Math.min(HARD_MAX_ROUNDS, maxToolRounds));
        conversation.agentProfile = agentProfile == null ? "" : agentProfile.trim();
        conversation.modelService = modelService == null ? "" : modelService.trim();
        workspace = nextWorkspace;
        if (!prefs.liteHarness() && prefs.smartContextEnabled()) {
            CodeIndex.prewarm(workspace);
        }
        saveCurrent();
        notifyUpdate();
        return true;
    }

    private void applyConversation(Conversation next) {
        conversation = next;
        taskBudget = null;
        prefs.setLastConversationId(next.id);
        loadConversationMemory();
        if (prefs.smartContextEnabled()) {
            CodeIndex.prewarm(workspace);
        }
        notifyUpdate();
    }

    boolean importConversation(Conversation imported) {
        synchronized (runLock) {
            if (running) {
                return false;
            }
        }
        if (imported == null || imported.id == null
                || !imported.id.matches("[A-Za-z0-9_-]{4,80}")) {
            return false;
        }
        if (conversations.load(imported.id) != null) {
            return false;
        }
        conversations.save(imported);
        workspace = baseWorkspace.sub(imported.workspace);
        applyConversation(imported);
        return true;
    }

    private String checkToolPolicy(String name, JSONObject args) {
        String mode = conversation == null ? Conversation.APPROVAL_AUTO
                : conversation.approvalMode;
        boolean dryRun = args != null && args.optBoolean("dry_run", false);
        String argsText = args == null ? "{}" : args.toString();
        if (name != null && name.startsWith("mcp_")) {
            // MCP It's all about you. server/schema ; Unknown tools are still denied by calling white lists and directory verification.
            boolean loaded = "mcp_discover".equals(name);
            for (McpManager.Tool tool : activeMcpTools) {
                if (tool.exposedName.equals(name)) { loaded = true; break; }
            }
            if (!loaded) return "Current MCP Tools not loaded:" + name;
            if (Conversation.APPROVAL_READONLY.equals(mode)) {
                return "Current session is read-only mode, no network MCP Call.";
            }
            if (Conversation.APPROVAL_ASK.equals(mode) && !isAlwaysAllowed(name, argsText)) {
                return "ASK";
            }
            return null;
        }
        String verdict = ToolPolicy.authorize(mode, name, dryRun,
                isAlwaysAllowed(name, argsText));
        if (verdict == null) {
            return null;
        }
        if (!"ASK".equals(verdict)) {
            return verdict;
        }
        ApprovalHandler handler = approvalHandler;
        if (handler == null) {
            // Activity There is no interface for vetting when destroyed or process is being pulled up.
            // Equipment-type tools follow the 'equipment control' master switch authorization to ensure that the mission continues after cutting to other applications;
            // The rest of the restricted operation still needs to be returned. App Confirm.
            if (prefs.allowDeviceControl()
                    && (ToolPolicy.classify(name, dryRun) & ToolPolicy.DEVICE) != 0) {
                return null;
            }
            return "Current session requires approval, but App Not at the receptionist, not sure. Please return. App Try again after that.";
        }
        int decision;
        try {
            decision = handler.requestApproval(name, argsText);
        } catch (Exception e) {
            return "Could not close temporary folder: %s" + e.getClass().getSimpleName();
        }
        if (decision == APPROVAL_ALWAYS) {
            rememberAllowedTool(name, argsText);
            return null;
        }
        return decision == APPROVAL_ONCE ? null : "User rejected tool " + name + " Implementation.";
    }


    private boolean requestBrowserConsent(String url) {
        ApprovalHandler handler = approvalHandler;
        if (handler == null) {
            return false;
        }
        try {
            JSONObject args = new JSONObject();
            args.put("url", url);
            args.put("reason", "Page needs to login state or JavaScript Render, read with login mobile browser");
            int decision = handler.requestApproval("web_browser_read", args.toString());
            return decision == APPROVAL_ONCE || decision == APPROVAL_ALWAYS;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String updatePlan(JSONArray steps) {
        if (steps == null) {
            return errorJson("Lack of planned steps");
        }
        JSONArray normalized = new JSONArray();
        int limit = Math.min(steps.length(), 20);
        int completed = 0;
        for (int i = 0; i < limit; i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) {
                continue;
            }
            String title = step.optString("title", "").trim();
            if (title.isEmpty()) {
                continue;
            }
            String status = step.optString("status", "pending");
            if (!"pending".equals(status) && !"in_progress".equals(status)
                    && !"completed".equals(status)) {
                status = "pending";
            }
            if ("completed".equals(status)) {
                completed++;
            }
            JSONObject item = new JSONObject();
            try {
                item.put("title", title.length() > 120 ? title.substring(0, 120) : title);
                item.put("status", status);
            } catch (Exception ignored) {
            }
            normalized.put(item);
        }
        if (normalized.length() == 0) {
            return errorJson("The plan is not effective.");
        }
        conversation.plan = normalized;
        if (runState != null) {
            runState.setPlan(normalized);
            runState.save();
        }
        saveCurrent();
        notifyUpdate();
        return okJson("The plan has been updated:" + completed + "/" + normalized.length() + " Completed");
    }

    /** Number of steps accepted: automatic extension of rotation only acknowledges this increase and no longer recognizes'Long output'.  */
    private int completedPlanSteps() {
        JSONArray plan = conversation == null ? null : conversation.plan;
        if (plan == null) {
            return 0;
        }
        int completed = 0;
        for (int i = 0; i < plan.length(); i++) {
            JSONObject step = plan.optJSONObject(i);
            if (step != null && "completed".equals(step.optString("status", ""))) {
                completed++;
            }
        }
        return completed;
    }

    /** Entry Directive: only affects the current round request and does not require the model to read anything again. */
    private void injectConvergeNudge() {
        try {
            JSONObject nudge = new JSONObject();
            nudge.put("role", "user");
            nudge.put("_synthetic", true);
            nudge.put("content", "[System closing] Do not read any more files or execute any orders."
                    + "Now output conclusions directly (permissible, but with justification and uncertainty)."
                    + "If there is a lack of critical information, call ask_user And ask a specific question."
                    + "Or a sentence that indicates the exact reason for the impossibility of completion.");
            synchronized (messages) {
                messages.add(nudge);
            }
            messagesChanged();
        } catch (Exception ignored) {
        }
    }

    boolean startTask(String text) {
        return startTask(text, null);
    }

    private static boolean mentionsImage(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return text.contains("Figure") || text.contains("Screenshot") || text.contains("Photos")
                || lower.contains("image") || lower.contains("picture")
                || lower.contains("screenshot") || lower.contains("photo");
    }

    /** Re-attach the newest stored image to the current user message. */
    private ImageAttachment latestStoredImage() {
        synchronized (messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                JSONObject message = messages.get(i);
                JSONArray parts = message == null ? null : message.optJSONArray("content");
                if (parts == null) continue;
                for (int p = parts.length() - 1; p >= 0; p--) {
                    JSONObject part = parts.optJSONObject(p);
                    if (part == null || !"image_url".equals(part.optString("type", ""))) continue;
                    JSONObject image = part.optJSONObject("image_url");
                    String url = image == null ? "" : JsonText.string(image, "url");
                    if (!url.startsWith("data:") || !url.contains(",")) continue;
                    try {
                        int comma = url.indexOf(',');
                        int semi = url.indexOf(';');
                        String mime = url.substring(5, semi > 5 ? semi : comma);
                        byte[] bytes = Base64.decode(url.substring(comma + 1), Base64.DEFAULT);
                        if (bytes.length > 0) return new ImageAttachment("", bytes, mime);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }

    private JSONObject buildUserMessage(String input, List<ImageAttachment> images,
                                        List<FileAttachment> files) throws Exception {
        JSONObject user = new JSONObject();
        user.put("role", "user");
        boolean hasFiles = files != null && !files.isEmpty();
        if (images == null || images.isEmpty()) {
            user.put("content", hasFiles ? input + "\n\n" + fileNote(files) : input);
            return user;
        }
        JSONArray content = new JSONArray();
        JSONObject textPart = new JSONObject();
        textPart.put("type", "text");
        textPart.put("text", input);
        content.put(textPart);
        if (hasFiles) {
            JSONObject filePart = new JSONObject();
            filePart.put("type", "text");
            filePart.put("text", fileNote(files));
            content.put(filePart);
        }
        long totalBytes = 0L;
        int used = 0;
        for (ImageAttachment image : images) {
            byte[] data = image.jpeg;
            if (data == null || data.length == 0) {
                continue;
            }
            if (totalBytes + data.length > MAX_IMAGE_TOTAL_BYTES) {
                continue;
            }
            totalBytes += data.length;
            used++;
            String mime = image.mime == null || image.mime.trim().isEmpty()
                    ? "image/jpeg" : image.mime.trim();
            JSONObject part = new JSONObject();
            part.put("type", "image_url");
            JSONObject url = new JSONObject();
            url.put("url", "data:" + mime + ";base64," + Base64.encodeToString(data, Base64.NO_WRAP));
            url.put("detail", "auto");
            part.put("image_url", url);
            content.put(part);
        }
        if (used == 0 && !hasFiles) {
            user.put("content", input);
            return user;
        }
        user.put("content", content);
        return user;
    }

    /** Tell the model where system-picked files live and how to treat binaries. */
    private static String fileNote(List<FileAttachment> files) {
        if (files == null || files.isEmpty()) {
            return "";
        }
        StringBuilder note = new StringBuilder();
        note.append("[Users upload files through system file selector and copy to workspace]\n");
        for (FileAttachment file : files) {
            note.append("- Original filename:").append(file.name).append('\n')
                    .append("  Workspace path:").append(file.path).append('\n')
                    .append("  Size:").append(humanBytes(file.size));
            if (!file.mime.isEmpty()) {
                note.append("; Type:").append(file.mime);
            }
            note.append('\n');
        }
        note.append("Text files are directly available read_file reading; binary/The big ones don't read by text."
                + "Description of the treatment or use shell_run the right order.");
        return note.toString();
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    boolean startTask(String text, List<ImageAttachment> images) {
        return startTask(text, images, null);
    }

    boolean startTask(String text, List<ImageAttachment> images, List<FileAttachment> files) {
        String input = text == null ? "" : text.trim();
        List<ImageAttachment> attachments = new ArrayList<>();
        if (images != null) {
            for (ImageAttachment image : images) {
                if (image != null && image.jpeg != null && image.jpeg.length > 0) {
                    attachments.add(image);
                }
            }
        }
        List<FileAttachment> fileAttachments = new ArrayList<>();
        if (files != null) {
            for (FileAttachment file : files) {
                if (file != null && file.path != null && !file.path.trim().isEmpty()) {
                    fileAttachments.add(file);
                }
            }
        }
        if (input.isEmpty()) {
            if (attachments.isEmpty() && fileAttachments.isEmpty()) {
                return false;
            }
            input = attachments.isEmpty() ? "Please check my upload file."
                    : "Please look at the picture I've attached and show what you see.";
        }
        synchronized (runLock) {
            if (running && workerThread != null && !workerThread.isAlive()) {
                Log.w("MobileAgent", "reclaiming dead worker before new run");
                running = false;
                stopRequested = false;
                pauseRequested = false;
            }
            if (running) {
                return false;
            }
            running = true;
            stopRequested = false;
            pauseRequested = false;
        }
        armRunWatchdog();
        if (conversation != null) {
            conversation.runActive = true;
            conversation.runStartedAt = System.currentTimeMillis();
            conversation.autoResumeCount = 0;
            saveCurrent();
        }
        runPromptTokens = 0L;
        runCompletionTokens = 0L;
        runCachedTokens = 0L;
        runToolCalls = 0;
        runInput = input;
        String visualGoal = isContinuationGoal(input) ? lastTaskGoal() : input;
        visualTask = looksLikeVisualTask(visualGoal);
        visualBaselineCaptured = false;
        visualIterations = 0;
        visualEditsSinceShot = 0;
        visualStopAnnounced = false;
        subagentCalls = 0;
        thinkingPruneNoted = false;
        thinkingFallbackTried = false;
        resetLoopGuard();
        runThinking = resolveThinking(input);
        Log.i("AgentUsage", "run thinking=" + runThinking);
        stopHooksFired = false;
        runOutcome = "";
        prepareHooks();

        final String prompt = input;
        List<ImageAttachment> sendImageList = new ArrayList<>(attachments);
        if (sendImageList.isEmpty() && mentionsImage(input)) {
            ImageAttachment recalled = latestStoredImage();
            if (recalled != null) sendImageList.add(recalled);
        }
        final boolean withImages = !isLocalModel() && !sendImageList.isEmpty();
        List<String> imagePaths = new ArrayList<>();
        for (ImageAttachment image : attachments) {
            imagePaths.add(image.path);
        }
        List<String> filePaths = new ArrayList<>();
        for (FileAttachment file : fileAttachments) {
            filePaths.add(file.path);
        }
        final List<ImageAttachment> sendImages = withImages ? sendImageList : null;

        repairMessagesInMemory();
        compactHistoryIfNeeded();
        addEntry(Entry.USER, input, true, imagePaths, filePaths);
        if (!withImages && !attachments.isEmpty()) {
            addEntry(Entry.SYSTEM, "Current use of local models, not supporting photo input, ignored "
                    + attachments.size() + " A picture.", false);
        }
        if (isLocalModel() && !fileAttachments.isEmpty()) {
            addEntry(Entry.SYSTEM, "Local models were unable to read the annexes and only a list of documents was sent.", false);
        }
        if (conversation != null) {
            conversation.paused = false;
            conversation.pausedAt = 0L;
            if (conversation.title == null || conversation.title.trim().isEmpty()
                    || Conversation.DEFAULT_TITLE.equals(conversation.title)) {
                conversation.title = Conversation.titleFrom(input);
            }
        }
        try {
            WorkStore.Ticket work = supervisor.begin(
                    conversation == null ? "" : conversation.id,
                    conversation == null ? "" : conversation.title,
                    input, "");
            workTicketId = work.id;
        } catch (Throwable error) {
            Log.w("AgentWork", "begin ticket failed", error);
            workTicketId = "";
        }
        if (isLocalModel()) {
            try {
                JSONObject user = new JSONObject();
                user.put("role", "user");
                user.put("content", fileAttachments.isEmpty()
                        ? input : input + "\n\n" + fileNote(fileAttachments));
                synchronized (messages) {
                    messages.add(user);
                }
                messagesChanged();
                saveCurrent();
            } catch (Exception e) {
                addEntry(Entry.ERROR, "Construct request failed", false);
                supervisor.fail(workTicketId, "Constructing request failed.");
                saveCurrent();
                finishRun(false);
                return false;
            }
            if (!headless) KeepAliveService.start(appContext);
            try {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        runLocalChat(prompt);
                    }
                });
            } catch (Throwable error) {
                addEntry(Entry.ERROR, "Synchronising '%s'"
                        + (error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage()), false);
                supervisor.fail(workTicketId, "Synchronising '%s'");
                finishRun(false);
                return false;
            }
            return true;
        }
        if (apiKeyForCurrent().isEmpty()) {
            addEntry(Entry.ERROR, "Current model service not configured API Key, Please fill in the Model Service first.", false);
            supervisor.fail(workTicketId, "Not configured API Key, Task not started.");
            saveCurrent();
            finishRun(false);
            return false;
        }

        try {
            prepareCloudRun();
        } catch (RuntimeException e) {
            Log.e("MobileAgent", "prepareCloudRun failed", e);
            addEntry(Entry.ERROR, "Start failed: tool range configuration is invalid (" + e.getMessage()
                    + "), Please try again after setting the tool range.", false);
            supervisor.fail(workTicketId, "Start failed: The tool range configuration is invalid.");
            saveCurrent();
            finishRun(false);
            return false;
        }
        try {
            JSONObject user = buildUserMessage(input, sendImages, fileAttachments);
            synchronized (messages) {
                messages.add(user);
            }
            messagesChanged();
            saveCurrent();
        } catch (Exception e) {
            addEntry(Entry.ERROR, "Construct request failed", false);
            supervisor.fail(workTicketId, "Constructing request failed.");
            saveCurrent();
            finishRun(false);
            return false;
        }

        if (!headless) KeepAliveService.start(appContext);
        final int gen = ++runGeneration;
        if (!submitRunLoop(gen)) {
            return false;
        }
        return true;
    }

    private void prepareCloudRun() {
        toolSearchCount = 0;
        toolLoadCount = 0;
        activeCapabilities = new Capabilities(baseWorkspace);
        Capabilities.AgentProfile profile = activeCapabilities.loadAgent(
                conversation == null ? "" : conversation.agentProfile);
        if (profile.hasToolRestriction()) {
            // It's possible that the old configuration is still in. mcp_* It's a combination of names, clean it up, avoid starting it./Sending straight to throw abnormal.
            Set<String> cleanedTools = new LinkedHashSet<>();
            List<String> rejectedTools = new ArrayList<>();
            if (profile.tools != null) {
                for (String name : profile.tools) {
                    if (name != null && name.matches("[A-Za-z][A-Za-z0-9_]*")) {
                        cleanedTools.add(name);
                    } else {
                        rejectedTools.add(String.valueOf(name));
                    }
                }
            }
            if (!rejectedTools.isEmpty()) {
                addEntry(Entry.SYSTEM, "Ignored Agent Invalid toolname in configuration:"
                        + joinPathList(rejectedTools) + "; can reset the tool range in settings.", false);
            }
            profileRestricted = true;
            profileScope = ToolScope.of(cleanedTools);
            activeToolScope = profileScope;
            activeToolNames = activeToolScope.schemaFilter();
        } else {
            // v2 tool gateway: a small permanent core plus task-scoped adapters.
            profileRestricted = false;
            profileScope = null;
            loadedAdapters.clear();
            autoLoadAdapters(runInput);
            refreshToolScope();
        }
        mcp.setAccessPolicy(new McpDirectory.AccessPolicy() {
            @Override
            public boolean authorize(McpDirectory.Binding binding) {
                if (!activeMcpTools.isEmpty()) {
                    // already loaded in this run; call permission still checked below
                }
                if (binding.stage == McpDirectory.Stage.DISCOVER
                        || binding.stage == McpDirectory.Stage.EXPOSE) {
                    return activeToolScope.allows("mcp_discover");
                }
                return activeToolScope.isAll()
                        || activeToolScope.allows("mcp_discover")
                        || activeToolScope.allows(binding.toolName);
            }
        });
        activeMcpTools = new ArrayList<>();
        cachedToolDefinitions = null;
        cachedToolDefinitionsKey = "";
        beginState();
        String system = buildSystemPrompt(profile);
        updateSystemMessage(system);
        Log.i("AgentUsage", "overhead system=" + estimateTokens(system)
                + " tools=" + estimateTokens(ToolDefs.tools(activeToolNames).toString())
                + " toolCount=" + ToolDefs.tools(activeToolNames).length()
                + " scope=" + activeToolScope.label());
    }

    private void injectRetrievedContext(String input) {
        if (prefs.liteHarness()) return;
        if (!prefs.smartContextEnabled() || input == null || input.trim().length() < 8) return;
        CodeIndex.prewarm(workspace);
        String pack = CodeIndex.get(workspace).contextIfReady(input, 3000);
        if (pack.isEmpty()) return;
        try {
            synchronized (messages) {
                for (int i = messages.size() - 1; i >= 0; i--) {
                    JSONObject user = messages.get(i);
                    if ("user".equals(MessageProtocol.role(user))
                            && !user.optBoolean("_synthetic", false)) {
                        if (!user.has("_retrieved_context")) user.put("_retrieved_context", pack);
                        break;
                    }
                }
            }
            messagesChanged();
            saveCurrent();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to save the context for the search", e);
        }
    }

    /** Lite: Fixed core tool, constructed only by additional task type/Equipment/Screenshot/Networking, not large-scale expansion. */
    private Set<String> selectLiteTools(String input) {
        Set<String> tools = new LinkedHashSet<>(Arrays.asList(
                "list_files", "read_file", "write_file", "edit_file",
                "grep", "shell_run", "update_plan", "ask_user"));
        String text = input == null ? "" : input.toLowerCase(Locale.ROOT);
        if (containsAny(text, "build", "apk", "install", "Build", "Install", "Pack up.", "Compile", "Signature")) {
            tools.add("build_apk");
            tools.add("install_apk");
        }
        boolean device = containsAny(text, "Cell phone.", "Interface", "Screen", "Apply",
                "Click", "Light it.", "Slide", "Open", "Start", "Screenshot", "Camera", "Album",
                "Library", "Settings", "Announcements", "Accessibility", "Wess.", "Chat", "Pay the treasure.", "- The treasure hunt.", "Dive",
                "Map", "Telephone", "Text message", "Other Organiser", "Clipboard", "Copy", "Paste",
                "Operating mobile phones", "gallery", "camera", "clipboard", "wechat", "chat", "message")
                || containsWord(text, "app") || containsWord(text, "tap")
                || containsWord(text, "click") || containsWord(text, "screen");
        if (device) {
            tools.add("open_app");
            tools.add("ui_dump");
            tools.add("ui_tap");
            tools.add("ui_set_text");
            tools.add("ui_scroll");
        }
        if (containsAny(text, "Screenshot", "Screenshot", "screenshot")) {
            tools.add("screenshot");
        }
        if (containsAny(text, "Move", "Rename", "Delete", "move", "rename", "delete")) {
            tools.add("move_file");
            tools.add("delete_file");
        }
        if (containsAny(text, "Self-inspection", "Diagnosis", "selfcheck", "self_check")) {
            tools.add("self_check");
        }
        if (text.length() >= 80 || containsAny(text, "Sonagent", "Sub-Agent",
                "spawn_subagent", "Parallel", "Multisteps", "Long mission", "Research", "Research",
                "Investigations", "research", "subagent")) {
            tools.add("spawn_subagent");
        }
        if (webToolAllowed()) {
            tools.add("web_fetch");
            tools.add("web_search");
            tools.add("browser_eval");
            boolean browserIntent = text.contains("http://") || text.contains("https://")
                    || text.contains("file://")
                    || containsAny(text, "Browser", "browser", "Page", "Screenshot", "Screenshot", "screenshot");
            if (browserIntent) {
                tools.add("browser_open");
                tools.add("browser_screenshot");
            }
            if (containsAny(text, "Picture", "Photos", "Image", "image", "photo", "Screenshot", "Wallpaper", "Figure")) {
                tools.add("image_search");
                tools.add("download_file");
                tools.add("view_image");
            }
        }
        if (webToolAllowed() || looksLikeDownloadTask(text)) {
            tools.add("download_file");
        }
        if (containsAny(text, "termux", "python", "node.js", "nodejs", " git ",
                "gcc", "clang", "pkg ", "apt ")) {
            tools.add("termux_run");
        }
        return tools;
    }

    private boolean webToolAllowed() {
        return prefs.allowWebTools()
                || looksLikeWebTask(runInput)
                || (workTicketId != null && supervisor.networkAllowed(workTicketId));
    }

    /** Explicit web intent is enough to authorize web tools for this run. */
    static boolean looksLikeWebTask(String input) {
        if (input == null) {
            return false;
        }
        String text = input.toLowerCase(Locale.ROOT);
        if (text.contains("http://") || text.contains("https://")) {
            return true;
        }
        return containsAny(text, "Web", "Website", "Link", "Page", "Website", "Browser", "Search",
                "Search it.", "Check it out.", "Capture", "Read Web Pages", "web page", "webpage",
                "url", "browser", "search", "fetch",
                "Picture", "Photos", "Image", "image", "photo", "Wallpaper", "Figure");
    }

    /** Explicit visual reconstruction intent; used for the hard iteration budget. */
    static boolean looksLikeVisualTask(String input) {
        if (input == null) {
            return false;
        }
        String text = input.toLowerCase(Locale.ROOT);
        return containsAny(text, "Revert", "Reset", "Reference Charts", "Reference Pictures", "Highlands", "Gohtsun.",
                "Revert", "Visually restored", "Take it.", "Simulate", "Press this.", "Like this one.",
                "three.js", "scene reconstruction", "reference fit", "reference-fit");
    }

    private static boolean looksLikeDownloadTask(String input) {
        if (input == null) return false;
        String text = input.toLowerCase(Locale.ROOT);
        return containsAny(text, "Download", "download", "gguf", "Model File", "aria2",
                "curl", "wget", ".apk", ".zip", ".pdf", "safetensors");
    }

    private Set<String> selectToolsForRun(String input) {
        Set<String> tools = new HashSet<>(Arrays.asList(
                "list_files", "read_file", "write_file", "edit_file", "move_file",
                "delete_file", "glob", "grep", "shell_run", "self_check",
                "update_plan", "remember", "ask_user", "repo_search", "apply_patch", "mcp_discover"));
        String text = input == null ? "" : input.toLowerCase(Locale.ROOT);
        boolean complex = text.length() >= 60 || hasRecentErrors()
                || containsAny(text, "build", "apk", "install", "debug", "fix",
                "error", "test", "verify", "review", "refactor", "analyze", "plan",
                "design", "subagent", "checkpoint", "skill", "knowledge",
                "Build", "Install", "Debug", "Rehabilitation", "Wrong.", "Test", "Authentication", "Review",
                "Reconstruct", "Analysis", "Check.", "Planned", "Design", "Sonagent", "Checkpoints",
                "Skills", "Knowledge", "Rewind", "Multisteps", "Structure", "Optimization", "Investigations");
        if (complex) {
            tools.addAll(Arrays.asList("build_apk", "install_apk", "spawn_subagent",
                    "list_checkpoints", "restore_checkpoint", "list_skills",
                    "read_skill", "search_knowledge", "repo_index", "repo_map"));
        }
        boolean device = containsAny(text, "Cell phone.", "Interface", "Screen", "Apply",
                "Click", "Light it.", "Slide", "Open", "Start", "Screenshot", "Camera", "Album",
                "Library", "Settings", "Announcements", "Accessibility", "Wess.", "Chat", "Pay the treasure.", "- The treasure hunt.", "Dive",
                "Map", "Telephone", "Text message", "Other Organiser", "Clipboard", "Copy", "Paste",
                "Operating mobile phones", "gallery", "camera", "clipboard", "wechat", "chat", "message")
                || containsWord(text, "app") || containsWord(text, "tap")
                || containsWord(text, "click") || containsWord(text, "screen");
        if (device) {
            tools.addAll(Arrays.asList("list_apps", "open_app", "ui_tap",
                    "ui_set_text", "ui_scroll", "system_action", "ui_dump",
                    "screenshot", "clipboard_read", "clipboard_write"));
        }
        if (mcp.hasEnabledServers()) {
            tools.add("mcp_discover");
        }
        if (webToolAllowed()) {
            tools.add("web_fetch");
            tools.add("web_search");
        }
        if (webToolAllowed() || looksLikeDownloadTask(text)) {
            tools.add("download_file");
        }
        if (containsAny(text, "termux", "python", "node.js", "nodejs", " git ",
                "gcc", "clang", "pkg ", "apt ")) {
            tools.add("termux_run");
        }
        return tools;
    }

    private static boolean containsWord(String text, String word) {
        if (text == null || word == null || word.isEmpty()) {
            return false;
        }
        int index = text.indexOf(word);
        while (index >= 0) {
            int end = index + word.length();
            boolean before = index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1));
            boolean after = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (before && after) {
                return true;
            }
            index = text.indexOf(word, index + 1);
        }
        return false;
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

    private static String argText(String arguments, String key) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        try {
            return new JSONObject(arguments).optString(key, "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private File journalDir() {
        return new File(appContext.getFilesDir(), "journal");
    }

    private void journalEvent(String callId, String tool, String status, String detail) {
        if (prefs.liteHarness()) return;
        try {
            RunJournal.record(journalDir(), currentRunId, "CALL", callId, tool, status, detail);
        } catch (Throwable ignored) {
        }
    }

    private void recordChange(String tool, String arguments, String result) {
        if (prefs.liteHarness()) return;
        if (toolError(result) != null) {
            return;
        }
        if ("apply_patch".equals(tool) && argsBool(arguments, "dry_run")) {
            return;
        }
        String path = argText(arguments, "path");
        if ("move_file".equals(tool)) {
            path = argText(arguments, "to");
        }
        if (path.isEmpty()) {
            return;
        }
        try {
            File file = workspace.resolve(path);
            String relative = file.isFile() ? workspace.relativePath(file) : path;
            String hash = FileTxn.hash(file);
            runLedger.changed(relative, hash, "workspace",
                    isCodePath(relative) ? VerificationLedger.V2_BUILD
                            : VerificationLedger.V1_READ, false);
            journalEvent("", tool, "CHANGE", relative + " " + hash);
        } catch (Exception ignored) {
        }
    }

    private void recordEvidence(String tool, String arguments, String result) {
        if (prefs.liteHarness()) return;
        if (toolError(result) != null) {
            return;
        }
        if ("read_file".equals(tool)) {
            String path = argText(arguments, "path");
            if (path.isEmpty()) return;
            try {
                VerificationLedger.Snapshot snapshot = runLedger.snapshot(
                        java.util.Collections.singletonList(path));
                java.util.Map<String, String> current = new java.util.HashMap<>();
                current.put(path, snapshot.hashes.get(path));
                runLedger.acceptEvidence(new VerificationLedger.Evidence(snapshot,
                        "file-read", VerificationLedger.V1_READ, current,
                        java.util.Collections.singletonList(path),
                        java.util.Collections.<String>emptyList(), 0, "", "", 0, 0));
            } catch (Exception ignored) {
            }
            return;
        }
        if ("build_apk".equals(tool)) {
            java.util.List<String> paths = scopeFor(argText(arguments, "project"));
            if (paths.isEmpty()) return;
            try {
                VerificationLedger.Snapshot snapshot = runLedger.snapshot(paths);
                String artifact = paths.get(0);
                runLedger.acceptEvidence(new VerificationLedger.Evidence(snapshot,
                        "build-apk", VerificationLedger.V2_BUILD,
                        new java.util.HashMap<>(snapshot.hashes), paths,
                        java.util.Collections.<String>emptyList(), 0,
                        artifact, snapshot.hashes.get(artifact), 0, 0));
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isCodePath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".kts")
                || lower.endsWith(".c") || lower.endsWith(".cc") || lower.endsWith(".cpp")
                || lower.endsWith(".h") || lower.endsWith(".hpp") || lower.endsWith(".js")
                || lower.endsWith(".jsx") || lower.endsWith(".ts") || lower.endsWith(".tsx")
                || lower.endsWith(".py") || lower.endsWith(".rs") || lower.endsWith(".go")
                || lower.endsWith(".sh") || lower.endsWith(".xml") || lower.endsWith(".gradle");
    }

    private List<String> scopeFor(String project) {
        List<String> all = runLedger.paths();
        if (project == null || project.trim().isEmpty() || ".".equals(project.trim())) {
            return all;
        }
        String prefix = project.trim().replace('\\', '/');
        while (prefix.startsWith("./")) {
            prefix = prefix.substring(2);
        }
        List<String> subset = new ArrayList<>();
        for (String path : all) {
            if (path.startsWith(prefix + "/")) {
                subset.add(path);
            }
        }
        return subset;
    }

    private static boolean argsBool(String arguments, String key) {
        try {
            return new JSONObject(arguments).optBoolean(key, false);
        } catch (Exception e) {
            return false;
        }
    }

    private static String joinPathList(List<String> paths) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < paths.size() && i < 8; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(paths.get(i));
        }
        return builder.toString();
    }

    private static boolean isParallelReadTool(String name) {
        return "list_files".equals(name) || "read_file".equals(name) || "glob".equals(name)
                || "grep".equals(name) || "repo_search".equals(name) || "repo_map".equals(name)
                || "search_knowledge".equals(name) || "read_skill".equals(name)
                || "list_skills".equals(name) || "list_checkpoints".equals(name);
    }

    private static boolean isEditTool(String name) {
        return "edit".equals(name) || "write_file".equals(name)
                || "edit_file".equals(name) || "apply_patch".equals(name)
                || "move_file".equals(name) || "delete_file".equals(name);
    }


    void requestPause() {
        synchronized (runLock) {
            if (!running) {
                return;
            }
            if (isLocalModel()) {
                requestStop();
                return;
            }
            supervisor.end(workTicketId, WorkStore.WAITING, "User pauses and can continue.");
            schedulePause("Pauseing...");
        }
    }

    void requestStop() {
        boolean wasRunning;
        boolean wasPaused;
        synchronized (runLock) {
            wasRunning = running;
            wasPaused = !running && conversation != null && conversation.paused;
            stopRequested = true;
            pauseRequested = false;
            limitPaused = false;
        }
        if (wasRunning) {
            supervisor.end(workTicketId, WorkStore.STOPPED, "Other Organiser");
        }
        llm.cancel();
        // A shell child blocks in waitFor(), which interrupt() cannot break; kill it
        // so Stop actually ends the round instead of leaking a zombie worker.
        Process shell = activeShellProcess;
        if (shell != null) {
            shell.destroyForcibly();
        }
        if (isLocalModel()) {
            LocalAi.get(appContext).cancelChat();
        }
        if (conversation != null && conversation.runActive) {
            conversation.runActive = false;
            saveCurrent();
        }
        if (wasRunning) {
            addEntry(Entry.SYSTEM, "Stopping...", false);
            armStopWatchdog();
        } else if (wasPaused && conversation != null) {
            conversation.paused = false;
            conversation.pausedAt = 0L;
            saveCurrent();
            addEntry(Entry.SYSTEM, "The mission has been discontinued.", false);
            notifyUpdate();
        }
    }

    boolean resumeTask() {
        return resumeTaskInternal(false);
    }

    private boolean resumeTaskInternal(boolean auto) {
        synchronized (runLock) {
            if (running && workerThread != null && !workerThread.isAlive()) {
                Log.w("MobileAgent", "reclaiming dead worker before resume");
                running = false;
                stopRequested = false;
                pauseRequested = false;
            }
            if (running || conversation == null || !conversation.paused) {
                return false;
            }
            if (isLocalModel()) {
                conversation.paused = false;
                conversation.pausedAt = 0L;
                saveCurrent();
                addEntry(Entry.SYSTEM, "Local models do not support stop-and-go responses, please retransmit.", false);
                notifyUpdate();
                return false;
            }
            if (apiKeyForCurrent().isEmpty()) {
                addEntry(Entry.ERROR, "Current model service not configured API Key, Can't go on.", false);
                notifyUpdate();
                return false;
            }
            if (messages.isEmpty()) {
                conversation.paused = false;
                conversation.pausedAt = 0L;
                saveCurrent();
                return false;
            }
            running = true;
            stopRequested = false;
            pauseRequested = false;
            conversation.paused = false;
            conversation.pausedAt = 0L;
            conversation.runActive = true;
            conversation.runStartedAt = System.currentTimeMillis();
            if (!auto) {
                conversation.autoResumeCount = 0;
            }
        }
        armRunWatchdog();
        repairMessagesInMemory();
        readMarks.clear();
        compactHistoryIfNeeded();
        runInput = lastTaskGoal();
        visualTask = looksLikeVisualTask(runInput);
        if (!auto) {
            visualBaselineCaptured = false;
            visualIterations = 0;
            visualEditsSinceShot = 0;
            visualStopAnnounced = false;
        }
        subagentCalls = 0;
        thinkingPruneNoted = false;
        thinkingFallbackTried = false;
        resetLoopGuard();
        runPromptTokens = 0L;
        runCompletionTokens = 0L;
        runCachedTokens = 0L;
        runToolCalls = 0;
        runThinking = resolveThinking(runInput);
        stopHooksFired = false;
        runOutcome = "";
        prepareHooks();
        try {
            prepareCloudRun();
        } catch (RuntimeException e) {
            Log.e("MobileAgent", "prepareCloudRun failed on resume", e);
            addEntry(Entry.ERROR, "Restore failed: tool range configuration is invalid (" + e.getMessage()
                    + "), Please try again after setting the tool range.", false);
            saveCurrent();
            finishRun(false);
            return false;
        }
        addEntry(Entry.SYSTEM, auto ? "The last interrupted task is automatically resumed."
                : "Continue with the last suspended mandate.", false);
        saveCurrent();
        if (!headless) KeepAliveService.start(appContext);
        final int gen = ++runGeneration;
        if (!submitRunLoop(gen)) {
            return false;
        }
        return true;
    }

    boolean injectMessage(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return false;
        }
        boolean resume;
        synchronized (runLock) {
            boolean active = running || (conversation != null && conversation.paused);
            if (!active) {
                return false;
            }
            resume = !running;
            addEntry(Entry.USER, value, true);
            try {
                JSONObject user = new JSONObject();
                user.put("role", "user");
                user.put("content", value);
                synchronized (messages) {
                    messages.add(user);
                }
                messagesChanged();
            } catch (Exception ignored) {
            }
            injectedSeq++;
            if (conversation != null
                    && (conversation.title == null || conversation.title.trim().isEmpty()
                    || Conversation.DEFAULT_TITLE.equals(conversation.title))) {
                conversation.title = Conversation.titleFrom(value);
            }
            saveCurrent();
        }
        notifyUpdate();
        if (resume) {
            return resumeTask();
        }
        return true;
    }


    private void updateSystemMessage(String content) {
        try {
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", content);
            synchronized (messages) {
                if (!messages.isEmpty()
                        && "system".equals(JsonText.string(messages.get(0), "role"))) {
                    messages.set(0, system);
                } else {
                    messages.add(0, system);
                }
            }
            messagesChanged();
        } catch (Exception ignored) {
        }
    }

    private String resolveThinking(String input) {
        String mode = conversation == null ? prefs.thinkingMode()
                : conversation.thinking;
        if (AppPrefs.THINKING_ON.equals(mode)) {
            return deepSeekSafeThinking(AppPrefs.THINKING_ON);
        }
        if (AppPrefs.THINKING_OFF.equals(mode) || mode == null) {
            return AppPrefs.THINKING_OFF;
        }
        // Lite The mode is closed by default; only the user's visible selection of 'deep thinking' is open.
        if (prefs.liteHarness() && !prefs.allowOverLimit()) {
            return AppPrefs.THINKING_OFF;
        }
        if (hasRecentErrors()) {
            return deepSeekSafeThinking(AppPrefs.THINKING_ON);
        }
        String text = input == null ? "" : input.toLowerCase(Locale.ROOT).trim();
        if (text.length() >= 90) {
            return deepSeekSafeThinking(AppPrefs.THINKING_ON);
        }
        String[] complexWords = {"build", "apk", "install", "debug", "error", "fix",
                "refactor", "analyze", "plan", "design", "review", "verify", "root cause",
                "subagent", "migrate", "architecture",
                "Build", "Install", "Debug", "Rehabilitation", "Wrong.", "Analysis", "Check.", "Design",
                "Reconstruct", "Planned", "Authentication", "Rewind", "Sonagent", "Multisteps", "Structure", "Optimization", "Achieved"};
        for (String word : complexWords) {
            if (text.contains(word)) {
                return deepSeekSafeThinking(AppPrefs.THINKING_ON);
            }
        }
        // Last run was suspended/The bump cap ends: this round is done with a clear mind.
        boolean stalled = conversation != null && (conversation.paused
                || "paused".equals(lastRunOutcome));
        return deepSeekSafeThinking(stalled ? AppPrefs.THINKING_ON : AppPrefs.THINKING_OFF);
    }

    /**
     * DeepSeek thinking Mode requires tool rotation reasoning_content (a) Retransmitting the original;
     * All we need in history is one tool wheel missing./If you're cut off, you can't press it again. thinking Request.
     */
    private String deepSeekSafeThinking(String desired) {
        if (!AppPrefs.THINKING_ON.equals(desired) || !isDeepSeekEndpoint()) {
            return desired;
        }
        int incomplete = 0;
        synchronized (messages) {
            for (JSONObject message : messages) {
                if (message == null || !"assistant".equals(MessageProtocol.role(message))) {
                    continue;
                }
                JSONArray calls = message.optJSONArray("tool_calls");
                if (calls == null || calls.length() == 0) {
                    continue;
                }
                String reasoning = message.optString("reasoning_content", "").trim();
                if (reasoning.isEmpty()) {
                    reasoning = message.optString("reasoning", "").trim();
                }
                if (reasoning.isEmpty() || reasoning.contains("Early thinking has folded.")) {
                    incomplete++;
                }
            }
        }
        if (incomplete > 0 && !thinkingPruneNoted) {
            thinkingPruneNoted = true;
            addEntry(Entry.SYSTEM, "Detected " + incomplete
                    + " The old tool rotations lacked a record of reflection; the current round requested that the old rounds be omitted to retain in-depth reflections.", false);
        }
        return desired;
    }

    private static boolean isThinkingProtocolError(String error) {
        if (error == null) {
            return false;
        }
        String lower = error.toLowerCase(Locale.ROOT);
        return lower.contains("reasoning") || lower.contains("thinking")
                || lower.contains("encrypted");
    }

    private boolean isDeepSeekEndpoint() {
        String url = conversation != null && conversation.baseUrl != null
                ? conversation.baseUrl : prefs.baseUrl();
        String model = conversation == null ? "" : conversation.model;
        return url != null && (url.contains("deepseek.com")
                || model.toLowerCase(Locale.ROOT).contains("deepseek"));
    }

    private String apiKeyForCurrent() {
        if (conversation == null) {
            return prefs.apiKeyFor("", prefs.baseUrl());
        }
        return prefs.apiKeyFor(conversation.modelService, conversation.baseUrl);
    }

    private boolean hasRecentErrors() {
        synchronized (entriesLock) {
            int from = Math.max(0, entries.size() - 8);
            for (int i = from; i < entries.size(); i++) {
                if (entries.get(i).type == Entry.ERROR) {
                    return true;
                }
            }
        }
        return false;
    }

    private String lastUserText() {
        synchronized (messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                JSONObject message = messages.get(i);
                if (!"user".equals(JsonText.string(message, "role"))
                        || message.optBoolean("_synthetic", false)) {
                    continue;
                }
                Object content = message.opt("content");
                if (content instanceof String) {
                    return (String) content;
                }
                return JsonText.contentText(content);
            }
        }
        return "";
    }

    /** The blogger says that the government is not going to be able to get the message, but rather to get the message out of the 'go on' 'go on' and 'question answer' and find the real job. */
    private String lastTaskGoal() {
        synchronized (messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                JSONObject message = messages.get(i);
                if (!"user".equals(JsonText.string(message, "role"))
                        || message.optBoolean("_synthetic", false)) {
                    continue;
                }
                Object content = message.opt("content");
                String text = content instanceof String
                        ? (String) content : JsonText.contentText(content);
                if (!isContinuationGoal(text)) {
                    return oneLine(text, 400);
                }
            }
        }
        List<Entry> history = getEntries();
        for (int i = history.size() - 1; i >= 0; i--) {
            Entry entry = history.get(i);
            if (entry.type == Entry.USER && entry.text != null
                    && !isContinuationGoal(entry.text)) {
                return oneLine(entry.text, 400);
            }
        }
        if (conversation != null && conversation.plan != null
                && conversation.plan.length() > 0) {
            StringBuilder plan = new StringBuilder();
            for (int i = 0; i < conversation.plan.length() && i < 6; i++) {
                JSONObject step = conversation.plan.optJSONObject(i);
                if (step == null) {
                    continue;
                }
                if (plan.length() > 0) {
                    plan.append("; ");
                }
                plan.append(step.optString("title", ""));
            }
            if (plan.length() > 0) {
                return oneLine(plan.toString(), 400);
            }
        }
        return conversation == null ? "" : oneLine(conversation.title, 400);
    }

    private static boolean isContinuationGoal(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return true;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return text.equals("Go on.") || text.equals("Go on.") || lower.equals("continue")
                || text.startsWith("Continue")
                || text.startsWith("(Auto-run)") || text.startsWith("(Autorun)")
                || text.startsWith("[System") || text.startsWith("[System")
                || text.startsWith("Answer to the question:");
    }

    private synchronized void resetLoopGuard() {
        loopTrail.clear();
        browserFailures = 0;
        browserOpensSinceProgress = 0;
        browserFailClass = "";
        loopStopReason = "";
    }

    /** The only thing that really changes the state is the action.'Progress'; run, inspect, Open page only does not count. */
    private synchronized void observeLoopGuard(String name, String strategy, boolean ok, String error) {
        boolean progress = ok && (isEditTool(name) || "update_plan".equals(name)
                || "build_apk".equals(name) || "install_apk".equals(name)
                || "browser_screenshot".equals(name) || "view_image".equals(name));
        if (progress) {
            loopTrail.clear();
            browserFailures = 0;
            browserOpensSinceProgress = 0;
            browserFailClass = "";
            return;
        }
        if ("browser_open".equals(name)) {
            browserOpensSinceProgress++;
            if (ok) {
                browserFailures = 0;
                browserFailClass = "";
            } else {
                browserFailures++;
                String cls = browserErrorClass(error);
                if (!cls.isEmpty()) {
                    browserFailClass = cls;
                }
                if (browserFailures >= 3) {
                    loopStopReason = "Inline browser failed continuously " + browserFailures + " Number of times"
                            + (browserFailClass.isEmpty() ? "" : "(" + browserFailClass + ")")
                            + ", Suspended to avoid empty rotations. Local static page is sent directly html Path (will be converted) file://); "
                            + "Local service is only available http://127.0.0.1:Port, and first run Confirm port is listening."
                            + "The idea is to move on and move on.";
                    return;
                }
            }
            if (browserOpensSinceProgress >= 8) {
                loopStopReason = "The current round has been opened continuously with the inner browser " + browserOpensSinceProgress
                        + " No screenshot, editing or construction; no blanks have been suspended."
                        + "Please specify the page you want to see. Call. browser_screenshot Or in another way,"
                        + "More 'go ahead.'";
                return;
            }
        }
        String key = strategy == null || strategy.trim().isEmpty() ? name : strategy;
        if (key == null || key.trim().isEmpty()) {
            return;
        }
        loopTrail.add(key);
        while (loopTrail.size() > 12) {
            loopTrail.remove(0);
        }
        try {
            String cycle = detectStrategyCycle();
            if (cycle != null) {
                loopStopReason = cycle;
            }
        } catch (Throwable e) {
            // Cycle protection is a supporting logic and no detection anomaly can interrupt the main mission.
            Log.w("AgentLoop", "cycle detection failed", e);
        }
    }

    private synchronized String detectStrategyCycle() {
        int size = loopTrail.size();
        for (int period = 2; period <= 3; period++) {
            int need = period * 3;
            if (size < need) {
                continue;
            }
            boolean same = true;
            // Compare from the second cycle, avoid i - period Fall in. 0 Before (IndexOutOfBounds). 
            for (int i = size - need + period; i < size; i++) {
                if (!loopTrail.get(i).equals(loopTrail.get(i - period))) {
                    same = false;
                    break;
                }
            }
            if (same) {
                // Purely identical strategy repetition (e.g., continuity) run)(b) To be assigned to the original duplicate call protection;
                // This is a cross-tool cycle, which must contain at least two strategies, otherwise it can be a mistake to keep running a regular screen.
                String first = loopTrail.get(size - period);
                boolean mixed = false;
                for (int i = size - period + 1; i < size; i++) {
                    if (!loopTrail.get(i).equals(first)) {
                        mixed = true;
                        break;
                    }
                }
                if (!mixed) {
                    continue;
                }
                StringBuilder seq = new StringBuilder();
                for (int i = size - period; i < size; i++) {
                    if (seq.length() > 0) {
                        seq.append(" → ");
                    }
                    seq.append(loopTrail.get(i));
                }
                return "Tool cycle detected (in %1)" + seq + " Repeated 3 No editing produced/Build/The blog is a good one."
                        + "The time has been suspended to avoid a turnover. file:// Path, more 'go on.'";
            }
        }
        return null;
    }

    private static String browserErrorClass(String error) {
        if (error == null) {
            return "";
        }
        String lower = error.toLowerCase(Locale.ROOT);
        if (lower.contains("err_name_not_resolved")) return "Domain Name Parsing Failed";
        if (lower.contains("err_connection_refused")) return "Port is not listening";
        if (lower.contains("err_file_not_found")) return "File does not exist";
        if (lower.contains("timeout") || lower.contains("Timeout")) return "Load timeout";
        return oneLine(error, 60);
    }

    private Conversation requestSettings() {
        Conversation source = conversation;
        if (source == null) {
            return null;
        }
        Conversation settings = new Conversation();
        settings.baseUrl = source.baseUrl;
        settings.model = source.model;
        settings.temperature = source.temperature;
        settings.maxTokens = prefs.liteHarness()
                ? Math.max(2048, Math.min(LITE_MAX_OUTPUT, source.maxTokens))
                : source.maxTokens;
        // Never reserve more than a third of the window for output; otherwise a
        // single long answer can make the whole conversation uncompilable.
        settings.maxTokens = Math.min(settings.maxTokens,
                Math.max(1024, contextBudget() / 3));
        settings.thinking = runThinking == null ? source.thinking : runThinking;
        settings.modelService = source.modelService;
        ModelProfile profile = prefs.modelProfile(source.baseUrl, source.model);
        settings.capTools = profile.tools;
        settings.capVision = profile.vision;
        settings.capThinking = profile.thinking;
        settings.capEffort = profile.effort;
        ModelProtocols.Resolved resolved = ModelProtocols.resolve(prefs,
                source.baseUrl, source.model);
        settings.protocol = resolved.protocol;
        settings.protocolAuto = resolved.automatic;
        if (!profile.thinking) {
            settings.thinking = AppPrefs.THINKING_OFF;
        }
        return settings;
    }

    private static boolean isRetryIntent(String input) {
        if (input == null) {
            return false;
        }
        String text = input.toLowerCase(Locale.ROOT);
        return containsAny(text, "retry", "Try again.", "Try again", "Try again", "I can't.",
                "Keep trying.", "Read it again.", "Catch him again.", "try again", "still not", "not working");
    }

    private String appVersionLabel() {
        try {
            android.content.pm.PackageInfo info = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void appendRuntimeCapabilities(StringBuilder builder) {
        if (runState != null && strategyLedger != null && capabilities != null) {
            builder.append(runState.renderBrief(strategyLedger, capabilities,
                    runState.recalled));
            return;
        }
        boolean web = webToolAllowed();
        builder.append("\n\n[Time-of-life capabilities (whichever is the case in this article))\n")
                .append("- App: ").append(appVersionLabel()).append('\n')
                .append("- Network Search/Capture: ").append(web ? "Available" : "Close").append('\n')
                .append("- web_fetch It's a static grab.WebView And log-in mobile browser.\n")
                .append("- In the historical discourse'Could not read/Site Limit/Tools not available'The conclusions may be outdated;"
                        + "Each mission must be re-called for validation and must not be refused a re-test based solely on historical conclusions.\n");
        if (isRetryIntent(runInput)) {
            builder.append("- The current round of users explicitly requested that the test be repeated: the reference to old failures is prohibited; a different strategy is used;"
                    + "Web Job Direct Call web_fetch. \n");
        }
    }

    private void beginState() {
        try {
            capabilities = CapabilityRegistry.snapshot(appContext, prefs);
            String conversationId = conversation == null ? "default" : conversation.id;
            runState = AgentState.load(workspace.root(), conversationId, capabilities);
            boolean changed = !runState.capabilityFingerprint.isEmpty()
                    && !runState.capabilityFingerprint.equals(capabilities.fingerprint);
            boolean retry = isRetryIntent(runInput);
            String goal = retry ? correctionGoal() : runInput;
            if (!retry && isContinuationGoal(goal)) {
                goal = lastTaskGoal();
            }
            runState.begin(goal, capabilities, retry);
            runState.capabilitiesChanged = changed;
            runState.setPlan(conversation == null ? new JSONArray() : conversation.plan);
            experienceStore = new ExperienceStore(workspace.root());
            strategyLedger = new StrategyLedger(retry ? runState.ledger : null,
                    experienceStore.priors());
            if (changed) {
                strategyLedger.resetFailures();
            }
            stateMonitor = new StateMonitor(changed, looksLikeWebTask(goal));
            runState.recalled = experienceStore.recall(goal, "",
                    capabilities.fingerprint, 2);
            lastStateDecision = null;
            runState.save();
            Log.i("AgentState", "begin caps=" + capabilities.render
                    + " changed=" + changed + " retry=" + retry);
        } catch (Throwable error) {
            Log.w("AgentState", "begin state failed", error);
            runState = null;
            strategyLedger = null;
            experienceStore = null;
            capabilities = null;
            stateMonitor = null;
        }
    }

    private void recordWebStrategy(String strategy, String url, boolean ok,
                                   String error, boolean infoGain) {
        if (strategyLedger == null) {
            return;
        }
        String action = strategy + ":" + ToolRegistry.sha256(
                url == null ? "" : url.trim()).substring(0, 16);
        strategyLedger.record(strategy, strategy, action, ok, error, infoGain);
        if (runState != null) {
            if (ok) {
                runState.noteEvidence("ok", strategy);
            } else {
                runState.noteError(strategy + ": " + (error == null ? "" : error));
            }
        }
    }

    private void noteToolState(String name, String arguments, String result) {
        if (strategyLedger == null || runState == null || stateMonitor == null) {
            return;
        }
        String strategy = StrategyLedger.strategyOf(name, arguments);
        String action = StrategyLedger.actionKey(name, arguments, result);
        String error = toolError(result);
        boolean ok = error == null;
        boolean probe = "termux_probe".equals(strategy);
        // Repetition is not progress. Counting any non-error output longer than 30
        // characters as a gain is what let 23 near-identical shell commands each buy
        // "new information" and stretch a task out to the hard round cap.
        boolean infoGain = ok && result != null && result.length() > 30 && !probe
                && !strategyLedger.alreadySucceeded(action);
        strategyLedger.record(strategy, name, action, ok, error, infoGain);
        observeLoopGuard(name, strategy, ok, error);
        if (ok) {
            runState.noteEvidence("ok", name);
        } else {
            runState.noteError(error);
            maybeAskOnFailure(name, error);
        }
        StateMonitor.Decision decision = stateMonitor.observe(runState,
                strategyLedger, capabilities, action, strategy, ok, infoGain);
        if (decision != null && decision.actionable()) {
            lastStateDecision = decision;
        }
    }

    private void noteVisualTool(String name, String result, String error) {
        if (!visualTask || error != null) {
            return;
        }
        if (isEditTool(name)) {
            visualEditsSinceShot++;
            if (visualBaselineCaptured && !visualStopAnnounced
                    && visualEditsSinceShot == VISUAL_MAX_EDITS_PER_ITERATION + 1
                    && visualIterations < VISUAL_MAX_ITERATIONS) {
                addEntry(Entry.SYSTEM, "Changed consecutively " + visualEditsSinceShot
                        + " The comparison is still pending.", false);
                injectSynthetic("[System correction] Visual tasks have been modified continuously " + visualEditsSinceShot
                        + " The number of times is still uncharted. Call first. browser_screenshot(or browser_open)The blog also shows how the government is doing this:"
                        + "Only a better version is retained; do not continue blind ratio.");
            }
            return;
        }
        if (!isVisualCaptureTool(name, result)) {
            return;
        }
        if (!visualBaselineCaptured) {
            visualBaselineCaptured = true;
            visualIterations = 0;
            visualEditsSinceShot = 0;
            addEntry(Entry.SYSTEM, "Visual baseline established (0/"
                    + VISUAL_MAX_ITERATIONS + "; After most 6 round revision).", false);
            injectSynthetic("[Visual baseline] The first rendering is available. Save the current version as the best version:"
                    + "index.best.html and screenshots/best.png; And then you change it every round. 1-2 The most important difference between the two is the fact that the two are not the same."
                    + "The retention or withdrawal is decided after the comparison of the screenshot.");
            return;
        }
        if (visualEditsSinceShot <= 0) {
            return;
        }
        visualIterations++;
        visualEditsSinceShot = 0;
        if (visualIterations >= VISUAL_MAX_ITERATIONS && !visualStopAnnounced) {
            visualStopAnnounced = true;
            addEntry(Entry.SYSTEM, "Visual expression has reached " + VISUAL_MAX_ITERATIONS
                    + " Round, stop modifying and delivering the best version.", false);
            injectSynthetic("[System discontinuation conditions] Visual restoration completed " + VISUAL_MAX_ITERATIONS
                    + " Round Revision+Cut. Stop changing. Confirm. index.best.html / screenshots/best.png It's the best version."
                    + "Direct final conclusions and best screenshot; no more edit/write/apply_patch. ");
        }
    }

    private static boolean isVisualCaptureTool(String name, String result) {
        if ("browser_screenshot".equals(name) || "screenshot".equals(name)) {
            return true;
        }
        return "browser_open".equals(name) && result != null
                && result.contains("\"screenshot\"");
    }

    /** When tools fail, the site (tools, parameters, results, recent context) is placed at the mission centre. */
    private void reportToolFailure(String name, JSONObject args, String result, String error) {
        if (conversation == null || name == null || error == null) {
            return;
        }
        String argsText = args == null ? "{}" : args.toString();
        String key = name + "|" + StrategyLedger.actionKey(name, argsText)
                + "|" + shorten(error, 300);
        synchronized (reportedErrors) {
            if (!reportedErrors.add(key)) {
                return;
            }
        }
        String label = ToolPresentation.label(name, args);
        String summary = "Tools " + name + " Failed:" + shorten(error, 220);
        String title = "Wrong:" + label + " · " + shorten(error, 60);
        String report = buildErrorReport(name, argsText, result, error);
        try {
            supervisor.store.reportError(conversation.id, title, summary, report);
            Log.i("AgentWork", "reported tool error: " + name);
        } catch (Throwable failure) {
            Log.w("AgentWork", "report tool error failed", failure);
        }
    }

    /** Entry.ERROR(Model/Network/The same is true for the task centre; tools failed by the call point. */
    private void reportAgentError(String text) {
        if (conversation == null || text == null || text.trim().isEmpty()) {
            return;
        }
        if (text.startsWith("Tools ") && text.contains("Failed")) {
            return;
        }
        String key = "agent|" + currentStep + "|" + shorten(text, 300);
        synchronized (reportedErrors) {
            if (!reportedErrors.add(key)) {
                return;
            }
        }
        String summary = shorten(text, 220);
        String title = "Wrong:" + shorten(text, 60);
        String report = buildErrorReport("-", "{}", text, text);
        try {
            supervisor.store.reportError(conversation.id, title, summary, report);
        } catch (Throwable failure) {
            Log.w("AgentWork", "report agent error failed", failure);
        }
    }

    private void maybeAskOnFailure(String name, String error) {
        if ("ask".equals(name)) {
            return;
        }
        FailurePolicy.Decision decision = failurePolicy.record(name, error);
        if (decision == null) {
            return;
        }
        if (!decision.needsUser) {
            addEntry(Entry.SYSTEM, "Tools failed, automatic change of strategy continues:" + decision.summary, false);
            injectSynthetic("[System correction] Tools " + name + " Failed:" + shorten(error, 220)
                    + "\n" + decision.summary + ". It's an internal fixable problem. Don't ask the user."
                    + "I don't want to try again." + selfRepairHint(name, error) + " Continue to meet the target.");
            return;
        }
        try {
            JSONArray options = new JSONArray();
            options.put("Continue in another way (recommended)");
            options.put("I'll give you a little idea.");
            options.put("Stop Task");
            JSONObject question = new JSONObject();
            question.put("question", decision.question);
            question.put("options", options);
            question.put("multi", false);
            JSONArray questions = new JSONArray();
            questions.put(question);
            JSONObject args = new JSONObject();
            args.put("questions", questions);
            addEntry(Entry.SYSTEM, "The following is suspended and is requested for guidance:" + decision.summary, false);
            askQuestions(args);
        } catch (Throwable failure) {
            Log.w("AgentFailure", "auto ask failed", failure);
        }
    }

    private static String selfRepairHint(String name, String error) {
        String text = error == null ? "" : error.toLowerCase(Locale.ROOT);
        if (text.contains("More than") && (text.contains("kb") || text.contains("mb"))) {
            return "Please narrow down the modifications and change them to local ones. edit/apply_patch or sub-paragraphs.";
        }
        if (text.contains("permission denied") || text.contains("Missing permissions")
                || text.contains("Unauthorized")) {
            return "Replace a tool or path that does not require this authority and do not repeat the same order.";
        }
        if (text.contains("I didn't find it.") || text.contains("not found")
                || text.contains("Cannot initialise Evolution's mail component.") || text.contains("no such file")) {
            return "First. inspect Confirm the path and symbols, and try again.";
        }
        if ("browser_eval".equals(name)) {
            return "Change browser_screenshot Look at the picture, or narrow it down. JS Query range.";
        }
        if ("browser_open".equals(name)) {
            return "Local HTML Pass directly; auto-stop maps when they are opened successfully, and do not repeat the same page.";
        }
        if ("run".equals(name) || "shell_run".equals(name)) {
            return "Or change it. inspect/edit Finish the same thing.";
        }
        if ("edit".equals(name) || "apply_patch".equals(name)) {
            return "First. inspect Read the exact original text, down old_text Scope, or amendment of subparagraphs.";
        }
        return "Adjust parameters, change tools or narrow ranges.";
    }

    private String buildErrorReport(String name, String argsText, String result, String error) {
        StringBuilder report = new StringBuilder();
        report.append("[Auto-recording tool to correct bugs\n");
        report.append("Time:").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()).format(new java.util.Date())).append('\n');
        report.append("Source session (for location only, not for the current mission):")
                .append(conversation == null ? "" : conversation.title)
                .append(" (").append(conversation == null ? "" : conversation.id).append(")\n");
        String goal = lastTaskGoal();
        report.append("The task the source session is doing:").append(shorten(goal, 400)).append('\n');
        report.append("This mission: to repair the failed tool for calling; not to redo the business task of the source session.\n");
        if (conversation != null && conversation.plan != null
                && conversation.plan.length() > 0) {
            report.append("Source Session Current Scheme:\n");
            for (int i = 0; i < conversation.plan.length() && i < 6; i++) {
                JSONObject step = conversation.plan.optJSONObject(i);
                if (step == null) {
                    continue;
                }
                report.append("- [").append(step.optString("status", ""))
                        .append("] ").append(step.optString("title", "")).append('\n');
            }
        }
        report.append("Workspace:").append(workspace == null ? "" : workspace.root().getAbsolutePath())
                .append('\n');
        report.append("Models:").append(conversation == null ? "" : conversation.model)
                .append(" · Round:").append(currentStep).append('\n');
        report.append("Tools:").append(name).append('\n');
        report.append("Parameters:").append(redact(shorten(argsText, 1600))).append('\n');
        report.append("Error:").append(redact(shorten(error, 1600))).append('\n');
        report.append("Original result:").append(redact(shorten(result, 2400))).append('\n');
        report.append("Recent context:\n");
        List<Entry> recent = getEntries();
        int from = Math.max(0, recent.size() - 8);
        for (int i = from; i < recent.size(); i++) {
            Entry entry = recent.get(i);
            if (entry == null || entry.text == null) {
                continue;
            }
            report.append("- ").append(entryTypeLabel(entry.type)).append(": ")
                    .append(redact(shorten(entry.text, 320))).append('\n');
        }
        report.append("\n[♪ Fix the border ♪\n")
                .append("- The only way to recreate, locate and repair failed tools is to call; not to reprogramme them by their origin session title."
                        + "The old business assignments in the source sessions are also not to be continued.\n")
                .append("- Minimum certification after restoration; external services/Environmental errors are described and alternatives are given.\n")
                .append("- Once the repairs have been completed, the conclusions are taken back to the source session and the original mission continues.\n\n")
                .append("Let's see if this is a configuration, permission, tool life cycle or code problem; it's re-emergence and repair."
                        + "The report is a change in what is being done and how it is verified.\n");
        return report.toString();
    }

    private void backfillRecentErrors() {
        if (conversation == null || supervisor == null) {
            return;
        }
        List<Entry> snapshot = getEntries();
        if (snapshot.isEmpty()) {
            return;
        }
        int start = Math.max(0, snapshot.size() - 200);
        int created = 0;
        Set<String> seen = new HashSet<>();
        for (int i = snapshot.size() - 1; i >= start && created < 20; i--) {
            Entry entry = snapshot.get(i);
            if (entry.type != Entry.ERROR || entry.text == null
                    || entry.text.trim().isEmpty()) {
                continue;
            }
            String summary = shorten(entry.text, 220);
            if (!seen.add(summary)) {
                continue;
            }
            if (supervisor.store.hasErrorReport(conversation.id, summary)) {
                continue;
            }
            String name = "-";
            String argsText = "{}";
            if (entry.text.startsWith("Tools ") && entry.text.contains(" Failed:")) {
                name = entry.text.substring(3, entry.text.indexOf(" Failed:")).trim();
                for (int j = i - 1; j >= Math.max(0, i - 8); j--) {
                    Entry prev = snapshot.get(j);
                    if (prev.type == Entry.TOOL
                            && prev.text.startsWith("Call " + name + " ")) {
                        int space = prev.text.indexOf(' ');
                        argsText = prev.text.substring(space + 1).trim();
                        break;
                    }
                }
            }
            JSONObject argsObj = null;
            try {
                argsObj = new JSONObject(argsText);
            } catch (Exception ignored) {
            }
            String label = "-".equals(name) ? "" : ToolPresentation.label(name, argsObj);
            String title = "Wrong:" + (label.isEmpty()
                    ? shorten(entry.text, 60) : label + " · " + shorten(entry.text, 60));
            String report = buildErrorReport(name, argsText, entry.text, entry.text);
            try {
                supervisor.store.reportError(conversation.id, title, summary, report);
                created++;
            } catch (Throwable failure) {
                Log.w("AgentWork", "backfill error failed", failure);
            }
        }
        if (created > 0) {
            Log.i("AgentWork", "backfilled error reports=" + created);
        }
    }

    void backfillRecentErrorsNow() {
        if (!headless) {
            backfillRecentErrors();
        }
    }

    private static String entryTypeLabel(int type) {
        if (type == Entry.USER) return "User";
        if (type == Entry.ASSISTANT) return "Assistant";
        if (type == Entry.TOOL) return "Tools";
        if (type == Entry.ERROR) return "Error";
        if (type == Entry.SYSTEM) return "System";
        if (type == Entry.REASONING) return "Think.";
        if (type == Entry.QUESTIONS) return "Question";
        if (type == Entry.IMAGE) return "Screenshot";
        return "Events";
    }

    private static String redact(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replaceAll(
                "(?i)(ghp_|github_pat_|sk-|xox[baprs]-)[A-Za-z0-9_\\-]{8,}",
                "$1[REDACTED]");
        value = value.replaceAll(
                "(?i)(\"(?:api[_-]?key|token|password|secret|authorization)\"\\s*:\\s*\")[^\"]*(\")",
                "$1[REDACTED]$2");
        return value;
    }

    private void injectStateCorrection(StateMonitor.Decision decision) {
        StringBuilder text = new StringBuilder();
        text.append("[Status correction] ").append(decision.reason).append("\n");
        text.append(runState.renderBrief(strategyLedger, capabilities, runState.recalled));
        text.append("\n- Ban Action:").append(decision.actionKey);
        text.append("\n- Possible next steps:");
        if (decision.suggested == null || decision.suggested.isEmpty()) {
            text.append("Change tool or direct error");
        } else {
            text.append(joinPathList(decision.suggested));
        }
        if (experienceStore != null) {
            text.append("\n- Similar experience:").append(ExperienceStore.render(
                    experienceStore.recall(runState.goal, runState.lastError,
                            capabilities == null ? "" : capabilities.fingerprint, 2)));
        }
        text.append("\nChange policy immediately; web missions call directly web_fetch(url), Don't use it. termux The website is a tool for the search for documents."
                + "Nor should it be based solely on historical failures.");
        if (decision.reason != null && decision.reason.contains("browser_open")) {
            text.append("\nInternal browser is available:browser_open Successfully, the page is open and automatically attachments to the model;"
                    + "Next step directly view the screenshot or call browser_screenshot/browser_eval, "
                    + "Do not open it again, and don't use it. am Or the system browser's under.");
        }
        addEntry(Entry.SYSTEM, "Status monitoring: strategy is being applied to correct (%)" + decision.reason + ")", false);
        try {
            JSONObject nudge = new JSONObject();
            nudge.put("role", "user");
            nudge.put("_synthetic", true);
            nudge.put("content", text.toString());
            synchronized (messages) {
                messages.add(nudge);
            }
            messagesChanged();
            saveCurrent();
        } catch (Exception ignored) {
        }
    }

    private void finishState(String outcome, int steps) {
        if (runState == null || strategyLedger == null) {
            return;
        }
        try {
            runOutcome = outcome;
            runState.finish(outcome, steps);
            runState.setLedger(strategyLedger.toJson());
            runState.save();
            boolean emptyPause = "paused".equals(outcome) && steps == 0
                    && runState.lastError.trim().isEmpty();
            if (experienceStore != null && !emptyPause) {
                String lesson = "completed".equals(outcome)
                        ? "Success strategy: " + strategyLedger.successStrategies()
                        : "Failed/Pause: " + runState.lastError
                        + "; Other Organiser: " + strategyLedger.summary();
                String id = ToolRegistry.sha256(runState.goal + "|"
                        + strategyLedger.summary() + "|" + outcome + "|"
                        + runState.lastError + "|" + (System.currentTimeMillis() / 60000));
                experienceStore.record(new ExperienceStore.Record(id,
                        System.currentTimeMillis(), runState.goal,
                        capabilities == null ? "" : capabilities.fingerprint,
                        strategyLedger.summary(), outcome, runState.lastError,
                        lesson, steps, runPromptTokens + runCompletionTokens));
                Log.i("AgentExperience", "record " + outcome + " strategies="
                        + strategyLedger.summary());
            }
            Log.i("AgentState", "finish " + outcome + " rounds=" + steps);
        } catch (Throwable error) {
            Log.w("AgentState", "finish state failed", error);
        }
    }

    private String correctionGoal() {
        synchronized (messages) {
            String fallback = null;
            for (int i = messages.size() - 1; i >= 0; i--) {
                JSONObject item = messages.get(i);
                if (!"user".equals(JsonText.string(item, "role"))
                        || item.optBoolean("_synthetic", false)) {
                    continue;
                }
                String text = JsonText.content(item).trim();
                if (text.contains("http://") || text.contains("https://")) {
                    return oneLine(text, 300);
                }
                if (fallback == null && looksLikeWebTask(text)) {
                    fallback = text;
                }
            }
            if (fallback != null && !fallback.trim().isEmpty()) {
                return oneLine(fallback, 300);
            }
        }
        return oneLine(runInput, 300);
    }

    private void injectRepeatCorrection(JSONArray toolCalls, String reason) {
        String name = "";
        String arguments = "{}";
        if (toolCalls != null && toolCalls.length() > 0) {
            JSONObject call = toolCalls.optJSONObject(0);
            JSONObject function = call == null ? null : call.optJSONObject("function");
            if (function != null) {
                name = JsonText.string(function, "name");
                arguments = JsonText.string(function, "arguments");
                if (arguments.isEmpty()) {
                    arguments = "{}";
                }
            }
        }
        if (runState != null && strategyLedger != null) {
            String strategy = StrategyLedger.strategyOf(name, arguments);
            String action = StrategyLedger.actionKey(name, arguments);
            StateMonitor.Decision decision = new StateMonitor.Decision(
                    StateMonitor.Type.BLOCK, reason, action, strategy,
                    strategyLedger.alternatives(looksLikeWebTask(runState.goal),
                            capabilities));
            injectStateCorrection(decision);
        } else {
            injectSimpleNudge(reason);
        }
    }

    private void injectReadOnlyNudge() {
        addEntry(Entry.SYSTEM, "Continuous 6 Turn only. If you know the documents and symbols to be changed,"
                + "The next round must be straight. edit Use it together. update_plan Updates the steps;do not read the whole document again.", false);
        injectSynthetic("[System correction] You've been in a row. 6 Round only. If you know the file to change/Symbol,"
                + "Next round must be called directly. edit(Minor conversion replace, It's a big change. write/apply_patch)and update_plan; "
                + "Do not use it again. run/awk/grep/cat/head/tail Read the source code. If there is no key information, then we can see it."
                + "Direct ask_user Explain what is missing and do not continue to turn over documents.");
    }

    private void injectEditPlanNudge(String path) {
        addEntry(Entry.SYSTEM, "Edited " + path + "; Please update the status of the planned steps.", false);
        injectSynthetic("[System Hint] Successfully edited " + path + ". Please call immediately. update_plan, "
                + "Mark the corresponding step as completed; And then we'll just do the necessary construction./Authenticate, do not reread the whole file.");
    }

        private void injectSynthetic(String text) {
        synchronized (deferredSynthetics) {
            if (inFlightCalls > 0) {
                // It cannot be inserted between the call and its result: the whole round is sentenced to an illegal sequence.
                deferredSynthetics.add(text);
                return;
            }
        }
        try {
            JSONObject nudge = new JSONObject();
            nudge.put("role", "user");
            nudge.put("_synthetic", true);
            nudge.put("_synthetic_source", "System correction");
            nudge.put("content", text);
            synchronized (messages) {
                messages.add(nudge);
            }
            messagesChanged();
            saveCurrent();
        } catch (Exception ignored) {
        }
    }

    /** After the tool results are completed, the system hint in the queue is added in the original order. */
    private void flushDeferredSynthetics() {
        List<String> queued;
        synchronized (deferredSynthetics) {
            if (inFlightCalls > 0 || deferredSynthetics.isEmpty()) {
                return;
            }
            queued = new ArrayList<>(deferredSynthetics);
            deferredSynthetics.clear();
        }
        for (String text : queued) {
            injectSynthetic(text);
        }
    }

    private void injectSimpleNudge(String reason) {
        addEntry(Entry.SYSTEM, "Repeated calls detected, corrections injected:" + reason, false);
        try {
            JSONObject nudge = new JSONObject();
            nudge.put("role", "user");
            nudge.put("_synthetic", true);
            nudge.put("content", "[System correction] " + reason
                    + ". Prohibits repeating the same call; changing the policy; web missions directly web_fetch, "
                    + "Don't use it. termux The search for web files should not be based on historical failures alone.");
            synchronized (messages) {
                messages.add(nudge);
            }
            messagesChanged();
            saveCurrent();
        } catch (Exception ignored) {
        }
    }

    /** One-time telling the model of the facts and the way of work.'One at a time.'The wheel. */
    private static final String ENV_FACTS =
            "[♪ The truth of the execution (do not repeat the detection) ♪\n"
                    + "- run Run in /system/bin/sh + toybox, PATH Only /system/bin:/vendor/bin:/product/bin. \n"
                    + "- The base level is... toybox(ls/cat/grep/sed/awk/find/head/tail/cp/mv/xargs...)and curl. \n"
                    + "- There's nothing to write about. /tmp; Temporary file to $TMPDIR(The user has been able to use the Cache Directory or the directory in the workspace."
                    + "Don't try. mkdir /tmp. \n"
                    + "- toybox grep Yes. POSIX BRE: `\\|` No, it's not.'or', It doesn't match in silence. Write in multiple mode. "
                    + "`grep -E 'a|b' file`; Yes. Java Find symbols in the source code first. inspect mode=search. \n"
                    + "- Yes. git/Package management/When building the tool chain:git Class operations take out a dedicated hosting tool or describe the capability boundaries and ask_user; "
                    + "Build APK Use build_apk, Don't use it. run Repeatedly. command Existence.\n"
                    + "- I want to make sure that some orders have not been made once. `command -v a b c` Finish the question, don't go round.\n"
                    + "- The workspace is /sdcard/MobileAgent, The relative path is calculated from here;run It's... stdout More than 2000 The character will be cut off.\n"
                    + "- Read files for inspect mode=read: Back to header `[I'm sorry. a-b Okay. / Total N Line; Next offset=c]`. "
                    + "It must continue to be used when it is necessary. offset Read it till you see it.'End of file'; One page is not a full text.\n"
                    + "- Don't use the big papers. run cat/head/tail Take it. Change it. inspect mode=read (a) Page breaks;"
                    + "Check only symbols/Use the clip inspect mode=search, Avoids reading of the entire document.\n"
                    + "- Internal browser: static HTML Use a working area path or file://; Local service is only available http://127.0.0.1:Port,"
                    + "First. run Validation port is listening.browser_open Could not close temporary folder: %s 2 The way things are changing,"
                    + "Don't. run↔browser_open Turn the plane.\n"
                    + "- Open application open_app; Don't use it. am/cmd Starts the interface. Local HTML Use browser_open, "
                    + "When you open successfully, you automatically screen and attach to the model, and do not open the same page repeatedly.\n"
                    + "[Visual Retrofit\n"
                    + "- Find a reference map and save a path; do not change the ratio by impression without a reference map.\n"
                    + "- update_plan Write 5-8 Acquirements: frontal diagram, contours, layers, width, column height/The length of the columns, the selection of the lids, the material light, the background.\n"
                    + "- A screenshot of each change, which is line-by-line with the reference chart and previous edition; only a better version is retained and the variation is reversed immediately.\n"
                    + "- Save Best Version As index.best.html and screenshots/best.png; Continuous 2 The most important thing is to stop the process and deliver the best screenshot without lifting it."
                    + "Most visual overlaps 6 Wheel, don't blindly change.\n\n"
                    + "[Working methods\n"
                    + "- Before you do it, update_plan List 3-6 Step by step and write completion of judgement; no automatic extension of rotation without a step plan.\n"
                    + "- Call me once every step of the way. update_plan, Take that step. status Replace with completed; "
                    + "All steps completed It is a direct conclusion and does not continue to be tested to the point of exhaustion.\n"
                    + "- The same order, the same document, should not be repeated: repetition without new information would only consume the wheel and budget.\n"
                    + "- Tool failure grouping: missing tool/Environmental deficiencies (if not) NDK/CMake/Command does not exist) or is not authorized = I can't try again."
                    + "Replace with an alternative available;paths/Parameters/Timeout = Try a variable at best 2 (a) The number of times;"
                    + "The same mistake happened. 2 Change of strategy, do not hit walls repeatedly, and do not ask users about it.\n"
                    + "- Error reporting tool is internal: do not use ask_user Ask the user; change the parameters and change the tools to continue."
                    + "Questions are only asked if user authorization, login, password or irreversible selection is required.\n"
                    + "- Before the launch tool is called, write a sentence: 'The next step is based on the results and mission objectives of the mission."
                    + "What's next, why, and what's the verdict; this phrase will show between steps.'Thinking'. "
                    + "Do not repeat the previous result, which is the basis for decision-making. Example:"
                    + "'Next: Edit ConversationStore.list Add archived=false Filter, then build authentication.'\n"
                    + "- The results of the tool are used only to determine the next step; if the task is completed, it is directly concluded."
                    + "Do not repeat or repeat the execution for the purpose of summarizing.\n"
                    + "- Separate calls are made in parallel in the same cycle.\n"
                    + "- Source code is the most located. 6 Secondary Read/searching;mostly for each file 2 times;known symbols/It's a line. edit, Do not read the whole document repeatedly; no use run It's... awk/sed/cat/head/tail (b) Read source code;edit Immediately after update_plan. \n"
                    + "- Open search for multi-files delegate, The main dialogue will contain only conclusions and will not reproduce the text in large part in the replies.\n";

    /** Small context/The executive summary of the encoded tasks. */
    private static final String ENV_FACTS_COMPACT =
            "['Executive'\n"
                    + "- run Yes. /system/bin/sh + toybox; The workspace is /sdcard/MobileAgent; Temporary file writing $TMPDIR. \n"
                    + "- Source Search/Read Only inspect mode=search/read; Ban run It's... awk/sed/cat/head/tail Read source code.\n"
                    + "- Build APK Use build_apk, Install for install_apk; Yes. git/Package management/The missing command indicates the limits of capability and does not repeat detection.\n"
                    + "- Internal browser: Local HTML Use file://; Local service is only available http://127.0.0.1:Port;browser_open Failed 2 Change the way.\n"
                    + "- Open application open_app; Don't use it. am/cmd starting interface;local HTML Use browser_open(It's automatic.\n"
                    + "- Visual restoration: first find reference diagrams; only better keep comparative reference charts and previous editions for each change; best available storage index.best.html; "
                    + "Continuous 2 Rounds No lifting or 6 The round stops delivery and does not change the ratio blindly.\n"
                    + "- Change tools for error/Continue the parameters, do not use them ask_user asking users; permissions only/Login/Password/The question is not reversible.\n"
                    + "- No, I'm not. NDK/CMake/Ninja/clang, Don't make it up on this machine. C/C++. \n";

    /** Take the real state of the tool chain and avoid the model being able to guess by memory. git/java.  */
    private String rootfsFacts() {
        Rootfs rootfs = Rootfs.get(appContext);
        if (!rootfs.ready()) {
            return "- Self-contained tool chain is being decompressed for the first time, and for the time being, toybox(No, I'm not. git/java/bash); "
                    + "If necessary, explain and try again later.\n";
        }
        String mode = rootfs.mode();
        if (!"direct".equals(mode)) {
            return "- Self-packed tool chain unpacked but**Could not close temporary folder: %s**(Mode " + mode + ", Reason:"
                    + rootfs.lastReason() + "). This round is still pressed toybox Work, don't try again. git/java; "
                    + "See you at the diagnostics. /sdcard/MobileAgent/.agent/diagnostics/rootfs.json. \n";
        }
        return "- Self-carrying tool chain is ready (PREFIX=" + rootfs.usrDir().getAbsolutePath()
                + ", Implementation modalities direct): git, bash, java/javac, aapt2, d8, zip, make The first time I was in the hospital, I was wondering if I could use it."
                + "Build Walk build_apk, No, I don't. Termux. \n"
                + "- The tool chain isn't there. NDK, CMake, Ninja, clang: Do not compile on this machine llama.cpp Or any native. C/C++; "
                + "This type of mission is directly required PC + Android NDK Or... Termux The government has also been able to provide information on the situation."
                + "Don't use it. run Repeated detection./Try to compile.\n";
    }

    private String appVersionName() {
        try {
            return appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "Unknown";
        }
    }

    /** A simple, short, untooled dialogue uses a streamlined system alert to reduce fixed costs per round. */
    private boolean isSimpleTask(String input) {
        if (input == null) {
            return false;
        }
        String text = input.trim();
        if (text.length() == 0 || text.length() > 90) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return !containsAny(lower, "Build", "Install", "Code", "Source", "Documentation", "Web", "Picture",
                "Search", "Browser", "apk", "build", "fix", "debug", "test", "skill",
                "Research", "Research", "Planned", "Open", "Run", "Command", "Screenshot", "Download",
                "http://", "https://", "file://");
    }

    private String buildSystemPrompt(Capabilities.AgentProfile profile) {
        boolean lite = prefs.liteHarness();
        boolean compact = !lite && (contextBudget() <= 48000
                || (isSimpleTask(runInput) && messageCount() < 12
                && (conversation == null || conversation.plan == null
                || conversation.plan.length() == 0)));
        String activeModel = conversation == null ? prefs.model() : conversation.model;
        String activeBase = conversation == null || conversation.baseUrl == null
                ? prefs.baseUrl() : conversation.baseUrl;
        ModelService activeService = conversation == null ? null
                : prefs.service(conversation.modelService);
        if (activeService == null) {
            activeService = prefs.serviceForBase(activeBase);
        }
        String activeServiceName = activeService == null
                ? activeBase : activeService.displayName();
        StringBuilder builder = new StringBuilder("[Current Run-time]\n")
                .append("- You're a 'CRLM', Run on Android Local on the phone. Agent Apply,"
                        + "No, it's not. Claude Code, ChatGPT or another desktop client.\n")
                .append("- Current model:").append(activeModel).append("\n")
                .append("- Model services:").append(activeServiceName)
                .append("(").append(activeBase).append(")\n")
                .append("- Application version:").append(appVersionName()).append("\n")
                .append("- When the user asks you what model or version you use, the information is used directly in the context of the running of this paragraph;"
                        + "Don't speculate on the training data, and don't call yourself. Claude Code. \n\n")
                .append("[Tool gateway v2]\n")
                .append("- Practical tools available for current cycle tools schema Whichever: Resident inspect/edit/run/ask/delegate/update_plan/tool_search/tool_load; Task-related adapter is automatically preloaded.\n")
                .append("- The government has also been able to provide information on the situation.tool_search One more time, one more time. tool_load One; call directly after loading successfully, do not repeat search/Load.\n")
                .append("- Maximum per round tool_search ").append(MAX_TOOL_SEARCHES)
                .append(" Sub-paragraphtool_load ").append(MAX_TOOL_LOADS)
                .append(" times;up to a maximum of loads ").append(MAX_LOADED_ADAPTERS)
                .append(" An adaptor full of the longest unused load will automatically unload and will not be stuck; answer if sufficient information is available.\n")
                .append("- Old name read_file/web_fetch/shell_run/spawn_subagent Waiting for the absence of a presence. schema ; they are mostly incorporated inspect/edit/run/ask/delegate. \n")
                .append("- It works. inspect/edit/run Do not load dedicated tools when completed; web pages/Download/Equipment priority is given to loaded primary adapters, not used run/curl Hang on.\n\n")
                .append(compact ? ENV_FACTS_COMPACT : ENV_FACTS)
                .append(compact ? "" : rootfsFacts());
        if (runInput != null && runInput.trim().startsWith("[Error reporting tool for autorecording")) {
            builder.append("\n\n[♪ I'm sorry, I'm sorry, I'm sorry, I'm sorry.\n")
                    .append("- The only task is to reproduce and repair the failed instrument.\n")
                    .append("- The source session title and source plan are used only for positioning, not for this task; re-tapping of old business tasks is prohibited.\n")
                    .append("- The changes and the means of validation are reported as soon as they are repaired and minimally validated, and the need for extension is not continued.\n");
        }
        ModelProfile modelProfile = conversation == null ? null
                : prefs.modelProfile(conversation.baseUrl, conversation.model);
        if (modelProfile != null && !modelProfile.tools) {
            builder.append("- The current model does not enable the tool to be called: it can only be answered by text, and does not forge the tool execution results.\n");
        }
        if (modelProfile != null && !modelProfile.vision) {
            builder.append("- The current model does not enable visual input: the photo attachment is rejected and do not pretend to see the picture.\n");
        }
        builder.append(lite ? ToolDefs.liteSystemPrompt()
                : compact ? ToolDefs.compactSystemPrompt() : ToolDefs.systemPrompt());
        // Keep the reusable rules before volatile workspace/memory text so the
        // API-side prompt prefix can stay cacheable across runs.
        if (compact) {
            builder.append("\n\n[Mission Rules\n")
                    .append("- Web site:web_search Find links. → web_fetch reading body;no use run/curl Hang on.\n")
                    .append("- long task step; missing information ask; tool failed to change strategy; not repeating the same command.\n")
                    .append("- Picture tasks first image_search; The candidate chart will go forward. feed/Attaches to the model, and must see the map for matching.\n")
                    .append("- Every change first. smoke, Eventually, the full amount is done again; no more than twice for the same type of failure, rather than using a screenshot instead of a diagnosis.\n");
        } else {
            builder.append("\n\n['The mission center rule.'\n")
                    .append("- We'll find the data first. web_search Find the link. Use it again. web_fetch (b) Reading the body of the text;web_fetch Support http→https, gzip and common Chinese coding.\n")
                    .append("- Picture tasks first image_search Candidates; candidate charts automatically feed/Add to the model,"
                            + "You must see the chart to confirm the subject/Style/Clarity, change of candidate if not matched, not simply by title or summary.\n")
                    .append("- Verify rhythm: Minimum for each change smoke; Only complete construction once before all is completed/Testing;"
                            + "The same type of failure is two times at most, followed by a diagnosis or inquiry; no duplicate screenshot is used to replace the diagnosis.\n")
                    .append("- Internal browser page blackscreen or JS If you're not doing it, use it first. browser_eval Read it. "
                            + "window.THREE / window.__errors / canvas Size; do not continue blinding.\n")
                    .append("- Ban termux_run curl/wget pages captured;reporting when web page failed HTTP State and specific errors, do not assert site limits on the sole basis of historical failures.\n")
                    .append("- The failure must be a different strategy: not just by historical conclusions.'Site Limit/Could not read'; Web Job Direct web_fetch, Don't use it. termux It's... ls/find/cat Find web files.\n")
                    .append("- Long missions such as research, construction and so forth are broken down into searchable steps; independent investigations are given to the extent possible spawn_subagent, Avoid long waiting periods on a single round.\n")
                    .append("- Call when key information is missing or a user is required to confirm direction ask_user(question, options), Do not guess; the mission will be suspended pending an answer.\n")
                    .append("- Day shell Use shell_run(Internal /system/bin/sh + toybox: ls/cat/grep/sed/find/tar/cp/mv/rm/sha256sum/timeout Wait, no need. Termux. \n")
                    .append("- termux_run Advanced tool only for user-specific requirements/Package Management (Package Management)python/node/git/gcc/pkg); default not to use;build with build_apk. \n")
                    .append("- Download Model/APK/ZIP/PDF/Pictures, etc. for files download_file(url,path), Support stop-and-validation; off-limits termux It's... curl/wget/aria2. \n")
                    .append("- Termux For construction only/system command;prohibited pkg/apt install, yes |, Get the long command. | tail/head; Long Build First open_app Termux Keep the front desk, log the file.\n")
                    .append("- Model download failed (%1)429/403)Change mirrors or try again later. Do not install aria2/Python; The first step is to try again on the 'local model' page.\n")
                    .append("- The tool is immediately stated when time is exceeded or blocked, and the same command is not repeated; the mission centre records the document, heartbeat and pending matters.");
        }
        builder.append("\n\n[Current Workspace]\n").append(workspace.label());
        if (lite) {
            String rules = activeCapabilities.loadRules();
            if (rules != null && !rules.trim().isEmpty()) {
                builder.append("\n\n[User Rule\n").append(trimForLocal(rules, 600));
            }
            if (profile != null && profile.instructions != null
                    && !profile.instructions.trim().isEmpty()) {
                builder.append("\n\n[Current Agent 'Orders'\n")
                        .append(trimForLocal(profile.instructions.trim(), 600));
            }
            appendRuntimeCapabilities(builder);
            builder.append(workingSetSection());
            return builder.toString();
        }
        builder.append(activeCapabilities.rulesSection());
        builder.append(activeCapabilities.memorySection());
        builder.append(activeCapabilities.lessonsSection());
        builder.append(activeCapabilities.pendingEvolutionSection());
        if (profile != null && profile.instructions != null
                && !profile.instructions.trim().isEmpty()) {
            builder.append("\n\n[Current Agent 'Orders'\n")
                    .append(profile.instructions.trim());
        }
        builder.append(activeCapabilities.skillsSection());
        builder.append(activeCapabilities.knowledgeSection());
        appendRuntimeCapabilities(builder);
        builder.append(workingSetSection());
        return builder.toString();
    }

    private void runLocalChat(String input) {
        maxSteps = 1;
        currentStep = 1;
        supervisor.heartbeat(workTicketId, currentRunId, 1, 1);
        activeCapabilities = new Capabilities(baseWorkspace);
        String modelPath = prefs.localModelPath();
        if (!LocalTemplates.supports(modelPath)) {
            addEntry(Entry.ERROR, "Current local model template is not validated ("
                    + LocalTemplates.family(modelPath) + "). "
                    + "To avoid a mistake. chat Template, please change to Qwen Series of local models or cloud-end models.", false);
            supervisor.fail(workTicketId, "The current local model template is not validated.");
            saveCurrent();
            finishRun(false);
            return;
        }
        addLocalChatNotice();
        String system = buildLocalSystemPrompt();
        String prompt = buildLocalChatPrompt(system);
        final StringBuilder reply = new StringBuilder();
        final Entry[] streamEntry = {null};
        final long[] lastNotify = {0};
        LocalAi.ChatResult result = LocalAi.get(appContext).streamChat(
                prompt, Math.min(conversation == null ? 256 : conversation.maxTokens, 256),
                conversation == null ? 0.7f : conversation.temperature,
                new LocalLlm.TokenListener() {
                    @Override
                    public void onToken(String piece) {
                        if (stopRequested) {
                            LocalAi.get(appContext).cancelChat();
                            return;
                        }
                        reply.append(piece);
                        Entry entry = streamEntry[0];
                        if (entry == null) {
                            entry = addEntry(Entry.ASSISTANT, "", false);
                            streamEntry[0] = entry;
                        }
                        updateEntryText(entry, reply.toString());
                        long now = System.currentTimeMillis();
                        if (now - lastNotify[0] >= 100) {
                            lastNotify[0] = now;
                            notifyUpdate();
                        }
                    }
                });
        if (streamEntry[0] != null) {
            updateEntryText(streamEntry[0], reply.toString());
            notifyUpdate();
        }
        String text = reply.toString().trim();
        if (text.isEmpty()) {
            String error = result.error == null || result.error.isEmpty()
                    ? "Local model does not return content" : result.error;
            addEntry(Entry.ERROR, "Local models: " + error, false);
        } else {
            try {
                JSONObject assistant = new JSONObject();
                assistant.put("role", "assistant");
                assistant.put("content", text);
                synchronized (messages) {
                    messages.add(assistant);
                }
            } catch (Exception ignored) {
            }
            if (result.error != null && !result.error.isEmpty()) {
                addEntry(Entry.ERROR, "Local model tips:" + result.error, false);
            }
            if (stopRequested) {
                addEntry(Entry.SYSTEM, "The mission has been discontinued.", false);
            }
            saveCurrent();
        }
        boolean stopped = stopRequested;
        if (stopped) {
            supervisor.end(workTicketId, WorkStore.STOPPED, "Local model tasks stopped.");
        } else if (text.isEmpty()) {
            supervisor.fail(workTicketId, "Local model does not return content.");
        } else {
            supervisor.ready(workTicketId, "Local model mission completed.");
        }
        finishRun(true);
        if (!stopped) {
            scheduleReview(1, 0L, false);
        }
    }

    private void addLocalChatNotice() {
        if (conversation == null) {
            return;
        }
        boolean present = false;
        synchronized (entriesLock) {
            for (Entry entry : entries) {
                if (entry.type == Entry.SYSTEM && entry.text != null
                        && entry.text.startsWith("Local model dialogue:")) {
                    present = true;
                    break;
                }
            }
        }
        if (!present) {
            addEntry(Entry.SYSTEM,
                    "Local model dialogue: completely offline, without tool capability; switch to cloud-based models when a document or operating device is needed.",
                    false);
        }
    }

    private String buildLocalSystemPrompt() {
        StringBuilder builder = new StringBuilder();
        builder.append("You're the local one running on the user phone. AI Assistant, use local models, completely offline.\n")
                .append("You do not have the tools, the document reading and writing, the command execution, the equipment operation or the networking capability;")
                .append("If users need these, please indicate the need to switch to cloud-based models.\n")
                .append("Answers are simple in Chinese; they are clear when they are not clear.\n");
        String rules = activeCapabilities.loadRules();
        if (!rules.isEmpty()) {
            builder.append("[User Rule\n").append(trimForLocal(rules, 300)).append('\n');
        }
        String memory = activeCapabilities.loadMemory();
        if (!memory.isEmpty()) {
            builder.append("[Long-term memory\n").append(trimForLocal(memory, 300)).append('\n');
        }
        return builder.toString();
    }

    private String buildLocalChatPrompt(String system) {
        List<Entry> snapshot;
        synchronized (entriesLock) {
            snapshot = new ArrayList<>(entries);
        }
        java.util.LinkedList<String> turns = new java.util.LinkedList<>();
        int size = 0;
        for (int i = snapshot.size() - 1; i >= 0 && size < 650; i--) {
            Entry entry = snapshot.get(i);
            if (entry.type != Entry.USER && entry.type != Entry.ASSISTANT) {
                continue;
            }
            String text = entry.text == null ? "" : entry.text.trim();
            if (text.isEmpty()) {
                continue;
            }
            String line = "<|im_start|>" + (entry.type == Entry.USER ? "user" : "assistant")
                    + "\n" + trimForLocal(text, 500) + "\n<|im_end|>\n";
            if (size + line.length() > 650) {
                break;
            }
            turns.addFirst(line);
            size += line.length();
        }
        StringBuilder builder = new StringBuilder();
        builder.append("<|im_start|>system\n").append(system).append("<|im_end|>\n");
        for (String turn : turns) {
            builder.append(turn);
        }
        builder.append("<|im_start|>assistant\n");
        return builder.toString();
    }

    private static String trimForLocal(String text, int max) {
        if (text == null) {
            return "";
        }
        String clean = text.trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    private void runLoop(final int gen) {
        // Each time, 'continue' or new directive is a separate budget cycle: replace the cumulative book of accounts, and then replace it with a single one.
        // Otherwise, once the session-level budget is exhausted, every subsequent time they continue to be stopped before dispatch.
        taskBudget = null;
        lastExtensionCompleted = completedPlanSteps();
        converged = false;
        limitPaused = false;
        reportedErrors.clear();
        failurePolicy.reset();
        workerThread = Thread.currentThread();
        int steps = 0;
        boolean stopped = false;
        boolean paused = false;
        boolean failed = false;
        long runStarted = System.currentTimeMillis();
        int softLimit = prefs.allowOverLimit() ? roundCeiling()
                : prefs.liteHarness() ? LITE_ROUNDS
                : conversation == null ? HARD_MAX_ROUNDS
                : Math.max(8, Math.min(HARD_MAX_ROUNDS, conversation.maxToolRounds));
        int nextBudget = softLimit;
        maxSteps = softLimit;
        currentStep = 0;
        String lastSignature = "";
        int duplicateRounds = 0;
        int emptyRounds = 0;
        int verifyNudges = 0;
        int readOnlyRounds = 0;
        int corrections = 0;
        int repeatHardStops = 0;
        int duplicateRoundHardStops = 0;
        int sameToolCount = 0;
        String lastToolName = "";
        webCacheReads.clear();
        sourceReadCalls = 0;
        sourceReadCounts.clear();
        sourceReadNudged = false;
        readOnlyNudged = false;
        Map<String, Integer> callCounts = new HashMap<>();
        boolean blocked = false;
        currentRunId = "run-" + System.currentTimeMillis();
        supervisor.heartbeat(workTicketId, currentRunId, 0, softLimit);
        runLedger = new VerificationLedger(currentRunId,
                new VerificationLedger.HashProvider() {
                    @Override
                    public String currentHash(String path) {
                        return FileTxn.hash(new File(workspace.root(), path));
                    }
                },
                Arrays.asList(
                        new VerificationLedger.Verifier() {
                            @Override public String id() { return "file-read"; }
                            @Override public int maxLevel() { return VerificationLedger.V1_READ; }
                            @Override public boolean authentic(VerificationLedger.Evidence e) {
                                return e.level <= VerificationLedger.V1_READ;
                            }
                        },
                        new VerificationLedger.Verifier() {
                            @Override public String id() { return "build-apk"; }
                            @Override public int maxLevel() { return VerificationLedger.V2_BUILD; }
                            @Override public boolean authentic(VerificationLedger.Evidence e) {
                                return e.level <= VerificationLedger.V2_BUILD && e.exitCode == 0;
                            }
                        }));
        journalEvent("RUN_START", "", "RUNNING", "generation " + gen);
        activeMcpTools = new ArrayList<>();
        cachedToolDefinitions = null;
        cachedToolDefinitionsKey = "";
        if (!prefs.liteHarness() && mcp.hasEnabledServers()) {
            List<String> serverNames = mcp.enabledServerNames();
            addEntry(Entry.SYSTEM, "MCP Server:" + joinPathList(serverNames)
                    + ". Call if needed mcp_discover Loading tools on demand.", false);
            Log.i("AgentUsage", "mcpServers=" + serverNames.size());
        }
        try {
            injectRetrievedContext(runInput);
            int netRetries = 0;
            while (gen == runGeneration) {
                if (stopRequested) {
                    stopped = true;
                    break;
                }
                if (pauseRequested) {
                    paused = true;
                    break;
                }
                if (steps >= roundCeiling()) {
                    limitPaused = true;
                    addEntry(Entry.ERROR, "Acquired the ceiling (%)" + roundCeilingLabel()
                            + " The task is suspended. Please break down the task into a smaller part and 'continue'."
                            + (prefs.allowOverLimit() ? "" :
                            "The project is also designed to provide a 'fault-out' of the ceiling and to enable the immediate removal of the lock and continuation (cost-sharing)."), false);
                    paused = true;
                    break;
                }
                if (!prefs.allowOverLimit()
                        && System.currentTimeMillis() - runStarted >= MAX_RUN_MS) {
                    limitPaused = true;
                    addEntry(Entry.ERROR, "Tasks running continuously beyond "
                            + (MAX_RUN_MS / 60000L) + " Minutes, automatically suspended."
                            + "The time limit can be lifted by checking progress or by a 'fault-over limit'.", false);
                    supervisor.block(workTicketId, "Tasks running continuously beyond "
                            + (MAX_RUN_MS / 60000L) + " Minutes, automatically suspended.");
                    blocked = true;
                    paused = true;
                    break;
                }
                if (steps >= nextBudget) {
                    boolean planned = conversation != null && conversation.plan != null
                            && conversation.plan.length() > 0;
                    int doneSteps = completedPlanSteps();
                    boolean productive = planned && doneSteps > lastExtensionCompleted
                            && strategyLedger != null
                            && strategyLedger.recentGains(6) >= 2
                            && !blocked && !failed;
                    if (productive && nextBudget < HARD_MAX_ROUNDS) {
                        int extended = Math.min(roundCeiling(),
                                nextBudget + ROUND_EXTENSION);
                        lastExtensionCompleted = doneSteps;
                        addEntry(Entry.SYSTEM, "Accepted " + doneSteps + "/"
                                + conversation.plan.length() + " Step, automatically extend until " + extended
                                + " Wheel (ceiling) " + roundCeilingLabel() + "). ", false);
                        nextBudget = extended;
                        maxSteps = extended;
                        supervisor.heartbeat(workTicketId, currentRunId, steps, extended);
                    } else if (!converged) {
                        // Nothing was accepted, so buying more rounds only buys more
                        // reading. Spend the remainder on an answer instead.
                        converged = true;
                        nextBudget = Math.min(roundCeiling(), steps + CONVERGE_ROUNDS);
                        maxSteps = nextBudget;
                        addEntry(Entry.SYSTEM, "The current budget cycle was exhausted without any steps being taken to accept and accept it, and was replaced by a mandatory receipt:"
                                + "Here. " + CONVERGE_ROUNDS + " Round, conclusions must be based on what has been read directly.",
                                false);
                        injectConvergeNudge();
                    } else {
                        limitPaused = true;
                        if (!planned) {
                            addEntry(Entry.SYSTEM, "The current round of steps is exhausted and the task has not yet been scheduled for acceptance."
                                    + "Next call first update_plan List 3-6 The blogger says that the court is not a court of law."
                                    + "More 'go on.'--No automatic extension would have occurred without a step-in-acceptance.", false);
                        }
                        addEntry(Entry.SYSTEM, "A maximum number of steps in this round has been reached (in thousands of years)" + nextBudget
                                + " (b) Round, no new information gains in the near future, and has been suspended to avoid continued consumption;"
                                + "The next round of the series is confirmed, or the next round is lifted by the 'up to the ceiling' point.", false);
                        paused = true;
                        break;
                    }
                }
                compactHistoryIfNeeded();
                PreparedContext prepared = prepareContext();
                if (prepared.error != null) {
                    limitPaused = true;
                    dumpBlockedRequest(prepared.error);
                    addEntry(Entry.ERROR, "The request has been blocked:" + prepared.error
                            + ". Saves the original text, not sent to the model. You can try again in the full context by point of 'break the ceiling'.", false);
                    paused = true;
                    break;
                }
                steps++;
                currentStep = steps;
                supervisor.heartbeat(workTicketId, currentRunId, steps, softLimit);
                int injectedAtRequest = injectedSeq;
                TaskBudget.Reservation budgetReservation = null;
                try {
                    // Give a huge positive number when the ceiling is broken; no benchmark budget to multiply
                    // Long.MAX_VALUE/16, Otherwise... long The multiplication will spill negative numbers and drop anomalies.
                    // The context model does not zoom in on the single task budget: windows are available 1M, But the cost ceiling.
                    // Press still 64k Window calculates, avoid burning millions at a time token. 
                    long windowBudget = (long) Math.min(contextBudget(), 64000) * 12L;
                    long limit = prefs.allowOverLimit() ? Long.MAX_VALUE / 4
                            : prefs.liteHarness() ? LITE_TASK_BUDGET
                            : Math.max(LITE_TASK_BUDGET,
                            Math.min(768000L, windowBudget));
                    if (taskBudget == null) taskBudget = new TaskBudget(limit);
                    budgetReservation = taskBudget.reserve(
                            prepared.settings == null ? "" : prepared.settings.model,
                            prepared.tokens,
                            prepared.settings == null ? 0 : prepared.settings.maxTokens);
                    taskBudget.started(budgetReservation);
                } catch (IllegalStateException budgetError) {
                    boolean productive = conversation != null && conversation.plan != null
                            && conversation.plan.length() > 0
                            && strategyLedger != null
                            && strategyLedger.recentGains(6) >= 2
                            && !blocked && !failed;
                    long currentLimit = taskBudget == null ? 0L : taskBudget.limit();
                    if (productive && currentLimit < MAX_TASK_BUDGET && taskBudget != null) {
                        long extra = Math.min(TASK_BUDGET_EXTENSION,
                                MAX_TASK_BUDGET - currentLimit);
                        taskBudget.extend(extra);
                        addEntry(Entry.SYSTEM, "Mandates are still progressing and budgets are automatically increased "
                                + (extra / 1000) + "k, Current ceiling "
                                + (taskBudget.limit() / 1000) + "k. ", false);
                        try {
                            budgetReservation = taskBudget.reserve(
                                    prepared.settings == null ? "" : prepared.settings.model,
                                    prepared.tokens,
                                    prepared.settings == null ? 0 : prepared.settings.maxTokens);
                            taskBudget.started(budgetReservation);
                        } catch (IllegalStateException stillExhausted) {
                            addEntry(Entry.ERROR, "Mandated budgets exhausted (hard ceiling) "
                                    + (taskBudget.limit() / 1000) + "k), Paused."
                                    + "The first of these is the 'continue' to start a new round of budgets.", false);
                            paused = true;
                            break;
                        }
                    } else {
                        addEntry(Entry.ERROR, "Mandated budgets exhausted (ceiling) "
                                + (currentLimit / 1000) + "k), Paused."
                                + "The first of these is the 'continue' to start a new round of budgets.", false);
                        paused = true;
                        break;
                    }
                }

                final StringBuilder streamedContent = new StringBuilder();
                final StringBuilder streamedReasoning = new StringBuilder();
                final Entry[] streamAssistant = {null};
                final Entry[] streamReasoning = {null};
                final long[] lastStreamNotify = {0};
                        runPhase = "Thinking....";
        runDetail = "";
        LlmClient.Result result = llm.chatStream(
                        prepared.messages, prepared.tools,
                        apiKeyForCurrent(), prepared.settings, new LlmClient.StreamListener() {
                            @Override
                            public void onContent(String delta) {
                                if (!"Generating responses...".equals(runPhase)) {
                runPhase = "Generating responses...";
                runDetail = "";
            }
                                if (stopRequested || delta == null || delta.isEmpty()) {
                                    return;
                                }
                                streamedContent.append(delta);
                                Entry entry = streamAssistant[0];
                                if (entry == null) {
                                    entry = addEntry(Entry.ASSISTANT, "", false);
                                    streamAssistant[0] = entry;
                                }
                                updateEntryText(entry, streamedContent.toString());
                                long now = System.currentTimeMillis();
                                if (now - lastStreamNotify[0] >= 100) {
                                    lastStreamNotify[0] = now;
                                    notifyUpdate();
                                }
                            }

                            @Override
                            public void onReasoning(String delta) {
                                if (stopRequested || delta == null || delta.isEmpty()) {
                                    return;
                                }
                                streamedReasoning.append(delta);
                    runDetail = oneLine(streamedReasoning.toString(), 60);
                    Entry entry = streamReasoning[0];
                                if (entry == null) {
                                    entry = addEntry(Entry.REASONING, "", false);
                                    streamReasoning[0] = entry;
                                }
                                updateEntryText(entry, streamedReasoning.toString());
                                long now = System.currentTimeMillis();
                                if (now - lastStreamNotify[0] >= 100) {
                                    lastStreamNotify[0] = now;
                                    notifyUpdate();
                                }
                            }
                        });
                runPromptTokens += result.promptTokens;
                runCompletionTokens += result.completionTokens;
                runCachedTokens += result.cachedTokens;
                if (budgetReservation != null && taskBudget != null) {
                    taskBudget.settle(budgetReservation, result.promptTokens,
                            result.completionTokens, result.promptTokens > 0);
                }
                if (!running) {
                    stopped = true;
                    break;
                }
                if (stopRequested) {
                    stopped = true;
                    break;
                }
                boolean pauseAfterMessage = pauseRequested;
                if (pauseAfterMessage && result.error != null) {
                    paused = true;
                    break;
                }
                if (result.error != null && !thinkingFallbackTried
                        && AppPrefs.THINKING_ON.equals(runThinking)
                        && isThinkingProtocolError(result.error)) {
                    thinkingFallbackTried = true;
                    runThinking = AppPrefs.THINKING_OFF;
                    if (prepared.settings != null) {
                        prepared.settings.thinking = AppPrefs.THINKING_OFF;
                    }
                    addEntry(Entry.SYSTEM, "thinking Request denied (" + shorten(result.error, 120)
                            + "), The blog has been retried to no-think mode.", false);
                    continue;
                }
                if (result.error != null) {
                    if (gen != runGeneration) {
                        return;
                    }
                    if (result.retryable && netRetries < 2
                            && !stopRequested && !pauseRequested) {
                        netRetries++;
                        addEntry(Entry.SYSTEM, "Connection interrupted (" + result.error
                                + "), " + (2 * netRetries) + " Auto retry after seconds ("
                                + netRetries + "/2)...", false);
                        if (streamAssistant[0] != null) {
                            updateEntryText(streamAssistant[0],
                                    "[Connection is down, the current round has been regenerated]");
                        }
                        try {
                            Thread.sleep(2000L * netRetries);
                        } catch (InterruptedException ignored) {
                        }
                        if (stopRequested) {
                            stopped = true;
                            break;
                        }
                        if (pauseRequested) {
                            paused = true;
                            break;
                        }
                        continue;
                    }
                    addEntry(Entry.ERROR, "Model request failed:" + result.error, false);
                    failed = true;
                    break;
                }
                netRetries = 0;

                JSONObject message = result.message;
                String content = JsonText.string(message, "content").trim();
                JSONArray toolCalls = message.optJSONArray("tool_calls");
                String reasoningField = null;
                String reasoning = "";
                if (message.has("reasoning_content")) {
                    reasoningField = "reasoning_content";
                    reasoning = JsonText.string(message, "reasoning_content").trim();
                }
                if (reasoning.isEmpty() && message.has("reasoning")) {
                    reasoningField = "reasoning";
                    reasoning = JsonText.string(message, "reasoning").trim();
                }

                JSONObject assistant = new JSONObject();
                assistant.put("role", "assistant");
                boolean hasCalls = toolCalls != null && toolCalls.length() > 0;
                assistant.put("content", content.isEmpty()
                        ? JSONObject.NULL : content);
                if (reasoningField != null && !reasoning.isEmpty()) {
                    assistant.put(reasoningField, reasoning);
                }
                if (hasCalls) {
                    assistant.put("tool_calls", toolCalls);
                }
                synchronized (messages) {
                    messages.add(assistant);
                }
                messagesChanged();

                if (!reasoning.isEmpty()) {
                    if (streamReasoning[0] != null) {
                        updateEntryText(streamReasoning[0], reasoning);
                    } else {
                        addEntry(Entry.REASONING, reasoning, false);
                    }
                }
                if (!content.isEmpty()) {
                    if (streamAssistant[0] != null) {
                        updateEntryText(streamAssistant[0], content);
                    } else {
                        addEntry(Entry.ASSISTANT, content, false);
                    }
                }
                notifyUpdate();
                saveCurrent();

                if (!hasCalls) {
                    if (content.isEmpty()) {
                        emptyRounds++;
                        boolean canRetry = emptyRounds <= EMPTY_REPLY_RETRY_LIMIT
                                && !stopRequested && !pauseRequested;
                        if (canRetry) {
                            addEntry(Entry.SYSTEM, "The current round model does not return the body or the tool call,"
                                    + "Auto-retry (%2)" + emptyRounds + "/"
                                    + EMPTY_REPLY_RETRY_LIMIT + "). ", false);
                            try {
                                JSONObject nudge = new JSONObject();
                                nudge.put("role", "user");
                                nudge.put("_synthetic", true);
                                nudge.put("content", "[System correction] You didn't return any text in the last round, nor did you launch the call."
                                        + "Please immediately execute a minimum enforceable action (e.g., list_files, read_file or ui_dump)(a) Continuation of the mandate;"
                                        + "If the task is completed, please provide a concise conclusion.");
                                synchronized (messages) {
                                    messages.add(nudge);
                                }
                                messagesChanged();
                            } catch (Exception ignored) {
                            }
                            continue;
                        }
                        addEntry(Entry.ERROR, "Models Continuous " + emptyRounds
                                + " The cycle did not return content and this execution was stopped to avoid a emptiness."
                                + "Can send 'continue' or check the model directly/Network configuration.", false);
                        failed = true;
                        break;
                    }
                    emptyRounds = 0;
                    List<String> unverified = prefs.liteHarness()
                            ? java.util.Collections.<String>emptyList()
                            : runLedger.unverified();
                    if (!unverified.isEmpty() && verifyNudges < 1
                            && !stopRequested && !pauseRequested) {
                        verifyNudges++;
                        addEntry(Entry.SYSTEM, "The change has not been successfully validated and the model has been reminded to perform a minimum cost validation.", false);
                        journalEvent("VERIFY_NUDGE", "", "PENDING", joinPathList(unverified));
                        try {
                            JSONObject nudge = new JSONObject();
                            nudge.put("role", "user");
                            nudge.put("_synthetic", true);
                            nudge.put("content", "[System Validation] The following changes have not been successfully constructed/Test evidence:"
                                    + joinPathList(unverified)
                                    + ". Read and build first/Tests, confirmation of results and conclusion;"
                                    + "If this is not possible, please specify the reasons and risks.");
                            synchronized (messages) {
                                messages.add(nudge);
                            }
                            messagesChanged();
                        } catch (Exception ignored) {
                        }
                        continue;
                    }
                    if (!unverified.isEmpty()) {
                        addEntry(Entry.ERROR, "Task not completed (BLOCKED): The following changes lack the required proof of validation:"
                                + joinPathList(unverified) + ". Please build/Continue after testing.", false);
                        journalEvent("UNVERIFIED_END", "", "BLOCKED", joinPathList(unverified));
                        blocked = true;
                        paused = true;
                        break;
                    } else if (!runLedger.paths().isEmpty()) {
                        addEntry(Entry.SYSTEM, "Authentication of account books:" + runLedger.summary(), false);
                        journalEvent("VERIFIED_END", "", "VERIFIED", runLedger.summary());
                    }
                    if (injectedSeq != injectedAtRequest) {
                        continue;
                    }
                    break;
                }
                emptyRounds = 0;

                                String incomingError = MessageProtocol.validatePending(validationSnapshot());
                if (incomingError != null) {
                    // A break should not hold the entire session forever: finish it right away./Clean up, check again.
                    Log.i("AgentUsage", "sequenceRepair reason=" + incomingError);
                    repairMessagesInMemory();
                    incomingError = MessageProtocol.validatePending(validationSnapshot());
                }
                if (incomingError != null) {
                    Log.e("AgentUsage", "modelCallBlocked reason=" + incomingError);
                    addEntry(Entry.ERROR, "Model call blocked:" + incomingError
                            + "(The post is a historical piece of information that has been broken, and it is not yet approved."
                            + "Send 'continue' retry; send as often as you appear /selfcheck Positioning.", false);
                    failed = true;
                    break;
                }
                String signature = toolSignature(toolCalls);
                if (!signature.isEmpty() && signature.equals(lastSignature)) {
                    duplicateRounds++;
                } else {
                    duplicateRounds = 1;
                    lastSignature = signature;
                }
                if (duplicateRounds >= 3) {
                    if (corrections < MAX_CORRECTIONS) {
                        corrections++;
                        appendToolStubs(toolCalls, 0);
                        injectRepeatCorrection(toolCalls,
                                "Call repetition of the same parameter " + duplicateRounds + " Wheel");
                        duplicateRounds = 1;
                        lastSignature = "";
                        callCounts.clear();
                        continue;
                    }
                    if (duplicateRoundHardStops < 6) {
                        duplicateRoundHardStops++;
                        corrections = 0;
                        appendToolStubs(toolCalls, 0);
                        injectRepeatCorrection(toolCalls,
                                "Call repetition of the same parameter " + duplicateRounds
                                        + " Round (replaced by multiple correction)");
                        duplicateRounds = 1;
                        lastSignature = "";
                        callCounts.clear();
                        continue;
                    }
                    addEntry(Entry.ERROR, "Repeat the call of the tool has been blocked, check the parameters or replace one method.", false);
                    appendToolStubs(toolCalls, 0);
                    failed = true;
                    break;
                }
                boolean repeatedTooOften = false;
                for (int i = 0; i < toolCalls.length(); i++) {
                    String callSig = callSignature(toolCalls.optJSONObject(i));
                    if (callSig.isEmpty()) {
                        continue;
                    }
                    Integer seen = callCounts.get(callSig);
                    int next = seen == null ? 1 : seen + 1;
                    callCounts.put(callSig, next);
                    if (next >= REPEAT_CALL_LIMIT) {
                        repeatedTooOften = true;
                    }
                }
                if (repeatedTooOften) {
                    if (corrections < MAX_CORRECTIONS) {
                        corrections++;
                        appendToolStubs(toolCalls, 0);
                        injectRepeatCorrection(toolCalls, "Cumulative appearance of the same tool called "
                                + REPEAT_CALL_LIMIT + " Number of times");
                        callCounts.clear();
                        lastSignature = "";
                        duplicateRounds = 1;
                        continue;
                    }
                    if (repeatHardStops < 6) {
                        repeatHardStops++;
                        corrections = 0;
                        appendToolStubs(toolCalls, 0);
                        injectRepeatCorrection(toolCalls, "Cumulative appearance of the same tool called "
                                + REPEAT_CALL_LIMIT + " Number of times (auto-blocked, change tool)/(refer to parameters)");
                        callCounts.clear();
                        lastSignature = "";
                        duplicateRounds = 1;
                        continue;
                    }
                    addEntry(Entry.ERROR, "Cumulative appearance of the same tool called " + REPEAT_CALL_LIMIT
                            + " Once, the time has been suspended to avoid an unlimited amount of space; please give a direct new direction for the next step.", false);
                    appendToolStubs(toolCalls, 0);
                    failed = true;
                    break;
                }

                if (pauseAfterMessage) {
                    appendToolStubs(toolCalls, 0);
                    paused = true;
                    break;
                }

                boolean interrupted = false;
                boolean roundChanged = false;
                int executed = 0;
                List<ScreenCaptureService.CaptureResult> roundScreenshots = new ArrayList<>();
                boolean parallelRound = toolCalls.length() > 1;
                for (int i = 0; i < toolCalls.length(); i++) {
                    JSONObject pc = toolCalls.optJSONObject(i);
                    JSONObject pf = pc == null ? null : pc.optJSONObject("function");
                    if (pf == null || !isParallelReadTool(
                            JsonText.string(pf, "name"))) {
                        parallelRound = false;
                        break;
                    }
                }
                if (parallelRound) {
                    List<OperationScheduler.Operation<String>> operations = new ArrayList<>();
                    List<String> callIds = new ArrayList<>();
                    List<String> names = new ArrayList<>();
                    List<String> argsList = new ArrayList<>();
                    for (int i = 0; i < toolCalls.length(); i++) {
                        JSONObject call = toolCalls.optJSONObject(i);
                        JSONObject function = call == null ? null : call.optJSONObject("function");
                        final String callId = call == null ? "" : JsonText.string(call, "id");
                        final String name = function == null ? "" : JsonText.string(function, "name");
                        final String arguments = function == null || JsonText.string(function, "arguments").isEmpty()
                                ? "{}" : JsonText.string(function, "arguments");
                        callIds.add(callId);
                        names.add(name);
                        argsList.add(arguments);
                        addEntry(Entry.TOOL, toolCallLabel(name, arguments), false);
                        journalEvent(callId, name, "RUNNING", "parallel");
                        final List<ScreenCaptureService.CaptureResult> shots = roundScreenshots;
                        operations.add(new OperationScheduler.Operation<String>(true,
                                new java.util.concurrent.Callable<String>() {
                                    @Override
                                    public String call() throws Exception {
                                        synchronized (deferredSynthetics) {
                                            inFlightCalls++;
                                        }
                                        try {
                                            return executeTool(name, new JSONObject(arguments), shots);
                                        } finally {
                                            synchronized (deferredSynthetics) {
                                                inFlightCalls--;
                                            }
                                        }
                                    }
                                }));
                    }
                    OperationScheduler scheduler = new OperationScheduler(2);
                    List<OperationScheduler.Result<String>> results;
                    try {
                        results = scheduler.execute(operations, new OperationScheduler.Cancellation() {
                            @Override
                            public boolean cancelled() {
                                return stopRequested || pauseRequested;
                            }
                        });
                    } finally {
                        scheduler.close();
                    }
                    for (int i = 0; i < results.size(); i++) {
                        OperationScheduler.Result<String> r = results.get(i);
                        String callId = callIds.get(i);
                        String name = names.get(i);
                        String arguments = argsList.get(i);
                        String toolResult;
                        if (r.cancelled) {
                            JSONObject cancelled = new JSONObject();
                            cancelled.put("ok", false);
                            cancelled.put("status", "CANCELLED");
                            cancelled.put("error", "Task suspended./Stop, call unexecuted");
                            toolResult = cancelled.toString();
                            interrupted = true;
                        } else if (r.error != null) {
                            toolResult = errorJson(r.error.getMessage() == null
                                    ? r.error.getClass().getSimpleName() : r.error.getMessage());
                        } else {
                            toolResult = r.value == null ? errorJson("Empty tool result") : r.value;
                        }
                        JSONObject toolMessage = new JSONObject();
                        toolMessage.put("role", "tool");
                        toolMessage.put("tool_call_id", callId);
                        toolMessage.put("content", handleLargeResult(callId, toolResult));
                        synchronized (messages) {
                            messages.add(toolMessage);
                        }
                        flushDeferredSynthetics();
                        executed++;
                        runToolCalls++;
                        String error = toolError(toolResult);
                        if (error != null) {
                            JSONObject parsedArgs = null;
                            try {
                                parsedArgs = new JSONObject(arguments);
                            } catch (Exception ignored) {
                            }
                            reportToolFailure(name, parsedArgs, toolResult, error);
                        }
                        noteToolStall(name, error);
                        journalEvent(callId, name, error == null ? "SUCCEEDED" : "FAILED",
                                error == null ? "" : shorten(error, 220));
                        if (error != null) {
                            addEntry(Entry.ERROR, "Tools " + name + " Failed:" + shorten(error, 220), false);
                        } else {
                            addEntry(Entry.TOOL, "Tools " + name + " Completed", false);
                        }
                        if (isEditTool(name)) {
                            recordChange(name, arguments, toolResult);
                        } else {
                            recordEvidence(name, arguments, toolResult);
                        }
                        noteToolState(name, arguments, toolResult);
                        noteVisualTool(name, toolResult, error);
                        if (name.equals(lastToolName)) {
                            sameToolCount++;
                        } else {
                            lastToolName = name;
                            sameToolCount = 1;
                        }
                    }
                } else {
                for (int i = 0; i < toolCalls.length(); i++) {
                    if (stopRequested || pauseRequested) {
                        interrupted = true;
                        break;
                    }
                    JSONObject call = toolCalls.optJSONObject(i);
                    if (call == null) {
                        executed++;
                        continue;
                    }
                    String callId = JsonText.string(call, "id");
                    JSONObject function = call.optJSONObject("function");
                    String name = function == null ? "" : JsonText.string(function, "name");
                    String arguments = function == null ? "{}"
                            : JsonText.string(function, "arguments");
                    if (arguments.isEmpty()) {
                        arguments = "{}";
                    }
                    addEntry(Entry.TOOL, toolCallLabel(name, arguments), false);
                    journalEvent(callId, name, "RUNNING", "");
                    if (!isReadOnlyToolCall(name, arguments)) {
                        roundChanged = true;
                    }

                    String toolResult;
                    try {
                        JSONObject args = new JSONObject(arguments);
                        synchronized (deferredSynthetics) {
                            inFlightCalls++;
                        }
                        try {
                            toolResult = executeTool(name, args, roundScreenshots);
                        } finally {
                            synchronized (deferredSynthetics) {
                                inFlightCalls--;
                            }
                        }
                        if (gen != runGeneration) {
                            // The tool has really run./The results are loaded.
                            // Otherwise, the next round will be awarded for the suspension of air conditioning.'Unclosed'And the whole round is blocked.
                            recordToolResult(callId, toolResult);
                            flushDeferredSynthetics();
                            return;
                        }
                    } catch (Exception e) {
                        String hint = "Parameter parsing failed:" + e.getMessage();
                        boolean truncated = arguments != null
                                && (arguments.length() > 1200
                                || !arguments.trim().endsWith("}")
                                || (llm != null && llm.wasTruncated()));
                        if (truncated) {
                            hint += ". The parameters used are likely to be cut because of the single output length limit:"
                                    + "Please break the long sections down. write_file Write a contract. 1500 Character,"
                                    + "Again. append=true Add the remainder; no super-parameters should be submitted at one time.";
                        }
                        toolResult = errorJson(hint);
                    }

                    JSONObject toolMessage = new JSONObject();
                    toolMessage.put("role", "tool");
                    toolMessage.put("tool_call_id", callId);
                    toolMessage.put("content", handleLargeResult(callId, toolResult));
                    synchronized (messages) {
                        messages.add(toolMessage);
                    }
                    flushDeferredSynthetics();
                    executed++;
                    runToolCalls++;

                    String error = toolError(toolResult);
                    if (error != null) {
                        JSONObject parsedArgs = null;
                        try {
                            parsedArgs = new JSONObject(arguments);
                        } catch (Exception ignored) {
                        }
                        reportToolFailure(name, parsedArgs, toolResult, error);
                    }
                    noteToolStall(name, error);
                    journalEvent(callId, name, error == null ? "SUCCEEDED" : "FAILED",
                            error == null ? "" : shorten(error, 220));
                    if (error != null) {
                        addEntry(Entry.ERROR, "Tools " + name + " Failed:" + shorten(error, 220), false);
                    } else {
                        addEntry(Entry.TOOL, "Tools " + name + " Completed", false);
                    }
                    if (isEditTool(name)) {
                        recordChange(name, arguments, toolResult);
                    } else {
                        recordEvidence(name, arguments, toolResult);
                    }
                    noteToolState(name, arguments, toolResult);
                        noteVisualTool(name, toolResult, error);
                    if (name.equals(lastToolName)) {
                        sameToolCount++;
                    } else {
                        lastToolName = name;
                        sameToolCount = 1;
                    }
                }
                }
                if (interrupted) {
                    appendToolStubs(toolCalls, executed);
                    messagesChanged();
                    saveCurrent();
                    if (pauseRequested) {
                        paused = true;
                    } else {
                        stopped = true;
                    }
                    break;
                }
                if (!loopStopReason.isEmpty()) {
                    String stop = loopStopReason;
                    loopStopReason = "";
                    addEntry(Entry.ERROR, stop, false);
                    paused = true;
                    break;
                }
                boolean correctedThisRound = false;
                StateMonitor.Decision stateDecision = lastStateDecision;
                lastStateDecision = null;
                if (stateDecision != null && stateDecision.actionable()
                        && corrections < MAX_CORRECTIONS) {
                    corrections++;
                    correctedThisRound = true;
                    if (strategyLedger != null) {
                        if (!"web_fetch".equals(stateDecision.strategy)
                                && !StrategyLedger.isVisualCapture(stateDecision.actionKey)) {
                            strategyLedger.block(stateDecision.actionKey);
                        }
                        if (looksLikeWebTask(runState == null ? "" : runState.goal)
                                && ("termux_probe".equals(stateDecision.strategy)
                                || "termux_run".equals(stateDecision.strategy)
                                || "shell_run".equals(stateDecision.strategy)
                                || "termux_web".equals(stateDecision.strategy)
                                || "read_file".equals(stateDecision.strategy)
                                || "list_files".equals(stateDecision.strategy))) {
                            strategyLedger.blockStrategy(stateDecision.strategy);
                        }
                    }
                    injectStateCorrection(stateDecision);
                    if (stateMonitor != null) {
                        stateMonitor.resetAfterCorrection();
                    }
                    if (runState != null) {
                        runState.noteSwitch(stateDecision.reason, stateDecision.suggested);
                        runState.setLedger(strategyLedger == null
                                ? new JSONObject() : strategyLedger.toJson());
                        runState.save();
                    }
                }
                if (roundChanged) {
                    readOnlyRounds = 0;
                    readOnlyNudged = false;
                } else {
                    readOnlyRounds++;
                    if (readOnlyRounds == 6 && !readOnlyNudged) {
                        readOnlyNudged = true;
                        injectReadOnlyNudge();
                    }
                                        if (readOnlyRounds >= NO_PROGRESS_READ_ROUNDS) {
                        if (readOnlyRounds > NO_PROGRESS_READ_ROUNDS) {
                            // Read-only but new information is available on each round (e.g., page break, big document,grep The government has been working on the issue of the Internet.
                            // Regular investigations are conducted without interruption; only successive rounds of unnew information are suspended.
                            readOnlyRounds = NO_PROGRESS_READ_ROUNDS;
                        } else if (prefs.allowOverLimit()) {
                            addEntry(Entry.SYSTEM, "Continuous " + readOnlyRounds
                                    + " The rotation is read-only and unchanged. You have already started the 'Break the ceiling' and the mission continues to run."
                                    + "But if I just want to draw conclusions, let me now just wrap this up.", false);
                            readOnlyRounds = 0;
                        } else {
                            addEntry(Entry.SYSTEM, "Continuous " + readOnlyRounds
                                    + " (a) Round-only, without any document or environmental change, to be suspended to avoid any empty rotation;"
                                    + "If there is a need to continue the investigation, please do 'continue' or give a direct indication of the next step.", false);
                            paused = true;
                            break;
                        }
                    }
                }
                if (!correctedThisRound && sameToolCount >= 6
                        && ("read_file".equals(lastToolName)
                        || "termux_run".equals(lastToolName)
                        || "shell_run".equals(lastToolName)
                        || "list_files".equals(lastToolName))) {
                    addEntry(Entry.SYSTEM, "Tools " + lastToolName + " Called consecutively "
                            + sameToolCount + " Quite a few times, have been suggested for smaller queries or direct edit. ", false);
                    injectSynthetic("[System Hint] " + lastToolName + " Called consecutively "
                            + sameToolCount + " Do not repeat the same readings./(a) Orders;"
                            + "Change to a smaller range of queries,inspect Or direct. edit/update_plan. ");
                    sameToolCount = 0;
                }
                if (runState != null) {
                    runState.setLedger(strategyLedger == null
                            ? new JSONObject() : strategyLedger.toJson());
                    runState.save();
                }
                messagesChanged();
                saveCurrent();
                if (!roundScreenshots.isEmpty()) {
                    appendScreenshotEvidence(roundScreenshots);
                }
            }
            if (!stopped && !paused && !failed && steps >= 3) {
                addEntry(Entry.SYSTEM,
                        "Caution: This task is more complex, generating a double disk in a 'message loop' and settling the reusable process into a skill.",
                        false);
            }
        } catch (Throwable e) {
            if (e instanceof android.view.WindowManager.BadTokenException) {
                // The failure of the window token is a question of the interface life cycle and the entire task should not be sentenced to death.
                Log.w("MobileAgent", "window token lost; pausing run", e);
                addEntry(Entry.SYSTEM,
                        "The interface window has expired and the bullet window has been jumped; the round is suspended and the point 'continue' can be restored.", false);
                paused = true;
            } else {
                failed = true;
                Log.e("MobileAgent", "Agent run failed", e);
                String stack = persistAgentError(e);
                addEntry(Entry.ERROR, "Agent Unusual:" + e.getClass().getSimpleName()
                        + (stack.isEmpty() ? "" : "\n" + shorten(stack, 900)), false);
            }
        } finally {
            if (workerThread == Thread.currentThread()) {
                workerThread = null;
            }
            finishState(AgentState.outcome(stopped, paused, failed), steps);
            if (gen != runGeneration) {
                // This round has been terminated or replaced by tasks that have been renewed, with a silent exit and no changes in the sharing status.
                supervisor.end(workTicketId, WorkStore.STOPPED, "Tasks replaced by new operations.");
                journalEvent("RUN_END", "", "REPLACED", "generation mismatch");
                return;
            }
            try {
                WorkStore.Ticket work = supervisor.store.find(workTicketId);
                boolean alreadyAttention = work != null && work.attention;
                String outcome = AgentState.outcome(stopped, paused, failed);
                if (alreadyAttention) {
                    // A tool watchdog or a blocked tool already raised attention;
                    // do not overwrite it with a normal completion.
                } else if ("paused".equals(outcome)) {
                    if (blocked) supervisor.block(workTicketId, "The task was blocked and awaiting processing.");
                    else supervisor.end(workTicketId, WorkStore.WAITING, "The task has been suspended and may continue.");
                } else if ("stopped".equals(outcome) || stopRequested) {
                    supervisor.end(workTicketId, WorkStore.STOPPED, "The mission has been discontinued.");
                } else if ("failed".equals(outcome)) {
                    supervisor.fail(workTicketId, "Agent Abnormal end.");
                } else if (blocked) {
                    supervisor.block(workTicketId, "The task was not validated and needs to be addressed.");
                } else {
                    supervisor.ready(workTicketId, "Mission complete, awaiting review/Rewind.");
                }
            } catch (Throwable workError) {
                Log.w("AgentWork", "finish ticket failed", workError);
            }
            journalEvent("RUN_END", "", paused ? (blocked ? "BLOCKED" : "PAUSED")
                    : stopped ? "CANCELLED" : failed ? "FAILED" : "COMPLETED",
                    runLedger.summary());
            if (paused) {
                if (conversation != null) {
                    conversation.paused = true;
                    conversation.pausedAt = System.currentTimeMillis();
                }
                addEntry(Entry.SYSTEM, "The mission has been suspended. Point 'continue' to resume from the current pace."
                        + "You can also enter a new command directly to continue.", false);
            } else if (conversation != null) {
                conversation.paused = false;
                conversation.pausedAt = 0L;
            }
            if (stopped || stopRequested) {
                addEntry(Entry.SYSTEM, "The mission has been discontinued.", false);
            }
            boolean autoResumed = conversation != null && conversation.autoResumeCount > 0;
            if (!paused && !stopped && !failed && conversation != null) {
                conversation.autoResumeCount = 0;
            }
            finishRun(true);
            if (!headless && !paused && !stopped && !failed) {
                scheduleReview(steps, System.currentTimeMillis() - runStarted, autoResumed);
            }
        }
    }

    /** Validable snapshots of current history: complete the suspension of air conditioners in a copy before submission to protocol verification. */
    private JSONArray validationSnapshot() {
        JSONArray history = new JSONArray();
        synchronized (messages) {
            for (JSONObject item : messages) {
                history.put(item);
            }
        }
        return MessageProtocol.repairDanglingCalls(history);
    }

    /**
     * The big result is a drop, and only a left handle in the context.+Preview: for models to use when detail is required inspect And the path of the handle,
     * This time. 10 The construction log for 10,000 characters does not double the billing in each subsequent round.
     */
    private String handleLargeResult(String callId, String result) {
        if (result == null || result.length() <= HANDLE_THRESHOLD_CHARS) {
            return result;
        }
        try {
            String convId = conversation == null ? "current" : conversation.id;
            File dir = new File(workspace.baseRoot(),
                    ".agent/handles/" + convId.replaceAll("[^A-Za-z0-9._-]", "_"));
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return result;
            }
            String name = (callId == null || callId.trim().isEmpty() ? "call"
                    : callId.replaceAll("[^A-Za-z0-9._-]", "_")) + ".txt";
            File target = new File(dir, name);
            FileOutputStream stream = new FileOutputStream(target);
            try {
                stream.write(result.getBytes("UTF-8"));
            } finally {
                stream.close();
            }
            boolean ok = true;
            String error = "";
            try {
                JSONObject parsed = new JSONObject(result);
                ok = parsed.optBoolean("ok", true);
                error = parsed.optString("error", "");
            } catch (Exception ignored) {
            }
            JSONObject wrapper = new JSONObject();
            wrapper.put("ok", ok);
            if (!error.isEmpty()) {
                wrapper.put("error", error);
            }
            wrapper.put("handle", workspace.relativePath(target));
            wrapper.put("bytes", result.length());
            wrapper.put("sha256", ToolRegistry.sha256(result)
                    .substring(0, 16));
            wrapper.put("note", "Full result is set; it is not put in context; details are required inspect mode=read "
                    + "Segment reading handle Path, don't rely on it. preview Guess.");
            wrapper.put("preview", result.substring(0, Math.min(800, result.length())));
            return wrapper.toString();
        } catch (Exception error) {
            return result;
        }
    }

    /**
     * History exceeds the threshold by moving the earlier part into the archive file, with only a cross-line index summary in the context.
     * Only"User Round"Border cut, avoiding the diversion of tools and their results.
     */
    private void compactHistoryIfNeeded() {
        if (conversation == null) {
            return;
        }
        int size;
        synchronized (messages) {
            size = messages.size();
        }
        if (size < COMPACT_MIN_MESSAGES) {
            return;
        }
        JSONArray snapshot = new JSONArray();
        synchronized (messages) {
            for (JSONObject item : messages) {
                snapshot.put(item);
            }
        }
        int cut = Math.max(1, snapshot.length() - COMPACT_KEEP_MESSAGES);
        while (cut > 1) {
            JSONObject at = snapshot.optJSONObject(cut);
            String role = at == null ? "" : MessageProtocol.role(at);
            if ("user".equals(role) && !at.optBoolean("_synthetic", false)) {
                break;
            }
            cut--;
        }
        if (cut <= 1) {
            return;
        }
        String digest = ContextArchive.append(workspace.baseRoot(), conversation.id, snapshot, 1, cut);
        if (digest.isEmpty()) {
            return;
        }
        synchronized (messages) {
            if (messages.size() > cut) {
                messages.subList(1, cut).clear();
            }
        }
        conversation.contextDigest = ContextArchive.merge(conversation.contextDigest, digest);
        messagesChanged();
        saveCurrent();
        int left;
        synchronized (messages) {
            left = messages.size();
        }
        Log.i("AgentUsage", "compacted=" + (cut - 1) + " archived=" + (cut - 1)
                + " remaining=" + left + " archiveBytes="
                + ContextArchive.bytes(workspace.baseRoot(), conversation.id));
    }

    /** The results of a tool that has already been implemented are written back to history as agreed and no call that has been called without results is allowed. */
    private void recordToolResult(String callId, String toolResult) {
        try {
            JSONObject toolMessage = new JSONObject();
            toolMessage.put("role", "tool");
            toolMessage.put("tool_call_id", callId);
            toolMessage.put("content", toolResult == null ? errorJson("Empty tool result") : toolResult);
            synchronized (messages) {
                messages.add(toolMessage);
            }
            messagesChanged();
        } catch (Exception ignored) {
        }
    }

    private void appendToolStubs(JSONArray calls, int from) {
        if (calls == null) {
            return;
        }
        for (int i = from; i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i);
            if (call == null) {
                continue;
            }
            try {
                JSONObject toolMessage = new JSONObject();
                toolMessage.put("role", "tool");
                toolMessage.put("tool_call_id", JsonText.string(call, "id"));
                JSONObject outcome = new JSONObject();
                outcome.put("ok", false);
                outcome.put("status", "CANCELLED");
                // Wording matters here: "Reauthorization" made later runs believe the tool
                // call was denied, when it was only interrupted.
                outcome.put("error", "The last call was interrupted before the call was made, and the call was not executed;"
                        + "It's not a question of permission. Just try calling again.");
                toolMessage.put("content", outcome.toString());
                synchronized (messages) {
                    messages.add(toolMessage);
                }
            } catch (Exception ignored) {
            }
        }
        messagesChanged();
    }

    private static String toolSignature(JSONArray calls) {
        if (calls == null || calls.length() == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i);
            JSONObject function = call == null ? null : call.optJSONObject("function");
            builder.append(function == null ? "" : JsonText.string(function, "name"))
                    .append(':')
                    .append(function == null ? "" : JsonText.string(function, "arguments"))
                    .append('\n');
        }
        String raw = builder.toString();
        return calls.length() + ":" + raw.length() + ":" + raw.hashCode();
    }

    private static String callSignature(JSONObject call) {
        if (call == null) {
            return "";
        }
        JSONObject function = call.optJSONObject("function");
        if (function == null) {
            return "";
        }
        String name = JsonText.string(function, "name");
        if (name.isEmpty()) {
            return "";
        }
        String arguments = JsonText.string(function, "arguments").replaceAll("\\s+", "");
        return name + "|" + arguments;
    }

    /** Read-only Tool/Read-only shell The order does not change the environment and is used to detect long-term air rotations. */
    private static boolean isReadOnlyToolCall(String name, String arguments) {
        if (name == null || name.isEmpty()) {
            return true;
        }
        if (isEditTool(name)) {
            return false;
        }
        if ("build_apk".equals(name) || "install_apk".equals(name)
                || "spawn_subagent".equals(name) || "apply_patch".equals(name)) {
            return false;
        }
        if ("run".equals(name) || "shell_run".equals(name)
                || "termux_run".equals(name)) {
            return isReadOnlyShell(arguments);
        }
        if ("inspect".equals(name)) {
            return true;
        }
        return isParallelReadTool(name)
                || "ui_dump".equals(name) || "screenshot".equals(name)
                || "list_apps".equals(name) || "self_check".equals(name)
                || "read_skill".equals(name) || "search_knowledge".equals(name)
                || "mcp_discover".equals(name) || "repo_index".equals(name)
                || "clipboard_read".equals(name) || "web_fetch".equals(name)
                || "web_search".equals(name)
                || "update_plan".equals(name) || "remember".equals(name);
    }

    private static boolean hasWriteRedirect(String command) {
        String stripped = command.replaceAll("'[^']*'", "''")
                .replaceAll("\"[^\"]*\"", "\"\"");
        for (int i = 0; i < stripped.length(); i++) {
            if (stripped.charAt(i) != '>') {
                continue;
            }
            char prev = i == 0 ? ' ' : stripped.charAt(i - 1);
            char next = i + 1 >= stripped.length() ? ' ' : stripped.charAt(i + 1);
            if (prev == '=' || prev == '-' || next == '=' || next == '&') {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean isReadOnlyShell(String arguments) {
        try {
            String command = new JSONObject(arguments == null ? "{}" : arguments)
                    .optString("command", "");
            if (command == null || command.trim().isEmpty()) {
                return true;
            }
            String lower = command.toLowerCase(Locale.ROOT);
            if (hasWriteRedirect(lower) || lower.contains("sed -i")
                    || lower.contains(" -delete") || lower.contains(" -exec")
                    || lower.contains("rm ") || lower.contains("mv ") || lower.contains("cp ")
                    || lower.contains("mkdir") || lower.contains("touch ") || lower.contains("tee ")
                    || lower.contains("chmod") || lower.contains("chown")
                    || lower.contains("zip ") || lower.contains("truncate")) {
                return false;
            }
            String[] segments = command.split("&&|;|\\|");
            for (String segment : segments) {
                String s = segment.trim();
                if (s.isEmpty()) {
                    continue;
                }
                int space = s.indexOf(' ');
                String head = (space < 0 ? s : s.substring(0, space)).toLowerCase(Locale.ROOT);
                if (head.startsWith("cd") || head.startsWith("export") || head.startsWith("set ")) {
                    continue;
                }
                if (!READ_ONLY_SHELL.contains(head)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }


    private void appendScreenshotEvidence(
            List<ScreenCaptureService.CaptureResult> screenshots) {
        try {
            JSONArray content = new JSONArray();
            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", "This is a photo search./Download/Please continue with the image you have taken.");
            content.put(text);
            int added = 0;
            long totalBytes = 0L;
            for (ScreenCaptureService.CaptureResult shot : screenshots) {
                if (shot.jpeg == null || shot.jpeg.length == 0) {
                    continue;
                }
                if (totalBytes + shot.jpeg.length > MAX_IMAGE_TOTAL_BYTES) {
                    continue;
                }
                totalBytes += shot.jpeg.length;
                String mime = imageMimeOf(shot.path);
                if (mime == null) {
                    mime = sniffImageMime(shot.jpeg);
                }
                if (mime == null) {
                    mime = "image/jpeg";
                }
                JSONObject image = new JSONObject();
                image.put("type", "image_url");
                JSONObject imageUrl = new JSONObject();
                imageUrl.put("url", "data:" + mime + ";base64,"
                        + Base64.encodeToString(shot.jpeg, Base64.NO_WRAP));
                imageUrl.put("detail", "low");
                image.put("image_url", imageUrl);
                content.put(image);
                added++;
                if (shot.path != null && !shot.path.trim().isEmpty()) {
                    String name = new java.io.File(shot.path).getName();
                    addEntry(Entry.IMAGE, "Picture · " + name, false,
                            Collections.singletonList(shot.path));
                }
            }
            if (added == 0) {
                return;
            }
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", content);
            user.put("_synthetic", true);
            synchronized (messages) {
                messages.add(user);
            }
            messagesChanged();
            addEntry(Entry.TOOL, "Other Organiser " + added + " Picture/Screenshot to Model", false);
        } catch (Exception e) {
            addEntry(Entry.ERROR, "Failed to construct the screenshot message", false);
        }
    }


    ContextInfo contextInfo() {
        ContextInfo cached = cachedContextInfo;
        return cached == null ? new ContextInfo(0, contextBudget(), 0, 0) : cached;
    }

    /**
     * Writes the full transcript, the model-facing message list and the live tool
     * schema to external storage, so a conversation can be inspected without root or
     * UI scraping. Deliberately manual: transcripts can hold secrets, so nothing is
     * exported automatically.
     */
    String exportConversationJson() {
        try {
            File dir = new File(workspace.baseRoot(), ".agent/export");
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            String id = conversation == null ? "current" : conversation.id;
            JSONObject out = new JSONObject();
            out.put("conversationId", id);
            out.put("exportedAt", System.currentTimeMillis());
            if (conversation != null) {
                out.put("title", conversation.title);
                out.put("model", conversation.model);
                out.put("contextTokens", conversation.contextTokens);
                out.put("maxToolRounds", conversation.maxToolRounds);
                out.put("paused", conversation.paused);
                JSONArray plan = new JSONArray();
                if (conversation.plan != null) {
                    for (int i = 0; i < conversation.plan.length(); i++) {
                        plan.put(conversation.plan.opt(i));
                    }
                }
                out.put("plan", plan);
            }
            JSONArray entryArray = new JSONArray();
            synchronized (entriesLock) {
                for (Entry entry : entries) {
                    JSONObject item = new JSONObject();
                    item.put("type", entry.type);
                    item.put("text", entry.text);
                    item.put("time", entry.time);
                    if (!entry.images.isEmpty()) {
                        JSONArray images = new JSONArray();
                        for (String image : entry.images) {
                            images.put(image);
                        }
                        item.put("images", images);
                    }
                    if (!entry.files.isEmpty()) {
                        JSONArray files = new JSONArray();
                        for (String file : entry.files) {
                            files.put(file);
                        }
                        item.put("files", files);
                    }
                    entryArray.put(item);
                }
            }
            JSONArray messageArray = new JSONArray();
            synchronized (messages) {
                for (JSONObject message : messages) {
                    messageArray.put(new JSONObject(message.toString()));
                }
            }
            JSONArray schemas = exportToolDefinitions();
            out.put("entries", entryArray);
            out.put("messages", messageArray);
            out.put("archivedMessages", conversation == null ? 0
                    : ContextArchive.countLines(ContextArchive.file(workspace.baseRoot(), id)));
            out.put("toolDefinitions", schemas);
            out.put("toolDefinitionTokens", estimateTokens(schemas.toString()));
            out.put("toolSchemaCost", toolSchemaCost(schemas));
            File target = new File(dir, id + ".json");
            java.io.OutputStream stream = new java.io.FileOutputStream(target);
            try {
                stream.write(out.toString(2).getBytes("UTF-8"));
            } finally {
                stream.close();
            }
            return workspace.relativePath(target) + "(" + target.length() + " bytes,"
                    + entryArray.length() + " The steps," + messageArray.length() + " The news is that"
                    + schemas.length() + " Tool Definition / "
                    + estimateTokens(schemas.toString()) + " tokens)";
        } catch (Exception e) {
            return "Export failed:" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage());
        }
    }

    /**
     * The schema set a round would actually send. A restored conversation has no live run,
     * so the run-scoped cache is empty; the permanent core is what the first round of the
     * next run sends, and exporting that makes the per-round schema cost measurable.
     */
    private JSONArray exportToolDefinitions() {
        if (!activeToolScope.isEmpty()) {
            return toolDefinitions();
        }
        return tools.schemas(CORE_TOOL_NAMES);
    }

    /** Per-tool token cost, biggest first: the input for trimming the resident tool set. */
    private JSONArray toolSchemaCost(JSONArray schemas) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < schemas.length(); i++) {
            JSONObject tool = schemas.optJSONObject(i);
            JSONObject fn = tool == null ? null : tool.optJSONObject("function");
            if (fn == null) {
                continue;
            }
            rows.add(new Object[] {fn.optString("name"), estimateTokens(tool.toString())});
        }
        Collections.sort(rows, new Comparator<Object[]>() {
            @Override public int compare(Object[] a, Object[] b) {
                return (Integer) b[1] - (Integer) a[1];
            }
        });
        JSONArray out = new JSONArray();
        for (Object[] row : rows) {
            try {
                out.put(new JSONObject().put("name", row[0]).put("tokens", row[1]));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private int contextBudget() {
        int configured = conversation == null ? prefs.contextTokens() : conversation.contextTokens;
        if (prefs.allowOverLimit()) {
            // The user accepted the cost: stop clamping the window, but never let it
            // fall below a workable size for the schemas and the system prompt.
            return Math.max(32000, configured);
        }
        if (prefs.liteHarness()) {
            // The tool gateway + output reserve needs more headroom than the old
            // 12k Lite window; the actual request is still only the history sent.
            // A 16k window cannot hold a ~7k system prompt, 2.5k of tool schemas and a
            // 4k output reserve at once; 24k is the smallest workable lite floor.
            return Math.max(24000, Math.max(8000, Math.min(LITE_CONTEXT_TOKENS, configured)));
        }
        // The old session might still be there. 12k windows;to system tips, tools schema And output reserved a floor,
        // Avoid rereading the models in very narrow contexts.
        return Math.max(24000, configured);
    }

    /** Wheel ceiling: default hard card 24, No ceilings are set when the user has broken the ceiling. */
    private int roundCeiling() {
        return prefs.allowOverLimit() ? OVER_LIMIT_ROUNDS : HARD_MAX_ROUNDS;
    }

    private String roundCeilingLabel() {
        return prefs.allowOverLimit() ? "Unlimited" : String.valueOf(HARD_MAX_ROUNDS);
    }

    /** The number of recent tool results in the original original language is maintained in the request. */
    private static final int TOOL_FULL_KEEP = 6;
    /** The request maintains the last number of systematic corrections in the original language. */
    private static final int SYNTHETIC_FULL_KEEP = 1;

    /**
     * Folds consumed tool payloads and synthetic nudges into one line, in the
     * outgoing request only. Without this the model loses the thread after a few
     * tool calls, re-runs the same searches, and every re-run looks like new
     * information.
     */
    private static JSONArray reclaimRequestHistory(JSONArray history) {
        List<Integer> toolIndexes = new ArrayList<>();
        List<Integer> syntheticIndexes = new ArrayList<>();
        List<Integer> reasoningIndexes = new ArrayList<>();
        for (int i = 0; i < history.length(); i++) {
            JSONObject message = history.optJSONObject(i);
            if (message == null) {
                continue;
            }
            if ("tool".equals(message.optString("role", ""))) {
                toolIndexes.add(Integer.valueOf(i));
            } else if (message.optBoolean("_synthetic", false)) {
                syntheticIndexes.add(Integer.valueOf(i));
            } else if (message.has("reasoning_content") || message.has("reasoning")) {
                reasoningIndexes.add(Integer.valueOf(i));
            }
        }
        dedupeToolResults(history, toolIndexes);
        foldOlder(history, toolIndexes, TOOL_FULL_KEEP, "Earlier tool output folded");
        foldOlder(history, syntheticIndexes, SYNTHETIC_FULL_KEEP, "Early system correction has been folded");
        // Past thinking is kept only for the newest turn: re-sending every earlier
        // reasoning chain is what makes thinking mode unaffordable per step.
        foldOlderReasoning(history, reasoningIndexes);
        return history;
    }

    /**
     * The same file read twice costs the same tokens again in every later round, and a
     * transcript full of near-identical reads is what the model reads as "new information"
     * while looping. Keep the newest copy verbatim and point the earlier ones at it, so the
     * dedupe never hides the payload the model most recently saw.
     */
    private static void dedupeToolResults(JSONArray history, List<Integer> toolIndexes) {
        Map<String, Integer> keptAt = new HashMap<>();
        int dropped = 0;
        int savedChars = 0;
        for (int i = toolIndexes.size() - 1; i >= 0; i--) {
            int index = toolIndexes.get(i).intValue();
            JSONObject message = history.optJSONObject(index);
            if (message == null) {
                continue;
            }
            String content = message.optString("content", "");
            if (content.length() < 200 || content.startsWith("[")) {
                continue;
            }
            String key = ToolRegistry.sha256(content);
            Integer kept = keptAt.get(key);
            if (kept == null) {
                keptAt.put(key, Integer.valueOf(index));
                continue;
            }
            try {
                message.put("content", "[with " + (kept.intValue() + 1) + " The result of the tool is the same, omitted]");
                dropped++;
                savedChars += content.length();
            } catch (Exception ignored) {
            }
        }
        if (dropped > 0) {
            Log.i("AgentUsage", "dedupedToolResults=" + dropped + " savedChars=" + savedChars);
        }
    }

    private static void foldOlderReasoning(JSONArray history, List<Integer> indexes) {
        int foldUntil = indexes.size() - 1;
        for (int i = 0; i < foldUntil; i++) {
            JSONObject message = history.optJSONObject(indexes.get(i).intValue());
            if (message == null) {
                continue;
            }
            // DeepSeek requires the full reasoning_content of assistant tool-call
            // turns to be sent back verbatim; truncating it triggers HTTP 400.
            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls != null && calls.length() > 0) {
                continue;
            }
            try {
                String field = message.has("reasoning_content")
                        ? "reasoning_content" : "reasoning";
                String text = message.optString(field, "");
                if (text.length() > 120) {
                    message.put(field, text.substring(0, 120) + "...(The early thinking has folded.");
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void foldOlder(JSONArray history, List<Integer> indexes, int keepFull,
                                  String label) {
        int foldUntil = indexes.size() - keepFull;
        for (int i = 0; i < foldUntil; i++) {
            JSONObject message = history.optJSONObject(indexes.get(i).intValue());
            if (message == null) {
                continue;
            }
            String content = message.optString("content", "");
            if (content.isEmpty() || content.startsWith("[" + label)) {
                continue;
            }
            try {
                message.put("content", "[" + label + "] " + digestLine(content));
            } catch (Exception ignored) {
            }
        }
    }

    private static String digestLine(String content) {
        String flat = content.replaceAll("\\s+", " ").trim();
        if (flat.length() > 160) {
            flat = flat.substring(0, 160) + "...";
        }
        return flat.isEmpty() ? "(No content)" : flat;
    }

    /** The bottom of the loop: all the remaining tools are being exported, corrected and thought-out. */
    private static JSONArray foldHard(JSONArray history) {
        JSONArray copy = MessageProtocol.copy(history);
        for (int i = 0; i < copy.length(); i++) {
            JSONObject message = copy.optJSONObject(i);
            if (message == null) {
                continue;
            }
            String role = message.optString("role", "");
            String content = message.optString("content", "");
            if (("tool".equals(role) || "user".equals(role) && message.has("_synthetic")
                    || "reasoning".equals(role)) && content.length() > 200) {
                try {
                    message.put("content", digestLine(content));
                } catch (Exception ignored) {
                }
            }
            for (String field : new String[]{"reasoning_content", "reasoning"}) {
                String text = message.optString(field, "");
                if (text.length() > 200) {
                    try {
                        message.put(field, digestLine(text));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return copy;
    }

    /**
     * Step text keeps the "Call <name> <args>" shape that MainActivity parses; the
     * intent rides inside the args and is rendered there, so changing this prefix
     * would silently break both the tool row and the stage grouping.
     */
    private static String toolCallLabel(String name, String arguments) {
        return "Call " + name + " " + shorten(arguments, 180);
    }

    /**
     * Last line of defence: whatever the transcript holds, secrets are scrubbed from
     * the outgoing request. Redaction used to live only in the compose box, so any
     * other entry point could send a raw token to the provider.
     */
    private static int scrubRequestHistory(JSONArray history) {
        int hits = 0;
        for (int i = 0; i < history.length(); i++) {
            JSONObject message = history.optJSONObject(i);
            if (message == null) {
                continue;
            }
            String content = message.optString("content", "");
            if (content.length() <= 24) {
                continue;
            }
            LocalPreprocessor.Sensitive sensitive =
                    LocalPreprocessor.redactForOverride(content);
            if (!sensitive.findings.isEmpty()) {
                try {
                    message.put("content", sensitive.text);
                    hits++;
                } catch (Exception ignored) {
                }
            }
        }
        return hits;
    }

    /**
     * thinking Request for all assistant Tool Wheel Full reasoning. The old no-thinking cycle.
     * No safe return; in'Request copy'Cut these wheels and keep them close. tool Results and summary
     * Write system prompts that the history of the storage remains unchanged.
     */
    private static int pruneIncompleteThinkingToolCalls(JSONArray history) {
        int pruned = 0;
        for (int i = 1; i < history.length(); ) {
            JSONObject message = history.optJSONObject(i);
            if (message == null || !"assistant".equals(MessageProtocol.role(message))) {
                i++;
                continue;
            }
            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls == null || calls.length() == 0) {
                i++;
                continue;
            }
            String reasoning = message.optString("reasoning_content", "").trim();
            if (reasoning.isEmpty()) {
                reasoning = message.optString("reasoning", "").trim();
            }
            if (!reasoning.isEmpty() && !reasoning.contains("Early thinking has folded.")) {
                i++;
                continue;
            }
            history.remove(i);
            while (i < history.length()) {
                JSONObject next = history.optJSONObject(i);
                if (next != null && "tool".equals(MessageProtocol.role(next))) {
                    history.remove(i);
                } else {
                    break;
                }
            }
            pruned++;
        }
        if (pruned > 0) {
            JSONObject system = history.optJSONObject(0);
            if (system != null && "system".equals(MessageProtocol.role(system))) {
                try {
                    system.put("content", JsonText.content(system)
                            + "\n\n[History Tip] The request for this round has been omitted to keep the depth of thought in mind "
                            + pruned + " A missing one. reasoning the old tool wheel times.");
                } catch (Exception ignored) {
                }
            }
        }
        return pruned;
    }

    private PreparedContext prepareContext() {
        JSONArray history = new JSONArray();
        int version;
        synchronized (messages) {
            for (JSONObject message : messages) history.put(message);
            history = MessageProtocol.copy(history);
            version = messagesVersion;
        }
        Conversation settings = requestSettings();
        JSONArray schemas = MessageProtocol.copy(toolDefinitions());
        int reserve = settings == null ? prefs.maxTokens() : settings.maxTokens;
        int budget = contextBudget();
        if (hasImage(history)) {
            // A vision request carries the image plus the normal output reserve;
            // 12k Lite windows are too small and would discard the attachment.
            budget = Math.max(budget, 16000);
        }
        // The output reserve must not eat the window: without this, a 12k budget
        // minus a 4k reserve leaves the model with almost no history to reason on.
        reserve = Math.min(reserve, Math.max(1024, budget / 8));
        // Consumed tool payloads and synthetic system nudges are folded to one line
        // in the request only; the stored transcript keeps them in full.
        history = reclaimRequestHistory(history);
        if (AppPrefs.THINKING_ON.equals(runThinking)) {
            pruneIncompleteThinkingToolCalls(history);
        }
        int scrubbed = scrubRequestHistory(history);
        if (scrubbed > 0) {
            Log.i("MobileAgent", "request scrubbed secrets in " + scrubbed + " messages");
        }
        ContextCompiler.Result compiled = ContextCompiler.compile(history, schemas,
                budget, reserve, conversation == null ? "" : conversation.contextDigest,
                ContextCompiler.ESTIMATE);
        if (compiled.error != null) {
            // Overflow must never become a deadlock: the old behaviour kept refusing
            // the request ("Original text reserved, not sent to model"), so every retry failed by the same
            // few dozen tokens. Shrink what is actually elastic - the output reserve
            // first, then the history - and send anyway.
            int[] fallbackReserves = {Math.max(1024, reserve / 2), 1024, 512};
            for (int attempt = 0; attempt < fallbackReserves.length && compiled.error != null;
                    attempt++) {
                ContextCompiler.Result tighter = ContextCompiler.compile(
                        attempt == 0 ? history : foldHard(history), schemas, budget,
                        fallbackReserves[attempt],
                        conversation == null ? "" : conversation.contextDigest,
                        ContextCompiler.ESTIMATE);
                if (tighter.error == null) {
                    compiled = tighter;
                    reserve = fallbackReserves[attempt];
                    addEntry(Entry.SYSTEM, "Context close to upper limit: Output reserved to " + reserve
                            + " token" + (attempt == 0 ? "" : "And compress earlier content")
                            + ", This round is sent as usual (the original language in the record remains unchanged).", false);
                }
            }
        }
        if (compiled.error != null) {
            ContextCompiler.Result tight = ContextCompiler.compile(foldHard(history), schemas,
                    budget, 256, conversation == null ? "" : conversation.contextDigest,
                    ContextCompiler.ESTIMATE);
            if (tight.error == null) {
                compiled = tight;
                reserve = 256;
                addEntry(Entry.SYSTEM, "(a) Extreme window tension: the current round only retains the smallest output space and the answers may be short;"
                        + "It is proposed that a new dialogue or context window be created.", false);
            }
        }
        if (compiled.error != null && prefs.allowOverLimit()) {
            // Last resort that only the user can authorise: build the request as if the
            // window were big enough and let the provider decide.
            compiled = ContextCompiler.compile(history, schemas, Integer.MAX_VALUE / 4,
                    reserve, conversation == null ? "" : conversation.contextDigest,
                    ContextCompiler.ESTIMATE);
            addEntry(Entry.SYSTEM, "You have already launched the Breaking the Maximum: this round is sent in full context, and the round is being sent to the government of the United States of America."
                    + "If the service provider refuses, please turn to the context window or open a new dialogue.", false);
        }
        if (compiled.error != null && compiled.error.contains("INVALID_MESSAGE_SEQUENCE")) {
            dumpBlockedRequest(compiled.error);
        }
        PreparedContext result = new PreparedContext(compiled.messages(), schemas, settings,
                compiled.totalTokens, compiled.droppedMessages,
                compiled.outputReserve + compiled.safetyMargin, compiled.error);
        cachedContextInfo = new ContextInfo(result.tokens, budget, result.dropped,
                result.reserved);
        cachedContextVersion = version;
        return result;
    }

    /** Serial blockages must be left on site: an interface screenshot alone simply does not determine which history is broken. */
    private void dumpBlockedRequest(String error) {
        try {
            File dir = new File(workspace.baseRoot(), ".agent/diagnostics");
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            JSONObject dump = new JSONObject();
            dump.put("error", error);
            dump.put("time", System.currentTimeMillis());
            dump.put("conversationId", conversation == null ? "" : conversation.id);
            JSONArray raw = new JSONArray();
            synchronized (messages) {
                for (JSONObject item : messages) {
                    raw.put(item);
                }
            }
            dump.put("raw", raw);
            try {
                dump.put("repaired", MessageProtocol.repairDanglingCalls(
                        MessageProtocol.copy(raw)));
            } catch (Exception ignored) {
            }
            File target = new File(dir, "blocked-" + System.currentTimeMillis() + ".json");
            FileOutputStream stream = new FileOutputStream(target);
            try {
                stream.write(dump.toString().getBytes("UTF-8"));
            } finally {
                stream.close();
            }
            Log.e("AgentUsage", "blockedRequestDump=" + target);
        } catch (Exception ignored) {
        }
    }



    private static boolean hasImage(JSONArray history) {
        for (int i = 0; i < history.length(); i++) {
            JSONObject message = history.optJSONObject(i);
            if (message == null) continue;
            JSONArray parts = message.optJSONArray("content");
            if (parts == null) continue;
            for (int p = 0; p < parts.length(); p++) {
                JSONObject part = parts.optJSONObject(p);
                if (part != null && "image_url".equals(part.optString("type", ""))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0;
        int asciiRun = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 128) {
                asciiRun++;
                if (asciiRun >= 4) {
                    tokens += 1;
                    asciiRun = 0;
                }
            } else {
                if (asciiRun > 0) {
                    tokens += 1;
                    asciiRun = 0;
                }
                tokens += 1;
            }
        }
        if (asciiRun > 0) {
            tokens += 1;
        }
        return (int) Math.ceil(tokens);
    }



    private static void appendBullets(StringBuilder builder,
                                      java.util.LinkedHashSet<String> values,
                                      int max, int charBudget) {
        int used = 0;
        int count = 0;
        for (String value : values) {
            if (count >= max || used >= charBudget) {
                break;
            }
            builder.append("- ").append(value).append('\n');
            used += value.length();
            count++;
        }
    }

    private static boolean looksImportant(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("I have to.") || lower.contains("Don't.") || lower.contains("Ban")
                || lower.contains("Default") || lower.contains("A promise.") || lower.contains("Rule")
                || lower.contains("Decision") || lower.contains("Attention.") || lower.contains("Hold on.")
                || lower.contains("must") || lower.contains("never") || lower.contains("always")
                || lower.contains("default") || lower.contains("require");
    }

    private static void collectPaths(Set<String> files, String arguments) {
        if (arguments == null || arguments.isEmpty() || files.size() >= 24) {
            return;
        }
        try {
            JSONObject args = new JSONObject(arguments);
            String path = args.optString("path", "");
            if (path.isEmpty()) {
                path = args.optString("project", "");
            }
            if (!path.isEmpty() && path.length() <= 160) {
                files.add(path);
            }
        } catch (Exception ignored) {
        }
    }


    private static String messageText(JSONObject message) {
        return JsonText.content(message).trim();
    }

    private static String oneLine(String text, int max) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    String compactNow() {
        return compactInternal(false);
    }

    private String compactInternal(boolean duringRun) {
        if (running && !duringRun) return "Task execution, cannot modify request view";
        PreparedContext prepared = prepareContext();
        if (prepared.error != null) return "Could not close temporary folder: %s" + prepared.error;
        return "Request View omission " + prepared.dropped
                + " an historical message; original text, attachments and call results retained, no records of the dialogue deleted";
    }

    String initProjectRules() {
        try {
            File file = workspace.resolve("AGENTS.md");
            if (file.isFile()) {
                return "AGENTS.md Existing, Uncovered";
            }
            String template = "# AGENTS.md\n\n"
                    + "## Item\n"
                    + "- Objectives:\n"
                    + "- Main directory:\n\n"
                    + "## A promise.\n"
                    + "- Answering language: Chinese\n"
                    + "- Build and validate:\n"
                    + "- Code style:\n\n"
                    + "## Ban\n"
                    + "- Delete, overwrite or install without confirmation by the user\n"
                    + "- Submit key, privacy data or external dispatch\n";
            writeTextToFile(file, template);
            return "Created AGENTS.md, Add item engagements to editor";
        } catch (Exception e) {
            return "Create AGENTS.md Failed:"
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Pause/Stop request exceeding 3.5 Force unlocking the window when the second is still out, so that the interface is not stuck.'Pauseing/Stopping'.  */
    private void armStopWatchdog() {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (runLock) {
                    if (!running || (!pauseRequested && !stopRequested)) {
                        return;
                    }
                    runGeneration++;
                }
                boolean wasPause = pauseRequested;
                if (wasPause && conversation != null) {
                    conversation.paused = true;
                    conversation.pausedAt = System.currentTimeMillis();
                }
                addEntry(Entry.SYSTEM, wasPause
                        ? "The current round has been suspended (responding request is being closed and can continue)."
                        : "The current round has been stopped (regarding request is being closed and can continue).", false);
                finishRun(true);
            }
        }, 3500L);
    }

    /** Submit the cloud run loop; a rejected executor must not leave running=true forever. */
    private boolean submitRunLoop(final int gen) {
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        runLoop(gen);
                    } catch (Throwable error) {
                        Log.e("MobileAgent", "run loop crashed", error);
                        addEntry(Entry.ERROR, "Agent End of crash:"
                                + (error.getMessage() == null
                                ? error.getClass().getSimpleName() : error.getMessage()), false);
                    } finally {
                        if (running && gen == runGeneration) {
                            finishRun(true);
                        }
                    }
                }
            });
            return true;
        } catch (Throwable error) {
            addEntry(Entry.ERROR, "Synchronising '%s'"
                    + (error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage()), false);
            supervisor.fail(workTicketId, "Synchronising '%s'");
            finishRun(false);
            return false;
        }
    }

    /** Run a live watch dog: threaded death/Not started/We have to finish the overtime. We can't let the whole thing go. running Permanently stuck to other sessions. */
    private void armRunWatchdog() {
        final int token;
        synchronized (runLock) {
            token = ++watchdogToken;
        }
        final long started = System.currentTimeMillis();
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running || token != watchdogToken) {
                    return;
                }
                Thread worker = workerThread;
                long elapsed = System.currentTimeMillis() - started;
                boolean dead = worker != null && !worker.isAlive();
                boolean neverStarted = worker == null && elapsed >= 20000L;
                boolean overtime = elapsed >= MAX_RUN_MS + 5L * 60L * 1000L;
                if (dead || neverStarted || overtime) {
                    addEntry(Entry.ERROR, dead
                            ? "The execution thread has been withdrawn but has not been completed and is automatically restored to serviceable status."
                            : neverStarted
                            ? "The task thread has not been started and is automatically reset."
                            : "Tasks exceeding the established deadline remain open and the current round has been terminated.", false);
                    runGeneration++;
                    finishRun(true);
                    return;
                }
                main.postDelayed(this, 15000L);
            }
        }, 15000L);
    }

    String lastRunOutcome() {
        return lastRunOutcome;
    }

    long lastRunTokens() {
        return lastRunTokens;
    }

    String lastRunError() {
        return lastRunErrorText;
    }

    void shutdown() {
        try {
            executor.shutdownNow();
        } catch (Throwable ignored) {
        }
        try {
            saveExecutor.shutdownNow();
        } catch (Throwable ignored) {
        }
    }

    private String lastAssistantText() {
        synchronized (entriesLock) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry entry = entries.get(i);
                if (entry != null && entry.type == Entry.ASSISTANT
                        && entry.text != null && !entry.text.trim().isEmpty()) {
                    return entry.text.trim();
                }
            }
        }
        return "";
    }

    private void finishRun(boolean stopService) {
        boolean pausedNow;
        synchronized (runLock) {
            pausedNow = conversation != null && conversation.paused;
            lastRunOutcome = runOutcome == null || runOutcome.isEmpty()
                    ? (pausedNow ? "paused" : "stopped") : runOutcome;
            lastRunTokens = runPromptTokens + runCompletionTokens;
            lastRunErrorText = runState == null ? "" : runState.lastError;
            running = false;
            stopRequested = false;
            pauseRequested = false;
            watchdogToken++;
        }
        if (!stopHooksFired && hooks != null) {
            stopHooksFired = true;
            String outcome = runOutcome == null || runOutcome.isEmpty()
                    ? (pausedNow ? "paused" : "stopped") : runOutcome;
            hooks.stopAsync(outcome, currentStep,
                    runPromptTokens + runCompletionTokens,
                    lastAssistantText(),
                    runState == null ? "" : runState.lastError,
                    runState == null ? runInput : runState.goal,
                    currentRunId,
                    conversation == null ? "" : conversation.id);
        }
        resetExecutorIfStuck();
        if (!pausedNow) {
            currentStep = 0;
        }
        if (conversation != null) {
            conversation.runActive = false;
        }
        if (runPromptTokens > 0 || runCompletionTokens > 0) {
            int hitRate = runPromptTokens > 0
                    ? (int) (runCachedTokens * 100 / runPromptTokens) : 0;
            int working;
            synchronized (messages) {
                working = messages.size();
            }
            long archivedBytes = conversation == null ? 0L
                    : ContextArchive.bytes(workspace.baseRoot(), conversation.id);
            String usage = "Current run token: Input " + runPromptTokens
                    + "(Cache Cache " + runCachedTokens + ", " + hitRate + "%), Output "
                    + runCompletionTokens + ", Tools " + runToolCalls
                    + " Number of times; context Work Set " + working + " Article / Archive "
                    + archivedBytes + " bytes.";
            addEntry(Entry.SYSTEM, usage, false);
            Log.i("AgentUsage", usage);
        }
        saveCurrent();
        try {
            WorkStore.Ticket ticket = supervisor.store.find(workTicketId);
            if (ticket != null && WorkStore.RUNNING.equals(ticket.status)) {
                if (pausedNow) supervisor.end(workTicketId, WorkStore.WAITING, "The task has been suspended.");
                else supervisor.fail(workTicketId, "The mandate ended without results.");
            }
        } catch (Throwable ignored) {
        }
        if (stopService && !headless) {
            KeepAliveService.stop(appContext);
        }
        notifyUpdate();
    }

    /**
     * Stop/The main line is suspended: if the old execution route is still not out (network or tools are stuck), the main line is closed.
     * Interrupt it and replace it with a new one-way implementer, ensuring that the new tasks that follow will not be behind the zombie line.
     */
    private void resetExecutorIfStuck() {
        Thread worker = workerThread;
        if (worker == null || !worker.isAlive() || worker == Thread.currentThread()) {
            return;
        }
        worker.interrupt();
        ExecutorService previous = executor;
        executor = Executors.newSingleThreadExecutor();
        previous.shutdownNow();
        Log.w("MobileAgent", "replaced stuck worker thread");
    }

    void recoverInterruptedRun() {
        if (recoveryChecked) {
            return;
        }
        recoveryChecked = true;
        if (running) {
            return;
        }
        Conversation stored = conversations.findLatestInterrupted();
        if (stored == null) {
            return;
        }
        boolean current = conversation != null && conversation.id.equals(stored.id);
        Conversation target = current ? conversation : stored;
        long lastActive = Math.max(target.updatedAt, target.runStartedAt);
        if (lastActive > 0
                && System.currentTimeMillis() - lastActive > AUTO_RESUME_MAX_AGE_MS) {
            target.runActive = false;
            pauseInterrupted(target, current,
                    "Previous mission interrupted beyond " + (AUTO_RESUME_MAX_AGE_MS / 60000L)
                            + " Minutes, not automatically recovered; point 'continue' can be recovered manually.");
            return;
        }
        int attempt = target.autoResumeCount + 1;
        target.runActive = false;
        target.autoResumeCount = attempt;
        target.updatedAt = System.currentTimeMillis();
        if (current) {
            saveCurrent();
        } else {
            conversations.save(target);
        }
        Log.i("AgentRecover", "interrupted conversation=" + target.id
                + " attempt=" + attempt + " auto=" + prefs.autoResumeInterrupted());
        if (!prefs.autoResumeInterrupted()) {
            pauseInterrupted(target, current,
                    "The last mission was interrupted; 'Auto-Recovery after Interruption' was closed and the dots were restored.");
            return;
        }
        if (attempt > MAX_AUTO_RESUME) {
            pauseInterrupted(target, current,
                    "Last job interrupted " + (attempt - 1) + " The automatic recovery has stopped on a number of occasions; the point 'continues' to recover manually.");
            return;
        }
        if (!current) {
            pauseInterrupted(target, false,
                    "The last mission was interrupted; the point after opening this conversation resumed 'continue'.");
            return;
        }
        if (isLocalModel()) {
            pauseInterrupted(target, true,
                    "Last job interrupted; local models do not support stop-and-go answers, please retransmit.");
            return;
        }
        if (prefs.apiKeyFor(target.modelService, target.baseUrl).isEmpty()) {
            pauseInterrupted(target, true,
                    "Previous task interrupted; current model service not configured API Key, Please fill in before point 'go on.'");
            return;
        }
        if (messages.isEmpty()) {
            pauseInterrupted(target, true,
                    "The last mission was interrupted and there was no context to restore; please retransmit the task.");
            return;
        }
        target.paused = true;
        target.pausedAt = System.currentTimeMillis();
        addEntry(Entry.SYSTEM, "Last job detected interrupted and is being automatically resumed (No. "
                + attempt + " - I'm not sure.", false);
        saveCurrent();
        resumeTaskInternal(true);
    }

    private void pauseInterrupted(Conversation target, boolean current, String note) {
        target.paused = true;
        target.pausedAt = System.currentTimeMillis();
        if (current) {
            addEntry(Entry.SYSTEM, note, false);
            saveCurrent();
            notifyUpdate();
        } else {
            appendStoredEntry(target, note);
            conversations.save(target);
        }
    }

    private void appendStoredEntry(Conversation target, String text) {
        if (target == null || text == null || text.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject entry = new JSONObject();
            entry.put("type", Entry.SYSTEM);
            entry.put("text", text.trim());
            entry.put("time", System.currentTimeMillis());
            target.entries.put(entry);
            target.updatedAt = System.currentTimeMillis();
        } catch (Exception ignored) {
        }
    }

    private void scheduleReview(int steps, long elapsedMs, boolean autoResumed) {
        if (prefs.liteHarness()) return;
        Conversation current = conversation;
        if (current == null) {
            return;
        }
        // Down-to-back frequency: short-term tasks are not repeated; maximum per hour of the same session 30 One automatic round-up of minutes, avoiding an additional cloud call for each conversation.
        if (steps < 3) {
            return;
        }
        long now = System.currentTimeMillis();
        if (current.lastReviewAt > 0 && now - current.lastReviewAt < 30L * 60L * 1000L) {
            Log.i("AgentEvolve", "review skipped (cooldown)");
            return;
        }
        List<Entry> snapshot;
        synchronized (entriesLock) {
            snapshot = new ArrayList<>(entries);
        }
        if (snapshot.isEmpty()) {
            return;
        }
        String digest = buildReviewDigest(current, snapshot, steps, elapsedMs, autoResumed);
        if (digest.trim().isEmpty()) {
            return;
        }
        final String conversationId = current.id;
        final Workspace reviewWorkspace = workspace;
        final String existing = buildExistingSummary(reviewWorkspace);
        current.lastReviewAt = System.currentTimeMillis();
        saveCurrent();
        Log.i("AgentEvolve", "schedule review conversation=" + conversationId
                + " digest=" + digest.length());
        EvolutionEngine.review(appContext, prefs, reviewWorkspace, current,
                conversationId, digest, existing, new EvolutionEngine.Callback() {
                    @Override
                    public void onDone(EvolutionEngine.Result result) {
                        String text = result.error != null && !result.error.trim().isEmpty()
                                ? "Rewind not completed:" + result.error
                                : result.summary;
                        appendReviewEntry(conversationId, text);
                    }
                });
    }

    private void appendReviewEntry(String conversationId, String text) {
        if (conversationId == null || text == null || text.trim().isEmpty()) {
            return;
        }
        if (conversation != null && conversation.id.equals(conversationId)) {
            addEntry(Entry.SYSTEM, text.trim(), false);
            conversation.lastReviewAt = System.currentTimeMillis();
            saveCurrent();
            notifyUpdate();
            return;
        }
        Conversation stored = conversations.load(conversationId);
        if (stored == null) {
            return;
        }
        appendStoredEntry(stored, text.trim());
        stored.lastReviewAt = System.currentTimeMillis();
        conversations.save(stored);
    }

    private String buildReviewDigest(Conversation current, List<Entry> snapshot,
                                     int steps, long elapsedMs, boolean autoResumed) {
        List<String> users = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> tools = new ArrayList<>();
        String answer = "";
        boolean built = false;
        for (Entry entry : snapshot) {
            if (entry == null) {
                continue;
            }
            String text = entry.text == null ? "" : entry.text.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (entry.type == Entry.USER) {
                users.add(shorten(text, 260));
            } else if (entry.type == Entry.ERROR) {
                errors.add(shorten(text, 220));
            } else if (entry.type == Entry.ASSISTANT) {
                answer = shorten(text, 500);
            } else if (entry.type == Entry.TOOL) {
                if (text.startsWith("Call ")) {
                    String rest = text.substring(3).trim();
                    int space = rest.indexOf(' ');
                    String name = space > 0 ? rest.substring(0, space) : rest;
                    if (!name.isEmpty() && !tools.contains(name)) {
                        tools.add(name);
                    }
                }
                if (text.contains("Build APK") || text.contains("Install APK")
                        || text.contains("BUILD_OK")) {
                    built = true;
                }
            }
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Workspace:").append(workspace.label()).append('\n');
        builder.append("Round:").append(steps).append(", Time-consuming:")
                .append(Math.max(0L, elapsedMs / 1000L)).append(" sec\n");
        if (!tools.isEmpty()) {
            builder.append("Use tools:");
            for (int i = 0; i < tools.size() && i < 12; i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(tools.get(i));
            }
            builder.append('\n');
        }
        if (built) {
            builder.append("This is about building/Install APK. \n");
        }
        if (!users.isEmpty()) {
            builder.append("User request:\n");
            int from = Math.max(0, users.size() - 3);
            for (int i = from; i < users.size(); i++) {
                builder.append("- ").append(users.get(i)).append('\n');
            }
        }
        if (!answer.isEmpty()) {
            builder.append("Final reply:").append(answer).append('\n');
        }
        if (!errors.isEmpty()) {
            builder.append("Error:\n");
            int from = Math.max(0, errors.size() - 5);
            for (int i = from; i < errors.size(); i++) {
                builder.append("- ").append(errors.get(i)).append('\n');
            }
        }
        if (autoResumed) {
            builder.append("Note: This is an automatic resumption of operation after the interruption of the process; the result of the missing tool or rerun may be caused by the interruption,"
                    + "Do not judge the tool as flawed.\n");
        }
        return reviewRedact(shorten(builder.toString(), 2600));
    }

    private String buildExistingSummary(Workspace target) {
        if (target == null) {
            return "";
        }
        Capabilities caps = new Capabilities(target);
        StringBuilder builder = new StringBuilder();
        String memory = caps.loadMemory().trim();
        if (!memory.isEmpty()) {
            builder.append("Memory:").append(shorten(memory, 700)).append('\n');
        }
        List<Capabilities.SkillInfo> skills = caps.listSkills();
        if (!skills.isEmpty()) {
            builder.append("Skills:");
            for (int i = 0; i < skills.size() && i < 20; i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(skills.get(i).name);
            }
            builder.append('\n');
        }
        List<String> agents = caps.listAgentNames();
        if (!agents.isEmpty()) {
            builder.append("SonAgent: ");
            for (int i = 0; i < agents.size() && i < 10; i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(agents.get(i));
            }
            builder.append('\n');
        }
        List<String> knowledge = caps.listKnowledgeFiles();
        if (!knowledge.isEmpty()) {
            builder.append("Knowledge base:");
            for (int i = 0; i < knowledge.size() && i < 12; i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(knowledge.get(i));
            }
            builder.append('\n');
        }
        return shorten(builder.toString().trim(), 1600);
    }

    private static String reviewRedact(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.replaceAll("sk-[A-Za-z0-9_-]{8,}", "sk-***");
        return clean.replaceAll("(?i)(api[_ ]?key|token|password|Password|Password)\\s*[:: =]\\s*\\S+",
                "$1:***");
    }

    private JSONArray toolDefinitions() {
        String key = (activeToolScope == null ? "" : activeToolScope.fingerprint())
                + "|" + (activeToolNames == null ? "" : activeToolNames.toString())
                + "|" + (activeMcpTools == null ? 0 : activeMcpTools.size());
        if (cachedToolDefinitions != null && key.equals(cachedToolDefinitionsKey)) {
            return MessageProtocol.copy(cachedToolDefinitions);
        }
        JSONArray merged = new JSONArray();
        JSONArray builtIn = tools.schemas(activeToolScope.schemaFilter());
        for (int i = 0; i < builtIn.length(); i++) {
            JSONObject tool = builtIn.optJSONObject(i);
            if (tool != null) {
                merged.put(tool);
            }
        }
        for (McpManager.Tool tool : activeMcpTools) {
            if (isToolAllowed(tool.exposedName)) {
                merged.put(tool.definition);
            }
        }
        cachedToolDefinitions = MessageProtocol.copy(merged);
        cachedToolDefinitionsKey = key;
        return merged;
    }

    private boolean isToolAllowed(String name) {
        if (activeToolScope.allows(name)) return true;
        // Discovered MCP tools are dynamic: mcp_discover is the capability that grants
        // this run access to tools already loaded and validated by the MCP directory.
        return name != null && name.startsWith("mcp_") && !"mcp_discover".equals(name)
                && activeToolScope.allows("mcp_discover");
    }

    private void prepareHooks() {
        hooks = null;
        if (!prefs.allowHooks()) {
            return;
        }
        hooks = HookManager.load(appContext, workspace, prefs);
        if (hooks != null && hooks.error != null) {
            addEntry(Entry.SYSTEM, "Hooks Not in force:" + hooks.error, false);
        }
    }

    private void runPostHooks(String name, JSONObject args, String result) {
        if (hooks == null) {
            return;
        }
        String error = toolError(result);
        hooks.afterTool(name, args, result, error == null, error,
                runState == null ? runInput : runState.goal,
                currentStep, currentRunId,
                conversation == null ? "" : conversation.id);
    }

    private String executeTool(String name, JSONObject args,
                               List<ScreenCaptureService.CaptureResult> screenshots) {
        if (loadedAdapters.containsKey(name)) consumeAdapter(name);
        if (visualTask && isEditTool(name)
                && visualIterations >= VISUAL_MAX_ITERATIONS) {
            return errorJson("Visual expression has reached " + VISUAL_MAX_ITERATIONS
                    + " Round (each change)+Cut-off round. Stop changing, confirm and deliver the best version. "
                    + "index.best.html / screenshots/best.png; If the user specifically requests to continue,"
                    + "Please let the user send a new command.");
        }
        if (visualTask && visualBaselineCaptured && isEditTool(name)
                && visualEditsSinceShot >= 6) {
            return errorJson("Changed consecutively 6 No cross-references. Call first browser_screenshot, "
                    + "Compare with reference charts and best versions; only a better version is retained and continued.");
        }
        try {
            if (strategyLedger != null && name != null && !name.startsWith("mcp_")) {
                String argsText = args == null ? "{}" : args.toString();
                String actionKey = StrategyLedger.actionKey(name, argsText);
                if (strategyLedger.isBlocked(actionKey)
                        && !StrategyLedger.isVisualCapture(actionKey)) {
                    return errorJson("Status monitor has blocked repeat actions:" + actionKey + "; Change of strategy, please.");
                }
                String strategy = StrategyLedger.strategyOf(name, argsText);
                if (strategyLedger.isStrategyBlocked(strategy)
                        && !StrategyLedger.isVisualCapture(actionKey)) {
                    return errorJson("State surveillance has blocked strategy:" + strategy + "; Change of strategy, please.");
                }
            }
            if (!isToolAllowed(name)) {
                String loadError = ensureAdapterLoaded(name);
                if (loadError != null) {
                    return errorJson(loadError);
                }
            }
            String policyError = checkToolPolicy(name, args);
            if (policyError != null) {
                return errorJson(policyError);
            }
            if (hooks != null) {
                HookManager.PreResult pre = hooks.beforeTool(name, args,
                        runState == null ? runInput : runState.goal,
                        currentStep, currentRunId,
                        conversation == null ? "" : conversation.id);
                if (pre.deny) {
                    return errorJson("Hooks Deny execution " + name + ": " + pre.reason);
                }
                if (pre.args != null) {
                    args = pre.args;
                }
            }
            roundShots = screenshots == null ? new ArrayList<>() : screenshots;
            if (!name.startsWith("mcp_") || "mcp_discover".equals(name)) {
                ToolRegistry.Invocation call;
                try {
                    call = tools.prepare(name, args == null ? new JSONObject() : args, workspace.root());
                } catch (Exception e) {
                    return errorJson("Parameters/Resource validation failed:" + e.getMessage());
                }
                try {
                    runPhase = describeStep(name, String.valueOf(call.args()));
                runDetail = "";
                    Object value = tools.run(call);
                    String result = value instanceof String
                            ? (String) value : okJson(String.valueOf(value));
                    runPostHooks(name, args, result);
                    return result;
                } catch (Exception e) {
                    String result = errorJson(e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage());
                    runPostHooks(name, args, result);
                    return result;
                }
            }
            for (McpManager.Tool tool : activeMcpTools) {
                if (!tool.exposedName.equals(name)) {
                    continue;
                }
                try {
                    String result = okJson(limit(mcp.callTool(tool, args), MAX_TOOL_OUTPUT));
                    runPostHooks(name, args, result);
                    return result;
                } catch (Exception e) {
                    String message = e.getMessage();
                    String result = errorJson("MCP Call failed:"
                            + (message == null || message.trim().isEmpty()
                            ? e.getClass().getSimpleName() : message));
                    runPostHooks(name, args, result);
                    return result;
                }
            }
            return errorJson("Unknown tool:" + name);
        } catch (Exception e) {
            return errorJson(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }


    private String mcpDiscover(String query, String server) {
        if (!mcp.hasEnabledServers()) {
            return errorJson("No enabled MCP Servers");
        }
        String needle = query == null ? "" : query.trim();
        String serverName = server == null ? "" : server.trim();
        if (needle.isEmpty() && serverName.isEmpty()) {
            return okJson("Available MCP Server:" + joinPathList(mcp.enabledServerNames())
                    + ". Provision server and query , and then load the tool as required.");
        }
        try {
            McpManager.Discovery discovery = mcp.discover(serverName, needle, currentRunId);
            if (discovery.needsServerSelection) {
                StringBuilder candidates = new StringBuilder();
                for (McpDirectory.ServerEntry entry : discovery.candidates) {
                    candidates.append("- ").append(entry.name)
                            .append(entry.knownToolCount > 0 ? "(" + entry.knownToolCount + " (a known tool)" : "")
                            .append('\n');
                }
                return okJson("Please specify server Afterward try:\n" + candidates.toString().trim());
            }
            int added = 0;
            StringBuilder result = new StringBuilder();
            for (McpManager.Tool tool : discovery.tools) {
                boolean replaced = false;
                for (int i = 0; i < activeMcpTools.size(); i++) {
                    if (activeMcpTools.get(i).exposedName.equals(tool.exposedName)) {
                        activeMcpTools.set(i, tool);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    activeMcpTools.add(tool);
                    cachedToolDefinitions = null;
                    cachedToolDefinitionsKey = "";
                    added++;
                }
                result.append("- ").append(tool.exposedName)
                        .append("(").append(tool.server.name).append(")\n");
                if (result.length() > 4000) break;
            }
            if (result.length() == 0) {
                return okJson("The server doesn't match. MCP Tools");
            }
            return okJson("Loaded as required " + added + " individual MCP Tools:\n" + result.toString().trim());
        } catch (Exception e) {
            String message = e.getMessage();
            return errorJson("MCP Found failure:" + (message == null || message.trim().isEmpty()
                    ? e.getClass().getSimpleName() : message));
        }
    }


    private String normalizeRepoPath(String path) {
        if (path == null || path.trim().isEmpty()) return "";
        try {
            File file = workspace.resolve(path);
            return workspace.relativePath(file);
        } catch (Exception e) {
            return path;
        }
    }

    private String repoIndex(boolean rebuild) {
        CodeIndex.Stats stats = CodeIndex.get(workspace).ensure(rebuild);
        return okJson("Code index updated: File " + stats.files + " Numbers " + stats.symbols
                + " One, source code. " + (stats.bytes / 1024) + " KB, Build "
                + stats.buildMs + "ms, Skip " + stats.skipped + " Non-source/Big file.");
    }

    private String repoSearch(String query, String path, int maxResults) {
        if (query == null || query.trim().isEmpty()) {
            return errorJson("Missing search keyword");
        }
        int max = Math.max(1, Math.min(20, maxResults));
        CodeIndex index = CodeIndex.get(workspace);
        index.ensure(false);
        List<CodeIndex.Hit> hits = index.search(query, normalizeRepoPath(path), "", max);
        if (hits.isEmpty()) {
            return okJson("No match found:" + query);
        }
        StringBuilder builder = new StringBuilder();
        for (CodeIndex.Hit hit : hits) {
            builder.append("[").append(hit.doc.path).append(':').append(hit.line)
                    .append("] Relevance ").append(hit.score).append('\n')
                    .append(hit.snippet).append("\n\n");
            if (builder.length() > 9000) {
                break;
            }
        }
        return okJson(shorten(builder.toString().trim(), 9000));
    }

    private String repoMap(String path, int maxFiles) {
        int max = Math.max(1, Math.min(300, maxFiles));
        CodeIndex index = CodeIndex.get(workspace);
        String scope = normalizeRepoPath(path);
        String map = index.map(scope, max);
        if ("INDEX_NOT_READY".equals(map)) {
            index.ensure(false);
            map = index.map(scope, max);
        }
        if ("INDEX_NOT_READY".equals(map)) {
            return errorJson("Code index construction not completed; please try again later repo_map, Or first. inspect View the directory.");
        }
        if (map.isEmpty()) {
            return okJson("No indexable source file in the workspace");
        }
        return okJson(shorten(map, 8000));
    }

    private String applyPatch(String path, String patch, boolean dryRun) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            return errorJson("Missing file path");
        }
        if (patch == null || patch.trim().isEmpty()) {
            return errorJson("Missing patch Contents");
        }
        File file = workspace.resolve(path);
        if (!file.isFile()) {
            return errorJson("File does not exist:" + path);
        }
        if (file.length() > MAX_EDIT_BYTES) {
            return errorJson("File Over 8MB, Please reduce the patch.");
        }
        String beforeHash = FileTxn.hash(file);
        String text = new String(readBytes(file, MAX_EDIT_BYTES), StandardCharsets.UTF_8);
        PatchTool.Result result;
        try {
            result = PatchTool.apply(text, patch, workspace.relativePath(file));
        } catch (Exception e) {
            String message = e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage();
            if (message.contains("PAIRED_HEADERS_REQUIRED")) {
                message += "(Patch should be @@ Previously provided --- a/<path> and +++ b/<path>; "
                        + "Or just send it. @@ hunk, The system works. path Auto-complementing of parameters)";
            }
            return errorJson("Patch not applied:" + message);
        }
        if (dryRun) {
            return okJson("Patches are checked through:" + result.hunks + " individual hunk, Not written");
        }
        String checkpoint;
        try {
            checkpoint = commitBytes(file, result.text.getBytes(StandardCharsets.UTF_8),
                    beforeHash);
        } catch (Exception e) {
            return errorJson("Patches were not applied and the original document was unchanged:" + e.getMessage());
        }
        return okJson("Modified with Patch " + workspace.relativePath(file) + ": "
                + result.hunks + " individual hunk, Checkpoints " + checkpoint);
    }

    private String listFiles(String path) throws Exception {
        File base;
        if (path == null || path.trim().isEmpty()) {
            base = workspace.root();
        } else {
            base = workspace.resolve(path);
            if (!base.isDirectory()) {
                return errorJson("Not Directory:" + path);
            }
        }
        List<File> files = workspace.listRecursive(base, MAX_LIST_FILES);
        StringBuilder builder = new StringBuilder();
        builder.append("Workspace:").append(workspace.label()).append('\n');
        builder.append("Contents").append(base.equals(workspace.root()) ? "/" : workspace.relativePath(base))
                .append('\n');
        if (files.isEmpty()) {
            builder.append("(Empty)\n");
        } else {
            for (File file : files) {
                if (files.indexOf(file) >= MAX_LIST_FILES) {
                    break;
                }
                String relative = workspace.relativePath(file);
                builder.append(file.isDirectory() ? "[Contents] " : "[Documentation] ")
                        .append(relative);
                if (file.isFile()) {
                    builder.append("  ").append(file.length()).append(" bytes");
                }
                builder.append('\n');
            }
            if (files.size() >= MAX_LIST_FILES) {
                builder.append("...List stopped\n");
            }
        }
        return okJson(limit(builder.toString(), MAX_TOOL_OUTPUT));
    }

    private String readFile(String path, int offset, int limit) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            return errorJson("Missing file path");
        }
        File file = workspace.resolve(path);
        if (!file.isFile()) {
            return errorJson("File does not exist:" + path);
        }
        long size = file.length();
        if (size > MAX_TEXT_READ_BYTES) {
            return errorJson("Oversized (oversized)" + size + " bytes, upper limit " + MAX_TEXT_READ_BYTES
                    + "); Here you go. inspect mode=search or run sed/grep Positioning requires a segment.");
        }
        int startLine = Math.max(1, offset);
        int maxLines = Math.max(1, Math.min(limit <= 0 ? 400 : limit, 2000));
        // Only the front-judged binary is detected and the entire document is not read into the memory.
        byte[] probe = readBytes(file, (int) Math.min(size, (long) MAX_READ_BYTES));
        for (byte value : probe) {
            if (value == 0) {
                return errorJson("This is a binary file and is not supported for reading.");
            }
        }
        String fileSha = "";
        try {
            fileSha = FileTxn.hash(file);
        } catch (Exception ignored) {
        }
        String key = workspace.relativePath(file);
        String range = startLine + "-" + (startLine + maxLines - 1);
        if (!fileSha.isEmpty()) {
            ReadMark mark = readMarks.get(key);
            if (mark != null && fileSha.equals(mark.sha) && mark.ranges.contains(range)) {
                return okJson("[Read, the content hasn't changed.] " + key + " I'm sorry. " + startLine + "-"
                        + (startLine + maxLines - 1) + " Linesha256 "
                        + fileSha.substring(0, Math.min(12, fileSha.length()))
                        + "). Revert the same text before; do not repeat; see other paragraphs offset. ");
            }
        }
        TextPage page = readTextPage(file, startLine, maxLines, readPageChars());
        if (page.error == null && !fileSha.isEmpty()) {
            ReadMark mark = readMarks.get(key);
            if (mark == null || !fileSha.equals(mark.sha)) {
                mark = new ReadMark();
                mark.sha = fileSha;
                readMarks.put(key, mark);
            }
            mark.ranges.add(range);
        }
        return page.error != null ? errorJson(page.error) : okJson(page.text);
    }

    static final class TextPage {
        final String text;
        final String error;
        final int total;

        TextPage(String text, String error, int total) {
            this.text = text == null ? "" : text;
            this.error = error;
            this.total = total;
        }
    }

    /** Fluid to read any line segment; only current page is placed in memory, and the number of lines is always accurate. */
    static TextPage readTextPage(File file, int startLine, int maxLines,
                                 int pageBudget) throws IOException {
        int budget = Math.max(512, pageBudget);
        int start = Math.max(1, startLine);
        int linesWanted = Math.max(1, maxLines);
        int total = 0;
        int endLine = 0;
        int used = 0;
        boolean lineCut = false;
        ArrayList<String> page = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                total++;
                if (total < start || page.size() >= linesWanted) {
                    continue;
                }
                if (used + line.length() + 8 > budget) {
                    if (page.isEmpty()) {
                        if (line.length() > budget - 64) {
                            line = line.substring(0, Math.max(1, budget - 64))
                                    + "...[The bank's been cut off.]";
                            lineCut = true;
                        }
                        page.add(line);
                        used += line.length() + 8;
                        endLine = total;
                    }
                    // Continue scanning to report the exact total line count.
                    continue;
                }
                page.add(line);
                used += line.length() + 8;
                endLine = total;
            }
        }
        if (total == 0) {
            return new TextPage("[File is empty / Total 0 rows;to end of file]\n", null, 0);
        }
        if (start > total) {
            return new TextPage("", "Start line out of range: total of files " + total + " Okay.", total);
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < page.size(); i++) {
            builder.append(String.format(Locale.US, "%4d| %s", start + i,
                    page.get(i))).append('\n');
        }
        boolean eof = endLine >= total;
        String header = "[I'm sorry. " + start + "-" + endLine + " Okay. / Total " + total + " Okay."
                + (lineCut ? "; There's a line cut off from this page." : "")
                + (eof ? "; End of file" : "; Next paragraph offset=" + (endLine + 1))
                + "]\n";
        if (!eof) {
            builder.append("[Page to Page ").append(endLine).append(" Until the end;")
                    .append("Here you go. offset=").append(endLine + 1)
                    .append(" Keep reading until you see it.'End of file'. ]\n");
        }
        return new TextPage(header + builder, null, total);
    }

    private int readPageChars() {
        // (a) To allow a page to read the results to be fully included in the current round of requests;Lite A small window automatically shrinks the page.
        int window = contextBudget();
        int budget = window >= 24000 ? window : window / 2;
        return Math.max(4000, Math.min(MAX_READ_PAGE_CHARS, budget));
    }

    /** Automatically lifts the single-wheel output ceiling when the output is cut, and reduces the length of the file to write failure. */

    private String writeFile(String path, String content, boolean append) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            return errorJson("Missing file path");
        }
        if (content == null) {
            content = "";
        }
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_WRITE_BYTES) {
            return errorJson("More than content " + MAX_WRITE_BYTES + " Byte Upper");
        }
        File file = workspace.resolve(path);
        if (file.isDirectory()) {
            return errorJson("path is a directory, not written:" + path);
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return errorJson("Could not create parent directory");
        }
        String before = FileTxn.hash(file);
        if (append && file.isFile()) {
            byte[] existing = readBytes(file, MAX_WRITE_BYTES);
            if ((long) existing.length + data.length > MAX_WRITE_BYTES) {
                return errorJson("Added Over " + MAX_WRITE_BYTES + " Byte Upper");
            }
            byte[] combined = new byte[existing.length + data.length];
            System.arraycopy(existing, 0, combined, 0, existing.length);
            System.arraycopy(data, 0, combined, existing.length, data.length);
            data = combined;
        }
        String checkpoint;
        try {
            checkpoint = commitBytes(file, data, before);
        } catch (Exception e) {
            return errorJson("Writing failed, original document unchanged:" + e.getMessage());
        }
        return okJson((append ? "Added " : "Written ") + workspace.relativePath(file) + "(" + data.length
                + " bytes, check points " + checkpoint + ")");
    }

    private String listApps(String query) {
        List<AppInfo> apps = launchableApps(query);
        if (apps.isEmpty()) {
            return errorJson("No matching applications found");
        }
        StringBuilder builder = new StringBuilder();
        int count = Math.min(apps.size(), 50);
        for (int i = 0; i < count; i++) {
            AppInfo app = apps.get(i);
            builder.append(app.label).append(" - ").append(app.packageName).append('\n');
        }
        if (apps.size() > count) {
            builder.append("...Show only before ").append(count).append(" individual\n");
        }
        return okJson(builder.toString());
    }

    private String openApp(String query) throws Exception {
        if (!prefs.allowDeviceControl()) {
            return errorJson("Device control is not open. Please click 'Equipment' at the top right corner to open other operations allowed App. ");
        }
        String needle = query == null ? "" : query.trim();
        if (needle.isEmpty()) {
            return errorJson("Missing application name or package name");
        }
        List<AppInfo> matches = launchableApps(needle);
        AppInfo chosen = null;
        for (AppInfo app : matches) {
            if (app.packageName.equalsIgnoreCase(needle)) {
                chosen = app;
                break;
            }
        }
        if (chosen == null) {
            List<AppInfo> exact = new ArrayList<>();
            for (AppInfo app : matches) {
                if (app.label.equalsIgnoreCase(needle)) {
                    exact.add(app);
                }
            }
            if (exact.size() == 1) {
                chosen = exact.get(0);
            } else if (matches.size() == 1) {
                chosen = matches.get(0);
            }
        }
        if (chosen == null) {
            StringBuilder builder = new StringBuilder("Match multiple applications, retry the package name:\n");
            int limit = Math.min(matches.size(), 12);
            for (int i = 0; i < limit; i++) {
                AppInfo app = matches.get(i);
                builder.append(app.label).append(" - ").append(app.packageName).append('\n');
            }
            return errorJson(builder.toString());
        }

        Intent launch = appContext.getPackageManager().getLaunchIntentForPackage(chosen.packageName);
        if (launch == null) {
            return errorJson("There is no access to this application:" + chosen.packageName);
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        appContext.startActivity(launch);
        return okJson("Opened " + chosen.label + "(" + chosen.packageName + ")");
    }

    /** To get back to the front desk of the application so that users can see the ongoing dialogue continues. */
    private String returnToApp() {
        try {
            Intent back = new Intent(appContext, MainActivity.class);
            back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            appContext.startActivity(back);
            return okJson("QuantifiedCRLM, Continuation of the current dialogue");
        } catch (Throwable t) {
            return errorJson("Could not close temporary folder: %s" + t.getMessage());
        }
    }

    private List<AppInfo> launchableApps(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = appContext.getPackageManager()
                .queryIntentActivities(intent, 0);
        List<AppInfo> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : infos) {
            if (info.activityInfo == null) {
                continue;
            }
            String packageName = info.activityInfo.packageName;
            if (!seen.add(packageName)) {
                continue;
            }
            CharSequence labelChar = info.loadLabel(appContext.getPackageManager());
            String label = labelChar == null ? packageName : labelChar.toString();
            if (needle.isEmpty()
                    || label.toLowerCase(Locale.ROOT).contains(needle)
                    || packageName.toLowerCase(Locale.ROOT).contains(needle)) {
                apps.add(new AppInfo(label, packageName));
            }
        }
        Collections.sort(apps, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo left, AppInfo right) {
                return left.label.compareToIgnoreCase(right.label);
            }
        });
        return apps;
    }

    private String uiDump() throws Exception {
        AgentAccessibilityService service = requireAccessibility();
        return okJson(limit(service.dumpUi(), MAX_TOOL_OUTPUT));
    }

    private void noteToolStall(String name, String error) {
        if (error == null || error.isEmpty()) return;
        if (error.contains("Termux Command timeout") || error.contains("Suspected of being systematically frozen")
                || error.contains("No output") || error.contains("No progress.")) {
            supervisor.block(workTicketId, "Tools " + name + " - It's stuck." + error);
        }
    }

    private void autoAddAdapter(String name) {
        if (name == null || name.isEmpty() || loadedAdapters.size() >= MAX_LOADED_ADAPTERS) return;
        if (tools.get(name) == null || CORE_TOOL_NAMES.contains(name)) return;
        loadedAdapters.put(name, AUTO_ADAPTER_USES);
    }

    private void autoLoadAdapters(String input) {
        String text = input == null ? "" : input.toLowerCase(Locale.ROOT);
        boolean webIntent = text.contains("http://") || text.contains("https://")
                || containsAny(text, "Web", "Website", "Website", "Browser", "Capture",
                "Read Web Pages", "web page", "webpage", "url")
                || (containsWord(text, "web") && containsWord(text, "search"))
                || containsAny(text, "Search", "Search it.", "Check it out.", "Find out what you're looking for.", "Find");
        if (webIntent) {
            autoAddAdapter("web_fetch");
            autoAddAdapter("browser_eval");
            boolean browserIntent = text.contains("http://") || text.contains("https://")
                    || text.contains("file://")
                    || containsAny(text, "Browser", "browser", "Page", "Screenshot", "Screenshot", "screenshot");
            if (browserIntent) {
                autoAddAdapter("browser_open");
                autoAddAdapter("browser_screenshot");
            }
            if (containsAny(text, "Search", "Search it.", "Check it out.", "Find out what you're looking for.", "Find")
                    || text.contains("web search") || text.contains("search the web")
                    || text.contains("google") || text.contains("bing")) {
                autoAddAdapter("web_search");
            }
        }
        if (looksLikeDownloadTask(input)) autoAddAdapter("download_file");
        if (containsAny(text, "Picture", "Photos", "Image", "image", "photo", "Wallpaper", "Figure")) {
            autoAddAdapter("image_search");
            autoAddAdapter("download_file");
            autoAddAdapter("view_image");
        }
        if (containsAny(text, "Code Map", "Warehouse structure", "code map", "repository map")) {
            autoAddAdapter("repo_map");
        }
        if (containsAny(text, "Code Index", "code index")) autoAddAdapter("repo_index");
        if (containsAny(text, "History", "Archive", "Earlier", "Before", "Context", "Check back.", "archive")) {
            autoAddAdapter("context_search");
        }
        if (containsAny(text, "Code Search", "code search", "repo search")) {
            autoAddAdapter("repo_search");
        }
        if (containsAny(text, "Patch", "patch", "Reconstruct", "refactor")) autoAddAdapter("apply_patch");
        if (containsAny(text, "Build", "Install", "Pack up.", "Compile", "Signature", "build", "apk", "install")) {
            if (TermuxBridge.isInstalled(appContext) || Rootfs.get(appContext).ready()) {
                autoAddAdapter("build_apk");
            }
            autoAddAdapter("install_apk");
        }
        boolean researchIntent = containsAny(text,
                "cli", "Command Line", "Interface", "api", "sdk", "Did you?", "How?", "Programme",
                "Research", "Information", "Rationale", "What is it?", "How?", "how to", "what is");
        boolean deviceIntent = prefs.allowDeviceControl() && !researchIntent
                && (containsAny(text,
                "Cell phone.", "Interface", "Screen", "Click", "Slide", "Open", "Start", "Screenshot", "Camera",
                "Library", "Settings", "Announcements", "Accessibility", "Wess.", "Chat", "Map", "Clipboard",
                "app", "tap", "click", "screen", "device")
                || containsWord(text, "app"));
        if (deviceIntent) {
            // Just preload it, it'll really be useful. 2 entrances; remainder as required tool_load, Avoid 6 The number of places was filled with equipment.
            autoAddAdapter("open_app");
            autoAddAdapter("ui_dump");
            if (containsAny(text, "Click", "tap", "button")) autoAddAdapter("ui_tap");
            if (containsAny(text, "Input", "Fill", "set text")) autoAddAdapter("ui_set_text");
            if (containsAny(text, "Slide", "scroll")) autoAddAdapter("ui_scroll");
            if (containsAny(text, "Screenshot", "Screenshot", "screenshot")) autoAddAdapter("screenshot");
        }
        if (containsAny(text, "mcp") && mcp.hasEnabledServers()) autoAddAdapter("mcp_discover");
        if (containsAny(text, "Skills", "skill", "Knowledge", "knowledge", "Memory", "remember")) {
            autoAddAdapter("list_skills");
            autoAddAdapter("read_skill");
            autoAddAdapter("search_knowledge");
            autoAddAdapter("remember");
        }
    }

    private void consumeAdapter(String name) {
        Integer remaining = loadedAdapters.remove(name);
        if (remaining != null) {
            // LRU recency only. Adapters stay loaded for the whole run; evicting
            // after N uses made a research task fail on its 4th web_search call.
            loadedAdapters.put(name, remaining);
        }
    }

    /** A call may arrive after an adapter was LRU-evicted; reload it silently. */
    private String ensureAdapterLoaded(String name) {
        if (name == null || profileRestricted) {
            return "Current Agent Unable to enable tool:" + name;
        }
        if (CORE_TOOL_NAMES.contains(name) || LEGACY_CORE_ALIASES.contains(name)) {
            return "Current Agent Unable to enable tool:" + name;
        }
        if (tools.get(name) == null) {
            return "Unknown tool:" + name;
        }
        boolean web = "web_fetch".equals(name) || "web_search".equals(name);
        boolean download = "download_file".equals(name);
        if ((web || download) && !webToolAllowed() && !looksLikeDownloadTask(runInput)) {
            return "Networking tool not authorized; please specify'Read Web Pages/Download File', or start the network grab in the settings.";
        }
        if (loadedAdapters.size() >= MAX_LOADED_ADAPTERS
                && evictAdapter(name) == null) {
            return "Full adapter slot (%2)" + MAX_LOADED_ADAPTERS + " individual; please complete the current tool before retrying.";
        }
        loadedAdapters.put(name, MANUAL_ADAPTER_USES);
        refreshToolScope();
        Log.i("AgentGateway", "adapter auto-reloaded: " + name);
        return null;
    }

    /** Evict the least recently used adapter so a needed one can always be loaded. */
    private String evictAdapter(String keep) {
        for (String name : loadedAdapters.keySet()) {
            if (!name.equals(keep)) {
                loadedAdapters.remove(name);
                return name;
            }
        }
        return null;
    }

    private static String adapterKeywords(String name) {
        switch (name) {
            case "web_fetch": return "Web Capture Text Website url http fetch";
            case "web_search": return "Web Search bing Information search Query";
            case "download_file": return "Download Model apk zip pdf Picture Passage download";
            case "apply_patch": return "Patch patch diff unified diff Modify";
            case "screenshot": return "Screenshot Screen Screenshot screenshot";
            case "open_app": return "Open Application Start app launch";
            case "ui_dump": return "Interface Controls Accessibility ui dump";
            case "ui_tap": return "Click Coordinates tap";
            case "build_apk": return "Build Pack up. apk build Compile";
            case "install_apk": return "Install apk install";
            case "termux_run": return "termux python node git pkg Package management Advanced";
            case "mcp_discover": return "mcp Servers Tools server";
            case "repo_map": return "Code Map Warehouse structure File symbol code map repository map structure";
            case "repo_index": return "Code Index Index code index";
            case "repo_search": return "Code Search Search code search";
            case "context_search": return "History Archive Earlier Before Context Check back. archive history Search";
            case "search_knowledge": return "Knowledge base Search knowledge";
            case "list_skills": return "Skills skill";
            case "read_skill": return "Skills skill";
            default: return "";
        }
    }

    private void refreshToolScope() {
        Set<String> names = new LinkedHashSet<>();
        if (profileScope != null) {
            names.addAll(profileScope.names());
            for (String adapter : loadedAdapters.keySet()) {
                if (profileScope.allows(adapter)) {
                    names.add(adapter);
                }
            }
        } else {
            names.addAll(CORE_TOOL_NAMES);
            names.addAll(loadedAdapters.keySet());
        }
        activeToolScope = ToolScope.of(names);
        activeToolNames = activeToolScope.schemaFilter();
        cachedToolDefinitions = null;
        cachedToolDefinitionsKey = "";
    }

    private static boolean toolResultOk(String result) {
        return result != null && result.startsWith("{\"ok\":true");
    }

    private static boolean isSourcePath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt")
                || lower.endsWith(".kts") || lower.endsWith(".xml")
                || lower.endsWith(".lua") || lower.endsWith(".py")
                || lower.endsWith(".gradle") || lower.endsWith(".cs");
    }

    private static String sourceName(String path) {
        String value = path == null ? "" : path.trim();
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    /** The entire page of the document is cleared after the editing is successful and the check-up is allowed. */
    private void clearSourceReadQuota(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        sourceReadCounts.remove(sourceName(path));
    }

    /** Textbook for the current operational phase (under consideration).../Implementation xxx.../Generating responses...).  */
    String runPhase() {
        return runPhase;
    }

    /** Additional description for the current phase (tool objects)/(a) A summary of the reflections, and runPhase() ♪ Scramble in a line of displays. ♪ */
    String runDetail() {
        return runDetail;
    }

    /** Pick a readable target in the tool parameter (prior path)/Command/Query term) for the status bar step description. */
    private static String extractArg(String raw, String... keys) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        for (String key : keys) {
            int idx = raw.indexOf("\"" + key + "\"");
            if (idx < 0) {
                continue;
            }
            int colon = raw.indexOf(':', idx + key.length() + 2);
            if (colon < 0) {
                continue;
            }
            int start = raw.indexOf('"', colon + 1);
            if (start < 0) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            int i = start + 1;
            while (i < raw.length()) {
                char c = raw.charAt(i);
                if (c == '\\' && i + 1 < raw.length()) {
                    sb.append(raw.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    break;
                }
                sb.append(c);
                i++;
            }
            String value = sb.toString().trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    /** Translation of tools into human-readable step names, for example'Edit File aaa.java'.  */
    private static String describeStep(String name, String argsRaw) {
        String verb;
        if ("inspect".equals(name)) {
            String mode = extractArg(argsRaw, "mode");
            if ("list".equals(mode)) {
                verb = "View Directory";
            } else if ("search".equals(mode)) {
                verb = "Search Code";
            } else if ("glob".equals(mode)) {
                verb = "Find Files";
            } else {
                verb = "Read Files";
            }
        } else if ("edit".equals(name)) {
            verb = "Edit File";
        } else if ("run".equals(name)) {
            verb = "Execute the command.";
        } else if ("update_plan".equals(name)) {
            verb = "Update the plan";
        } else if ("ask".equals(name)) {
            verb = "Ask the user";
        } else if ("delegate".equals(name)) {
            verb = "Sub-institution inquiry";
        } else if ("tool_search".equals(name)) {
            verb = "Search for available tools";
        } else if ("tool_load".equals(name)) {
            verb = "Loading Tool";
        } else if ("build_apk".equals(name)) {
            verb = "Build APK";
        } else if ("install_apk".equals(name)) {
            verb = "Install APK";
        } else if ("web_search".equals(name)) {
            verb = "Search Web";
        } else if ("web_fetch".equals(name)) {
            verb = "Read Web Pages";
        } else if (name != null && name.startsWith("mcp_")) {
            verb = "Call MCP Tools";
        } else {
            verb = "Implementation " + name;
        }
        String target = extractArg(argsRaw, "path", "file", "filePath", "project",
                "url", "query", "command", "pattern");
        if (target.isEmpty()) {
            target = extractArg(argsRaw, "name");
        }
        int slash = Math.max(target.lastIndexOf('/'), target.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < target.length()) {
            target = target.substring(slash + 1);
        }
        return oneLine(target.isEmpty() ? verb : verb + " " + target, 40);
    }

    // One-line summary is used below oneLine(String, int) Achieved.

    private String guardSourceRead(String path, String mode) {
        if (!"read".equals(mode) && !"search".equals(mode)) {
            return null;
        }
        boolean fileSpecific = isSourcePath(path);
        if (!fileSpecific && !"search".equals(mode)) {
            return null;
        }
        if (sourceReadCalls >= SOURCE_READ_TOTAL_LIMIT) {
            return "The current task source reading budget is exhausted (%2)" + SOURCE_READ_TOTAL_LIMIT
                    + " Please base the work set directly on the work set. edit, or ask_user (b) An indication of what is missing;"
                    + "Don't continue reading.";
        }
                // search Return only hitline, pageless, source code, so that it does not have a quota per file, and avoid early sealing of the positioning phase
        if (fileSpecific && "read".equals(mode)) {
            String name = sourceName(path);
            Integer used = sourceReadCounts.get(name);
            int count = used == null ? 0 : used.intValue();
            if (count >= SOURCE_READ_PER_FILE_LIMIT) {
                return "Source File " + name + " Read the whole page already reached " + count
                        + " Next up, do not return the full text.① Direct edit(Use known line numbers/Symbol,"
                        + "The editing successfully automatically resets the file quota);② Use inspect mode=search Retrieving symbols (not this quota),"
                        + "Or use it. run It's... grep -n Single line of location.";
            }
            sourceReadCounts.put(name, Integer.valueOf(count + 1));
        }
        if ("read".equals(mode)) {
            sourceReadCalls++;
        }
        if (sourceReadCalls == 12 && !sourceReadNudged) {
            sourceReadNudged = true;
            addEntry(Entry.SYSTEM, "Source Read Used 12/" + SOURCE_READ_TOTAL_LIMIT
                    + " Next time, close to the budget ceiling; please stop expanding reading and start editing.", false);
        }
        return null;
    }

        private static String guardShellSourceRead(String command) {
        return ShellSourceGuard.check(command);
    }

    private void recordInspectWorkingSet(JSONObject args, String result) {
        if (!toolResultOk(result)) {
            return;
        }
        String mode = args.optString("mode", "read").trim().toLowerCase(Locale.ROOT);
        String path = args.optString("path", "");
        String intent = args.optString("intent", "").replace('\n', ' ').trim();
        if ("read".equals(mode)) {
            int offset = Math.max(1, args.optInt("offset", 1));
            int limit = args.optInt("limit", SOURCE_PAGE_LINES);
            recordWorkingSet("Read", path, intent + " [Okay. " + offset + "-"
                    + (offset + Math.max(1, limit) - 1) + "]");
        } else if ("search".equals(mode)) {
            String query = args.optString("query", args.optString("pattern", ""));
            recordWorkingSet("Search", path, intent + (query.isEmpty() ? "" : " Query:" + query));
        } else if ("list".equals(mode) || "glob".equals(mode)) {
            recordWorkingSet("Positioning", path, intent);
        }
    }

    private void recordRunWorkingSet(JSONObject args, String result) {
        if (!toolResultOk(result)) {
            return;
        }
        String command = args.optString("command", "");
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.contains("build") || lower.contains("javac") || lower.contains("aapt2")
                || lower.contains("install") || lower.contains("test")
                || lower.contains("gradle")) {
            String intent = args.optString("intent", "").replace('\n', ' ').trim();
            recordWorkingSet("Authentication", "", (intent + " " + command).trim());
        }
    }

    private void recordWorkingSet(String kind, String path, String detail) {
        if (conversation == null) {
            return;
        }
        String p = path == null ? "" : path.trim().replace('\n', ' ');
        String d = detail == null ? "" : detail.trim().replace('\n', ' ');
        if (d.length() > 110) {
            d = d.substring(0, 110) + "...";
        }
        if (p.isEmpty() && d.isEmpty()) {
            return;
        }
        try {
            JSONObject item = new JSONObject();
            item.put("kind", kind == null ? "" : kind);
            item.put("path", p);
            item.put("detail", d);
            item.put("at", System.currentTimeMillis());
            synchronized (conversation.workingSet) {
                for (int i = conversation.workingSet.length() - 1; i >= 0; i--) {
                    JSONObject old = conversation.workingSet.optJSONObject(i);
                    if (old != null && kind.equals(old.optString("kind", ""))
                            && p.equals(old.optString("path", ""))
                            && d.equals(old.optString("detail", ""))) {
                        return;
                    }
                }
                conversation.workingSet.put(item);
                while (conversation.workingSet.length() > 24) {
                    conversation.workingSet.remove(0);
                }
            }
            saveCurrent();
        } catch (Exception ignored) {
        }
    }

    private String workingSetSection() {
        if (conversation == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        JSONArray set = conversation.workingSet;
        if (set != null && set.length() > 0) {
            builder.append("\n\n[Jobwork Set (retain after compression; do not repeat confirmed files))\n");
            int from = Math.max(0, set.length() - 16);
            for (int i = from; i < set.length(); i++) {
                JSONObject item = set.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String kind = item.optString("kind", "");
                String path = item.optString("path", "");
                String detail = item.optString("detail", "");
                builder.append("- [").append(kind).append("] ");
                if (!path.isEmpty()) {
                    builder.append(path).append(" ");
                }
                if (!detail.isEmpty()) {
                    builder.append("- ").append(detail);
                }
                builder.append('\n');
            }
        }
        JSONArray plan = conversation.plan;
        if (plan != null && plan.length() > 0) {
            builder.append("[Current plan");
            for (int i = 0; i < plan.length() && i < 8; i++) {
                JSONObject step = plan.optJSONObject(i);
                if (step == null) {
                    continue;
                }
                if (i > 0) {
                    builder.append(" → ");
                }
                builder.append(step.optString("title", ""))
                        .append("(").append(step.optString("status", "pending")).append(")");
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private String inspectTool(JSONObject args) throws Exception {
        String mode = args.optString("mode", "read").trim().toLowerCase(Locale.ROOT);
        String path = args.optString("path", "");
        String blocked = guardSourceRead(path, mode);
        if (blocked != null) {
            return errorJson(blocked);
        }
        if ("read".equals(mode)) {
            int defaultLimit = isSourcePath(path) ? SOURCE_PAGE_LINES : 200;
            int limit = args.has("limit") ? args.optInt("limit", defaultLimit) : defaultLimit;
            return readFile(path, args.optInt("offset", 1), limit);
        }
        if ("list".equals(mode)) {
            return listFiles(path);
        }
        if ("search".equals(mode)) {
            String query = args.optString("query", args.optString("pattern", ""));
            return grepFiles(query, path, args.optString("glob", ""),
                    args.optBoolean("ignore_case", false), args.optInt("context", 0),
                    args.optInt("max_results", 30));
        }
        if ("glob".equals(mode)) {
            return globFiles(args.optString("pattern", ""), path,
                    args.optInt("max_results", 100));
        }
        return errorJson("inspect.mode Support only read/list/search/glob");
    }

    private String editTool(JSONObject args) throws Exception {
        String mode = args.optString("mode", "").trim().toLowerCase(Locale.ROOT);
        String path = args.optString("path", "");
        if ("write".equals(mode)) {
            return writeFile(path, args.optString("content", ""),
                    args.optBoolean("append", false));
        }
        if ("replace".equals(mode)) {
            return editFile(path, args.optString("old_text", ""),
                    args.optString("new_text", ""), args.optBoolean("replace_all", false));
        }
        return errorJson("edit.mode Support only write/replace; Complex patches please tool_load(apply_patch)");
    }

    private static int searchScore(String query, String name, String description,
                                   String keywords) {
        if (query == null || query.isEmpty()) return 0;
        String q = query.toLowerCase(Locale.ROOT);
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String d = description == null ? "" : description.toLowerCase(Locale.ROOT);
        String k = keywords == null ? "" : keywords.toLowerCase(Locale.ROOT);
        int score = 0;
        if (n.startsWith(q)) score += 5;
        else if (n.contains(q)) score += 4;
        if (k.contains(q)) score += 3;
        if (d.contains(q)) score += 2;
        for (String token : q.split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (token.length() < 2) continue;
            if (n.contains(token)) score += 3;
            if (k.contains(token)) score += 2;
            if (d.contains(token)) score += 1;
        }
        return score;
    }

    private String toolSearch(String query, int limit) {
        if (toolSearchCount >= MAX_TOOL_SEARCHES) {
            return errorJson("Current round tool_search Achieved " + MAX_TOOL_SEARCHES
                    + " Sub-capsion; please use the tools or answers already available directly and do not search again.");
        }
        toolSearchCount++;
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int cap = Math.max(1, Math.min(limit <= 0 ? 6 : limit, 10));
        JSONArray all = tools.schemas(null);
        List<String[]> matches = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject wrapper = all.optJSONObject(i);
            JSONObject fn = wrapper == null ? null : wrapper.optJSONObject("function");
            if (fn == null) continue;
            String name = fn.optString("name", "");
            if (name.isEmpty() || CORE_TOOL_NAMES.contains(name)
                    || LEGACY_CORE_ALIASES.contains(name) || loadedAdapters.containsKey(name)) {
                continue;
            }
            String desc = fn.optString("description", "");
            String keywords = adapterKeywords(name);
            int score = searchScore(q, name, desc, keywords);
            if (!q.isEmpty() && score == 0) continue;
            ToolRegistry.ToolSpec spec = tools.get(name);
            String risk = "Read-only";
            if (spec != null) {
                int effects = spec.effectsForDryRun(false);
                if ((effects & ToolPolicy.INSTALL) != 0) risk = "Install";
                else if ((effects & ToolPolicy.DEVICE) != 0) risk = "Equipment";
                else if ((effects & ToolPolicy.PROCESS) != 0) risk = "Implementation";
                else if ((effects & ToolPolicy.WRITE) != 0) risk = "Writing";
                else if ((effects & ToolPolicy.NETWORK) != 0) risk = "Networking";
            }
            matches.add(new String[]{name, desc, risk, String.valueOf(score)});
        }
        matches.sort((a, b) -> {
            int byScore = Integer.compare(Integer.parseInt(b[3]), Integer.parseInt(a[3]));
            return byScore != 0 ? byScore : a[0].compareTo(b[0]);
        });
        if (matches.isEmpty()) {
            return okJson("There is no matching adapter. You can try keywords: web page, download, screenshot, device, build,MCP, Memory, index.");
        }
        StringBuilder out = new StringBuilder("Available adapter (used) tool_load Load, current round is valid, auto-dismount at the end of the mission:\n");
        for (int i = 0; i < matches.size() && i < cap; i++) {
            String[] item = matches.get(i);
            out.append("- ").append(item[0]).append(" [").append(item[2]).append("] ")
                    .append(limitText(item[1], 180))
                    .append("(Load:tool_load(\"").append(item[0]).append("\"))\n");
        }
        return okJson(out.toString().trim());
    }

    private String toolLoad(String name) {
        String key = name == null ? "" : name.trim();
        if (key.isEmpty()) return errorJson("Missing adapter name;first tool_search Find");
        if (CORE_TOOL_NAMES.contains(key)) {
            return errorJson(key + " Already a permanent core tool, no loading");
        }
        if (LEGACY_CORE_ALIASES.contains(key)) {
            String mapped = "read_file".equals(key) || "list_files".equals(key)
                    || "grep".equals(key) || "glob".equals(key) ? "inspect"
                    : "write_file".equals(key) || "edit_file".equals(key) ? "edit"
                    : "shell_run".equals(key) ? "run"
                    : "ask_user".equals(key) ? "ask" : "delegate";
            return errorJson(key + " It's an old name. It's incorporated. " + mapped + "; Please use it directly. " + mapped);
        }
        ToolRegistry.ToolSpec spec = tools.get(key);
        if (spec == null) return errorJson("Unknown adapter:" + key + "; First. tool_search Find");
        if (profileRestricted) {
            return errorJson("Current Agent Unable to enable tool:" + key + "; Change if you need to use it Agent Scope of the tool.");
        }
        if (loadedAdapters.containsKey(key)) {
            return okJson("Adapter " + key + " Loaded in this cycle and can be called directly.");
        }
        if (toolLoadCount >= MAX_TOOL_LOADS) {
            return errorJson("Current round tool_load Achieved " + MAX_TOOL_LOADS
                    + " Sub-caps; please use existing tools or answers directly.");
        }
        boolean web = "web_fetch".equals(key) || "web_search".equals(key);
        boolean download = "download_file".equals(key);
        if ((web || download) && !webToolAllowed() && !looksLikeDownloadTask(runInput)) {
            return errorJson("Networking tool not authorized; please specify'Read Web Pages/Download File', or start the network grab in the settings.");
        }
        if ("build_apk".equals(key) && !TermuxBridge.isInstalled(appContext)
                && !Rootfs.get(appContext).ready()) {
            return errorJson("No self-contained tool chain. Termux, Cannot build APK. "
                    + "Self-carrying tool chains are activated at the backstage for the first time and retry later.");
        }
        if ("termux_run".equals(key) && !TermuxBridge.isInstalled(appContext)) {
            return errorJson("Termux not installed;day commands used run, Build APK Use build_apk. ");
        }
        if (("open_app".equals(key) || "ui_dump".equals(key) || "ui_tap".equals(key)
                || "ui_set_text".equals(key) || "ui_scroll".equals(key)
                || "system_action".equals(key) || "list_apps".equals(key)
                || "screenshot".equals(key) || key.startsWith("clipboard_"))
                && !prefs.allowDeviceControl()) {
            return errorJson("Device control is not open; please start with settings before loading " + key);
        }
        if ("mcp_discover".equals(key) && !mcp.hasEnabledServers()) {
            return errorJson("Not enabled MCP servers;configuring first in settings MCP. ");
        }
        String evicted = null;
        if (loadedAdapters.size() >= MAX_LOADED_ADAPTERS) {
            evicted = evictAdapter(key);
            if (evicted == null) {
                return errorJson("There are no spare adapters; please use the tools or answers available directly.");
            }
        }
        toolLoadCount++;
        loadedAdapters.put(key, MANUAL_ADAPTER_USES);
        refreshToolScope();
        String note = evicted == null ? ""
                : "(Automatically unmounted for the longest time " + evicted + " (Pilot space)";
        return okJson("Loaded " + key + ". Direct calls are available in current round of assignments (maximum) "
                + MANUAL_ADAPTER_USES + " (a) Number of times, automatic unmounting of the end of the mission)" + note);
    }

    private static String limitText(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /**
     * Asks the user one or more questions as a choice card. Options keep the order the
     * model gives them, so the first one is its recommendation; the user can pick, pick
     * several when multi is set, or type their own answer.
     */
    private String askQuestions(JSONObject args) {
        JSONArray incoming = args.optJSONArray("questions");
        if (incoming == null) {
            // The schema declares questions as a string, so accept JSON text too.
            String raw = args.optString("questions", "").trim();
            if (raw.startsWith("[")) {
                try {
                    incoming = new JSONArray(raw);
                } catch (Exception ignored) {
                }
            }
        }
        JSONArray kept = new JSONArray();
        if (incoming != null && incoming.length() > 0) {
            for (int i = 0; i < incoming.length(); i++) {
                JSONObject item = normalizeQuestion(incoming.optJSONObject(i));
                if (item != null) {
                    kept.put(item);
                }
            }
        } else {
            JSONObject single = normalizeQuestion(args);
            if (single != null) {
                kept.put(single);
            }
        }
        if (kept.length() == 0) {
            return errorJson("Lack of questions to ask users");
        }
        addEntry(Entry.QUESTIONS, kept.toString(), true);
        pauseRequested = true;
        try {
            supervisor.end(workTicketId, WorkStore.WAITING,
                    "Wait for the user to answer " + kept.length() + " A question.");
        } catch (Throwable ignored) {
        }
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < kept.length(); i++) {
            if (i > 0) {
                summary.append("; ");
            }
            summary.append(kept.optJSONObject(i).optString("question", ""));
        }
        return okJson("The user has been asked questions, the task has been suspended until the user has chosen or filled in the question card." + summary);
    }

    /**
     * Accepts options as a JSON array, a JSON array string, or a text block separated
     * by newlines / '|'. Models often put "A. ...\nB. ..." in one string; splitting
     * only on '|' turned that into a single option card.
     */
    static JSONObject normalizeQuestion(JSONObject source) {
        if (source == null) {
            return null;
        }
        String question = source.optString("question", "").trim();
        if (question.isEmpty()) {
            return null;
        }
        JSONObject out = new JSONObject();
        try {
            out.put("question", question);
            JSONArray list = new JSONArray();
            JSONArray options = source.optJSONArray("options");
            if (options != null) {
                for (int i = 0; i < options.length(); i++) {
                    addOptions(list, options.optString(i, ""));
                }
            } else {
                addOptions(list, source.optString("options", ""));
            }
            out.put("options", list);
            // "Multiple Selections" only means something with at least two choices; a single option
            // is just a suggested answer, so never present it as a multi-select list.
            out.put("multi", source.optBoolean("multi", false) && list.length() >= 2);
        } catch (Exception ignored) {
            return null;
        }
        return out;
    }

    private static void addOptions(JSONArray list, String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return;
        }
        if (text.startsWith("[")) {
            try {
                JSONArray nested = new JSONArray(text);
                for (int i = 0; i < nested.length(); i++) {
                    addOptions(list, nested.optString(i, ""));
                }
                return;
            } catch (Exception ignored) {
            }
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = normalized.split("\\n|\\|");
        if (parts.length == 1) {
            // Some models emit "A. foo B. bar C. baz" on one line.
            String[] inline = normalized.split(
                    "(?=\\s*(?:[A-Za-z]|\\d{1,2})\\s*[., )):: ]\\s+)");
            if (inline.length > 1) {
                parts = inline;
            }
        }
        for (String part : parts) {
            String value = stripOptionMarker(part);
            if (!value.isEmpty()) {
                list.put(value);
            }
        }
    }

    private static String stripOptionMarker(String part) {
        String value = part == null ? "" : part.trim();
        value = value.replaceFirst("^\\s*(?:[A-Za-z]|\\d{1,2})\\s*[., )):: ]\\s+", "");
        value = value.replaceFirst("^[-•*·]\\s+", "");
        return value.trim();
    }

    private String shellRun(String command, int timeoutSeconds) {
        return shellRun(command, timeoutSeconds, 0, false);
    }

    /** am/cmd activity Unreliable in the application of identity, direct to original tools, avoid unnecessary Permission denied.  */
    private static String guardShellLaunch(String command) {
        if (command == null) {
            return null;
        }
        String[] segments = command.split("&&|;|\\|");
        for (String segment : segments) {
            String s = segment.trim();
            if (s.isEmpty() || s.startsWith("cd ") || s.startsWith("export ")
                    || s.startsWith("set ")) {
                continue;
            }
            int space = s.indexOf(' ');
            String head = (space < 0 ? s : s.substring(0, space)).toLowerCase(Locale.ROOT);
            if (head.equals("am") || head.endsWith("/am")) {
                return "Don't use it. am Start interface or broadcast: apply identity execution am Unreliable."
                        + "Open application open_app; Open Local HTML Or read it for retrofitting. browser_open"
                        + "(It's automatic, but it needs to be re-interfaced. browser_screenshot. ";
            }
            if (head.equals("cmd") && s.toLowerCase(Locale.ROOT).contains(" activity ")
                    && s.toLowerCase(Locale.ROOT).contains(" start")) {
                return "Don't use it. cmd activity start starting interface;opening application open_app, "
                        + "Local Web Pages browser_open(It's automatic.";
            }
        }
        return null;
    }

    /**
     * @param maxLines >0 Only keep the previous maxLines Lines are also marked with the omitted number of lines for model active control of output volume.
     * @param summaryOnly true , and then only exit codes, total lines and end lines, which are suitable for the scene of the conclusion (counting)/The winner is the judge of the game.
     */
    private String shellRun(String command, int timeoutSeconds, int maxLines, boolean summaryOnly) {
        String blocked = guardShellSourceRead(command);
        if (blocked != null) {
            return errorJson(blocked);
        }
        String launchBlocked = guardShellLaunch(command);
        if (launchBlocked != null) {
            return errorJson(launchBlocked);
        }
        if (command == null || command.trim().isEmpty()) {
            return errorJson("Missing shell Command");
        }
        long timeoutMs = Math.max(5, Math.min(1800, timeoutSeconds)) * 1000L;
        ShellRunner.Result result = ShellRunner.run(appContext, workspace, command, timeoutMs,
                new ShellRunner.Progress() {
                    @Override
                    public void tick() {
                        supervisor.heartbeat(workTicketId, currentRunId, currentStep, maxSteps);
                    }
                }, new ShellRunner.ProcessSink() {
                    @Override
                    public void attach(Process process) {
                        activeShellProcess = process;
                        if (process != null && stopRequested) {
                            process.destroyForcibly();
                        }
                    }
                });
                if (result.error != null) return errorJson(result.error);
        String output = limit(result.output, 6000);
        if (summaryOnly) {
            output = summaryLines(output);
        } else if (maxLines > 0) {
            output = headLines(output, maxLines);
        }
        if (result.exitCode != 0) {
            return errorJson("Command exit code " + result.exitCode + "\n" + output
                    + "\n(Repeating the same command will not bring new information; more output is needed to post the results to the file inspect mode=read (in two parts)");
        }
        return okJson("exit=0\n" + output);
    }

    /**
     * Conclusions only: total number of lines + Endline non-empty content. Use 'Count it for a count.'/The government has not yet made any decisions about the results of the campaign.
     * Avoids the insertion of hundreds of lines of output into the context.
     */
    private static String summaryLines(String text) {
        if (text == null || text.isEmpty()) {
            return "(No output)";
        }
        String[] lines = text.split("\n", -1);
        String last = "";
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) {
                last = lines[i].trim();
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Total ").append(lines.length).append(" Okay.");
        if (!last.isEmpty()) {
            sb.append("; End line:").append(last);
        }
        return sb.toString();
    }

    /** Save only before n Lines, with a list of omitted lines, to facilitate model judgement as to whether re-extracting is necessary. */
    private static String headLines(String text, int n) {
        if (text == null || n <= 0) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        if (lines.length <= n) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        sb.append("\n...(omitted ").append(lines.length - n).append(" All right, all right. ")
          .append(lines.length).append(" Line");
        return sb.toString();
    }

    private String termuxRun(String command, int timeoutSeconds) {
        if (command == null || command.trim().isEmpty()) {
            return errorJson("Missing Termux Command");
        }
        String launchBlocked = guardShellLaunch(command);
        if (launchBlocked != null) {
            return errorJson(launchBlocked);
        }
        // Research tasks get a per-ticket network mandate from their scope. Fetch
        // natively instead of blocking on a background Termux curl, which the OS
        // can freeze.
        String lower = command.toLowerCase(Locale.ROOT);
        if (Pattern.compile("\\b(?:pkg|apt|apt-get)\\s+(?:install|update|upgrade|full-upgrade)\\b")
                .matcher(lower).find()) {
            return errorJson("Passes are forbidden termux_run Install/Upgrading software:pkg/apt It is easy to get stuck in interactive confirmation or mirror speed measurement."
                    + "Build tool preloaded; download files download_file; Please specify and be assisted by users if tools are missing Termux Manually installed.");
        }
        if (lower.contains("yes |") || lower.contains("yes|")) {
            return errorJson("Ban yes Pipe-driven interactive commands, easily contained permanently; please change to -y or non-interactive parameters.");
        }
        if (Pattern.compile("\\|\\s*(?:tail|head)\\b").matcher(lower).find()) {
            return errorJson("Don't take orders from the Chief. | tail / | head: The output will be buffered.45 The second watch dog will be miscalculated as frozen."
                    + "Please write the full output to the log file and use it after the command. read_file Or alone. tail Look.");
        }
        if (lower.contains("aria2c ")) {
            return errorJson("Don't use it. aria2/aria2c downloading;please use in-house download_file(url,path), Supports stoppoint transmission and validation.");
        }
        boolean networkCommand = (lower.contains("curl ") || lower.contains("wget "))
                && (lower.contains("http://") || lower.contains("https://"));
        boolean savingDownload = networkCommand && (lower.contains(" -o ")
                || lower.contains("--output") || lower.contains(" -o") || lower.contains(" -O"));
        if (savingDownload) {
            return errorJson("Download files with internal download_file(url,path), Don't use it. termux curl/wget Saves the file."
                    + "The text is only available on the web page. web_fetch. ");
        }
        if (networkCommand) {
            String url = firstHttpUrl(command);
            if (url != null) {
                boolean mandated = supervisor.networkAllowed(workTicketId);
                boolean webIntent = looksLikeWebTask(runInput);
                if (prefs.allowWebTools() || mandated || webIntent) {
                    supervisor.trigger("research", conversation == null ? "" : conversation.id, url);
                    return webFetch(url, mandated || webIntent);
                }
                return errorJson("Detected Termux Pages are accessible, but the task is not authorized online."
                        + "Please use it directly. web_fetch / web_search; If the page really needs to be connected, it's a good idea to have a new page."
                        + "Please state clearly that'Read this page'Or to Settings → Harness'The blog is a blog of the blog #GazaDiam #Seño #Feb14."
                        + "Don't use it. termux_run curl/wget Grab the web page.");
            }
        }
        if (!prefs.allowDeviceControl()) {
            return errorJson("Device control not active, no callable Termux");
        }
        long timeoutMs = Math.max(10, Math.min(1800, timeoutSeconds)) * 1000L;
        TermuxBridge.Result result = TermuxBridge.run(
                appContext, workspace, command, timeoutMs, new Runnable() {
                    @Override
                    public void run() {
                        supervisor.heartbeat(workTicketId, currentRunId,
                                currentStep, maxSteps);
                    }
                });
        if (result.error != null) {
            return errorJson(result.error);
        }
        String output = limit(result.output, 6000);
        if (result.exitCode != 0) {
            return errorJson("Command exit code " + result.exitCode + "\n" + output);
        }
        return okJson("exit=0\n" + output);
    }

    private String globFiles(String pattern, String path, int maxResults) throws Exception {
        if (pattern == null || pattern.trim().isEmpty()) {
            return errorJson("Missing glob Mode");
        }
        int limit = Math.max(1, Math.min(maxResults <= 0 ? 100 : maxResults, 200));
        File root = path == null || path.trim().isEmpty()
                ? workspace.root() : workspace.resolve(path);
        if (!root.isDirectory()) {
            return errorJson("Not Directory:" + path);
        }
        Pattern regex = Pattern.compile(globToRegex(pattern.trim()));
        boolean hasSlash = pattern.contains("/");
        List<File> files = workspace.listRecursive(root, 3000);
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            String relative = workspace.relativePath(file);
            String target = hasSlash ? relative : file.getName();
            if (!regex.matcher(target).matches()) {
                continue;
            }
            builder.append(relative).append("  ").append(file.length()).append(" B\n");
            count++;
            if (count >= limit) {
                break;
            }
        }
        if (count == 0) {
            return okJson("No match file:" + pattern);
        }
        if (count >= limit) {
            builder.append("...Show only before ").append(limit).append(" individual\n");
        }
        return okJson(limit(builder.toString(), MAX_SEARCH_OUTPUT));
    }

    private String grepFiles(String query, String path, String glob, boolean ignoreCase,
                             int context, int maxResults) throws Exception {
        if (query == null || query.trim().isEmpty()) {
            return errorJson("Missing search keyword");
        }
        int limit = Math.max(1, Math.min(maxResults <= 0 ? 50 : maxResults, 200));
        int contextLines = Math.max(0, Math.min(context, 3));
        Pattern regex;
        try {
            regex = Pattern.compile(query, ignoreCase
                    ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
        } catch (PatternSyntaxException e) {
            regex = Pattern.compile(Pattern.quote(query), ignoreCase
                    ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
        }
        File root = path == null || path.trim().isEmpty()
                ? workspace.root() : workspace.resolve(path);
        List<File> files = new ArrayList<>();
        if (root.isFile()) {
            files.add(root);
        } else if (root.isDirectory()) {
            files = workspace.listRecursive(root, 3000);
        } else {
            return errorJson("Path does not exist:" + path);
        }
        StringBuilder builder = new StringBuilder();
        int count = 0;
        boolean full = false;
        for (File file : files) {
            if (full || !file.isFile() || file.length() > 512 * 1024) {
                continue;
            }
            String relative = workspace.relativePath(file);
            if (glob != null && !glob.trim().isEmpty()
                    && !matchesGlob(glob.trim(), relative, file.getName())) {
                continue;
            }
            byte[] data = readBytes(file, 512 * 1024);
            boolean binary = false;
            for (byte value : data) {
                if (value == 0) {
                    binary = true;
                    break;
                }
            }
            if (binary) {
                continue;
            }
            String[] lines = new String(data, StandardCharsets.UTF_8).split("\n", -1);
            Set<Integer> shown = new HashSet<>();
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (!regex.matcher(line).find()) {
                    continue;
                }
                int from = Math.max(0, i - contextLines);
                int to = Math.min(lines.length - 1, i + contextLines);
                for (int j = from; j <= to; j++) {
                    if (!shown.add(j)) {
                        continue;
                    }
                    String clean = lines[j].trim();
                    if (clean.length() > 220) {
                        clean = clean.substring(0, 220) + "...";
                    }
                    builder.append(relative).append(':').append(j + 1)
                            .append(j == i ? ": " : "- ").append(clean).append('\n');
                }
                count++;
                if (count >= limit) {
                    full = true;
                    break;
                }
            }
            if (full) {
                break;
            }
        }
        if (count == 0) {
            return okJson("No match:" + query);
        }
        if (full) {
            builder.append("...Show only before ").append(limit).append(" Article\n");
        }
        return okJson(limit(builder.toString(), MAX_SEARCH_OUTPUT));
    }

    private String editFile(String path, String oldText, String newText,
                            boolean replaceAll) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            return errorJson("Missing file path");
        }
        if (oldText == null || oldText.isEmpty()) {
            return errorJson("Missing old_text");
        }
        if (oldText.trim().isEmpty()) {
            return errorJson("old_text There's no room for that.");
        }
        File file = workspace.resolve(path);
        if (!file.isFile()) {
            return errorJson("File does not exist:" + path);
        }
        if (file.length() > MAX_EDIT_BYTES) {
            return errorJson("File Over 8MB, Please narrow down the scope of the change.");
        }
        String beforeHash = FileTxn.hash(file);
        String text = new String(readBytes(file, MAX_EDIT_BYTES), StandardCharsets.UTF_8);
        List<int[]> ranges = new ArrayList<>();
        int from = 0;
        while (true) {
            int index = text.indexOf(oldText, from);
            if (index < 0) {
                break;
            }
            ranges.add(new int[]{index, index + oldText.length()});
            from = index + oldText.length();
        }
        boolean fuzzy = false;
        if (ranges.isEmpty()) {
            ranges = fuzzyMatches(text, oldText);
            fuzzy = true;
        }
        if (ranges.isEmpty()) {
            return errorJson("I didn't find it. old_text, Please. read_file Can not get folder: %s: %s");
        }
        if (ranges.size() > 1 && !replaceAll) {
            return errorJson("old_text " + (fuzzy ? "Approximation" : "") + "Matches "
                    + ranges.size() + " Location (line number:" + describeRanges(text, ranges)
                    + "), Please provide a longer only context, or settings replace_all=true");
        }
        String safeNew = newText == null ? "" : newText;
        StringBuilder updated = new StringBuilder(text);
        for (int i = ranges.size() - 1; i >= 0; i--) {
            int[] range = ranges.get(i);
            updated.replace(range[0], range[1], safeNew);
        }
        String checkpoint;
        try {
            checkpoint = commitBytes(file, updated.toString().getBytes(StandardCharsets.UTF_8),
                    beforeHash);
        } catch (Exception e) {
            return errorJson("The modification failed and the original document was unchanged:" + e.getMessage());
        }
                return okJson("Modified " + workspace.relativePath(file) + ", Replace "
                + ranges.size() + " Location"
                + (fuzzy ? "(Ignore Space/Indentation difference approximate matching)" : "")
                + ", Checkpoints " + checkpoint
                + compactDiff(text, updated.toString(), ranges));
    }

        /**
     * Generate compact diff: Only the change is shown in the line and the line before and after. MAX_DIFF_CHARS Limit.
     * The aim is to make the model look at itself and avoid the waste of reading the entire file. token. 
     */
    private static String compactDiff(String before, String after, List<int[]> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            return "";
        }
        int startLine = lineOf(before, ranges.get(0)[0]);
        int endLine = lineOf(before, ranges.get(ranges.size() - 1)[1]);
        String[] oldLines = before.split("\n", -1);
        String[] newLines = after.split("\n", -1);
        int delta = newLines.length - oldLines.length;
        int from = Math.max(0, startLine - 2);
        int to = Math.min(oldLines.length - 1, endLine + 1);
        StringBuilder sb = new StringBuilder();
        sb.append("\n--- diff I'm sorry. ").append(startLine + 1).append("-").append(endLine + 1)
          .append(" Lines (total) ").append(ranges.size()).append(" (Places)---");
        int budget = MAX_DIFF_CHARS;
        for (int i = from; i <= to && budget > 0; i++) {
            String oldLine = i < oldLines.length ? oldLines[i] : "";
            String newLine = i + delta >= 0 && i + delta < newLines.length ? newLines[i + delta] : "";
            if (oldLine.equals(newLine)) {
                String ctx = "- " + clipLine(oldLine);
                sb.append('\n').append(ctx);
                budget -= ctx.length();
            } else {
                String del = "- " + clipLine(oldLine);
                String add = "+ " + clipLine(newLine);
                sb.append('\n').append(del).append('\n').append(add);
                budget -= del.length() + add.length();
            }
        }
        if (budget <= 0) {
            sb.append("\n...(diff Interrupted)");
        }
        return sb.toString();
    }

    private static final int MAX_DIFF_CHARS = 1200;

    private static int lineOf(String text, int offset) {
        int line = 0;
        int limit = Math.min(offset, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String clipLine(String line) {
        if (line == null) {
            return "";
        }
        return line.length() <= 160 ? line : line.substring(0, 160) + "...";
    }

    /** When a precise match fails, try to integrate it again by using a blank to ease indentation/The difference between lines leads to unnecessary failure. */
    private static List<int[]> fuzzyMatches(String text, String needle) {
        List<int[]> ranges = new ArrayList<>();
        Normalized haystack = normalize(text);
        Normalized target = normalize(needle);
        if (haystack.value.isEmpty() || target.value.isEmpty()) {
            return ranges;
        }
        int from = 0;
        while (true) {
            int index = haystack.value.indexOf(target.value, from);
            if (index < 0) {
                break;
            }
            int start = haystack.map[index];
            int end = haystack.map[index + target.value.length() - 1] + 1;
            ranges.add(new int[]{start, end});
            from = index + Math.max(1, target.value.length());
        }
        return ranges;
    }

    private static final class Normalized {
        final String value;
        final int[] map;

        Normalized(String value, int[] map) {
            this.value = value;
            this.map = map;
        }
    }

    private static Normalized normalize(String source) {
        StringBuilder builder = new StringBuilder();
        List<Integer> map = new ArrayList<>();
        boolean inSpace = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!inSpace) {
                    builder.append(' ');
                    map.add(i);
                    inSpace = true;
                }
            } else {
                builder.append(c);
                map.add(i);
                inSpace = false;
            }
        }
        int start = 0;
        int end = builder.length();
        while (start < end && builder.charAt(start) == ' ') {
            start++;
        }
        while (end > start && builder.charAt(end - 1) == ' ') {
            end--;
        }
        int[] positions = new int[end - start];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = map.get(start + i);
        }
        return new Normalized(builder.substring(start, end), positions);
    }

    private static String describeRanges(String text, List<int[]> ranges) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < ranges.size() && i < 8; i++) {
            int line = 1;
            int end = Math.min(ranges.get(i)[0], text.length());
            for (int k = 0; k < end; k++) {
                if (text.charAt(k) == '\n') {
                    line++;
                }
            }
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private String moveFile(String from, String to, boolean overwrite) throws Exception {
        if (from == null || from.trim().isEmpty() || to == null || to.trim().isEmpty()) {
            return errorJson("Missing source or target path");
        }
        File source = workspace.resolve(from);
        File target = workspace.resolve(to);
        if (!source.isFile()) {
            return errorJson("Source file does not exist:" + from);
        }
        if (target.isDirectory()) {
            return errorJson("Target path is directory:" + to);
        }
        if (source.getCanonicalPath().equals(target.getCanonicalPath())) {
            return errorJson("Source and target paths are the same");
        }
        String targetCheckpoint = "";
        if (target.exists()) {
            if (!overwrite) {
                return errorJson("Targets already exist:" + to + "; Please set up if you want to overwrite overwrite=true");
            }
            targetCheckpoint = checkpointFile(target);
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return errorJson("Could not close temporary folder: %s");
        }
        String targetBefore = FileTxn.hash(target);
        String sourceCheckpoint = checkpointFile(source);
        try {
            if (!source.renameTo(target)) {
                byte[] data = readBytes(source, 16 * 1024 * 1024);
                commitBytes(target, data, targetBefore);
                if (!source.delete()) {
                    return errorJson("Could not close temporary folder: %s" + from);
                }
            }
        } catch (Exception e) {
            return errorJson("Move failed:" + e.getMessage());
        }
        CodeIndex.invalidate(workspace);
        StringBuilder message = new StringBuilder("Moved ")
                .append(workspace.relativePath(source)).append(" → ")
                .append(workspace.relativePath(target))
                .append(", Checkpoints ").append(sourceCheckpoint);
        if (!targetCheckpoint.isEmpty()) {
            message.append("; Checkpoint for the target covered ").append(targetCheckpoint);
        }
        return okJson(message.toString());
    }

    private String deleteFile(String path) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            return errorJson("Missing file path");
        }
        File file = workspace.resolve(path);
        if (file.isDirectory()) {
            return errorJson("Directory cannot be deleted;delete_file Remove single files only");
        }
        if (!file.isFile()) {
            return errorJson("File does not exist:" + path);
        }
        String checkpoint;
        try {
            String relative = workspace.relativePath(file);
            commitBytesDelete(file);
            checkpoint = lastCommitCheckpoint;
        } catch (Exception e) {
            return errorJson("Deleted failed, original document unchanged:" + e.getMessage());
        }
        markCheckpointAfter(checkpoint, "NEW");
        CodeIndex.invalidate(workspace);
        return okJson("Deleted " + workspace.relativePath(file) + ", Checkpoints " + checkpoint
                + "; Call if needed to resume restore_checkpoint");
    }

    private String spawnSubagent(String task, int maxSteps) {
        String cleanTask = task == null ? "" : task.trim();
        if (cleanTask.isEmpty()) {
            return errorJson("Missing sub-agents");
        }
        if (subagentCalls >= MAX_SUBAGENT_CALLS) {
            return errorJson("This sub-agent has reached " + MAX_SUBAGENT_CALLS
                    + " Sub-ceiling; please close the door on the basis of already existing conclusions or close the task to the next round.");
        }
        subagentCalls++;
        String workerId = supervisor.startWorker(workTicketId, cleanTask);
        if (workerId == null) {
            return errorJson("Worker Full (maximum) "
                    + supervisor.store.maxWorkers + " If you want to try the current sub-agent, you can do it later.");
        }
        try {
            return spawnSubagentCore(cleanTask, maxSteps);
        } finally {
            supervisor.endWorker(workTicketId);
        }
    }

    private String spawnSubagentCore(String cleanTask, int maxSteps) {
        if (conversation == null) {
            return errorJson("Session not available");
        }
        int rounds = Math.max(1, Math.min(maxSteps <= 0 ? 8 : maxSteps, 12));
        long deadline = System.currentTimeMillis() + 180000L;
        JSONArray subMessages = new JSONArray();
        try {
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", SUBAGENT_PROMPT + "\n\n[Workspace" + workspace.label()
                    + activeCapabilities.rulesSection() + activeCapabilities.memorySection());
            subMessages.put(system);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", cleanTask);
            subMessages.put(user);
            Set<String> subAllowed = new HashSet<>(SUBAGENT_TOOL_NAMES);
            if (!activeToolScope.isAll()) {
                Set<String> parent = new HashSet<>(activeToolScope.names());
                // Core inspect covers the legacy read-only tools used by the subagent.
                if (parent.contains("inspect")) {
                    parent.addAll(Arrays.asList("read_file", "list_files", "grep", "glob"));
                }
                subAllowed.retainAll(parent);
            }
            JSONArray subTools = tools.schemas(subAllowed);
            String lastText = "";
            for (int step = 0; step < rounds; step++) {
                if (stopRequested || pauseRequested) {
                    return errorJson("Sub-agent stopped");
                }
                if (System.currentTimeMillis() > deadline) {
                    return errorJson("Sub-agent timeout (in thousands of years)180 seconds) stopped; please try again after narrowing the task");
                }
                LlmClient.Result result = llm.chat(subMessages, subTools,
                        apiKeyForCurrent(), conversation);
                if (result.error != null) {
                    return errorJson("Could not close temporary folder: %s" + result.error);
                }
                JSONObject message = result.message;
                String content = JsonText.string(message, "content").trim();
                JSONArray calls = message.optJSONArray("tool_calls");
                JSONObject assistant = new JSONObject();
                assistant.put("role", "assistant");
                assistant.put("content", content.isEmpty() ? JSONObject.NULL : content);
                String subReasoning = JsonText.string(message, "reasoning_content");
                if (subReasoning.isEmpty()) {
                    subReasoning = JsonText.string(message, "reasoning");
                }
                if (!subReasoning.isEmpty()) {
                    assistant.put("reasoning_content", subReasoning);
                }
                if (calls != null && calls.length() > 0) {
                    assistant.put("tool_calls", calls);
                }
                subMessages.put(assistant);
                if (!content.isEmpty()) {
                    lastText = content;
                }
                if (calls == null || calls.length() == 0) {
                    if (content.isEmpty()) {
                        return errorJson("The sub-agent did not return the content");
                    }
                    return okJson("Sub-Agent conclusions (research only):\n" + limit(content, 6000));
                }
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject call = calls.optJSONObject(i);
                    if (call == null) {
                        continue;
                    }
                    String callId = call.optString("id", "");
                    JSONObject function = call.optJSONObject("function");
                    String name = function == null ? "" : function.optString("name", "");
                    String arguments = function == null ? "{}"
                            : function.optString("arguments", "{}");
                    String toolResult;
                    if (!subAllowed.contains(name)) {
                        toolResult = errorJson("Sub-agents are not allowed to use tools:" + name);
                    } else {
                        try {
                            JSONObject args = new JSONObject(
                                    arguments.isEmpty() ? "{}" : arguments);
                            toolResult = executeSubagentTool(name, args);
                        } catch (Exception e) {
                            toolResult = errorJson("Sub-agent resolution failed:" + e.getMessage());
                        }
                    }
                    JSONObject toolMessage = new JSONObject();
                    toolMessage.put("role", "tool");
                    toolMessage.put("tool_call_id", callId);
                    toolMessage.put("content", handleLargeResult(callId, toolResult));
                    subMessages.put(toolMessage);
                }
            }
            return okJson("Sub-agents have reached " + rounds + " Round ceiling, phased conclusions:\n"
                    + (lastText.isEmpty() ? "((No conclusion reached)" : limit(lastText, 4000)));
        } catch (Exception e) {
            return errorJson("Sub-agent anomaly:" + (e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private String executeSubagentTool(String name, JSONObject args) throws Exception {
        if (hooks != null) {
            HookManager.PreResult pre = hooks.beforeTool(name, args,
                    runState == null ? runInput : runState.goal,
                    currentStep, currentRunId,
                    conversation == null ? "" : conversation.id);
            if (pre.deny) {
                return errorJson("Hooks Reject Sub-Agent Execution " + name + ": " + pre.reason);
            }
            if (pre.args != null) {
                args = pre.args;
            }
        }
        try {
            ToolRegistry.Invocation call = tools.prepare(name, args, workspace.root());
            Object value = tools.run(call);
            String result = value instanceof String
                    ? (String) value : okJson(String.valueOf(value));
            runPostHooks(name, args, result);
            return result;
        } catch (Exception e) {
            String result = errorJson(e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
            runPostHooks(name, args, result);
            return result;
        }
    }


    /** List old_text Each line number matches the one that allows you to locate directly and to fill the only context. */

    private static String replaceFirst(String text, String oldText, String newText) {
        int index = text.indexOf(oldText);
        if (index < 0) {
            return text;
        }
        return text.substring(0, index) + newText + text.substring(index + oldText.length());
    }

    private static String globToRegex(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '/') {
                        i++;
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                if ("\\.+()^$|{}[]".indexOf(c) >= 0) {
                    regex.append('\\');
                }
                regex.append(c);
            }
        }
        return regex.append('$').toString();
    }

    private static boolean matchesGlob(String pattern, String relative, String name) {
        Pattern regex = Pattern.compile(globToRegex(pattern));
        return regex.matcher(pattern.contains("/") ? relative : name).matches();
    }

    private String listCheckpoints(int maxResults) throws Exception {
        File base = checkpointBase();
        List<File> dirs = checkpointDirs(base);
        if (dirs.isEmpty()) {
            return okJson("No check points yet.");
        }
        int limit = Math.max(1, Math.min(maxResults <= 0 ? 20 : maxResults, 50));
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (File dir : dirs) {
            String manifest = readTextFile(new File(dir, "manifest.txt"));
            String[] lines = manifest.split("\n");
            if (lines.length < 2) {
                continue;
            }
            builder.append(dir.getName()).append("  ")
                    .append("FILE".equals(lines[0]) ? "Modify" : "New")
                    .append(' ').append(lines[1].trim()).append('\n');
            count++;
            if (count >= limit) {
                break;
            }
        }
        return okJson(builder.toString());
    }

    private String restoreCheckpoint(String id) throws Exception {
        File base = checkpointBase();
        List<File> dirs = checkpointDirs(base);
        if (dirs.isEmpty()) {
            return errorJson("No check points yet.");
        }
        File chosen = null;
        if (id == null || id.trim().isEmpty()) {
            chosen = dirs.get(0);
        } else {
            String clean = id.trim();
            if (!clean.matches("cp-[A-Za-z0-9-]+")) {
                return errorJson("Checkpoints id Invalid");
            }
            chosen = new File(base, clean);
        }
        if (chosen == null || !chosen.isDirectory()) {
            return errorJson("Checkpoint does not exist");
        }
        String manifest = readTextFile(new File(chosen, "manifest.txt"));
        String[] lines = manifest.split("\n");
        if (lines.length < 2 || lines[1].trim().isEmpty()) {
            return errorJson("Checkpoint list is corrupted.");
        }
        File target = workspace.resolve(lines[1].trim());
        String expectedAfter = "";
        if (lines.length >= 3 && lines[2].trim().startsWith("AFTER ")) {
            expectedAfter = lines[2].trim().substring(6).trim();
        }
        if (!expectedAfter.isEmpty()) {
            String current = FileTxn.hash(target);
            if (!expectedAfter.equals(current)) {
                return errorJson("The document has been modified by other operations after the checkpoint, and the restoration will cover the follow-up, and has been rejected:"
                        + lines[1].trim());
            }
        }
        try {
            checkpointFile(target);
        } catch (Exception ignored) {
        }
        if ("NEW".equals(lines[0])) {
            if (target.isFile()) {
                target.delete();
            }
            return okJson("Restored to pre-new status:" + lines[1].trim());
        }
        File backup = new File(chosen, "backup.bin");
        if (!backup.isFile()) {
            return errorJson("Checkpoint backup file missing");
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return errorJson("Could not close temporary folder: %s");
        }
        try {
            String before = FileTxn.hash(target);
            commitBytes(target, readBytes(backup, 64 * 1024 * 1024), before);
        } catch (Exception e) {
            return errorJson("Recovery failed, objective unchanged:" + e.getMessage());
        }
        markCheckpointAfter(chosen.getName(), FileTxn.hash(target));
        return okJson("Restored:" + lines[1].trim());
    }

    /** Atoms submit and record check points; transaction logs are in app Private directory, not reporting success when it fails. */
    private String commitBytes(File file, byte[] data, String expectedBefore) throws Exception {
        String before = FileTxn.hash(file);
        if (expectedBefore != null && !expectedBefore.isEmpty()
                && !expectedBefore.equals(before)) {
            throw new IllegalStateException("The document has been modified by other operations. Please read it again and then change it.");
        }
        if (stopRequested) {
            throw new InterruptedException("Task stopped");
        }
        String checkpoint = checkpointFile(file);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create directory");
        }
        File temp = File.createTempFile("." + file.getName() + "-", ".tmp", parent);
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(data);
                out.flush();
                out.getFD().sync();
            }
            if (!temp.renameTo(file)) {
                throw new IOException("Failed to replace file");
            }
        } finally {
            if (temp.exists()) {
                temp.delete();
            }
        }
        markCheckpointAfter(checkpoint, FileTxn.hash(file));
        CodeIndex.invalidate(workspace, file);
        return checkpoint;
    }

    private String lastCommitCheckpoint = "";

    private void commitBytesDelete(File file) throws Exception {
        if (stopRequested) {
            throw new InterruptedException("Task stopped");
        }
        String checkpoint = checkpointFile(file);
        if (!file.delete()) {
            throw new IOException("Failed to delete file");
        }
        lastCommitCheckpoint = checkpoint;
        CodeIndex.invalidate(workspace, file);
    }

    private void markCheckpointAfter(String checkpointId, String afterHash) {
        if (checkpointId == null || checkpointId.isEmpty() || afterHash == null) {
            return;
        }
        try {
            File dir = new File(checkpointBase(), checkpointId);
            File manifest = new File(dir, "manifest.txt");
            String existing = readTextFile(manifest);
            if (existing.contains("\nAFTER ")) {
                return;
            }
            writeTextToFile(manifest, existing.trim() + "\nAFTER " + afterHash + "\n");
        } catch (Exception ignored) {
        }
    }

    private File checkpointBase() throws IOException {
        File base = workspace.resolve(".agent/checkpoints");
        if (!base.isDirectory() && !base.mkdirs()) {
            throw new IOException("Could not close temporary folder: %s");
        }
        return base;
    }

    private String checkpointFile(File file) throws Exception {
        File base = checkpointBase();
        String id = "cp-" + System.currentTimeMillis() + "-"
                + Integer.toHexString((int) (Math.random() * 0xffff));
        File dir = new File(base, id);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not close temporary folder: %s");
        }
        String relative = workspace.relativePath(file);
        if (file.isFile()) {
            copyFile(file, new File(dir, "backup.bin"));
            writeTextToFile(new File(dir, "manifest.txt"), "FILE\n" + relative);
        } else {
            writeTextToFile(new File(dir, "manifest.txt"), "NEW\n" + relative);
        }
        pruneCheckpoints(base, 50);
        return id;
    }

    private static List<File> checkpointDirs(File base) {
        List<File> dirs = new ArrayList<>();
        File[] children = base.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && child.getName().startsWith("cp-")) {
                    dirs.add(child);
                }
            }
        }
        Collections.sort(dirs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return b.getName().compareTo(a.getName());
            }
        });
        return dirs;
    }

    private static void pruneCheckpoints(File base, int keep) {
        List<File> dirs = checkpointDirs(base);
        for (int i = keep; i < dirs.size(); i++) {
            deleteTree(dirs.get(i));
        }
    }

    private static void deleteTree(File file) {
        if (file == null) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }

    private static void copyFile(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create directory");
        }
        FileInputStream input = new FileInputStream(source);
        try {
            FileOutputStream output = new FileOutputStream(target, false);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            } finally {
                output.close();
            }
        } finally {
            input.close();
        }
    }

    private static void writeTextToFile(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create directory");
        }
        FileOutputStream output = new FileOutputStream(file, false);
        try {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            output.close();
        }
    }

    private static String readTextFile(File file) {
        try {
            return new String(readBytes(file, 2 * 1024 * 1024), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private String clipboardRead() {
        if (!prefs.allowDeviceControl()) {
            return errorJson("Device control not open, clipboard not readable");
        }
        try {
            ClipboardManager manager = (ClipboardManager)
                    appContext.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null || !manager.hasPrimaryClip()
                    || manager.getPrimaryClip() == null) {
                return okJson("Clipboard is empty");
            }
            ClipData clip = manager.getPrimaryClip();
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < clip.getItemCount(); i++) {
                CharSequence text = clip.getItemAt(i).coerceToText(appContext);
                if (text != null && text.length() > 0) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(text);
                }
            }
            return okJson(limit(builder.toString(), 10000));
        } catch (Exception e) {
            return errorJson("Clipboard reading failed:" + e.getClass().getSimpleName());
        }
    }

    private String clipboardWrite(String text) {
        if (!prefs.allowDeviceControl()) {
            return errorJson("Device control not open, unable to write to clipboard");
        }
        if (text == null) {
            text = "";
        }
        try {
            ClipboardManager manager = (ClipboardManager)
                    appContext.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) {
                return errorJson("Clipboard not available");
            }
            manager.setPrimaryClip(ClipData.newPlainText("CRLM", text));
            return okJson("Copyed " + text.length() + " Character to Clipboard");
        } catch (Exception e) {
            return errorJson("Clipboard writing failed:" + e.getClass().getSimpleName());
        }
    }

    private static String firstHttpUrl(String command) {
        if (command == null) return null;
        Matcher matcher = Pattern.compile("https?://[^\\s\"'`<>|)]+").matcher(command);
        if (!matcher.find()) return null;
        String value = matcher.group();
        while (value.endsWith(".") || value.endsWith(",") || value.endsWith(";")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.length() > 8 ? value : null;
    }

    private String webFetch(String urlText) {
        return webFetch(urlText, false);
    }

    private String downloadFileTool(String urlText, String pathText, String sha256, long expectedSize) {
        if (urlText == null || urlText.trim().isEmpty()) return errorJson("Missing Download Address");
        if (!webToolAllowed() && !looksLikeDownloadTask(runInput)) {
            return errorJson("The network download is not open. Please turn on the network tool or specify'Download this file/Model'. ");
        }
        String normalized;
        try {
            normalized = WebTools.normalizeUrl(urlText);
        } catch (Exception e) {
            return errorJson("Downloading address invalid, only supported http/https");
        }
        if (!WebTools.isAllowed(normalized)) return errorJson("Only downloading of public network is allowed https Address");
        String path = pathText == null ? "" : pathText.trim();
        if (path.isEmpty()) path = fileNameFromUrl(normalized);
        File target;
        try {
            target = workspace.resolve(path);
        } catch (Exception e) {
            return errorJson("Target path invalid:" + e.getMessage());
        }
        if (target.isDirectory()) return errorJson("Target path is directory, file name required");
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return errorJson("Could not create download directory");
        }
        File part = new File(parent, target.getName() + ".part");
        long existing = part.isFile() ? part.length() : 0L;
        HttpURLConnection connection = null;
        FileOutputStream output = null;
        try {
            URL url = new URL(normalized);
            for (int redirect = 0; redirect < 5; redirect++) {
                if (!WebTools.isAllowed(url.toString())) return errorJson("Downloading address is not allowed (private Internet or non-local) https)");
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                connection.setRequestProperty("User-Agent", "MobileAgent/" + appVersionLabel());
                connection.setRequestProperty("Accept", "*/*");
                if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    connection = null;
                    if (location == null || location.isEmpty()) return errorJson("Redirection missing Location");
                    url = new URL(url, location);
                    continue;
                }
                if (code == 416 && existing > 0) {
                    connection.disconnect();
                    connection = null;
                    existing = 0L;
                    part.delete();
                    url = new URL(normalized);
                    redirect--;
                    continue;
                }
                if (code == 200 && existing > 0) {
                    existing = 0L;
                    part.delete();
                }
                if (code != 200 && code != 206) {
                    String hint = code == 429 ? "(The flow is restricted, please try again or change the mirror later)"
                            : code == 403 ? "(Site is forbidden to download directly, which may require login or mirroring)" : "";
                    return errorJson("Download failed HTTP " + code + hint
                            + "; Don't change it. termux curl/aria2, Change the mirror or try again later.");
                }
                long total = -1L;
                if (code == 206) {
                    String range = connection.getHeaderField("Content-Range");
                    if (range != null && range.contains("/")) {
                        try { total = Long.parseLong(range.substring(range.lastIndexOf('/') + 1).trim()); }
                        catch (Exception ignored) { }
                    }
                }
                long length = connection.getContentLengthLong();
                if (total < 0 && length > 0) total = existing + length;
                output = new FileOutputStream(part, existing > 0);
                InputStream input = connection.getInputStream();
                byte[] buffer = new byte[64 * 1024];
                long downloaded = existing;
                long lastHeartbeat = 0L;
                long lastLog = 0L;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (stopRequested) {
                        output.close();
                        output = null;
                        return errorJson("Download stopped, breakpoint reserved:" + part.getAbsolutePath());
                    }
                    output.write(buffer, 0, read);
                    downloaded += read;
                    if (downloaded > MAX_DOWNLOAD_BYTES) {
                        output.close();
                        output = null;
                        return errorJson("File Over 2GB Download limit");
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastHeartbeat >= 5000L) {
                        supervisor.heartbeat(workTicketId, currentRunId, currentStep, maxSteps);
                        lastHeartbeat = now;
                    }
                    if (now - lastLog >= 2000L) {
                        Log.i("AgentDownload", "downloaded=" + downloaded
                                + (total > 0 ? "/" + total : ""));
                        lastLog = now;
                    }
                }
                output.close();
                output = null;
                connection.disconnect();
                connection = null;
                long actual = part.length();
                if (expectedSize > 0 && actual != expectedSize) {
                    return errorJson("Downloads do not match: Expect " + expectedSize + ", Actual " + actual
                            + "; Saved .part For continuation.");
                }
                String actualSha = sha256File(part);
                if (sha256 != null && !sha256.trim().isEmpty()
                        && !actualSha.equalsIgnoreCase(sha256.trim())) {
                    return errorJson("SHA-256 Unmatched: Expectations " + sha256.trim() + ", Actual " + actualSha
                            + "; Saved .part");
                }
                if (target.exists() && !target.delete()) return errorJson("Could not overwrite existing files");
                if (!part.renameTo(target)) return errorJson("Could not rename download file");
                boolean attached = attachDownloadedImage(target);
                return okJson("Download completed:" + path + ", Size " + actual + " bytes,sha256="
                        + actualSha + (attached
                        ? "; Pictures attached to models/feed, Please check and verify whether it matches." : ""));
            }
            return errorJson("Too many redirections");
        } catch (Exception e) {
            return errorJson("Download failed:" + (e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            try { if (output != null) output.close(); } catch (Exception ignored) { }
            if (connection != null) connection.disconnect();
        }
    }

    private static String fileNameFromUrl(String urlText) {
        try {
            String path = new URL(urlText).getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.isEmpty()) name = "download.bin";
            return name.replaceAll("[^A-Za-z0-9._\\-\u4e00-\u9fa5]", "_");
        } catch (Exception e) {
            return "download.bin";
        }
    }

    private static String sha256File(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        } finally {
            input.close();
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private String webFetch(String urlText, boolean perTicketMandate) {
        if (!webToolAllowed() && !perTicketMandate) {
            return errorJson("Networking tool not started. Please go to Settings → Harness'The blog is a blog that has been created to promote the Internet capture."
                    + "Or specifically.'Read this page/Search...'; The purpose of the web page was not identified in the current mission.");
        }
        WebTools.Result result = WebTools.fetch(urlText);
        recordWebStrategy("static_fetch", urlText, result.error == null,
                result.error, result.error == null && !result.jsShell);
        if (result.jsShell || result.renderSuggested) {
            String normalized = urlText;
            try {
                normalized = WebTools.normalizeUrl(urlText);
            } catch (Exception ignored) {
            }
            WebViewReader.Result rendered = WebViewReader.read(appContext, normalized, 30000L);
            recordWebStrategy("webview_render", normalized, rendered.error == null,
                    rendered.error, rendered.error == null);
            if (rendered.error == null) {
                return okJson(packWebResult(rendered.data));
            }
            String browserError = "";
            boolean browserAllowed = prefs.allowDeviceControl()
                    || requestBrowserConsent(normalized);
            if (browserAllowed && AgentAccessibilityService.getInstance() != null) {
                BrowserReader.Result browser = BrowserReader.read(appContext, normalized, 6);
                recordWebStrategy("browser_read", normalized, browser.error == null,
                        browser.error, browser.error == null);
                if (browser.error == null) {
                    return okJson(packWebResult(browser.data));
                }
                browserError = "; Login browser reading failed:" + browser.error;
            } else if (!browserAllowed) {
                browserError = "; User is not authorized to read with login browser";
            } else {
                browserError = "; Accessibility services are not enabled, and cannot be read with login browser";
            }
            if (result.error == null) {
                return okJson(result.data + "\n\n[JS Rendering failed:" + rendered.error
                        + browserError + "]");
            }
            return errorJson(result.error + "; JS Rendering also failed:" + rendered.error
                    + browserError);
        }
        return result.error != null ? errorJson(result.error) : okJson(packWebResult(result.data));
    }

    /** Keep a short preview in chat and write the full page to the workspace. */
    private String packWebResult(String data) {
        if (data == null) {
            return "";
        }
        if (data.length() <= 5000) {
            return data;
        }
        String path = ".agent/web/last-page.txt";
        try {
            File file = workspace.resolve(path);
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            writeTextToFile(file, data);
            String preview = data.substring(0, 4500);
            return preview + "\n\n...[Preview Break]\n\n[Full text " + data.length()
                    + " Word saved:`" + path + "`. Please respond immediately to the user based on the above preview;"
                    + "We need more details. read_file Read this file, don't use it. list_files/termux Find.]";
        } catch (Exception e) {
            return data.length() > 8000 ? data.substring(0, 8000) + "\n...[Interrupt]" : data;
        }
    }

    private String webSearch(String query) {
        if (!webToolAllowed()) {
            return errorJson("Networking tool not started. Please go to Settings → Harness'The blog is a blog that has been created to promote the Internet capture."
                    + "Or specifically.'Search...'Try again after that.");
        }
        WebTools.Result result = WebTools.search(query);
        return result.error != null ? errorJson(result.error) : okJson(result.data);
    }

    /**
     * Photo search: Download thumbnails directly after the candidate is retrieved, go download_file The pictures are logical.
     * Next round of models will be feed/The candidates are seen in the news and must be visualized to determine whether or not they match.
     */
    private String imageSearchTool(JSONObject args) {
        if (!webToolAllowed()) {
            return errorJson("Networking tool not started. Please go to Settings → Harness'The blog is a blog that has been created to promote the Internet capture."
                    + "Or specifically.'Search for pictures...'Try again after that.");
        }
        String query = args == null ? "" : args.optString("query", "").trim();
        if (query.isEmpty()) {
            return errorJson("Missing query(Photo keyword to search for)");
        }
        int count = args.optInt("count", 3);
        if (count < 1) count = 1;
        if (count > 4) count = 4;
        WebTools.ImageSearchResult result = WebTools.searchImages(query);
        if (result.error != null) {
            return errorJson(result.error + "; We can change the keywords, or we can use them first. web_search Look for pictures.");
        }
        String stamp = String.valueOf(System.currentTimeMillis());
        JSONArray candidates = new JSONArray();
        int downloaded = 0;
        for (int i = 0; i < result.candidates.size() && i < count; i++) {
            WebTools.ImageCandidate candidate = result.candidates.get(i);
            String url = candidate.thumbUrl.isEmpty() ? candidate.imageUrl : candidate.thumbUrl;
            if (url.isEmpty()) {
                continue;
            }
            String path = "image-search/" + stamp + "-" + (i + 1)
                    + imageExtensionForUrl(url);
            String downloadResult = downloadFileTool(url, path, "", 0L);
            JSONObject item = new JSONObject();
            try {
                item.put("index", i + 1);
                item.put("title", candidate.title);
                item.put("image_url", candidate.imageUrl);
                item.put("thumb_url", candidate.thumbUrl);
                item.put("page_url", candidate.pageUrl);
                if (toolError(downloadResult) == null) {
                    item.put("local_path", path);
                    item.put("status", "downloaded");
                    downloaded++;
                } else {
                    item.put("status", "download_failed");
                    item.put("error", shorten(toolError(downloadResult), 300));
                }
                candidates.put(item);
            } catch (Exception ignored) {
            }
        }
        try {
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("query", query);
            out.put("source", "Bing Images");
            out.put("candidates", candidates);
            out.put("downloaded", downloaded);
            out.put("note", "The candidate thumbnails have been downloaded and will be attached to the model and shown in the feed. "
                    + "Next round must see the main subject by image/Style/Clarity; change the candidate if you do not match,"
                    + "Do not make a determination on the title or on the web summary alone.");
            return out.toString();
        } catch (Exception e) {
            return errorJson("Failed to construct the photo search result");
        }
    }

    private static String imageExtensionForUrl(String url) {
        try {
            String path = new java.net.URL(url).getPath().toLowerCase(Locale.ROOT);
            if (path.endsWith(".png")) return ".png";
            if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return ".jpg";
            if (path.endsWith(".webp")) return ".webp";
            if (path.endsWith(".gif")) return ".gif";
        } catch (Exception ignored) {
        }
        return ".jpg";
    }

    private boolean attachDownloadedImage(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        if (imageMimeOf(file.getName()) == null) {
            return false;
        }
        long size = file.length();
        if (size <= 0L || size > MAX_IMAGE_VIEW_BYTES) {
            return false;
        }
        try {
            byte[] bytes = readBytes(file, (int) size);
            synchronized (roundShots) {
                if (roundShots.size() >= 6) {
                    return false;
                }
                roundShots.add(ScreenCaptureService.CaptureResult.success(
                        bytes, file.getAbsolutePath()));
            }
            return true;
        } catch (Exception e) {
            Log.w("AgentImage", "attach downloaded image failed", e);
            return false;
        }
    }

    String selfCheck() {
        StringBuilder report = new StringBuilder("CRLM Self-inspection\n");
        List<String> fixes = new ArrayList<>();
        int okCount = 0;
        int warnCount = 0;
        try {
            android.content.pm.PackageInfo info = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0);
            report.append("Version:").append(info.versionName)
                    .append("(").append(info.versionCode).append(")\n");
        } catch (Exception ignored) {
        }

        try {
            File tmp = new File(workspace.root(), ".agent/tmp");
            if (!tmp.isDirectory() && !tmp.mkdirs()) {
                throw new Exception("Could not create .agent/tmp");
            }
            File probe = new File(tmp, "selfcheck-" + System.currentTimeMillis());
            FileOutputStream output = new FileOutputStream(probe);
            output.write("ok".getBytes(StandardCharsets.UTF_8));
            output.close();
            boolean readable = probe.isFile() && probe.length() == 2;
            probe.delete();
            if (!readable) {
                throw new Exception("Unread After Writing");
            }
            okCount++;
            report.append("[OK] Workspace readable:").append(workspace.label()).append('\n');
        } catch (Exception e) {
            warnCount++;
            report.append("[FAIL] The workspace is not written:").append(e.getMessage()).append('\n');
            fixes.add("Check storage privileges and current session workspace");
        }

        long usable = baseWorkspace.baseRoot().getUsableSpace();
        report.append(usable > 0 ? "[OK] Available storage:" + formatBytes(usable)
                : "[WARN] Unable to read available storage").append('\n');
        if (usable > 0 && usable < 512L * 1024L * 1024L) {
            warnCount++;
            fixes.add("Insufficient storage available 512MB, Clean up before downloading models or building");
        }

        if (prefs.hasKey()) {
            okCount++;
            report.append("[OK] Clouds API Key Configured\n");
        } else {
            warnCount++;
            report.append("[WARN] No cloud configured API Key(Local models are still available)\n");
            fixes.add("Fill in global settings API Key, Or switch local models");
        }

        String enabled = Settings.Secure.getString(appContext.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean accessibility = enabled != null
                && enabled.contains(appContext.getPackageName());
        if (accessibility) {
            okCount++;
            report.append("[OK] Accessibility services are in place\n");
        } else {
            warnCount++;
            report.append("[WARN] Accessibility is not operational,ui_tap/ui_dump Not Available\n");
            fixes.add("Reopen in System SettingsCRLM Interface Operation");
        }
        boolean storagePermission = workspace.hasStoragePermission();
        report.append(storagePermission ? "[OK] Storage permission granted\n"
                : "[WARN] Storage Permissions Not Granted\n");
        if (!storagePermission) {
            fixes.add("Restart after you have given storage permission App");
        }

        boolean backend = LocalLlm.available();
        report.append(backend ? "[OK] Local reasoning library available\n"
                : "[FAIL] Local reasoning library not available (liblocal_llm.so)\n");
        if (!backend) {
            warnCount++;
            fixes.add("Reinstall Complete APK(Organisation lib/arm64-v8a)");
        }

        LocalAi ai = LocalAi.get(appContext);
        List<File> models = ai.models();
        int goodModels = 0;
        int badModels = 0;
        StringBuilder badDetail = new StringBuilder();
        for (File file : models) {
            String problem = LocalAi.verifyGguf(file, LocalAi.specForFile(file));
            if (problem == null) {
                goodModels++;
            } else {
                badModels++;
                badDetail.append(file.getName()).append(": ").append(problem).append("; ");
            }
        }
        report.append("[INFO] Local models:").append(goodModels).append(" It's normal.");
        if (badModels > 0) {
            report.append(", ").append(badModels).append(" An anomaly:")
                    .append(badDetail);
            fixes.add("Open the Local Model page, verify and reset the anomaly model");
        }
        report.append('\n');
        if (models.isEmpty()) {
            warnCount++;
            fixes.add("Download one from the local model market. GGUF");
        } else {
            okCount++;
        }
        if (ai.isDownloading()) {
            report.append("[INFO] Downloading ").append(ai.downloadingName())
                    .append(" ").append(ai.downloadingPercent()).append("%\n");
        }
        File selected = ai.selectedFile();
        if (selected != null) {
            report.append("[INFO] Current local model:").append(selected.getName())
                    .append(" · ").append(formatBytes(selected.length())).append('\n');
        }

        if (TermuxBridge.isInstalled(appContext) || Rootfs.get(appContext).ready()) {
            File toolchain = new File(baseWorkspace.baseRoot(), "toolchain");
            boolean toolchainOk = new File(toolchain, "android.jar").isFile()
                    && new File(toolchain, "d8.jar").isFile()
                    && new File(toolchain, "apksigner.jar").isFile()
                    && (new File(toolchain, "crlm-debug.keystore").isFile()
                    || new File(toolchain, "mobileagent-debug.keystore").isFile()
                    || new File(toolchain, "agent-debug.keystore").isFile());
            report.append(toolchainOk ? "[OK] Build Tool Chain File Complete\n"
                    : "[FAIL] The construction of the tool chain is missing android.jar/d8.jar/apksigner.jar/keystore\n");
            if (!toolchainOk) {
                warnCount++;
                fixes.add("Sync toolchain Build after Directory");
            }
            report.append("[INFO] Termux installed; routine commands built-in shell_run, "
                    + "Build/Advanced command detection is available. build_apk or termux_run(The project will be independently verified.\n");
            okCount++;
        } else {
            warnCount++;
            report.append("[WARN] Not installed Termux, Could not build locally on mobile phone APK\n");
            fixes.add("Install Termux And give it to him. RUN_COMMAND Permissions");
        }

        try {
            CodeIndex.Status status = CodeIndex.get(workspace).status();
            report.append("[INFO] Code index:").append(status.state)
                    .append(", Version ").append(status.version)
                    .append(", Estimate ").append(status.estimatedBytes / 1024)
                    .append(" KB, Semantic Channels ").append(status.semantic).append('\n');
            okCount++;
        } catch (Throwable e) {
            warnCount++;
            report.append("[WARN] Code index status not available:")
                    .append(e.getClass().getSimpleName()).append('\n');
        }
        boolean patchOk = "OK".equals(PatchTool.selfTest());
        report.append(patchOk ? "[OK] apply_patch Self-check Passed\n"
                : "[FAIL] apply_patch Self-check failed\n");
        if (patchOk) {
            okCount++;
        } else {
            warnCount++;
            fixes.add("apply_patch Parsing anomaly. Check. PatchTool");
        }

        if (conversation != null) {
            ContextInfo info = contextInfo();
            report.append("[INFO] Current dialogue:").append(conversation.title)
                    .append(" · Context ").append(info.percent).append("%(About ")
                    .append(info.usedTokens).append("/").append(info.budgetTokens)
                    .append(" tokens)· Message ").append(messageCount())
                    .append(" Article · Planned ").append(conversation.plan.length())
                    .append(" Step\n");
            if (conversation.paused) {
                report.append("[INFO] Mission is suspended, but can be restored with a little 'continue'\n");
            }
            if (running) {
                report.append("[INFO] Mandates are in progress: ").append(currentStep)
                        .append("/").append(maxSteps).append(" Wheel\n");
            }
        }

        report.append("\nConclusions").append(warnCount == 0
                ? "All critical checks passed (" + okCount + " (Items)"
                : "Completed " + okCount + " Item, requiring attention " + warnCount + " Item").append('\n');
        if (!fixes.isEmpty()) {
            report.append("Recommendations:\n");
            for (String fix : fixes) {
                report.append("- ").append(fix).append('\n');
            }
        }
        return report.toString();
    }

    private String buildApk(String project) {
        if (!prefs.allowDeviceControl()) {
            return errorJson("Device control not open, unable to build APK");
        }
        String projectArg = project == null || project.trim().isEmpty()
                ? "." : project.trim();
        if (!workspace.hasStoragePermission()) {
            return errorJson("You need to store permission to get Termux Visit to the workspace");
        }
        try {
            File projectDir = workspace.resolve(projectArg);
            if (!projectDir.isDirectory()) {
                return errorJson("The project directory does not exist:" + projectArg);
            }
            File manifest = new File(projectDir, "AndroidManifest.xml");
            File src = new File(projectDir, "src");
            File res = new File(projectDir, "res");
            if (!manifest.isFile() || !src.isDirectory() || !res.isDirectory()) {
                return errorJson("Project structure is incomplete. AndroidManifest.xml, src/ and res/values/strings.xml");
            }
            File toolchain = new File(baseWorkspace.baseRoot(), "toolchain");
            if (!new File(toolchain, "android.jar").isFile()
                    || !new File(toolchain, "d8.jar").isFile()
                    || !new File(toolchain, "apksigner.jar").isFile()) {
                return errorJson("Local build tool chain missing:toolchain As required android.jar, "
                        + "d8.jar, apksigner.jar");
            }
            File keystore = new File(toolchain, "crlm-debug.keystore");
            String alias = "crlm";
            if (!keystore.isFile()) {
                keystore = new File(toolchain, "mobileagent-debug.keystore");
                alias = "mobileagent";
            }
            if (!keystore.isFile()) {
                keystore = new File(toolchain, "agent-debug.keystore");
                alias = "agentdebug";
            }
            if (!keystore.isFile()) {
                return errorJson("Missing signature keystore: toolchain/crlm-debug.keystore");
            }
            if (!Rootfs.get(appContext).ready() && !TermuxBridge.isInstalled(appContext)) {
                return errorJson("There's no self-taught tool chain, no installed. Termux, Cannot build");
            }

            String projectPath = projectDir.getAbsolutePath();
            String projectName = projectDir.getName();
            // Prefer the app's own userland; fall back to Termux only when it is not
            // unpacked yet, so self-build no longer depends on an external app.
            final boolean localToolchain = Rootfs.get(appContext).ready();
            String toolPrefix = localToolchain
                    ? Rootfs.get(appContext).usrDir().getAbsolutePath() + "/bin"
                    : "/data/data/com.termux/files/usr/bin";
            File buildRootDir = localToolchain
                    ? new File(appContext.getFilesDir(), ".agent-build") : null;
            String buildRoot = localToolchain
                    ? buildRootDir.getAbsolutePath() : "$HOME/.agent-build";
            final File buildLock = buildRootDir == null ? null
                    : new File(buildRootDir, projectName + ".lock");
            String command = "set -e\n"
                    + "export PATH=" + shellQuote(toolPrefix) + ":$PATH\n"
                    + "P=" + shellQuote(projectPath) + "\n"
                    + "T=" + shellQuote(toolchain.getAbsolutePath()) + "\n"
                    + "K=" + shellQuote(keystore.getAbsolutePath()) + "\n"
                    + "ALIAS=" + alias + "\n"
                    + "B=\"" + buildRoot + "/" + projectName.replace("\"", "") + "\"\n"
                    + "LOCK=\"$B.lock\"\n"
                    + "for tool in java javac aapt2 zip; do\n"
                    + "  if ! command -v \"$tool\" >/dev/null 2>&1; then\n"
                    + "    echo \"MISSING_TOOL=$tool\"; echo \"BUILD_FAILED stage=preflight\"; exit 91\n"
                    + "  fi\n"
                    + "done\n"
                    + "mkdir -p \"${LOCK%/*}\" 2>/dev/null\n"
                    + "if ! mkdir \"$LOCK\" 2>/dev/null; then\n"
                    + "  MT=$(stat -c %Y \"$LOCK\" 2>/dev/null)\n"
                    + "  if [ -n \"$MT\" ]; then AGE=$(( $(date +%s) - MT )); else AGE=0; fi\n"
                    + "  if [ -n \"$MT\" ] && [ \"$AGE\" -gt 600 ]; then\n"
                    + "    echo \"[build] Clear the residual build lock ($AGE Stays a second ago, most of them the last time the building was killed.\"\n"
                    + "    rm -rf \"$LOCK\"; mkdir \"$LOCK\" 2>/dev/null "
                    + "|| { echo \"BUILD_FAILED stage=lock: Could not create build lock\"; exit 92; }\n"
                    + "  else\n"
                    + "    echo \"BUILD_FAILED stage=lock: Another construction is under way ($LOCK)\"; exit 92\n"
                    + "  fi\n"
                    + "fi\n"
                    + "ok=0; stage=init\n"
                    + "cleanup() { code=$?; rm -rf \"$LOCK\"; "
                    + "if [ \"$ok\" != 1 ]; then echo \"BUILD_FAILED stage=$stage exit=$code\"; fi; }\n"
                    + "trap cleanup EXIT\n"
                    + "rm -rf \"$B\"; mkdir -p \"$B/classes\" \"$B/dex\" \"$B/src\" \"$B/gen\" \"$B/assets\"\n"
                    + "cp -r \"$P/src/.\" \"$B/src/\"\n"
                    + "cp \"$P/AndroidManifest.xml\" \"$B/\"\n"
                    + "cp -r \"$P/res\" \"$B/res\"\n"
                    + "if [ -d \"$P/assets\" ]; then cp -r \"$P/assets/.\" \"$B/assets/\"; fi\n"
                    + "rm -f \"$B/assets/source-seed.zip\"\n"
                    + "(cd \"$P\" && zip -qr \"$B/assets/source-seed.zip\" . "
                    + "-x 'build/*' 'app.apk' 'app.apk.tmp' '*.idsig' '*.keystore' '.git/*' "
                    + "'assets/source-seed.zip' 'bootstrap.zip' 'assets/bootstrap.zip' 'toolchain-mac/*')\n"
                    // Resources must be linked before javac: R.java is an aapt2
                    // output, and compiling first breaks every project that uses R.
                    + "stage=resources; echo \"[stage] resources\"; S=$(date +%s)\n"
                    + "aapt2 compile --dir \"$B/res\" -o \"$B/res.zip\"\n"
                    + "aapt2 link -o \"$B/unsigned.apk\" -I \"$T/android.jar\" "
                    + "--manifest \"$B/AndroidManifest.xml\" --auto-add-overlay "
                    + "-A \"$B/assets\" "
                    + "--java \"$B/gen\" \"$B/res.zip\"\n"
                    + "echo \"[build] resources $(( $(date +%s) - S ))s\"\n"
                    + "stage=javac; echo \"[stage] javac\"; S=$(date +%s)\n"
                    + "PARSER=\"$P/deps/javaparser-core-3.26.2.jar\"\n"
                    + "CP=\"$T/android.jar\"; [ -f \"$PARSER\" ] && CP=\"$CP:$PARSER\"\n"
                    + "javac -nowarn -encoding UTF-8 -source 8 -target 8 -classpath \"$CP\" "
                    + "-d \"$B/classes\" $(find \"$B/src\" \"$B/gen\" -name '*.java')\n"
                    + "echo \"[build] javac $(( $(date +%s) - S ))s\"\n"
                    + "stage=d8; echo \"[stage] d8\"; S=$(date +%s)\n"
                    + "DEX_INPUTS=\"$(find \"$B/classes\" -name '*.class')\"\n"
                    + "[ -f \"$PARSER\" ] && DEX_INPUTS=\"$DEX_INPUTS $PARSER\"\n"
                    + "java -cp \"$T/d8.jar\" com.android.tools.r8.D8 --lib \"$T/android.jar\" "
                    + "--min-api 24 --output \"$B/dex\" $DEX_INPUTS\n"
                    + "echo \"[build] d8 $(( $(date +%s) - S ))s\"\n"
                    + "stage=package; echo \"[stage] package\"\n"
                    + "cd \"$B/dex\"; zip -q -u \"$B/unsigned.apk\" classes.dex\n"
                    + "if [ -d \"$P/lib\" ]; then (cd \"$P\" && zip -q -r \"$B/unsigned.apk\" lib); fi\n"
                    + "APK=\"$B/unsigned.apk\"\n"
                    + "if command -v zipalign >/dev/null 2>&1; then\n"
                    + "  zipalign -f 4 \"$B/unsigned.apk\" \"$B/aligned.apk\"; APK=\"$B/aligned.apk\"\n"
                    + "else echo \"[build] zipalign Not installed, Skip Alignment\"; fi\n"
                    + "stage=sign; echo \"[stage] sign\"\n"
                    + "java -jar \"$T/apksigner.jar\" sign "
                    + "--ks \"$K\" --ks-pass pass:android "
                    + "--key-pass pass:android --ks-key-alias \"$ALIAS\" "
                    + "--v1-signing-enabled true --v2-signing-enabled true "
                    + "--out \"$B/app.apk\" \"$APK\"\n"
                    + "stage=verify; echo \"[stage] verify\"\n"
                    + "java -jar \"$T/apksigner.jar\" verify \"$B/app.apk\" >/dev/null\n"
                    + "SIZE=$(stat -c %s \"$B/app.apk\" 2>/dev/null || wc -c < \"$B/app.apk\")\n"
                    + "if [ ! -s \"$B/app.apk\" ] || [ \"$SIZE\" -lt 4096 ]; then echo \"BUILD_FAILED stage=verify: APK Missing or volume abnormality $SIZE\"; exit 93; fi\n"
                    + "if ! unzip -l \"$B/app.apk\" 2>/dev/null | grep -q 'classes.dex'; then echo \"BUILD_FAILED stage=verify: Missing classes.dex\"; exit 94; fi\n"
                    + "if ! unzip -l \"$B/app.apk\" 2>/dev/null | grep -q 'AndroidManifest.xml'; then echo \"BUILD_FAILED stage=verify: Missing AndroidManifest.xml\"; exit 95; fi\n"
                    + "if ! unzip -l \"$B/app.apk\" 2>/dev/null | grep -q 'assets/source-seed.zip'; then echo \"BUILD_FAILED stage=verify: Missing assets/source-seed.zip\"; exit 96; fi\n"
                    + "if [ -f \"$P/assets/lang/zh.json\" ] && ! unzip -l \"$B/app.apk\" 2>/dev/null | grep -q 'assets/lang/zh.json'; then echo \"BUILD_FAILED stage=verify: Missing assets/lang/zh.json\"; exit 97; fi\n"
                    + "cp \"$B/app.apk\" \"$P/app.apk.tmp\" && mv -f \"$P/app.apk.tmp\" \"$P/app.apk\"\n"
                    + "stage=done; ok=1; echo BUILD_OK \"$P/app.apk\" \"$SIZE\"\n";

            Runnable beat = new Runnable() {
                @Override
                public void run() {
                    supervisor.heartbeat(workTicketId, currentRunId, currentStep, maxSteps);
                }
            };
            int buildExit;
            String buildError;
            String rawLog;
            if (localToolchain) {
                // Quiet stages (javac/d8/aapt2) can exceed the normal silence limit, so
                // builds get a 5 minute one instead of 45 seconds.
                ShellRunner.Result local = ShellRunner.run(appContext, workspace, command,
                        900000L, new ShellRunner.Progress() {
                            @Override
                            public void tick() {
                                beat.run();
                            }
                        }, new ShellRunner.ProcessSink() {
                            @Override
                            public void attach(Process process) {
                                activeShellProcess = process;
                                if (process != null && stopRequested) {
                                    process.destroyForcibly();
                                }
                            }
                        }, 300000L);
                buildError = local.error;
                rawLog = local.output;
                buildExit = local.exitCode;
            } else {
                TermuxBridge.Result remote = TermuxBridge.run(
                        appContext, workspace, command, 900000L, beat);
                buildError = remote.error;
                rawLog = remote.output;
                buildExit = remote.exitCode;
            }
            String log = rawLog == null ? "" : rawLog;
            if (buildLock != null && (stopRequested || buildError != null)) {
                // A killed build never reaches its own trap, so the lock is dropped
                // here; otherwise every later build fails with a stale "in progress".
                buildLock.delete();
            }
            if (buildError != null) {
                return errorJson(buildError.startsWith("Termux Command timeout")
                        ? buildError + "\n\nThis is usually a process that hangs up or builds a script that is blocked."
                        + "No, it's not.'It's too much work.'. See the results. stage And the residual log."
                        : buildError);
            }
            if (buildExit != 0) {
                return errorJson("Build failed (exit code) " + buildExit + "), "
                        + "Here's the key log. Press. error:/file:line Fixing the source code or the tool chain,"
                        + "Don't blame it on me.'It's too much work./Timeout'. \n" + buildLogDigest(log));
            }
            File apk = new File(projectDir, "app.apk");
            if (!apk.isFile()) {
                return errorJson("Build End Not Found app.apk\n" + buildLogDigest(log));
            }
            return okJson("Build successful.APK: " + workspace.relativePath(apk)
                    + "\nSize:" + apk.length() + " bytes\n" + buildLogDigest(log));
        } catch (Exception e) {
            return errorJson("Build failed:" + e.getMessage());
        }
    }

    private String installApk(String path) {        if (!prefs.allowDeviceControl()) {
            return errorJson("Device control not open, not installed APK");
        }
        if (path == null || path.trim().isEmpty()) {
            return errorJson("Missing APK Path");
        }
        try {
            File apk = workspace.resolve(path);
            if (!apk.isFile()) {
                return errorJson("APK Cannot initialise Evolution's mail component." + path);
            }
            String relative = workspace.baseRelativePath(apk);
            InstallWatcher.Target target = InstallWatcher.archiveTarget(appContext, apk.getAbsolutePath());
            int installedBefore = target == null ? -1
                    : InstallWatcher.installedVersion(appContext, target.packageName);
            boolean selfUpdate = target != null
                    && InstallWatcher.isSelfPackage(appContext, target.packageName);
            if (selfUpdate) {
                // The process will be terminated by the self-renewal:'Dialogue pending the results of the installation'Set the wheel. Reopen it. App Auto-repeat after.
                InstallWatcher.rememberPendingConversation(appContext, target.packageName,
                        InstallWatcher.activeConversation(appContext));
            }
            // Bottom: Even if you can't read the package name, just... APK Path points to the application ' s own build product and is also treated as self-renewal.
            if (!selfUpdate && relative != null && relative.contains("mobile-agent")) {
                selfUpdate = true;   // We have to synchronize the real thing, or we'll be there right after the installation is complete. clearPendingConversation Clear it.
                InstallWatcher.rememberPendingConversation(appContext, appContext.getPackageName(),
                        InstallWatcher.activeConversation(appContext));
            }
            android.util.Log.i("InstallWatcher", "install start: pkg="
                    + (target == null ? "?" : target.packageName)
                    + " self=" + selfUpdate
                    + " pending=" + InstallWatcher.hasPendingConversation(appContext)
                    + " conv=" + InstallWatcher.activeConversation(appContext));
            android.net.Uri.Builder builder = new android.net.Uri.Builder()
                    .scheme("content")
                    .authority(ApkProvider.AUTHORITY);
            for (String segment : relative.split("/")) {
                if (!segment.isEmpty()) {
                    builder.appendPath(segment);
                }
            }
            android.net.Uri uri = builder.build();
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
            boolean assisted = false;
            if (prefs.allowInstallAutomation()) {
                DeviceSecretStore secret = new DeviceSecretStore(appContext);
                if (secret.hasSecret()) {
                    assisted = InstallAssistant.start(appContext, secret, 120000L);
                }
            }
                        if (target == null) {
                return okJson("System installer opened:" + relative
                        + ". Cannot From APK Read the package name and the installation results are not automatically verified. Please confirm this on your own after installation has been completed.");
            }
            // Just a little bit. 3 sec for quick determination; every time incomplete 20 - I'll review it in seconds. 6 Stop after a second.
            InstallWatcher.Poll poll = InstallWatcher.pollInstalled(appContext, target.packageName,
                    target.versionCode, installedBefore, 3000L, 20000L, 6, 130000L);
            if (poll.confirmed) {
                // Note: Never be left in a state of suspense when updating.--The process will soon be killed and it will be on its own to resume dialogue.
                // Only non-self-renewal (packing of others) is cleared to avoid the next recovery by error.
                if (!selfUpdate) {
                    InstallWatcher.clearPendingConversation(appContext);
                }
                return okJson("Installation success:" + target.packageName
                        + ", Current Version " + poll.version
                        + "(Round " + poll.attempts + " Number of times / " + (poll.elapsedMs / 1000) + " sec)"
                        + (assisted ? ", Installing aids is already used in the password box." : "")
                        + "\nNext steps could be pursued directly.");
            }
            return okJson("System installer opened:" + relative
                    + ", However, the results of the installation are not confirmed (requirement) " + poll.attempts + " Number of times / "
                    + (poll.elapsedMs / 1000) + " seconds, installed version " + poll.version + "). "
                    + (selfUpdate
                    ? "This is self-updating: if the installation is completed, the application will be restarted and will automatically return to this dialogue."
                    : "It may still be waiting for confirmation in the system; equipment may be available later to check to confirm whether the installation was successful."));
        } catch (Exception e) {
            return errorJson("Installer startup failed:" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()));
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private Capabilities capabilities() {
        if (activeCapabilities == null) {
            activeCapabilities = new Capabilities(baseWorkspace);
        }
        return activeCapabilities;
    }

    private String remember(String note) {
        if (note == null || note.trim().isEmpty()) {
            return errorJson("Missing memory content");
        }
        boolean added = capabilities().appendMemory(note);
        return okJson(added ? "Long-term memory written:" + note.trim()
                : "This memory exists and is not repeated");
    }

    int appendMemories(List<String> notes) {
        if (notes == null) {
            return 0;
        }
        int added = 0;
        for (String note : notes) {
            if (capabilities().appendMemory(note)) {
                added++;
            }
        }
        return added;
    }

    void suggestMemories(final MemoryCallback callback) {
        if (running) {
            callback.onMemories(null, "Mission is in progress, and then the memory is extracted.");
            return;
        }
        final String transcript = transcriptForReview(9000, false);
        if (transcript.isEmpty()) {
            callback.onMemories(null, "The current conversation is not sufficiently textual.");
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONArray request = new JSONArray();
                    JSONObject system = new JSONObject();
                    system.put("role", "system");
                    system.put("content", "You're a long-term memory organizer. You only extract stable, reusable information from the conversation:"
                            + "Long-term preferences of users, working methods, project constraints, important facts."
                            + "Do not record one-time task details, original chats, keys, accounts, passwords,token or other sensitive information."
                            + "Most 5 Article, every sentence in Chinese, does not repeat."
                            + "Output only JSON string arrays, e.g. [\"User preference for a simple Chinese response\"]; No content output suitable []. ");
                    request.put(system);
                    JSONObject user = new JSONObject();
                    user.put("role", "user");
                    user.put("content", transcript);
                    request.put(user);
                    LlmClient.Result result = llm.chat(request, new JSONArray(),
                            apiKeyForCurrent(), conversation);
                    if (result.error != null) {
                        postMemories(callback, null, "Refining failed:" + result.error);
                        return;
                    }
                    final List<String> suggestions = parseMemorySuggestions(
                            JsonText.string(result.message, "content"));
                    postMemories(callback, suggestions,
                            suggestions.isEmpty() ? "No new memories worth saving for long." : "");
                } catch (Exception e) {
                    postMemories(callback, null, "Refining failed:"
                            + (e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage()));
                }
            }
        });
    }

    private void postMemories(final MemoryCallback callback, final List<String> items,
                              final String error) {
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onMemories(items, error);
            }
        });
    }

    private static List<String> parseMemorySuggestions(String content) {
        List<String> items = new ArrayList<>();
        if (content == null || content.trim().isEmpty()) {
            return items;
        }
        String text = content.trim();
        int open = text.indexOf('[');
        int close = text.lastIndexOf(']');
        if (open >= 0 && close > open) {
            try {
                JSONArray array = new JSONArray(text.substring(open, close + 1));
                for (int i = 0; i < array.length(); i++) {
                    addMemorySuggestion(items, array.optString(i, ""));
                }
            } catch (Exception ignored) {
            }
        }
        if (items.isEmpty()) {
            String[] lines = text.split("\n");
            for (String line : lines) {
                String clean = line.replaceFirst("^\\s*[-*\\d., ]+\\s*", "").trim();
                addMemorySuggestion(items, clean);
            }
        }
        return items;
    }

    private static void addMemorySuggestion(List<String> items, String value) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (clean.length() < 3 || clean.length() > 160) {
            return;
        }
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.contains("sk-") || lower.contains("api key")
                || lower.contains("password") || lower.contains("Password")
                || lower.contains("secret")) {
            return;
        }
        if (items.contains(clean) || items.size() >= 5) {
            return;
        }
        items.add(clean);
    }

    void reviewSession(final ReviewCallback callback) {
        if (running) {
            callback.onReview(null, null, "Task is under way, and then reset after completion");
            return;
        }
        final String transcript = transcriptForReview(16000, true);
        if (transcript.isEmpty()) {
            callback.onReview(null, null, "The current conversation has no replayable content");
            return;
        }
        final String stats = sessionStats();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONArray request = new JSONArray();
                    JSONObject system = new JSONObject();
                    system.put("role", "system");
                    system.put("content", "You're a strict retrofet coach. Markdown Rewind."
                            + "It must include:## Objective,## Completion and evidence,## Problems and risks,## Not completed,## Next move."
                            + "Write only what can be supported in the record, distinguishing between proven facts and assumptions, and not fiction."
                            + "Next time, we'll do the most. 3 bar, sorted by value. Total length 300-600 Word, in Chinese.");
                    request.put(system);
                    JSONObject user = new JSONObject();
                    user.put("role", "user");
                    user.put("content", "Session statistics:\n" + stats
                            + "\n\nSession record:\n" + transcript);
                    request.put(user);
                    LlmClient.Result result = llm.chat(request, new JSONArray(),
                            apiKeyForCurrent(), conversation);
                    if (result.error != null) {
                        postReview(callback, null, null, "Could not close temporary folder: %s" + result.error);
                        return;
                    }
                    String review = JsonText.string(result.message, "content").trim();
                    if (review.isEmpty()) {
                        postReview(callback, null, null, "Model does not return the redisc content");
                        return;
                    }
                    String path = saveReview(review, stats);
                    postReview(callback, review, path, null);
                } catch (Exception e) {
                    postReview(callback, null, null, "Could not close temporary folder: %s"
                            + (e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage()));
                }
            }
        });
    }

    private void postReview(final ReviewCallback callback, final String review,
                            final String path, final String error) {
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onReview(review, path, error);
            }
        });
    }

    void suggestSkillDraft(final SkillDraftCallback callback) {
        if (running) {
            callback.onDraft(null, null, null, "Mission in progress. Repositioning skills after completion");
            return;
        }
        final String transcript = transcriptForReview(12000, true);
        if (transcript.isEmpty()) {
            callback.onDraft(null, null, null, "There is no such thing as a conversation.");
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONArray request = new JSONArray();
                    JSONObject system = new JSONObject();
                    system.put("role", "system");
                    system.put("content", "You're a skill editor. You're a process that can be used again on this successful mission. Skill, "
                            + "Write only those steps that have been verified, without one-off details, keys or privacy."
                            + "Output only JSON object, format:{\"name\":\"kebab-case-name\","
                            + "\"description\":\"A Chinese description\","
                            + "\"body\":\"Markdown, Organisation ## Apply scenes,## Steps,## Validation,## Common pits\"}. "
                            + "name (a) Lowercase letters, numbers and hyphens only;body 300-900 Word.");
                    request.put(system);
                    JSONObject user = new JSONObject();
                    user.put("role", "user");
                    user.put("content", transcript);
                    request.put(user);
                    LlmClient.Result result = llm.chat(request, new JSONArray(),
                            apiKeyForCurrent(), conversation);
                    if (result.error != null) {
                        postSkill(callback, null, null, null,
                                "Could not close temporary folder: %s" + result.error);
                        return;
                    }
                    String content = JsonText.string(result.message, "content").trim();
                    int open = content.indexOf('{');
                    int close = content.lastIndexOf('}');
                    if (open < 0 || close <= open) {
                        postSkill(callback, null, null, null, "The draft skills format is incorrect");
                        return;
                    }
                    JSONObject draft = new JSONObject(content.substring(open, close + 1));
                    String name = sanitizeSkillName(draft.optString("name", ""));
                    String description = draft.optString("description", "").trim();
                    String body = draft.optString("body", "").trim();
                    if (name.isEmpty() || body.isEmpty()) {
                        postSkill(callback, null, null, null, "Draft skills lack name or step");
                        return;
                    }
                    postSkill(callback, name, description, body, null);
                } catch (Exception e) {
                    postSkill(callback, null, null, null, "Could not close temporary folder: %s"
                            + (e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage()));
                }
            }
        });
    }

    private void postSkill(final SkillDraftCallback callback, final String name,
                           final String description, final String body,
                           final String error) {
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onDraft(name, description, body, error);
            }
        });
    }

    private static String sanitizeSkillName(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (clean.isEmpty()) {
            clean = "learned-skill-" + (System.currentTimeMillis() % 100000);
        }
        if (clean.length() > 48) {
            clean = clean.substring(0, 48);
        }
        return clean;
    }

    private String saveReview(String review, String stats) {
        try {
            File dir = workspace.resolve(".agent/reviews");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return "";
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(new java.util.Date());
            String name = "review-" + (conversation == null ? "session" : conversation.id)
                    + "-" + stamp + ".md";
            File file = new File(dir, name);
            String header = "# Session Rewind · " + currentTitle() + "\n\n"
                    + "- Time:" + new SimpleDateFormat("yyyy-MM-dd HH:mm",
                    Locale.US).format(new java.util.Date()) + "\n"
                    + "- Models:" + (conversation == null ? "-" : conversation.model) + "\n"
                    + "- Workspace:" + workspace.label() + "\n\n"
                    + "## Statistics\n" + stats + "\n\n---\n\n";
            writeTextToFile(file, header + review + "\n");
            return workspace.relativePath(file);
        } catch (Exception e) {
            return "";
        }
    }

    private String sessionStats() {
        int user = 0;
        int assistant = 0;
        int tools = 0;
        int errors = 0;
        synchronized (entriesLock) {
            for (Entry entry : entries) {
                if (entry.type == Entry.USER) {
                    user++;
                } else if (entry.type == Entry.ASSISTANT) {
                    assistant++;
                } else if (entry.type == Entry.TOOL) {
                    tools++;
                } else if (entry.type == Entry.ERROR) {
                    errors++;
                }
            }
        }
        int done = 0;
        int total = 0;
        JSONArray plan = getPlan();
        for (int i = 0; i < plan.length(); i++) {
            JSONObject step = plan.optJSONObject(i);
            if (step != null) {
                total++;
                if ("completed".equals(step.optString("status", ""))) {
                    done++;
                }
            }
        }
        ContextInfo info = contextInfo();
        return "User Message " + user + " -Long, the assistant. " + assistant + " bar, tool call "
                + tools + " Once, wrong. " + errors + " Number of meetings; planned " + done + "/" + total
                + " completed; context approximate " + info.usedTokens + "/" + info.budgetTokens
                + " tokens(" + info.percent + "%). ";
    }

    private String transcriptForReview(int maxChars, boolean includeTools) {
        List<Entry> snapshot;
        synchronized (entriesLock) {
            snapshot = new ArrayList<>(entries);
        }
        java.util.LinkedList<String> lines = new java.util.LinkedList<>();
        int size = 0;
        for (int i = snapshot.size() - 1; i >= 0 && size < maxChars; i--) {
            Entry entry = snapshot.get(i);
            String line;
            if (entry.type == Entry.USER) {
                line = "User:" + oneLine(entry.text, 600);
            } else if (entry.type == Entry.ASSISTANT) {
                line = "Assistant:" + oneLine(entry.text, 600);
            } else if (entry.type == Entry.ERROR) {
                line = "Error:" + oneLine(entry.text, 300);
            } else if (entry.type == Entry.SYSTEM) {
                line = "System:" + oneLine(entry.text, 200);
            } else if (entry.type == Entry.TOOL && includeTools) {
                line = "Tools:" + oneLine(toolNameOnly(entry.text), 120);
            } else {
                continue;
            }
            if (line.trim().isEmpty() || "Tools:".equals(line)) {
                continue;
            }
            lines.addFirst(line);
            size += line.length() + 1;
        }
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line).append('\n');
        }
        return builder.toString().trim();
    }

    private static String toolNameOnly(String text) {
        if (text == null) {
            return "";
        }
        String body = text.trim();
        if (body.startsWith("Call ")) {
            body = body.substring(3).trim();
        } else if (body.startsWith("Tools ")) {
            body = body.substring(3).trim();
        }
        int space = body.indexOf(' ');
        return space < 0 ? body : body.substring(0, space);
    }

    private String listSkills() {
        List<Capabilities.SkillInfo> skills = capabilities().listSkills();
        if (skills.isEmpty()) {
            return okJson("There's no skills at this time. .agent/skills/<name>/SKILL.md Create skills.");
        }
        StringBuilder builder = new StringBuilder();
        for (Capabilities.SkillInfo skill : skills) {
            builder.append(skill.name);
            if (skill.description != null && !skill.description.isEmpty()) {
                builder.append(": ").append(skill.description);
            }
            builder.append('\n');
        }
        return okJson(builder.toString());
    }

    private String readSkill(String name) {
        if (name == null || name.trim().isEmpty()) {
            return errorJson("Lack of skills names");
        }
        String content = capabilities().readSkill(name);
        if (content.isEmpty()) {
            return errorJson("Skills do not exist:" + name);
        }
        return okJson(limit(content, 12000));
    }

    private String searchKnowledge(String query) {
        if (query == null || query.trim().isEmpty()) {
            return errorJson("Lack of search");
        }
        List<Capabilities.SearchHit> hits = capabilities().searchKnowledge(query);
        if (hits.isEmpty()) {
            return okJson("The local knowledge base did not find the relevant content.");
        }
        StringBuilder builder = new StringBuilder();
        for (Capabilities.SearchHit hit : hits) {
            builder.append("[").append(hit.file).append("] Relevance ")
                    .append(hit.score).append('\n')
                    .append(hit.snippet).append("\n\n");
        }
        return okJson(limit(builder.toString(), 8000));
    }

    private String uiTap(JSONObject args) throws Exception {
        String text = args.optString("text", "");
        int x = args.optInt("x", Integer.MIN_VALUE);
        int y = args.optInt("y", Integer.MIN_VALUE);
        AgentAccessibilityService service = requireAccessibility();
        if (!text.isEmpty()) {
            service.tapByText(text);
            return okJson("Clicked:" + text);
        }
        if (x != Integer.MIN_VALUE && y != Integer.MIN_VALUE) {
            service.tapAt(x, y);
            return okJson("Clicked on coordinates:" + x + "," + y);
        }
        return errorJson("Please provide text or x/y");
    }

    private String uiSetText(String target, String text) throws Exception {
        if (target.isEmpty()) {
            return errorJson("Missing input box matching text");
        }
        AgentAccessibilityService service = requireAccessibility();
        service.setText(target, text);
        return okJson("Entered to:" + target);
    }

    private String uiScroll(String direction) throws Exception {
        AgentAccessibilityService service = requireAccessibility();
        service.swipe(direction);
        return okJson("Slided:" + direction);
    }

    private String systemAction(String action) throws Exception {
        AgentAccessibilityService service = requireAccessibility();
        service.globalAction(action);
        return okJson("System operations implemented:" + action);
    }

    private AgentAccessibilityService requireAccessibility() throws Exception {
        if (!prefs.allowDeviceControl()) {
            throw new IllegalStateException(
                    "Device control is not open. Please click 'Equipment' at the top right corner to open other operations allowed App. ");
        }
        AgentAccessibilityService service = AgentAccessibilityService.getInstance();
        if (service == null) {
            throw new IllegalStateException(
                    "Accessibility services are not enabled. Please enable in the system settingsCRLM The interface operation.'");
        }
        return service;
    }

    private static byte[] readBytes(File file, int max) throws IOException {
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
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private Entry addEntry(int type, String text, boolean notify) {
        return addEntry(type, text, notify, null, null);
    }

    private Entry addEntry(int type, String text, boolean notify, List<String> images) {
        return addEntry(type, text, notify, images, null);
    }

    private Entry addEntry(int type, String text, boolean notify, List<String> images,
                           List<String> files) {
        Entry entry = new Entry(type, text, System.currentTimeMillis(), images, files);
        synchronized (entriesLock) {
            entries.add(entry);
            while (entries.size() > MAX_ENTRIES) {
                entries.remove(0);
            }
        }
        if (type == Entry.ERROR) {
            reportAgentError(text);
        }
        notifyUpdate();
        return entry;
    }

    private void updateEntryText(Entry entry, String text) {
        if (entry == null) {
            return;
        }
        synchronized (entriesLock) {
            entry.text = text == null ? "" : text;
        }
    }

    private void notifyUpdate() {
        if (headless) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                Listener current = listener;
                if (current != null) {
                    current.onAgentUpdate();
                }
            }
        });
    }

    private static String okJson(String data) {
        try {
            JSONObject object = new JSONObject();
            object.put("ok", true);
            object.put("data", data);
            return object.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"Result tectonic failure\"}";
        }
    }

    private static String errorJson(String message) {
        try {
            JSONObject object = new JSONObject();
            object.put("ok", false);
            object.put("error", message == null ? "Unknown error" : message);
            return object.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"Unknown error\"}";
        }
    }

    private static String toolError(String result) {
        try {
            JSONObject object = new JSONObject(result);
            if (object.optBoolean("ok", true)) {
                return null;
            }
            return object.optString("error", "Tool execution failed");
        } catch (Exception e) {
            return "Tool result cannot be parsed";
        }
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String clean = text.replace('\n', ' ').replace('\r', ' ');
        if (clean.length() > max) {
            return clean.substring(0, max) + "...";
        }
        return clean;
    }

    private int messageCount() {
        synchronized (messages) {
            return messages.size();
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private static String buildLogDigest(String log) {
        if (log == null || log.trim().isEmpty()) {
            return "(Build without output)";
        }
        String[] lines = log.split("\n", -1);
        List<String> picked = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String lower = line.toLowerCase(Locale.ROOT);
            boolean important = lower.contains("error:")
                    || lower.contains("Error:")
                    || lower.contains("exception")
                    || lower.contains("failed")
                    || lower.contains("missing_tool")
                    || lower.contains("missing ")
                    || line.startsWith("[stage]")
                    || line.startsWith("[build]");
            if (important) {
                picked.add(line);
                if (lower.contains("error:") && i + 2 < lines.length) {
                    picked.add(lines[++i]);
                    picked.add(lines[++i]);
                }
            }
        }
        if (picked.isEmpty()) {
            for (int i = Math.max(0, lines.length - 30); i < lines.length; i++) {
                picked.add(lines[i]);
            }
        } else if (picked.size() > 60) {
            picked = picked.subList(picked.size() - 60, picked.size());
        }
        StringBuilder builder = new StringBuilder("Key log:\n");
        for (String line : picked) {
            builder.append(line).append('\n');
        }
        String text = builder.toString().trim();
        if (text.length() > 8000) {
            text = text.substring(text.length() - 8000);
        }
        return text;
    }

    private static String limit(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() > max) {
            return text.substring(0, max) + "\n...[Content has been cut]";
        }
        return text;
    }

    private static final class AppInfo {
        final String label;
        final String packageName;

        AppInfo(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }
}
