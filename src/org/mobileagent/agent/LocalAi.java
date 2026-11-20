package org.mobileagent.agent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalAi {
    static final class ModelSpec {
        final String id;
        final String name;
        final String fileName;
        final String url;
        final String description;
        final String license;
        final long bytes;
        final int minRamMb;

        ModelSpec(String id, String name, String fileName, String url,
                  String description, String license, long bytes, int minRamMb) {
            this.id = id;
            this.name = name;
            this.fileName = fileName;
            this.url = url;
            this.description = description;
            this.license = license;
            this.bytes = bytes;
            this.minRamMb = minRamMb;
        }
    }

    interface ProbeCallback {
        void onResult(boolean ok, String detail);
    }

    private static final ModelSpec[] CATALOG = {
            new ModelSpec("qwen3-0.6b", "Qwen3 0.6B", "qwen3-0.6b-q4_k_m.gguf",
                    "https://hf-mirror.com/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
                    "Fastest. Fits for offline pre-treatment and intent judgement.", "Apache-2.0", 396705472L, 3072),
            new ModelSpec("qwen3-1.7b", "Qwen3 1.7B", "qwen3-1.7b-q4_k_m.gguf",
                    "https://hf-mirror.com/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
                    "(b) Stronger Chinese dialogue and reasoning;Kirin 980 Slower up.", "Apache-2.0", 1107409472L, 6144),
            new ModelSpec("qwen2.5-0.5b", "Qwen2.5 0.5B", "qwen2.5-0.5b-instruct-q4_k_m.gguf",
                    "https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                    "Stable Chinese Command Judgement", "Apache-2.0", 491400032L, 3072),
            new ModelSpec("qwen2.5-coder-0.5b", "Qwen2.5 Coder 0.5B",
                    "qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
                    "https://hf-mirror.com/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
                    "Speculation and Structure Output", "Apache-2.0", 491400064L, 3072),
            new ModelSpec("qwen2.5-1.5b", "Qwen2.5 1.5B",
                    "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                    "https://hf-mirror.com/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                    "Chinese conversation is better quality and slower", "Apache-2.0", 1117320736L, 6144),
            new ModelSpec("llama-3.2-1b", "Llama 3.2 1B",
                    "llama-3.2-1b-instruct-q4_k_m.gguf",
                    "https://hf-mirror.com/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                    "English/Multilingual; following Llama 3.2 Community permit", "Llama-3.2", 807694464L, 4096),
            new ModelSpec("gemma-3-1b", "Gemma 3 1B",
                    "gemma-3-1b-it-q4_k_m.gguf",
                    "https://hf-mirror.com/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
                    "Google small models; following Gemma Use terms", "Gemma", 806058272L, 4096),
            new ModelSpec("deepseek-r1-1.5b", "DeepSeek R1 Distill 1.5B",
                    "deepseek-r1-distill-qwen-1.5b-q4_k_m.gguf",
                    "https://hf-mirror.com/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
                    "Detachment models; Mr. Twisted to reflect, slow to move", "MIT", 1117321312L, 6144),
            new ModelSpec("tinyllama-1.1b", "TinyLlama 1.1B",
                    "tinyllama-1.1b-chat-q4_k_m.gguf",
                    "https://hf-mirror.com/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
                    "Very small memory occupancy; general Chinese capacity", "Apache-2.0", 668788096L, 3072)
    };

    static java.util.List<ModelSpec> catalog() {
        return java.util.Collections.unmodifiableList(
                new java.util.ArrayList<>(java.util.Arrays.asList(CATALOG)));
    }

    static ModelSpec specById(String id) {
        for (ModelSpec spec : CATALOG) {
            if (spec.id.equals(id)) {
                return spec;
            }
        }
        return null;
    }

    static ModelSpec specForFile(File file) {
        if (file == null) {
            return null;
        }
        for (ModelSpec spec : CATALOG) {
            if (spec.fileName.equals(file.getName())) {
                return spec;
            }
        }
        return null;
    }

    static ModelSpec recommendedSpec() {
        return CATALOG[0];
    }

    private static final String RECOMMENDED_NAME = recommendedSpec().fileName;

    static final class ClassifyResult {
        final String kind;
        final String note;
        final String raw;
        final long elapsedMs;
        final String error;

        ClassifyResult(String kind, String note, String raw, long elapsedMs, String error) {
            this.kind = kind == null ? "none" : kind;
            this.note = note == null ? "" : note;
            this.raw = raw == null ? "" : raw;
            this.elapsedMs = elapsedMs;
            this.error = error;
        }

        boolean ok() {
            return error == null;
        }
    }

    public static final class ChatResult {
        public final String text;
        public final String error;

        ChatResult(String text, String error) {
            this.text = text == null ? "" : text;
            this.error = error;
        }
    }

    public interface ChatTokenListener {
        void onToken(String piece);
    }

    interface ClassifyCallback {
        void onResult(ClassifyResult result);
    }

    interface LoadCallback {
        void onDone(boolean ok, String info, String error);
    }

    interface DownloadCallback {
        void onProgress(int percent, long bytes, long total);

        void onDone(File file, String error);

        default void onStatus(String message) {
        }
    }

    interface CopyCallback {
        void onDone(File file, String error);
    }

    private static final String SYSTEM_PROMPT =
            "You're a cell phone.AgentLocal Catalogue. Output only one line, do not explain:\n"
                    + "RISK=<0or1>; KIND=<none|sensitive|impossible|trivial|complex>; "
                    + "NOTE=<No more than12A Han.>\n"
                    + "sensitive=Key/Password/Bank card/ID card/Cell phone number\n"
                    + "impossible=root/Brusher/Around installation/Silent installation/Recover the factory./Transfer of payments/Send Message/Stealing other applications\n"
                    + "trivial=Greetings./Thank you./Ask function\n"
                    + "complex=Multistep or multifile task\n"
                    + "Equipment-level destruction operations, such as plant restoration, formatting, disk removal, etc. impossible, Don't mark it. complex\n"
                    + "Example:\n"
                    + "Hello. -> RISK=0; KIND=trivial; NOTE=Greetings.\n"
                    + "Help me.rootCell phone. -> RISK=1; KIND=impossible; NOTE=root\n"
                    + "keyYes.sk-abcdefghijkl -> RISK=1; KIND=sensitive; NOTE=Key\n"
                    + "Write login pages and test -> RISK=0; KIND=complex; NOTE=Multistep development\n"
                    + "Collate and check files -> RISK=0; KIND=complex; NOTE=Multistep operation\n"
                    + "Reset this phone to factory state -> RISK=1; KIND=impossible; NOTE=Recover the factory.\n"
                    + "Delete the entire disk -> RISK=1; KIND=impossible; NOTE=Delete Disk\n"
                    + "Send me a message to my boss. -> RISK=1; KIND=impossible; NOTE=Send Message\n";

    private static final Pattern KIND_PATTERN = Pattern.compile(
            "(?i)KIND\\s*[=:: ]\\s*([a-zA-Z]+)");
    private static final Pattern NOTE_PATTERN = Pattern.compile(
            "(?i)NOTE\\s*[=:: ]\\s*([^;\\n]+)");
    private static volatile LocalAi instance;

    private final Context appContext;
    private final AppPrefs prefs;
    private final LocalLlm llm = new LocalLlm();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
    private final Object downloadLock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, ClassifyResult> cache =
            Collections.synchronizedMap(new LinkedHashMap<String, ClassifyResult>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ClassifyResult> eldest) {
                    return size() > 24;
                }
            });
    private volatile boolean downloading;
    private volatile boolean downloadCanceled;
    private volatile boolean deletePartOnCancel;
    private volatile String downloadingId = "";
    private volatile int downloadPercent;
    private volatile HttpURLConnection activeDownload;
    private volatile File loadedFile;
    private volatile String loadedInfo = "";
    private volatile String loadError = "";

    private static final class VerifyEntry {
        final long size;
        final long mtime;
        final String problem;
        VerifyEntry(long size, long mtime, String problem) {
            this.size = size;
            this.mtime = mtime;
            this.problem = problem;
        }
    }

    /** GGUF Verify Cache:UI Read the cache, never trigger the full amount here. metadata Scan. */
    private static final Map<String, VerifyEntry> VERIFY_CACHE = new java.util.HashMap<>();
    private static final java.util.Set<String> VERIFYING = new java.util.HashSet<>();

    public static LocalAi get(Context context) {
        if (instance == null) {
            synchronized (LocalAi.class) {
                if (instance == null) {
                    instance = new LocalAi(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private LocalAi(Context context) {
        appContext = context;
        prefs = new AppPrefs(context);
    }

    boolean backendAvailable() {
        return LocalLlm.available();
    }

    File modelDir() {
        File base = new Workspace(appContext).baseRoot();
        File dir = new File(base, "models");
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        return dir;
    }

    File recommendedFile() {
        return new File(modelDir(), RECOMMENDED_NAME);
    }

    List<File> models() {
        List<File> files = new ArrayList<>();
        File[] children = modelDir().listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isFile() && child.getName().toLowerCase(Locale.ROOT)
                        .endsWith(".gguf")) {
                    files.add(child);
                }
            }
        }
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        return files;
    }

    /**
     * UI Just do it.'File Exists + GGUF magic'Level fast judgement, complete. metadata The blogger says:
     * And it's done. (path,size,mtime) Cache; this is the way to secure the main route, not to get stuck. UI Yeah.
     */
    File selectedFile() {
        File file = quickSelectedFile();
        if (file != null) {
            scheduleVerify(file);
        }
        return file;
    }

    private File quickSelectedFile() {
        String path = prefs.localModelPath();
        if (!path.isEmpty()) {
            File file = new File(path);
            if (isPlausibleGguf(file)) {
                return file;
            }
        }
        File recommended = recommendedFile();
        if (isPlausibleGguf(recommended)) {
            return recommended;
        }
        List<File> files = models();
        for (File file : files) {
            if (isPlausibleGguf(file)) {
                return file;
            }
        }
        return files.isEmpty() ? null : files.get(0);
    }

    private static boolean isPlausibleGguf(File file) {
        if (file == null || !file.isFile() || file.length() < 1024L * 1024L) {
            return false;
        }
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            byte[] magic = new byte[4];
            if (input.read(magic) != 4) {
                return false;
            }
            return magic[0] == 'G' && magic[1] == 'G' && magic[2] == 'U' && magic[3] == 'F';
        } catch (Exception e) {
            return false;
        } finally {
            closeQuietly(input);
        }
    }

    private void scheduleVerify(final File file) {
        if (file == null) {
            return;
        }
        final String key = file.getAbsolutePath();
        synchronized (VERIFY_CACHE) {
            if (isVerifyCached(file) || !VERIFYING.add(key)) {
                return;
            }
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    verifyGgufCached(file, specForFile(file));
                } finally {
                    synchronized (VERIFY_CACHE) {
                        VERIFYING.remove(key);
                    }
                }
            }
        });
    }

    /** Use when loading the model: When the whole check is done on the back-stage line, the bad file is returned to another available model. */
    private File verifiedFile() {
        File first = quickSelectedFile();
        if (first != null && verifyGgufCached(first, specForFile(first)) == null) {
            return first;
        }
        for (File file : models()) {
            if (first != null && file.equals(first)) {
                continue;
            }
            if (verifyGgufCached(file, specForFile(file)) == null) {
                return file;
            }
        }
        return null;
    }

    void selectModel(File file) {
        if (file != null && file.isFile()) {
            prefs.setLocalModelPath(file.getAbsolutePath());
        }
    }

    boolean isLoaded() {
        return llm.isLoaded() && loadedFile != null;
    }

    File loadedFile() {
        return loadedFile;
    }

    String loadedInfo() {
        return loadedInfo;
    }

    String loadError() {
        return loadError;
    }

    String statusText() {
        if (!backendAvailable()) {
            return "Local run library not available";
        }
        if (isLoaded()) {
            return "Loaded · " + loadedInfo;
        }
        if (downloading) {
            return "Downloading · " + downloadingName() + " " + downloadPercent + "%";
        }
        File file = selectedFile();
        if (file != null) {
            ModelSpec spec = specForFile(file);
            return "Selected · " + (spec == null ? file.getName() : spec.name)
                    + " · " + formatSize(file.length());
        }
        return "Not installed";
    }

    void loadSelected(final LoadCallback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final File file = verifiedFile();
                if (file == null) {
                    postLoad(callback, false, "", "No available GGUF model, download or import first");
                    return;
                }
                if (!backendAvailable()) {
                    postLoad(callback, false, "", "Local running library failed to package or load");
                    return;
                }
                if (isLoaded() && file.equals(loadedFile)) {
                    postLoad(callback, true, loadedInfo, null);
                    return;
                }
                loadError = "";
                llm.close();
                long start = System.currentTimeMillis();
                boolean ok = tryLoad(file);
                if (!ok) {
                    postLoad(callback, false, "", loadError);
                    return;
                }
                loadedFile = file;
                loadedInfo = llm.info();
                if (loadedInfo == null || loadedInfo.trim().isEmpty()) {
                    loadedInfo = file.getName();
                }
                loadedInfo = loadedInfo + " · " + formatSize(file.length())
                        + " · " + (System.currentTimeMillis() - start) + "ms";
                prefs.setLocalModelPath(file.getAbsolutePath());
                postLoad(callback, true, loadedInfo, null);
            }
        });
    }

    private void postLoad(final LoadCallback callback, final boolean ok,
                          final String info, final String error) {
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onDone(ok, info, error);
            }
        });
    }

    void unload() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                llm.close();
                loadedFile = null;
                loadedInfo = "";
            }
        });
    }

    private void ensureSelectedLoaded() {
        File file = selectedFile();
        if (file == null) throw new IllegalStateException("LOCAL_MODEL_NOT_SELECTED");
        if (!backendAvailable()) throw new IllegalStateException("LOCAL_NATIVE_API_V2_UNAVAILABLE");
        if (!isLoaded() || !file.equals(loadedFile)) {
            if (!tryLoad(file)) throw new IllegalStateException(loadError);
            loadedFile = file;
            loadedInfo = llm.info();
        }
    }

    private boolean tryLoad(File file) {
        try {
            boolean ok = llm.load(file.getAbsolutePath(), 6, 2048);
            if (!ok) loadError = "LOCAL_MODEL_LOAD_FAILED";
            else loadError = "";
            return ok;
        } catch (Exception e) {
            loadedFile = null;
            loadError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return false;
        }
    }

    /** Blocking local-only roles API. Call off the UI thread; thinking is rendered by the actual GGUF Jinja. */
    public ChatResult streamChatRoles(List<LocalModelAdapter.Message> messages, int maxTokens,
                                      float temperature, boolean thinking, ChatTokenListener listener) {
        synchronized (llm) {
            final StringBuilder generated = new StringBuilder();
            try {
                ensureSelectedLoaded();
                String prompt = llm.formatChat(messages, true, thinking);
                boolean ok = llm.generateStream(prompt, maxTokens, temperature, new LocalLlm.TokenListener() {
                    @Override public void onToken(String piece) {
                        generated.append(piece);
                        if (listener != null) listener.onToken(piece);
                    }
                });
                return new ChatResult(generated.toString(), ok ? null : "LOCAL_GENERATION_FAILED");
            } catch (Exception e) {
                return new ChatResult(generated.toString(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
    }

    public String formatChat(List<LocalModelAdapter.Message> messages, boolean addGeneration, boolean thinking) {
        synchronized (llm) { ensureSelectedLoaded(); return llm.formatChat(messages, addGeneration, thinking); }
    }
    public int countTokens(String formattedPrompt) {
        synchronized (llm) { ensureSelectedLoaded(); return llm.countTokens(formattedPrompt); }
    }
    public int countChatTokens(List<LocalModelAdapter.Message> messages, boolean thinking) {
        synchronized (llm) { ensureSelectedLoaded(); return llm.countTokens(llm.formatChat(messages, true, thinking)); }
    }
    public int chatContextSize() {
        synchronized (llm) { ensureSelectedLoaded(); return llm.contextSize(); }
    }
    public GgufMetadata localModelMetadata() {
        synchronized (llm) { ensureSelectedLoaded(); return llm.metadata(); }
    }
    public String localCapability() {
        synchronized (llm) { ensureSelectedLoaded(); return llm.capability(); }
    }

    /** Transitional caller compatibility: validate actual model, decode legacy framing, then re-render. */
    ChatResult streamChat(String prompt, int maxTokens, float temperature, LocalLlm.TokenListener listener) {
        try {
            ChatTokenListener proxy = listener == null ? null : new ChatTokenListener() {
                @Override public void onToken(String piece) { listener.onToken(piece); }
            };
            return streamChatRoles(LocalModelAdapter.legacyMessages(prompt), maxTokens, temperature, false, proxy);
        } catch (Exception e) { return new ChatResult("", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
    }

    public void cancelChat() {
        llm.cancel();
    }

    void classify(final String text, final ClassifyCallback callback) {
        final String input = text == null ? "" : text.trim();
        if (input.isEmpty()) {
            postClassify(callback, new ClassifyResult("none", "", "", 0, null));
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized (llm) {
                    long start = System.currentTimeMillis();
                    try {
                        ensureSelectedLoaded();
                        String cacheKey = llm.metadata().modelSha256 + "|" + llm.metadata().templateSha256
                                + "|classifier-v2|" + input;
                        ClassifyResult cached = cache.get(cacheKey);
                        if (cached != null) { postClassify(callback, cached); return; }
                        String raw = llm.generate(buildPrompt(input), 24, 0.0f);
                        long elapsed = System.currentTimeMillis() - start;
                        if (raw == null || raw.trim().isEmpty()) {
                            postClassify(callback, new ClassifyResult("none", "", "", elapsed, "Local model does not return content"));
                            return;
                        }
                        ClassifyResult result = parse(raw, elapsed);
                        cache.put(cacheKey, result);
                        postClassify(callback, result);
                    } catch (Exception e) {
                        postClassify(callback, new ClassifyResult("none", "", "",
                                System.currentTimeMillis() - start, e.getMessage()));
                    }
                }
            }
        });
    }

    private void postClassify(final ClassifyCallback callback,
                              final ClassifyResult result) {
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onResult(result);
            }
        });
    }

    void warmInBackground() {
        if (!isLoaded()) {
            return;
        }
        File current = loadedFile;
        if (current != null && current.length() > 700L * 1024L * 1024L) {
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                warmUp();
            }
        });
    }

    private void warmUp() {
        try {
            llm.generate(buildPrompt("Hello."), 4, 0.0f);
        } catch (Exception ignored) {
        }
    }

    private String buildPrompt(String input) {
        return llm.formatChat(java.util.Arrays.asList(
                new LocalModelAdapter.Message("system", SYSTEM_PROMPT),
                new LocalModelAdapter.Message("user", input)), true, false);
    }

    private static ClassifyResult parse(String raw, long elapsed) {
        String kind = "none";
        String note = "";
        Matcher kindMatcher = KIND_PATTERN.matcher(raw);
        if (kindMatcher.find()) {
            kind = kindMatcher.group(1).toLowerCase(Locale.ROOT);
        } else {
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.contains("impossible")) {
                kind = "impossible";
            } else if (lower.contains("sensitive")) {
                kind = "sensitive";
            } else if (lower.contains("trivial")) {
                kind = "trivial";
            } else if (lower.contains("complex")) {
                kind = "complex";
            }
        }
        if (!"sensitive".equals(kind) && !"impossible".equals(kind)
                && !"trivial".equals(kind) && !"complex".equals(kind)) {
            kind = "none";
        }
        Matcher noteMatcher = NOTE_PATTERN.matcher(raw);
        if (noteMatcher.find()) {
            note = noteMatcher.group(1).trim();
        }
        if (note.length() > 80) {
            note = note.substring(0, 80) + "...";
        }
        String evidence = (note + " " + raw).toLowerCase(Locale.ROOT);
        if (evidence.contains("Key") || evidence.contains("Password")
                || evidence.contains("Bank card") || evidence.contains("ID card")
                || evidence.contains("Cell phone number") || evidence.contains("api key")) {
            kind = "sensitive";
        } else if (evidence.contains("Recover the factory.") || evidence.contains("Formatting")
                || evidence.contains("Delete Disk") || evidence.contains("Brusher")
                || evidence.contains("root") || evidence.contains("bootloader")
                || evidence.contains("Around") || evidence.contains("Crack")
                || evidence.contains("factory reset") || evidence.contains("wipe data")) {
            kind = "impossible";
        }
        return new ClassifyResult(kind, note, raw.trim(), elapsed, null);
    }

    void downloadRecommended(final DownloadCallback callback) {
        download(recommendedSpec(), callback);
    }

    void download(final ModelSpec spec, final DownloadCallback callback) {
        if (spec == null) {
            callback.onDone(null, "Model does not exist");
            return;
        }
        synchronized (downloadLock) {
            if (downloading) {
                callback.onDone(null, "Downloading " + downloadingName()
                        + ", Please cancel or wait.");
                return;
            }
            downloading = true;
            downloadCanceled = false;
            deletePartOnCancel = false;
            downloadingId = spec.id;
            downloadPercent = 0;
        }
        KeepAliveService.start(appContext);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                File target = new File(modelDir(), spec.fileName);
                File part = partFile(spec);
                try {
                    if (target.isFile()) {
                        String problem = verifyGguf(target, spec);
                        if (problem == null) {
                            prefs.setLocalModelPath(target.getAbsolutePath());
                            postDownload(callback, target, null);
                            return;
                        }
                        File corrupt = new File(target.getAbsolutePath()
                                + ".corrupt-" + System.currentTimeMillis());
                        if (target.renameTo(corrupt)) {
                            postStatus(callback, "File validation failed (%1)" + problem
                                    + "), Renamed to " + corrupt.getName() + ", Start Redownloading");
                        } else {
                            postDownload(callback, null, "The same name file exists but the verification failed:"
                                    + problem + "; Please delete it first. " + target.getName());
                            return;
                        }
                    }
                    long already = part.isFile() ? part.length() : 0L;
                    if (spec.bytes > 0 && already > spec.bytes) {
                        part.delete();
                        already = 0L;
                    }
                    long need = Math.max(1L, spec.bytes - already);
                    long reserve = need + 128L * 1024L * 1024L;
                    long usable = target.getParentFile() == null
                            ? 0L : target.getParentFile().getUsableSpace();
                    if (usable > 0 && usable < reserve) {
                        postDownload(callback, null, "Insufficient storage space: required "
                                + formatSize(reserve) + ", Available " + formatSize(usable));
                        return;
                    }
                    HttpURLConnection connection = null;
                    InputStream input = null;
                    FileOutputStream output = null;
                    try {
                        URL url = new URL(spec.url);
                        connection = (HttpURLConnection) url.openConnection();
                        activeDownload = connection;
                        connection.setConnectTimeout(20000);
                        connection.setReadTimeout(120000);
                        connection.setInstanceFollowRedirects(true);
                        connection.setRequestProperty("User-Agent",
                                "MobileAgent/0.15 (Android)");
                        connection.setRequestProperty("Accept", "application/octet-stream");
                        connection.setRequestProperty("Accept-Encoding", "identity");
                        if (already > 0) {
                            connection.setRequestProperty("Range", "bytes=" + already + "-");
                        }
                        int code = connection.getResponseCode();
                        boolean append = code == 206 && already > 0;
                        if (code == 416 && already > 0) {
                            String problem = verifyGguf(part, spec);
                            if (problem == null) {
                                finalizePart(part, target, spec, callback);
                                return;
                            }
                            part.delete();
                            postDownload(callback, null,
                                    "Invalid continuation position (in thousands of euros)HTTP 416), Unfinished files deleted, please redownload");
                            return;
                        }
                        if (code == 200 && already > 0) {
                            part.delete();
                            already = 0L;
                            append = false;
                        }
                        if (code != 200 && code != 206) {
                            String body = readErrorBody(connection);
                            postDownload(callback, null, "Download failed (%1)HTTP " + code + ")"
                                    + (body.isEmpty() ? "" : ": " + body));
                            return;
                        }
                        String type = connection.getContentType();
                        if (type != null && type.toLowerCase(Locale.ROOT)
                                .contains("text/html")) {
                            postDownload(callback, null,
                                    "Server returns the page instead of the model (simulate error page)");
                            return;
                        }
                        long total = spec.bytes;
                        long rangeTotal = parseRangeTotal(
                                connection.getHeaderField("Content-Range"));
                        if (rangeTotal > 0) {
                            total = rangeTotal;
                        }
                        long contentLength = connection.getContentLengthLong();
                        if (total <= 0 && contentLength > 0) {
                            total = contentLength;
                        }
                        input = connection.getInputStream();
                        output = new FileOutputStream(part, append);
                        byte[] buffer = new byte[64 * 1024];
                        long written = already;
                        long lastNotify = 0L;
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            if (downloadCanceled) {
                                postDownload(callback, null, deletePartOnCancel
                                        ? "Download cancelled, incomplete files deleted"
                                        : "Download cancelled, incomplete files are reserved and can continue downloading");
                                return;
                            }
                            output.write(buffer, 0, read);
                            written += read;
                            long now = System.currentTimeMillis();
                            if (now - lastNotify >= 400 || read < buffer.length) {
                                lastNotify = now;
                                int percent = total > 0
                                        ? (int) Math.min(99, written * 100 / total) : 0;
                                downloadPercent = percent;
                                postProgress(callback, percent, written,
                                        total > 0 ? total : written);
                            }
                        }
                        output.flush();
                        output.getFD().sync();
                        input.close();
                        input = null;
                        output.close();
                        output = null;
                        String problem = verifyGguf(part, spec);
                        if (problem != null) {
                            postDownload(callback, null, "Download completed but validation failed:" + problem
                                    + "; Unfinished files are kept and can continue downloading");
                            return;
                        }
                        finalizePart(part, target, spec, callback);
                    } catch (Exception e) {
                        if (downloadCanceled) {
                            postDownload(callback, null, deletePartOnCancel
                                    ? "Download cancelled, incomplete files deleted"
                                    : "Download cancelled, incomplete files are reserved and can continue downloading");
                        } else {
                            postDownload(callback, null, describeDownloadError(e));
                        }
                    } finally {
                        activeDownload = null;
                        closeQuietly(input);
                        closeQuietly(output);
                        if (connection != null) {
                            connection.disconnect();
                        }
                        if (downloadCanceled && deletePartOnCancel && part.isFile()) {
                            part.delete();
                        }
                    }
                } catch (Exception e) {
                    postDownload(callback, null, describeDownloadError(e));
                } finally {
                    synchronized (downloadLock) {
                        downloading = false;
                        downloadingId = "";
                        downloadPercent = 0;
                        downloadCanceled = false;
                        deletePartOnCancel = false;
                    }
                    KeepAliveService.stop(appContext);
                }
            }
        });
    }

    private void finalizePart(File part, File target, ModelSpec spec,
                              DownloadCallback callback) {
        try {
            if (target.exists() && !target.delete()) {
                postDownload(callback, null, "The file with the same name cannot be covered:" + target.getName());
                return;
            }
            if (!part.renameTo(target)) {
                copyFile(part, target);
                part.delete();
            }
            String problem = verifyGguf(target, spec);
            if (problem != null) {
                postDownload(callback, null, "Final verification failed:" + problem);
                return;
            }
            prefs.setLocalModelPath(target.getAbsolutePath());
            downloadPercent = 100;
            postProgress(callback, 100, target.length(), target.length());
            postDownload(callback, target, null);
        } catch (Exception e) {
            postDownload(callback, null, "Failed to save model:" + describeDownloadError(e));
        }
    }

    private static void copyFile(File source, File target) throws Exception {
        InputStream input = new FileInputStream(source);
        FileOutputStream output = new FileOutputStream(target, false);
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
            output.getFD().sync();
        } finally {
            closeQuietly(input);
            closeQuietly(output);
        }
    }

    private static long parseRangeTotal(String value) {
        if (value == null) {
            return 0L;
        }
        int slash = value.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= value.length()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.substring(slash + 1).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String readErrorBody(HttpURLConnection connection) {
        try {
            InputStream input = connection.getErrorStream();
            if (input == null) {
                return "";
            }
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1 && total < 8192) {
                output.write(buffer, 0, read);
                total += read;
            }
            input.close();
            String text = output.toString("UTF-8");
            return text.length() > 240 ? text.substring(0, 240) + "..." : text.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String describeDownloadError(Exception e) {
        String name = e.getClass().getSimpleName();
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (e instanceof java.net.UnknownHostException) {
            return "Download failed: the download domain name cannot be parsed (check the network)/DNS)";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "Download failed: connection or read timeout (network slow, retryable)";
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return "Download failed:TLS Secure connection failed (%1)" + message + ")";
        }
        return "Download failed:" + name + (message.isEmpty() ? "" : ": " + message);
    }

    static String verifyGgufCached(File file, ModelSpec spec) {
        if (file == null) {
            return "File does not exist";
        }
        String key = file.getAbsolutePath();
        long size = file.length();
        long mtime = file.lastModified();
        synchronized (VERIFY_CACHE) {
            VerifyEntry entry = VERIFY_CACHE.get(key);
            if (entry != null && entry.size == size && entry.mtime == mtime) {
                return entry.problem;
            }
        }
        String problem = verifyGguf(file, spec);
        synchronized (VERIFY_CACHE) {
            VERIFY_CACHE.put(key, new VerifyEntry(size, mtime, problem));
        }
        return problem;
    }

    private static boolean isVerifyCached(File file) {
        if (file == null) {
            return false;
        }
        synchronized (VERIFY_CACHE) {
            VerifyEntry entry = VERIFY_CACHE.get(file.getAbsolutePath());
            return entry != null && entry.size == file.length()
                    && entry.mtime == file.lastModified();
        }
    }

    private static String cachedProblem(File file) {
        if (file == null) {
            return null;
        }
        synchronized (VERIFY_CACHE) {
            VerifyEntry entry = VERIFY_CACHE.get(file.getAbsolutePath());
            if (entry != null && entry.size == file.length()
                    && entry.mtime == file.lastModified()) {
                return entry.problem;
            }
        }
        return null;
    }

    static String verifyGguf(File file, ModelSpec spec) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Defense: the main course never works GGUF Full scan, read cache only; backstage check will refresh caches once completed.
            String cached = cachedProblem(file);
            return cached != null ? cached : null;
        }
        if (file == null || !file.isFile()) {
            return "File does not exist";
        }
        long length = file.length();
        if (length < 1024L * 1024L) {
            return "Too small (in thousands of cases)" + formatSize(length) + ")";
        }
        if (spec != null && spec.bytes > 0) {
            long minimum = (long) (spec.bytes * 0.97);
            if (length < minimum) {
                return "Incomplete file (incomplete)" + formatSize(length) + " / "
                        + formatSize(spec.bytes) + ")";
            }
        }
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            byte[] magic = new byte[4];
            if (input.read(magic) != 4) {
                return "Could not close temporary folder: %s";
            }
            String head = new String(magic, java.nio.charset.StandardCharsets.US_ASCII);
            if (!"GGUF".equals(head)) {
                return "No, it's not. GGUF File (headers) " + head + ")";
            }
            GgufMetadata metadata = GgufMetadata.read(file, false);
            LocalModelAdapter.requireSupported(metadata);
            return null;
        } catch (Exception e) {
            return "GGUF metadata Could not close temporary folder: %s" + e.getMessage();
        } finally {
            closeQuietly(input);
        }
    }

    void deleteModel(File file) {
        if (file == null) {
            return;
        }
        File loaded = loadedFile;
        if (loaded != null && loaded.getAbsolutePath().equals(file.getAbsolutePath())) {
            unload();
        }
        ModelSpec spec = specForFile(file);
        if (spec != null) {
            partFile(spec).delete();
        }
        file.delete();
        if (file.getAbsolutePath().equals(prefs.localModelPath())) {
            prefs.setLocalModelPath("");
        }
    }

    String verifyInstalled() {
        List<File> files = models();
        if (files.isEmpty()) {
            return "No installed model yet";
        }
        int good = 0;
        StringBuilder bad = new StringBuilder();
        for (File file : files) {
            String problem = verifyGgufCached(file, specForFile(file));
            if (problem == null) {
                good++;
            } else {
                bad.append(file.getName()).append(": ").append(problem).append('\n');
            }
        }
        if (bad.length() == 0) {
            return "Validation of:" + good + " individual GGUF metadata/Templates profile normal;calculating models when loaded SHA256";
        }
        return "Validation completed:" + good + " It's normal." + (files.size() - good)
                + " An anomaly.\n" + bad.toString().trim();
    }

    void probe(final ModelSpec spec, final ProbeCallback callback) {
        if (spec == null) {
            postProbe(callback, false, "Model does not exist");
            return;
        }
        probeExecutor.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                InputStream input = null;
                try {
                    URL url = new URL(spec.url);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(20000);
                    connection.setReadTimeout(30000);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("User-Agent",
                            "MobileAgent/0.15 (Android)");
                    connection.setRequestProperty("Range", "bytes=0-131071");
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    int code = connection.getResponseCode();
                    String host = connection.getURL() == null
                            ? "" : connection.getURL().getHost();
                    if (code != 200 && code != 206) {
                        postProbe(callback, false, "HTTP " + code + " · " + host
                                + " · " + readErrorBody(connection));
                        return;
                    }
                    input = connection.getInputStream();
                    byte[] head = new byte[4];
                    int read = input.read(head);
                    boolean gguf = read == 4 && "GGUF".equals(
                            new String(head, java.nio.charset.StandardCharsets.US_ASCII));
                    postProbe(callback, gguf, "HTTP " + code + " · " + host
                            + " · Documentation " + formatSize(spec.bytes)
                            + " · Synchronising folder " + (gguf ? "GGUF" : "Unusual")
                            + " · Read " + Math.max(0, read) + " Bytes");
                } catch (Exception e) {
                    postProbe(callback, false, describeDownloadError(e));
                } finally {
                    closeQuietly(input);
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        });
    }

    void cancelDownload() {
        downloadCanceled = true;
        HttpURLConnection connection = activeDownload;
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    void cancelDownloadAndDelete() {
        deletePartOnCancel = true;
        cancelDownload();
    }

    boolean isDownloading() {
        return downloading;
    }

    boolean isDownloading(ModelSpec spec) {
        return downloading && spec != null && spec.id.equals(downloadingId);
    }

    String downloadingName() {
        ModelSpec spec = specById(downloadingId);
        return spec == null ? "Model" : spec.name;
    }

    int downloadingPercent() {
        return downloadPercent;
    }

    File partFile(ModelSpec spec) {
        return spec == null ? null
                : new File(modelDir(), spec.fileName + ".part");
    }

    boolean hasPartial(ModelSpec spec) {
        File part = partFile(spec);
        return part != null && part.isFile() && part.length() > 0;
    }

    long partialBytes(ModelSpec spec) {
        File part = partFile(spec);
        return part != null && part.isFile() ? part.length() : 0L;
    }

    File installedFile(ModelSpec spec) {
        return spec == null ? null : new File(modelDir(), spec.fileName);
    }

    private void postStatus(final DownloadCallback callback, final String status) {
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onStatus(status);
            }
        });
    }

    private void postProbe(final ProbeCallback callback, final boolean ok,
                           final String detail) {
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onResult(ok, detail);
            }
        });
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private void postProgress(final DownloadCallback callback, final int percent,
                              final long bytes, final long total) {
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onProgress(percent, bytes, total);
            }
        });
    }

    private void postDownload(final DownloadCallback callback, final File file,
                              final String error) {
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onDone(file, error);
            }
        });
    }

    void copyModel(final InputStream input, final String fileName,
                   final CopyCallback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                String name = fileName == null ? "" : fileName.trim();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
                    postCopy(callback, null, "Choose .gguf Model File");
                    return;
                }
                File target = new File(modelDir(), name);
                File part = new File(target.getAbsolutePath() + ".part");
                try {
                    FileOutputStream output = new FileOutputStream(part, false);
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    try {
                        while ((read = input.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                        }
                    } finally {
                        output.close();
                        input.close();
                    }
                    if (!part.renameTo(target)) {
                        postCopy(callback, null, "Import completed but rename failed");
                        return;
                    }
                    prefs.setLocalModelPath(target.getAbsolutePath());
                    postCopy(callback, target, null);
                } catch (Exception e) {
                    postCopy(callback, null, "Import failed:"
                            + (e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage()));
                }
            }
        });
    }

    private void postCopy(final CopyCallback callback, final File file,
                          final String error) {
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onDone(file, error);
            }
        });
    }

    static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.2f GB",
                    bytes / (1024d * 1024d * 1024d));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.0f MB",
                    bytes / (1024d * 1024d));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024d);
        }
        return bytes + " B";
    }
}
