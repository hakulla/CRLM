package org.mobileagent.agent;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Java8/API24 index. No workspace I/O on ready/search/map paths.
 * ensure is the explicit blocking administrative API; all builds use one bounded worker.
 * Canonical checks before/after opening reject links, but are NOT an OS TOCTOU sandbox.
 */
final class CodeIndex {
    static final double K1 = 1.2, B = 0.75;
    private static final long CACHE_BUDGET = 128L << 20;
    private static final int CACHE_COUNT = 6;
    private static long workerReservation; // Guarded by CACHE; one worker, includes next-generation build estimate.
    private static final Map<String, CodeIndex> CACHE = new LinkedHashMap<>(8, .75f, true);
    private static final ExecutorService WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(CACHE_COUNT), r -> {
                Thread t = new Thread(r, "code-index"); t.setDaemon(true); return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final ParserConfiguration PARSER_CONFIG = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
            .setStoreTokens(true).setAttributeComments(false); // JavaParser needs tokens to retain source ranges; AST is discarded.
    private static final Set<String> EXT = set("java", "kt", "kts", "md", "txt", "sh",
            "c", "cc", "cpp", "h", "hpp", "js", "jsx", "ts", "tsx", "py", "html", "css", "rs", "go");
    // Config files are deliberately excluded, rather than sending their values to a model.
    private static final Set<String> CONFIG = set("json", "xml", "properties", "yml", "yaml", "toml", "ini", "conf", "env");
    private static final Set<String> SKIP = set("build", "bin", "out", "target", "dist", "node_modules",
            "models", "toolchain", "toolchain-mac", "deps", "__pycache__", "coverage");
    private static final Pattern SECRET_NAME = Pattern.compile("(?i)(?:.*(?:credentials?|secrets?|passwords?|keystore|api[-_]?keys?).*|id_(?:rsa|dsa|ecdsa|ed25519)|.*\\.(?:pem|key|p12|pfx|jks)|(?:auth|token|config)\\.json)");
    private static final Pattern SECRET_TEXT = Pattern.compile("(?im)(?:-----BEGIN (?:[A-Z ]*PRIVATE KEY|OPENSSH)|AKIA[0-9A-Z]{16}|\\b(?:api[_-]?key|password|passwd|secret|(?:access[_-]?|auth[_-]?)?token|credential|authorization|private[_-]?key)\\b[\\\"']?\\s*[:=]\\s*(?:[\\\"'][^\\\"'\\r\\n]{8,}[\\\"']|[A-Za-z0-9_./+\\-]{8,}))");

    /** Conservative accounting limits, not a claim of measured Android heap size. */
    static final class Limits {
        final int files, fileBytes, fileTokens, symbols, chunks, pairs, vocabulary, entries, errors;
        final long sourceBytes, memoryBytes, checkMs, expiryMs;
        Limits() { this(20000, 512 << 10, 50000, 2000, 100000, 1200000, 200000,
                100000, 256, 24L << 20, 64L << 20, 2000, 120000); }
        Limits(int files, int fileBytes, int fileTokens, int symbols, int chunks, int pairs,
               int vocabulary, int entries, int errors, long sourceBytes, long memoryBytes,
               long checkMs, long expiryMs) {
            this.files = files; this.fileBytes = fileBytes; this.fileTokens = fileTokens;
            this.symbols = symbols; this.chunks = chunks; this.pairs = pairs;
            this.vocabulary = vocabulary; this.entries = entries; this.errors = errors;
            if (files < 1 || fileBytes < 1 || fileTokens < 1 || symbols < 1 || chunks < 1 || pairs < 1
                    || vocabulary < 1 || entries < 1 || errors < 1 || sourceBytes < 1 || memoryBytes < 4096
                    || checkMs < 1 || expiryMs < 1) throw new IllegalArgumentException("Positive index limits required");
            this.sourceBytes = sourceBytes; this.memoryBytes = Math.min(memoryBytes, CACHE_BUDGET / 2);
            this.checkMs = checkMs; this.expiryMs = expiryMs;
        }
    }
    interface Clock { long millis(); }
    interface Hooks {
        default void beforeScan() throws IOException { }
        default InputStream open(File f) throws IOException { return new FileInputStream(f); }
        default void afterRead(File f) throws IOException { }
    }
    private static final Hooks NO_HOOKS = new Hooks() { };

    static final class Symbol {
        final String id, name, kind, owner, qualifiedName, signature, modifiers, sourceHash, declaredType, declarationSignature;
        final int line, endLine, arity;
        final boolean staticMember, exactDispatch;
        Symbol(String workspace, String path, String hash, String declaredType, String declarationSignature,
               String name, String kind, String owner, String qualifiedName, String signature, String modifiers,
               int line, int endLine, int arity, boolean staticMember, boolean exactDispatch) {
            this.sourceHash = hash; this.declaredType = declaredType; this.declarationSignature = declarationSignature;
            this.name = name; this.kind = kind; this.owner = owner; this.qualifiedName = qualifiedName;
            this.signature = signature; this.modifiers = modifiers; this.line = line; this.endLine = endLine;
            this.arity = arity; this.staticMember = staticMember; this.exactDispatch = exactDispatch;
            this.id = workspace + "|" + path + "|" + qualifiedName + "|" + signature;
        }
    }
    static final class Edge {
        final String from, to, kind, status, reason;
        final int line;
        Edge(String from, String to, String kind, String status, String reason, int line) {
            this.from = from; this.to = to; this.kind = kind; this.status = status;
            this.reason = reason; this.line = line;
        }
    }
    private static final class Reference {
        final String from, owner, target, scope, kind;
        final List<String> arguments;
        final int line;
        Reference(String from, String owner, String target, String scope, String kind,
                  List<String> arguments, int line) {
            this.from = from; this.owner = owner; this.target = target; this.scope = scope;
            this.kind = kind; this.arguments = immutable(arguments); this.line = line;
        }
    }
    static final class Doc {
        final String path, text, sourceHash, parserStatus, packageName;
        final int lines;
        final long mtime, bytes, estimatedBytes;
        final List<Symbol> symbols;
        final List<Chunk> chunks;
        private final List<Reference> references;
        private final int[] lineStarts;
        Doc(String path, String text, String hash, long mtime, long bytes, ParseData data,
            List<Chunk> chunks, long estimate) {
            this.path = path; this.text = text; this.sourceHash = hash; this.mtime = mtime;
            this.bytes = bytes; this.lineStarts = lineStarts(text); this.lines = lineStarts.length;
            this.symbols = immutable(data.symbols); this.references = immutable(data.references);
            this.packageName = data.packageName; this.parserStatus = data.status;
            this.chunks = immutable(chunks); this.estimatedBytes = estimate;
        }
        int lineAt(int offset) {
            int i = Arrays.binarySearch(lineStarts, Math.max(0, offset));
            return i >= 0 ? i + 1 : -i - 1;
        }
    }
    static final class Chunk {
        final String path, sourceHash;
        final int startLine, endLine, startOffset, endOffset, length;
        final List<String> symbolIds;
        private final Map<String, int[]> positions;
        Chunk(String path, String hash, int start, int end, int startOffset, int endOffset,
              Map<String, Ints> terms, List<String> symbols) {
            this.path = path; this.sourceHash = hash; this.startLine = start; this.endLine = end;
            this.startOffset = startOffset; this.endOffset = endOffset; this.symbolIds = immutable(symbols);
            Map<String, int[]> copy = new TreeMap<>(); int n = 0;
            for (Map.Entry<String, Ints> e : terms.entrySet()) { copy.put(e.getKey(), e.getValue().array()); n += e.getValue().size; }
            this.positions = Collections.unmodifiableMap(copy); this.length = n;
        }
        int[] termPositions(String term) {
            int[] found = positions.get(term); return found == null ? new int[0] : found.clone();
        }
    }
    private static final class Posting {
        final int chunk, tf;
        private final int[] positions;
        Posting(int chunk, int[] positions) { this.chunk = chunk; this.positions = positions; this.tf = positions.length; }
    }
    static final class Hit {
        final Doc doc;
        final int score, line, startLine, endLine, matchingOffset;
        final String snippet, sourceHash, freshness;
        final double bm25, structureScore;
        final List<String> reasons;
        Hit(Doc doc, Chunk chunk, int line, int matchingOffset, double score, double bm25, double structure,
            List<String> reasons, String freshness) {
            this.doc = doc; this.line = line; this.matchingOffset = matchingOffset;
            this.startLine = chunk.startLine; this.endLine = chunk.endLine;
            this.sourceHash = doc.sourceHash; this.score = (int) Math.round(score * 1000000);
            this.bm25 = bm25; this.structureScore = structure; this.reasons = immutable(reasons);
            this.freshness = freshness; this.snippet = snippet(doc, chunk, line, matchingOffset);
        }
    }
    static final class Stats {
        final int files, symbols, skipped, indexed, unsupported, failed, chunks;
        final long bytes, buildMs, estimatedBytes, version;
        final String status, hash;
        final boolean scanComplete;
        final List<String> errors;
        Stats(Snapshot s) {
            files = s.docs.size(); symbols = s.symbols.size(); skipped = s.skipped;
            indexed = files; unsupported = s.unsupported; failed = s.failed; chunks = s.chunks.size();
            bytes = s.bytes; buildMs = s.buildMs; estimatedBytes = s.estimatedBytes;
            version = s.version; hash = s.hash; errors = s.errors; scanComplete = s.scanComplete;
            status = skipped + failed + unsupported > 0 || !scanComplete ? "PARTIAL" : "READY";
        }
    }
    static final class Status {
        final String state, semantic = "UNCONFIGURED", semanticReason = "No authorized embedding model/runtime";
        final String workspaceId, hash, lastError;
        final long version, ageMs, requestedVersion, estimatedBytes;
        final boolean building, dirty;
        Status(CodeIndex i, Snapshot s) {
            workspaceId = i.key; version = s == null ? 0 : s.version; requestedVersion = i.revision.get();
            ageMs = s == null ? -1 : Math.max(0, i.clock.millis() - s.builtAt);
            building = i.building; dirty = s == null || version < requestedVersion;
            hash = s == null ? "" : s.hash; lastError = i.lastError;
            estimatedBytes = s == null ? 0 : s.estimatedBytes;
            state = s == null ? (lastError.isEmpty() ? "NOT_READY" : "FAILED")
                    : dirty || ageMs >= i.limits.expiryMs ? "STALE" : s.stats.status;
        }
    }
    static final class Snapshot {
        final String workspaceId, hash;
        final long version, builtAt, buildMs, bytes, estimatedBytes;
        final int skipped, unsupported, failed;
        final boolean scanComplete;
        final double avgLength;
        final List<Doc> docs;
        final List<Chunk> chunks;
        final List<Symbol> symbols;
        final List<Edge> edges;
        final List<String> errors;
        final Stats stats;
        private final Map<String, List<Posting>> postings;
        private final Map<String, List<Integer>> exact;
        private final Map<String, Symbol> byId;
        private final Map<String, Integer> symbolChunk;
        private final Map<String, List<Edge>> graph;
        private final Map<String, Doc> byPath;
        Snapshot(String key, long version, long builtAt, long buildMs, Build b,
                 List<Chunk> chunks, Map<String, List<Posting>> postings,
                 Map<String, List<Integer>> exact, List<Edge> edges, long estimate) {
            this.workspaceId = key; this.version = version; this.builtAt = builtAt; this.buildMs = buildMs;
            this.docs = immutable(b.docs); this.chunks = immutable(chunks); this.edges = immutable(edges);
            this.postings = freezeLists(postings); this.exact = freezeLists(exact);
            this.bytes = b.bytes; this.estimatedBytes = estimate; this.skipped = b.skipped;
            this.unsupported = b.unsupported; this.failed = b.failed; this.errors = immutable(b.errors); this.scanComplete = !b.stopped;
            Map<String, Doc> paths = new TreeMap<>(); Map<String, Symbol> ids = new LinkedHashMap<>();
            Map<String, Integer> locations = new HashMap<>(); Map<String, List<Edge>> graph = new HashMap<>();
            long total = 0;
            MessageDigest digest = digest();
            for (Doc d : docs) {
                paths.put(d.path, d); hashPart(digest, d.path); hashPart(digest, d.sourceHash);
                for (Symbol s : d.symbols) ids.put(s.id, s);
            }
            for (int n = 0; n < chunks.size(); n++) {
                Chunk c = chunks.get(n); total += c.length;
                for (String id : c.symbolIds) if (!locations.containsKey(id)) locations.put(id, n);
            }
            for (Edge e : edges) if ("KNOWN".equals(e.status)) {
                add(graph, e.from, e); add(graph, e.to, e);
            }
            this.byPath = Collections.unmodifiableMap(paths); this.byId = Collections.unmodifiableMap(ids);
            this.symbols = immutable(new ArrayList<>(ids.values())); this.symbolChunk = Collections.unmodifiableMap(locations);
            this.graph = freezeLists(graph); this.avgLength = chunks.isEmpty() ? 1 : (double) total / chunks.size();
            this.hash = hex(digest.digest()); this.stats = new Stats(this);
        }
    }
    private final Workspace workspace;
    private final File root;
    private final String key, baseKey, scanPrefix;
    private final Limits limits;
    private final Clock clock;
    private final Hooks hooks;
    private final Object stateLock = new Object();
    private final AtomicLong revision = new AtomicLong(1);
    private volatile Snapshot snapshot;
    private volatile boolean building, retired;
    private volatile String lastError = "";
    private long forceRevision = 1, lastCheck = Long.MIN_VALUE;

    private CodeIndex(Workspace w) { this(w, new Limits(), System::currentTimeMillis, NO_HOOKS); }
    CodeIndex(Workspace w, Limits limits, Clock clock, Hooks hooks) {
        this.workspace = w; this.limits = limits; this.clock = clock; this.hooks = hooks;
        if (w == null || limits == null || clock == null || hooks == null) throw new IllegalArgumentException("Index arguments required");
        try {
            root = safeRoot(w); key = root.getPath(); baseKey = w.baseRoot().getCanonicalPath();
            scanPrefix = key.equals(baseKey) ? "" : key.substring(baseKey.length() + 1).replace(File.separatorChar, '/');
        } catch (IOException e) { throw new IllegalArgumentException("Unsafe index root", e); }
    }
    private static File safeRoot(Workspace w) throws IOException {
        File base = w.baseRoot().getCanonicalFile(), candidate = w.root().getAbsoluteFile();
        File canonical = candidate.getCanonicalFile();
        // Android may have a platform alias in baseRoot; no links are allowed inside that base.
        File rawBase = w.baseRoot().getAbsoluteFile();
        String lexicalBase = rawBase.getPath();
        if (rawBase.getParentFile() != null && !new File(rawBase.getParentFile().getCanonicalFile(), rawBase.getName()).equals(base))
            throw new IOException("LINK_BASE");
        String lexical = candidate.getPath();
        if (!covers(lexicalBase, lexical) || !covers(base.getPath(), canonical.getPath())) throw new IOException("OUTSIDE_BASE");
        String suffix = lexical.substring(lexicalBase.length());
        if (!new File(base.getPath() + suffix).getAbsolutePath().equals(canonical.getPath())) throw new IOException("LINK_SCOPE");
        if (!canonical.isDirectory()) throw new IOException("ROOT_NOT_DIRECTORY");
        return canonical;
    }
    static CodeIndex get(Workspace w) { 
        if (w == null) throw new IllegalArgumentException("workspace required");
        String key;
        try { key = safeRoot(w).getPath(); } catch (IOException e) { throw new IllegalArgumentException("Unsafe index root", e); }
        synchronized (CACHE) {
            CodeIndex i = CACHE.get(key);
            if (i == null) {
                trimCache(null, 0);
                if (CACHE.size() >= CACHE_COUNT) {
                    Iterator<CodeIndex> it = CACHE.values().iterator(); boolean removed = false;
                    while (it.hasNext()) {
                        CodeIndex candidate = it.next();
                        synchronized (candidate.stateLock) {
                            if (candidate.building) continue;
                            candidate.retired = true; candidate.snapshot = null; it.remove(); removed = true; break;
                        }
                    }
                    if (!removed) throw new IllegalStateException("INDEX_CACHE_BUSY");
                }
                i = new CodeIndex(w); CACHE.put(key, i);
            }
            return i;
        }
    }
    private static boolean covers(String a, String b) { return a.equals(b) || b.startsWith(a + File.separator); }
    static void invalidate(Workspace w) { invalidate(w, null); }
    static void invalidate(Workspace w, File file) {
        if (w == null) return;
        CodeIndex origin = get(w);
        // Use lexical paths too: a deleted/moved/link-replaced file may no longer resolve canonically.
        String changed = origin.key;
        if (file != null) {
            try { changed = file.getCanonicalPath(); }
            catch (IOException ignored) { changed = file.getAbsolutePath(); }
        }
        String lexicalChanged = file == null ? origin.key : file.getAbsolutePath();
        String ignoreParent = file != null && ".gitignore".equals(file.getName()) ? file.getParentFile().getAbsolutePath() : "";
        List<CodeIndex> affected = new ArrayList<>();
        synchronized (CACHE) {
            for (CodeIndex i : CACHE.values()) if (i == origin || covers(i.key, changed)
                    || covers(changed, i.key) || covers(i.key, lexicalChanged) || covers(lexicalChanged, i.key)
                    || (!ignoreParent.isEmpty() && covers(ignoreParent, i.key))
                    || (file == null && (covers(i.key, origin.key) || covers(origin.key, i.key)))) affected.add(i);
        }
        for (CodeIndex i : affected) { i.markDirty(); i.request(false, false); }
    }
    void markDirty() { synchronized (stateLock) { revision.incrementAndGet(); } }
    static void prewarm(Workspace w) { if (w != null) get(w).request(false, false); }
    Status status() { return new Status(this, snapshot); }
    Snapshot snapshotIfReady() { return snapshot; }

    private Snapshot request(boolean force, boolean wait) {
        synchronized (stateLock) {
            if (retired) {
                if (wait) throw new IllegalStateException("INDEX_EVICTED: reacquire with get");
                return snapshot;
            }
            long now = clock.millis(); Snapshot s = snapshot;
            if (force) forceRevision = revision.incrementAndGet();
            boolean due = lastCheck == Long.MIN_VALUE || now - lastCheck >= limits.checkMs
                    || (s != null && now - s.builtAt >= limits.expiryMs);
            if (!building && !force && s != null && s.version == revision.get() && due) revision.incrementAndGet();
            long target = revision.get();
            if (!building && (s == null || s.version < target)) {
                building = true; lastCheck = now;
                try { WORKER.execute(this::buildLoop); }
                catch (RejectedExecutionException e) { building = false; lastError = "BUILD_QUEUE_FULL"; stateLock.notifyAll(); }
            }
            if (wait) {
                while (building && (snapshot == null || snapshot.version < target)) {
                    try { stateLock.wait(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException("Index wait interrupted", e);
                    }
                }
                if (snapshot == null || snapshot.version < target) throw new IllegalStateException("Index failed: " + lastError);
            }
            return snapshot;
        }
    }
    Stats ensure(boolean force) { return request(force, true).stats; }
    private void buildLoop() {
        while (true) {
            long target, full;
            synchronized (stateLock) { target = revision.get(); full = forceRevision; }
            try {
                long start = clock.millis();
                synchronized (CACHE) {
                    trimCache(this, limits.memoryBytes + (snapshot == null ? 0 : snapshot.estimatedBytes));
                    workerReservation = limits.memoryBytes;
                }
                hooks.beforeScan(); checkRoot();
                Build b = new Build(); List<IgnoreRule> inherited = ancestorRules(b);
                collect(root, "", 0, inherited, b,
                        full > (snapshot == null ? 0 : snapshot.version) ? null : snapshot);
                Snapshot next = assemble(b, target, start);
                synchronized (CACHE) {
                    workerReservation = 0;
                    trimCache(this, next.estimatedBytes);
                    synchronized (stateLock) { snapshot = next; lastCheck = clock.millis(); lastError = ""; stateLock.notifyAll(); }
                }
            } catch (IOException | RuntimeException | Error e) {
                // Always release waiters, including parser stack overflow or missing dependency linkage.
                synchronized (CACHE) { workerReservation = 0; }
                synchronized (stateLock) { lastError = "BUILD_FAILED:" + e.getClass().getSimpleName(); building = false; stateLock.notifyAll(); }
                if (e instanceof VirtualMachineError && !(e instanceof StackOverflowError)) throw (VirtualMachineError)e;
                return;
            }
            synchronized (stateLock) {
                if (revision.get() == target) { building = false; stateLock.notifyAll(); return; }
                // Never clear a dirty event that arrived while reading/parsing the older generation.
            }
        }
    }
    private static void trimCache(CodeIndex keep, long incoming) {
        long total = incoming + workerReservation;
        for (CodeIndex i : CACHE.values()) if (i != keep && i.snapshot != null) total += i.snapshot.estimatedBytes;
        Iterator<Map.Entry<String, CodeIndex>> it = CACHE.entrySet().iterator();
        while (it.hasNext() && (CACHE.size() > CACHE_COUNT || total > CACHE_BUDGET)) {
            CodeIndex i = it.next().getValue(); if (i == keep) continue;
            synchronized (i.stateLock) {
                if (i.building) continue; // Never orphan an active writer outside CACHE accounting.
                if (i.snapshot != null) total -= i.snapshot.estimatedBytes;
                i.retired = true; i.snapshot = null; it.remove();
            }
        }
        if (total > CACHE_BUDGET) throw new IllegalStateException("CACHE_BUDGET_BUSY");
        // Live holders of old snapshots remain valid; GC reclaims them after their last reader.
    }
    static long cacheEstimatedBytes() {
        synchronized (CACHE) { long n = 0; for (CodeIndex i : CACHE.values()) if (i.snapshot != null) n += i.snapshot.estimatedBytes; return n; }
    }
    static long accountedEstimatedBytes() {
        synchronized (CACHE) { return cacheEstimatedBytes() + workerReservation; }
    }
    private void checkRoot() throws IOException {
        if (!workspace.root().getCanonicalPath().equals(key) || !root.getCanonicalPath().equals(key)
                || !root.isDirectory()) throw new IOException("ROOT_CHANGED");
    }
    private void checkPath(File f) throws IOException { checkPath(f, key); }
    private void checkPath(File f, String scope) throws IOException {
        String absolute = f.getAbsolutePath(); String canonical = f.getCanonicalPath();
        if (!covers(scope, absolute) || !absolute.equals(canonical)) throw new IOException("LINK_OR_OUTSIDE_SCOPE");
        checkRoot();
    }
    private String basePath(String relative) {
        return scanPrefix.isEmpty() ? relative : relative.isEmpty() ? scanPrefix : scanPrefix + "/" + relative;
    }
    private List<IgnoreRule> ancestorRules(Build b) throws IOException {
        File base = workspace.baseRoot().getCanonicalFile(), dir = base;
        List<IgnoreRule> rules = new ArrayList<>(); String relative = "";
        String scope = basePath("");
        if (hardIgnore(scope) != null) { issue(b, "", "EXCLUDED_SCOPE", 0); b.stopped = true; return rules; }
        while (!dir.equals(root)) {
            File ignore = new File(dir, ".gitignore");
            if (ignore.exists()) {
                try {
                    byte[] bytes = boundedRead(ignore, 16384, base.getPath()); b.ignoreBytes += bytes.length;
                    if (b.ignoreBytes > 262144) throw new IOException("IGNORE_BUDGET");
                    for (String line : utf8(bytes).split("\\r?\\n")) {
                        IgnoreRule rule = IgnoreRule.parse(relative, line); if (rule != null) rules.add(rule);
                    }
                } catch (IOException | RuntimeException e) {
                    issue(b, "", "ANCESTOR_IGNORE_UNREADABLE", 2); b.stopped = true; return rules;
                }
            }
            String remaining = scope.substring(relative.isEmpty() ? 0 : relative.length() + 1);
            String part = remaining.split("/", 2)[0];
            relative = relative.isEmpty() ? part : relative + "/" + part; dir = new File(dir, part);
            if (ignored(relative, true, rules)) { issue(b, "", "GITIGNORE_SCOPE", 0); b.stopped = true; return rules; }
        }
        return rules;
    }
    private static final class Build {
        final List<Doc> docs = new ArrayList<>(); final List<String> errors = new ArrayList<>();
        long bytes, memory = 4096, ignoreBytes;
        final Set<String> vocabulary = new HashSet<>();
        int skipped, unsupported, failed, entries, chunks, pairs;
        boolean stopped;
    }
    private void issue(Build b, String path, String code, int category) {
        if (category == 0) b.skipped++; else if (category == 1) b.unsupported++; else b.failed++;
        if (b.errors.size() < limits.errors) b.errors.add(path + ":" + code);
    }
    private void collect(File dir, String relative, int depth, List<IgnoreRule> inherited, Build b, Snapshot old) throws IOException {
        if (b.stopped) return;
        if (depth > 64) { issue(b, relative, "DEPTH_LIMIT", 0); return; }
        try { checkPath(dir); } catch (IOException e) { issue(b, relative, "LINK_OR_ROOT_CHANGED", 2); return; }
        List<IgnoreRule> rules = new ArrayList<>(inherited);
        File ignore = new File(dir, ".gitignore");
        if (ignore.exists()) {
            try {
                checkPath(ignore); byte[] data = boundedRead(ignore, 16384);
                b.ignoreBytes += data.length;
                if (b.ignoreBytes > 262144) { b.stopped = true; issue(b, relative, "IGNORE_BUDGET", 2); return; }
                String text = utf8(data);
                for (String line : text.split("\\r?\\n")) {
                    IgnoreRule rule = IgnoreRule.parse(basePath(relative), line); if (rule != null) rules.add(rule);
                }
            } catch (IOException | RuntimeException e) { issue(b, relative + "/.gitignore", "IGNORE_UNREADABLE", 2); return; }
        }
        File[] children = dir.listFiles();
        if (children == null) { issue(b, relative, "LIST_FAILED", 2); return; }
        if (children.length > limits.entries - b.entries) {
            issue(b, relative, "ENTRY_BUDGET", 0); b.stopped = true; return;
        }
        b.entries += children.length;
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File f : children) {
            if (b.stopped) return;
            String path = relative.isEmpty() ? f.getName() : relative + "/" + f.getName();
            if (".gitignore".equals(f.getName())) continue;
            String hard = hardIgnore(path);
            if (hard != null) { issue(b, path, hard, 0); continue; }
            try { checkPath(f); } catch (IOException e) { issue(b, path, "LINK_OR_OUTSIDE_SCOPE", 2); continue; }
            boolean directory = f.isDirectory();
            if (ignored(basePath(path), directory, rules)) { issue(b, path, "GITIGNORE", 0); continue; }
            if (directory) { collect(f, path, depth + 1, rules, b, old); continue; }
            if (!f.isFile()) { issue(b, path, "NOT_REGULAR", 2); continue; }
            if (!EXT.contains(ext(f.getName()))) { issue(b, path, "LEXICAL_UNSUPPORTED", 1); continue; }
            if (b.docs.size() >= limits.files) { issue(b, path, "FILE_BUDGET", 0); b.stopped = true; return; }
            try {
                Doc d = readDoc(f, path, old == null ? null : old.byPath.get(path));
                int pairs = 0; for (Chunk c : d.chunks) pairs += c.positions.size();
                if (b.bytes + d.bytes > limits.sourceBytes || b.memory + d.estimatedBytes > limits.memoryBytes
                        || b.chunks + d.chunks.size() > limits.chunks || b.pairs + pairs > limits.pairs) {
                    issue(b, path, "INDEX_BUDGET", 0); b.stopped = true; return;
                }
                Set<String> freshTerms = new HashSet<>();
                for (Chunk c : d.chunks) for (String t : c.positions.keySet()) if (!b.vocabulary.contains(t)) freshTerms.add(t);
                if (b.vocabulary.size() + freshTerms.size() > limits.vocabulary) {
                    issue(b, path, "VOCABULARY_BUDGET", 0); b.stopped = true; return;
                }
                b.vocabulary.addAll(freshTerms);
                b.docs.add(d); b.bytes += d.bytes; b.memory += d.estimatedBytes;
                b.chunks += d.chunks.size(); b.pairs += pairs;
                if (!"JAVA_AST".equals(d.parserStatus)) issue(b, path, d.parserStatus, 1);
            } catch (IndexLimit e) { issue(b, path, e.code, 0); }
            catch (IOException e) { issue(b, path, "READ_OR_UTF8_FAILED", 2); }
        }
    }
    private static String hardIgnore(String path) {
        for (String p : path.split("/")) {
            String lower = p.toLowerCase(Locale.ROOT);
            if (p.startsWith(".") || SKIP.contains(lower)) return "INTERNAL_OR_BUILD";
            if (SECRET_NAME.matcher(p).matches()) return "CREDENTIAL_NAME";
        }
        return CONFIG.contains(ext(path)) ? "CONFIG_PRIVACY" : null;
    }
    private static boolean ignored(String path, boolean directory, List<IgnoreRule> rules) {
        boolean ignored = false;
        for (IgnoreRule r : rules) if (r.matches(path, directory)) ignored = !r.negative;
        return ignored;
    }
    private static final class IgnoreRule {
        final String base; final Pattern pattern; final boolean negative, dirOnly;
        IgnoreRule(String base, String glob, boolean negative, boolean dirOnly, boolean anchored) {
            this.base = base; this.negative = negative; this.dirOnly = dirOnly;
            this.pattern = Pattern.compile((anchored || glob.contains("/") ? "^" : "^(?:.*/)?") + globRegex(glob) + "$");
        }
        static IgnoreRule parse(String base, String line) {
            int end = line.length();
            while (end > 0 && line.charAt(end - 1) == ' ' && (end < 2 || line.charAt(end - 2) != '\\')) end--;
            String g = line.substring(0, end);
            if (g.isEmpty() || g.startsWith("#")) return null;
            boolean n = g.startsWith("!"); if (n) g = g.substring(1);
            boolean anchored = g.startsWith("/"); if (anchored) g = g.substring(1);
            boolean dir = g.endsWith("/"); if (dir) g = g.substring(0, g.length() - 1);
            return g.isEmpty() ? null : new IgnoreRule(base, g, n, dir, anchored);
        }
        boolean matches(String path, boolean directory) {
            if (!base.isEmpty()) {
                if (!path.startsWith(base + "/")) return false;
                path = path.substring(base.length() + 1);
            }
            return (!dirOnly || directory) && pattern.matcher(path).matches();
        }
    }
    private static String globRegex(String g) {
        StringBuilder r = new StringBuilder();
        for (int n = 0; n < g.length(); n++) {
            char c = g.charAt(n);
            if (c == '\\' && n + 1 < g.length()) r.append(Pattern.quote(g.substring(++n, n + 1)));
            else if (c == '*') {
                if (n + 1 < g.length() && g.charAt(n + 1) == '*') {
                    n++; if (n + 1 < g.length() && g.charAt(n + 1) == '/') { n++; r.append("(?:.*/)?"); }
                    else r.append(".*");
                } else r.append("[^/]*");
            } else if (c == '?') r.append("[^/]");
            else if (c == '[') {
                int end = g.indexOf(']', n + 1);
                if (end < 0) r.append("\\["); else {
                    String cls = g.substring(n + 1, end); if (cls.startsWith("!")) cls = "^" + cls.substring(1);
                    r.append('[').append(cls.replace("\\", "\\\\")).append(']'); n = end;
                }
            } else r.append(Pattern.quote(String.valueOf(c)));
        }
        return r.toString();
    }
    private static final class IndexLimit extends IOException {
        final String code; IndexLimit(String code) { this.code = code; }
    }
    private byte[] boundedRead(File f, int cap) throws IOException { return boundedRead(f, cap, key); }
    private byte[] boundedRead(File f, int cap, String scope) throws IOException {
        checkPath(f, scope);
        if (f.length() > cap) throw new IndexLimit("FILE_BYTE_LIMIT");
        try (InputStream in = hooks.open(f); ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(cap, 8192))) {
            byte[] buf = new byte[8192]; int total = 0;
            while (true) {
                // Read at most one byte beyond cap; never allocate/store that byte.
                int n = in.read(buf, 0, Math.min(buf.length, cap - total + 1));
                if (n == -1) break;
                if (n == 0) throw new IOException("ZERO_READ");
                if (n > cap - total) throw new IndexLimit("FILE_BYTE_LIMIT");
                total += n; out.write(buf, 0, n);
            }
            checkPath(f, scope); return out.toByteArray();
        }
    }
    private Doc readDoc(File f, String path, Doc old) throws IOException {
        // All refreshes traverse collect: exactly the same canonical/ignore/privacy rules.
        checkPath(f); long mtime = f.lastModified(), length = f.length();
        byte[] bytes = boundedRead(f, limits.fileBytes); hooks.afterRead(f); checkPath(f);
        if (length != bytes.length || length != f.length() || mtime != f.lastModified()) throw new IOException("CHANGED_DURING_READ");
        String hash = hex(digest().digest(bytes));
        if (old != null && old.sourceHash.equals(hash)) {
            if (old.mtime == mtime) return old;
            ParseData reused = new ParseData(old.parserStatus); reused.packageName = old.packageName;
            reused.symbols.addAll(old.symbols); reused.references.addAll(old.references);
            return new Doc(path, old.text, hash, mtime, bytes.length, reused, old.chunks, old.estimatedBytes);
        }
        String text = utf8(bytes);
        if (text.indexOf('\0') >= 0) throw new IOException("BINARY");
        if (SECRET_TEXT.matcher(text).find()) throw new IndexLimit("CREDENTIAL_CONTENT");
        checkTokenBudget(text, limits.fileTokens); // Fail before allocating AST/position maps for token floods.
        ParseData data = "java".equals(ext(path)) ? parseJava(key, path, text) : new ParseData("LEXICAL_ONLY");
        if (data.symbols.size() > limits.symbols) throw new IndexLimit("SYMBOL_BUDGET");
        List<Chunk> chunks = chunk(path, hash, text, data.symbols);
        int tokens = 0; long estimate = 4096 + 8L * text.length() + 8L * bytes.length;
        for (Symbol s : data.symbols) estimate += 1024L + 4L * (s.id.length() + s.signature.length()
                + s.declarationSignature.length() + s.declaredType.length() + s.owner.length() + s.qualifiedName.length() + s.name.length());
        for (Reference r : data.references) estimate += 1024L + 4L * (r.from.length() + r.target.length());
        for (Chunk c : chunks) {
            tokens += c.length; estimate += 1024 + 8L * c.length;
            for (String t : c.positions.keySet()) estimate += 256L + 8L * t.length();
        }
        if (tokens > limits.fileTokens) throw new IndexLimit("TOKEN_BUDGET");
        if (estimate > limits.memoryBytes) throw new IndexLimit("MEMORY_BUDGET");
        return new Doc(path, text, hash, mtime, bytes.length, data, chunks, estimate);
    }
    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static final class ParseData {
        final List<Symbol> symbols = new ArrayList<>(); final List<Reference> references = new ArrayList<>();
        String packageName = "", status;
        ParseData(String status) { this.status = status; }
    }
    static List<Symbol> extractSymbols(String text, String ext) {
        return "java".equals(ext) ? immutable(parseJava("standalone", "fixture.java", text).symbols) : Collections.emptyList();
    }
    private static ParseData parseJava(String workspace, String path, String text) {
        ParseData data = new ParseData("JAVA_AST");
        String sourceHash = hex(digest().digest(text.getBytes(StandardCharsets.UTF_8)));
        ParseResult<CompilationUnit> parsed;
        try { parsed = new JavaParser(PARSER_CONFIG).parse(text); }
        catch (RuntimeException e) { return new ParseData("LEXICAL_ONLY:JAVA_PARSE_FAILED"); }
        if (!parsed.isSuccessful() || !parsed.getResult().isPresent()) return new ParseData("LEXICAL_ONLY:JAVA_PARSE_FAILED");
        CompilationUnit cu = parsed.getResult().get(); data.packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        Map<Node, Symbol> nodes = new IdentityHashMap<>();
        cu.walk(n -> {
            String name = null, kind = null, signature = "", mods = ""; int arity = -1; boolean stat = false, exact = false;
            if (n instanceof TypeDeclaration) {
                TypeDeclaration<?> t = (TypeDeclaration<?>) n; name = t.getNameAsString();
                kind = n instanceof ClassOrInterfaceDeclaration ? (((ClassOrInterfaceDeclaration)n).isInterface() ? "interface" : "class")
                        : n instanceof EnumDeclaration ? "enum" : n instanceof AnnotationDeclaration ? "annotation" : "record";
                mods = t.getModifiers().toString(); signature = name;
            } else if (n instanceof CallableDeclaration) {
                CallableDeclaration<?> c = (CallableDeclaration<?>) n; name = c.getNameAsString();
                kind = n instanceof MethodDeclaration ? "method" : "constructor";
                signature = name + "(" + parameterTypes(c) + ")";
                mods = c.getModifiers().toString(); arity = c.getParameters().size();
                stat = c.getModifiers().stream().anyMatch(m -> "static".equals(m.getKeyword().asString()));
                exact = stat || c.getModifiers().stream().anyMatch(m -> "private".equals(m.getKeyword().asString()) || "final".equals(m.getKeyword().asString()));
            } else if (n instanceof VariableDeclarator && n.getParentNode().orElse(null) instanceof FieldDeclaration) {
                VariableDeclarator v = (VariableDeclarator)n; FieldDeclaration f = (FieldDeclaration)n.getParentNode().get();
                name = v.getNameAsString(); kind = "field"; signature = name + ":" + v.getType(); mods = f.getModifiers().toString(); stat = f.isStatic();
            } else if (n instanceof EnumConstantDeclaration) {
                name = ((EnumConstantDeclaration)n).getNameAsString(); kind = "enum_constant"; signature = name;
            } else if (n instanceof AnnotationMemberDeclaration) {
                AnnotationMemberDeclaration a = (AnnotationMemberDeclaration)n; name = a.getNameAsString(); kind = "annotation_member"; signature = name + "():" + a.getType();
            } else if (n instanceof Parameter && n.getParentNode().orElse(null) instanceof RecordDeclaration) {
                Parameter p = (Parameter)n; name = p.getNameAsString(); kind = "record_component"; signature = name + ":" + p.getType();
            } else if (n instanceof CompactConstructorDeclaration) {
                CompactConstructorDeclaration c = (CompactConstructorDeclaration)n; name = c.getNameAsString(); kind = "constructor"; signature = name + "(compact)";
            } else if (n instanceof ImportDeclaration) {
                ImportDeclaration i = (ImportDeclaration)n; name = i.getNameAsString() + (i.isAsterisk() ? ".*" : ""); kind = "import"; signature = (i.isStatic() ? "static " : "") + name;
            }
            if (name == null) return;
            String owner = ownerName(n, data.packageName);
            String identity = name;
            if (n instanceof TypeDeclaration && n.getParentNode().orElse(null) instanceof com.github.javaparser.ast.stmt.LocalClassDeclarationStmt)
                identity += "@" + startLine(n);
            String qualified = "import".equals(kind) ? name : owner.isEmpty() ? identity : owner + "." + identity;
            String declaredType = n instanceof MethodDeclaration ? ((MethodDeclaration)n).getType().toString()
                    : n instanceof VariableDeclarator ? ((VariableDeclarator)n).getType().toString()
                    : n instanceof Parameter ? ((Parameter)n).getType().toString()
                    : n instanceof TypeDeclaration ? ((TypeDeclaration<?>)n).getNameAsString() : "";
            int start = startLine(n), end = endLine(n);
            // Field modifiers/annotations belong to the full declaration range, not just its name token.
            if ("field".equals(kind)) { start = startLine(n.getParentNode().get()); end = endLine(n.getParentNode().get()); }
            Symbol s = new Symbol(workspace, path, sourceHash, declaredType, declarationSignature(n, signature),
                    name, kind, owner, qualified, signature, mods, start, end, arity, stat, exact);
            data.symbols.add(s); nodes.put(n, s);
        });
        boolean inheritedTypes = cu.findAll(ClassOrInterfaceDeclaration.class).stream().anyMatch(t -> !t.getExtendedTypes().isEmpty());
        cu.walk(n -> {
            Symbol source = enclosingSymbol(n, nodes);
            if (source == null) return;
            if (n instanceof MethodCallExpr) {
                MethodCallExpr call = (MethodCallExpr)n;
                data.references.add(new Reference(source.id, source.owner, call.getNameAsString(),
                        call.getScope().map(Object::toString).orElse(""), "call", argumentTypes(call.getArguments()), startLine(n)));
            } else if (n instanceof ObjectCreationExpr) {
                ObjectCreationExpr call = (ObjectCreationExpr)n;
                data.references.add(new Reference(source.id, source.owner, call.getType().getNameWithScope(),
                        call.getScope().isPresent() || inheritedTypes ? "unresolved-enclosing-or-inherited-type" : "", "construct",
                        argumentTypes(call.getArguments()), startLine(n)));
            } else if (n instanceof NameExpr) {
                data.references.add(new Reference(source.id, source.owner, ((NameExpr)n).getNameAsString(), "", "reference", Collections.emptyList(), startLine(n)));
            }
        });
        data.symbols.sort(Comparator.comparingInt((Symbol s) -> s.line).thenComparing(s -> s.id));
        return data;
    }
    private static int startLine(Node n) { return n.getBegin().map(p -> p.line).orElse(1); }
    private static int endLine(Node n) { return n.getEnd().map(p -> p.line).orElse(startLine(n)); }
    private static String declarationSignature(Node n, String fallback) {
        if (n instanceof CallableDeclaration) {
            CallableDeclaration<?> c = (CallableDeclaration<?>)n; List<String> modifiers = new ArrayList<>();
            c.getModifiers().forEach(m -> modifiers.add(m.getKeyword().asString()));
            return (modifiers.isEmpty() ? "" : String.join(" ", modifiers) + " ")
                    + (c.getTypeParameters().isEmpty() ? "" : "<" + joinNodes(c.getTypeParameters()) + "> ")
                    + (n instanceof MethodDeclaration ? ((MethodDeclaration)n).getType() + " " : "")
                    + c.getNameAsString() + "(" + parameterTypes(c) + ")"
                    + (c.getThrownExceptions().isEmpty() ? "" : " throws " + joinNodes(c.getThrownExceptions()));
        }
        if (n instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration t = (ClassOrInterfaceDeclaration)n;
            String header = (t.isInterface() ? "interface " : "class ") + t.getNameAsString();
            if (!t.getTypeParameters().isEmpty()) header += "<" + joinNodes(t.getTypeParameters()) + ">";
            if (!t.getExtendedTypes().isEmpty()) header += " extends " + joinNodes(t.getExtendedTypes());
            if (!t.getImplementedTypes().isEmpty()) header += " implements " + joinNodes(t.getImplementedTypes());
            if (!t.getPermittedTypes().isEmpty()) header += " permits " + joinNodes(t.getPermittedTypes());
            return header;
        }
        if (n instanceof RecordDeclaration) {
            RecordDeclaration t = (RecordDeclaration)n;
            return "record " + t.getNameAsString() + (t.getTypeParameters().isEmpty() ? "" : "<" + joinNodes(t.getTypeParameters()) + ">")
                    + "(" + joinNodes(t.getParameters()) + ")" + (t.getImplementedTypes().isEmpty() ? "" : " implements " + joinNodes(t.getImplementedTypes()));
        }
        if (n instanceof EnumDeclaration) {
            EnumDeclaration t = (EnumDeclaration)n;
            return "enum " + t.getNameAsString() + (t.getImplementedTypes().isEmpty() ? "" : " implements " + joinNodes(t.getImplementedTypes()));
        }
        return fallback;
    }
    private static String joinNodes(List<? extends Node> nodes) {
        List<String> text = new ArrayList<>(); for (Node n : nodes) text.add(n.toString()); return String.join(",", text);
    }
    private static String parameterTypes(CallableDeclaration<?> c) {
        List<String> types = new ArrayList<>();
        for (Parameter p : c.getParameters()) types.add(p.getType().toString().replaceAll("\\s+", " ") + (p.isVarArgs() ? "..." : ""));
        return String.join(",", types);
    }
    private static String ownerName(Node n, String pkg) {
        List<String> owners = new ArrayList<>(); Node p = n.getParentNode().orElse(null);
        while (p != null) {
            if (p instanceof TypeDeclaration) {
                String name = ((TypeDeclaration<?>)p).getNameAsString();
                if (p.getParentNode().orElse(null) instanceof com.github.javaparser.ast.stmt.LocalClassDeclarationStmt) name += "@" + startLine(p);
                owners.add(name);
            } else if (p instanceof ObjectCreationExpr && ((ObjectCreationExpr)p).getAnonymousClassBody().isPresent()) owners.add("<anonymous@" + startLine(p) + ">");
            p = p.getParentNode().orElse(null);
        }
        Collections.reverse(owners); String o = String.join(".", owners);
        return pkg.isEmpty() ? o : o.isEmpty() ? pkg : pkg + "." + o;
    }
    private static Symbol enclosingSymbol(Node n, Map<Node, Symbol> nodes) {
        Node p = n.getParentNode().orElse(null);
        while (p != null) {
            Symbol s = nodes.get(p);
            if (s != null && ("method".equals(s.kind) || "constructor".equals(s.kind) || "field".equals(s.kind))) return s;
            p = p.getParentNode().orElse(null);
        }
        return null;
    }
    private static List<String> argumentTypes(List<Expression> args) {
        List<String> result = new ArrayList<>();
        for (Expression a : args) result.add(a instanceof StringLiteralExpr ? "String" : a instanceof IntegerLiteralExpr ? "int"
                : a instanceof LongLiteralExpr ? "long" : a instanceof BooleanLiteralExpr ? "boolean"
                : a instanceof CharLiteralExpr ? "char" : "?");
        return result;
    }
    private static List<Chunk> chunk(String path, String hash, String text, List<Symbol> symbols) {
        int[] lines = lineStarts(text); TreeSet<Integer> cuts = new TreeSet<>(); cuts.add(1); cuts.add(lines.length + 1);
        for (Symbol s : symbols) {
            if (!"class".equals(s.kind) && !"interface".equals(s.kind) && !"enum".equals(s.kind)) {
                cuts.add(s.line); cuts.add(Math.min(lines.length + 1, s.endLine + 1));
            }
        }
        List<Integer> points = new ArrayList<>(cuts); List<Chunk> result = new ArrayList<>();
        for (int p = 0; p + 1 < points.size(); p++) {
            int line = points.get(p), end = points.get(p + 1);
            while (line < end) {
                int next = Math.min(end, line + 48); int from = lines[line - 1];
                while (next > line + 1 && (next <= lines.length ? lines[next - 1] : text.length()) - from > 4096) next--;
                int to = next <= lines.length ? lines[next - 1] : text.length();
                // Long single lines are split only on token boundaries, without dropping the tail.
                int offset = from;
                do {
                    int stop = Math.min(to, offset + 4096);
                    if (stop < to && Character.isLowSurrogate(text.charAt(stop))) stop++;
                    while (stop < to && isIdentifier(text.codePointAt(stop))) stop += Character.charCount(text.codePointAt(stop));
                    if (stop > to) stop = to;
                    Map<String, Ints> tf = tokenPositions(text.substring(offset, stop), offset);
                    List<String> ids = new ArrayList<>();
                    for (Symbol s : symbols) if (s.line >= line && s.line < next && offset == from) ids.add(s.id);
                    result.add(new Chunk(path, hash, line, next - 1, offset, stop, tf, ids));
                    offset = stop;
                } while (offset < to);
                line = next;
            }
        }
        return result;
    }
    private Snapshot assemble(Build b, long version, long start) throws IOException {
        List<Chunk> chunks = new ArrayList<>(); Map<String, List<Posting>> postings = new TreeMap<>();
        Map<String, List<Integer>> exact = new HashMap<>(); long estimate = b.memory;
        // Amortized linear append: no copying the whole posting list for each document.
        for (Doc d : b.docs) for (Chunk c : d.chunks) {
            int id = chunks.size(); chunks.add(c);
            add(exact, d.path.toLowerCase(Locale.ROOT), id);
            for (String sid : c.symbolIds) for (Symbol s : d.symbols) if (sid.equals(s.id)) {
                add(exact, s.name.toLowerCase(Locale.ROOT), id);
                add(exact, s.qualifiedName.toLowerCase(Locale.ROOT), id);
                add(exact, s.signature.toLowerCase(Locale.ROOT), id);
            }
            for (Map.Entry<String, int[]> e : c.positions.entrySet()) {
                if (!postings.containsKey(e.getKey()) && postings.size() >= limits.vocabulary) throw new IOException("VOCABULARY_BUDGET");
                add(postings, e.getKey(), new Posting(id, e.getValue()));
            }
        }
        // All per-term/posting/graph maps were included in conservative per-doc accounting.
        List<Edge> edges = graph(b.docs);
        return new Snapshot(key, version, clock.millis(), Math.max(0, clock.millis() - start), b,
                chunks, postings, exact, edges, estimate);
    }
    private static String project(String path) {
        int slash = path.indexOf('/'); if (slash < 0) return "";
        String first = path.substring(0, slash);
        return set("src", "lib", "tests", "test", "main").contains(first) ? "" : first;
    }
    private static List<Edge> graph(List<Doc> docs) {
        List<Edge> result = new ArrayList<>(); Map<String, List<Symbol>> types = new HashMap<>();
        Map<String, List<Symbol>> members = new HashMap<>();
        for (Doc d : docs) for (Symbol s : d.symbols) {
            if (isType(s)) add(types, project(d.path) + "|" + s.qualifiedName, s);
            else add(members, project(d.path) + "|" + s.owner + "|" + s.name, s);
        }
        for (Doc d : docs) {
            String project = project(d.path);
            for (Symbol s : d.symbols) {
                List<Symbol> owners = types.get(project + "|" + s.owner);
                if (owners != null && owners.size() == 1) result.add(new Edge(owners.get(0).id, s.id, "contains", "KNOWN", "AST containment", s.line));
                if ("import".equals(s.kind)) {
                    List<Symbol> imported = types.get(project + "|" + s.name);
                    result.add(new Edge(s.id, imported != null && imported.size() == 1 ? imported.get(0).id : "",
                            "import", imported != null && imported.size() == 1 ? "KNOWN" : "UNKNOWN", "Explicit import; wildcard/static/external imports unresolved", s.line));
                }
            }
            for (Reference r : d.references) {
                if ("reference".equals(r.kind)) {
                    result.add(new Edge(r.from, "", r.kind, "UNKNOWN", "No type/shadowing resolver", r.line)); continue;
                }
                String owner = r.owner;
                if ("construct".equals(r.kind)) owner = r.scope.isEmpty() ? resolveType(r.target, d, types) : null;
                else if (!r.scope.isEmpty() && !"this".equals(r.scope)) owner = resolveType(r.scope, d, types);
                String name = "construct".equals(r.kind) && owner != null ? owner.substring(owner.lastIndexOf('.') + 1) : r.target;
                List<Symbol> candidates = owner == null ? null : members.get(project + "|" + owner + "|" + name);
                List<Symbol> viable = new ArrayList<>();
                if (candidates != null) for (Symbol s : candidates) {
                    if (!("method".equals(s.kind) || "constructor".equals(s.kind)) || s.arity != r.arguments.size()) continue;
                    // Only zero-argument calls can be proven without overload conversion/type inference.
                    if (s.arity == 0 && ("construct".equals(r.kind) || s.exactDispatch)
                            && (r.scope.isEmpty() || "this".equals(r.scope) || "construct".equals(r.kind))) viable.add(s);
                    // A scoped spelling may be a shadowed variable, not a type; no fake certainty without a resolver.
                }
                List<Symbol> ownerTypes = owner == null ? null : types.get(project + "|" + owner);
                boolean known = viable.size() == 1 && r.arguments.isEmpty() && ownerTypes != null && ownerTypes.size() == 1;
                result.add(new Edge(r.from, known ? viable.get(0).id : "", r.kind, known ? "KNOWN" : "UNKNOWN",
                        known ? "Explicit zero-arg static/private/final/constructor target" : "Unresolved overload, receiver, dynamic dispatch or external target", r.line));
            }
        }
        return result;
    }
    private static boolean isType(Symbol s) { return set("class", "interface", "enum", "annotation", "record").contains(s.kind); }
    private static String resolveType(String value, Doc d, Map<String, List<Symbol>> types) {
        if (value.indexOf('.') < 0) for (Symbol s : d.symbols)
            if (isType(s) && s.name.equals(value) && !s.owner.equals(d.packageName)) return null; // Lexical nested/local shadowing.
        List<String> names = new ArrayList<>(); names.add(value);
        if (!d.packageName.isEmpty()) names.add(d.packageName + "." + value);
        for (Symbol i : d.symbols) if ("import".equals(i.kind) && i.name.endsWith("." + value) && !i.signature.startsWith("static ")) names.add(i.name);
        String found = null;
        for (String n : names) {
            List<Symbol> c = types.get(project(d.path) + "|" + n);
            if (c != null && c.size() == 1) { if (found != null && !found.equals(n)) return null; found = n; }
        }
        return found;
    }
    private static final class Ints {
        int[] values = new int[4]; int size;
        void add(int n) { if (size > 0 && values[size - 1] == n) return; if (size == values.length) values = Arrays.copyOf(values, size * 2); values[size++] = n; }
        int[] array() { return Arrays.copyOf(values, size); }
    }
    private static void checkTokenBudget(String text, int cap) throws IndexLimit {
        int count = 0;
        for (int i = 0; i < text.length();) {
            int c = text.codePointAt(i);
            if (isHan(c)) {
                int start = i, points = 0;
                while (i < text.length() && isHan(text.codePointAt(i))) {
                    i += Character.charCount(text.codePointAt(i)); points++;
                    if (count + 2L * points - 1 > cap) throw new IndexLimit("TOKEN_BUDGET");
                }
                count += points * 2 - 1;
                if (points > 2 && i - start <= 32) count++;
            } else if (isIdentifier(c)) {
                int start = i;
                while (i < text.length() && !isHan(text.codePointAt(i)) && isIdentifier(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i));
                String id = text.substring(start, i); if (id.length() <= 128) count++;
                int part = 0;
                for (int n = 0; n <= id.length(); n++) {
                    boolean separator = n == id.length() || id.charAt(n) == '_' || id.charAt(n) == '$';
                    boolean boundary = n > part && n < id.length() && Character.isUpperCase(id.charAt(n))
                            && (Character.isLowerCase(id.charAt(n - 1)) || Character.isDigit(id.charAt(n - 1))
                            || (n + 1 < id.length() && Character.isLowerCase(id.charAt(n + 1))));
                    if (separator || boundary) {
                        if (n > part && n - part <= 128 && !(part == 0 && n == id.length())) count++;
                        part = separator ? n + 1 : n;
                    }
                }
            } else i += Character.charCount(c);
            if (count > cap) throw new IndexLimit("TOKEN_BUDGET");
        }
    }
    private static Map<String, Ints> tokenPositions(String text, int base) {
        Map<String, Ints> result = new LinkedHashMap<>();
        for (int i = 0; i < text.length();) {
            int c = text.codePointAt(i);
            // Han precedes Character.isLetter; supplementary CJK is not split into surrogate halves.
            if (isHan(c)) {
                int start = i; List<Integer> points = new ArrayList<>();
                while (i < text.length() && isHan(text.codePointAt(i))) { points.add(i); i += Character.charCount(text.codePointAt(i)); }
                for (int n = 0; n < points.size(); n++) {
                    int a = points.get(n), end = n + 1 < points.size() ? points.get(n + 1) : i;
                    token(result, text.substring(a, end), base + a);
                    if (n + 1 < points.size()) token(result, text.substring(a, n + 2 < points.size() ? points.get(n + 2) : i), base + a);
                }
                if (i - start <= 32) token(result, text.substring(start, i), base + start);
            } else if (isIdentifier(c)) {
                int start = i;
                while (i < text.length() && !isHan(text.codePointAt(i)) && isIdentifier(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i));
                String id = text.substring(start, i); Set<String> emitted = new HashSet<>();
                emit(result, id, base + start, emitted);
                int part = 0;
                for (int n = 0; n <= id.length(); n++) {
                    boolean separator = n == id.length() || id.charAt(n) == '_' || id.charAt(n) == '$';
                    boolean boundary = n > part && n < id.length() && Character.isUpperCase(id.charAt(n))
                            && (Character.isLowerCase(id.charAt(n - 1)) || Character.isDigit(id.charAt(n - 1))
                            || (n + 1 < id.length() && Character.isLowerCase(id.charAt(n + 1))));
                    if (separator || boundary) {
                        if (n > part) emit(result, id.substring(part, n), base + start + part, emitted);
                        part = separator ? n + 1 : n;
                    }
                }
            } else i += Character.charCount(c);
        }
        return result;
    }
    private static void emit(Map<String, Ints> map, String term, int at, Set<String> emitted) {
        String lower = term.toLowerCase(Locale.ROOT);
        if (lower.length() <= 128 && emitted.add(lower + "@" + at)) token(map, lower, at);
    }
    private static void token(Map<String, Ints> map, String t, int at) { if (!t.isEmpty()) map.computeIfAbsent(t, k -> new Ints()).add(at); }
    private static boolean isHan(int c) { return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN; }
    private static boolean isIdentifier(int c) { return Character.isLetterOrDigit(c) || c == '_' || c == '$'; }
    static List<String> terms(String query) {
        // Bound total input explicitly; do NOT silently truncate the first 32 terms/tail.
        if (query == null) return Collections.emptyList();
        if (query.length() > 32768) throw new IllegalArgumentException("QUERY_TOO_LARGE");
        List<String> result = new ArrayList<>(tokenPositions(query, 0).keySet());
        if (result.size() > 2048) throw new IllegalArgumentException("QUERY_TERM_LIMIT");
        return immutable(result);
    }
    List<Hit> search(String query, String pathPrefix, String glob, int maxResults) {
        request(false, false); Snapshot s = snapshot;
        return searchIn(s, query, pathPrefix, glob, maxResults);
    }
    private List<Hit> searchIn(Snapshot s, String query, String prefix, String glob, int max) {
        if (s == null || max <= 0) return Collections.emptyList();
        List<String> queryTerms = terms(query); if (queryTerms.isEmpty()) return Collections.emptyList();
        String scope = normalizePrefix(prefix); if (scope == null) return Collections.emptyList();
        if (glob != null && glob.length() > 1024) throw new IllegalArgumentException("GLOB_TOO_LARGE");
        Pattern filter = glob == null || glob.trim().isEmpty() ? null : Pattern.compile("^" + globRegex(glob.trim()) + "$", Pattern.CASE_INSENSITIVE);
        Map<Integer, Double> lexical = new HashMap<>(); Map<Integer, Map<Integer, Double>> lineScores = new HashMap<>();
        for (String term : queryTerms) {
            List<Posting> list = s.postings.get(term); if (list == null) continue;
            double idf = Math.log(1 + (s.chunks.size() - list.size() + .5) / (list.size() + .5));
            for (Posting p : list) {
                Chunk c = s.chunks.get(p.chunk); if (!matches(c.path, scope, filter)) continue;
                double score = idf * p.tf * (K1 + 1) / (p.tf + K1 * (1 - B + B * c.length / s.avgLength));
                lexical.merge(p.chunk, score, Double::sum);
                Doc d = s.byPath.get(c.path); Set<Integer> seen = new HashSet<>();
                for (int at : p.positions) {
                    int line = d.lineAt(at);
                    if (seen.add(line)) lineScores.computeIfAbsent(p.chunk, k -> new HashMap<>()).merge(line, idf, Double::sum);
                }
            }
        }
        Map<Integer, Double> fused = new HashMap<>(), structural = new HashMap<>();
        Map<Integer, List<String>> reasons = new HashMap<>();
        List<Integer> rank = new ArrayList<>(lexical.keySet());
        rank.sort((a, b) -> compareChunks(s, a, b, lexical.get(a), lexical.get(b)));
        for (int n = 0; n < rank.size(); n++) { int id = rank.get(n); fused.put(id, 1.0 / (61 + n)); add(reasons, id, "BM25(k1=1.2,b=0.75)"); }
        Set<Integer> exactIds = new TreeSet<>(); String raw = query.trim().toLowerCase(Locale.ROOT);
        List<Integer> whole = s.exact.get(raw);
        if (whole != null) for (int id : whole) if (matches(s.chunks.get(id).path, scope, filter)) exactIds.add(id);
        // A fully specified symbol/signature/path is one exact channel, not competing component-name seeds.
        // Fall back to component exact matches only when no whole-query target exists in this scope.
        if (exactIds.isEmpty()) for (String q : queryTerms) {
            List<Integer> locations = s.exact.get(q); if (locations == null) continue;
            for (int id : locations) if (matches(s.chunks.get(id).path, scope, filter)) exactIds.add(id);
        }
        int r = 0;
        for (int id : exactIds) { double v = 1.0 / (61 + r++); fused.merge(id, v, Double::sum); structural.put(id, v); add(reasons, id, "Exact symbol/path"); }
        // Bounded two-hop known graph; UNKNOWN edges never enter expansion.
        Set<String> visited = new HashSet<>(); List<String> frontier = new ArrayList<>();
        for (int id : exactIds) for (String sid : s.chunks.get(id).symbolIds) if (visited.add(sid)) frontier.add(sid);
        int expanded = 0;
        for (int hop = 1; hop <= 2 && !frontier.isEmpty() && expanded < 32; hop++) {
            List<String> next = new ArrayList<>();
            for (String sid : frontier) {
                int fan = 0;
                for (Edge e : s.graph.getOrDefault(sid, Collections.emptyList())) {
                    if (fan++ >= 8 || expanded >= 32) break;
                    String target = sid.equals(e.from) ? e.to : e.from;
                    if (!visited.add(target)) continue;
                    Integer id = s.symbolChunk.get(target); if (id == null || !matches(s.chunks.get(id).path, scope, filter)) continue;
                    double v = 1.0 / (93 + expanded++); fused.merge(id, v, Double::sum); structural.merge(id, v, Double::sum);
                    add(reasons, id, "Graph " + hop + " hop " + e.kind + ": " + e.reason); next.add(target);
                }
            }
            frontier = next;
        }
        List<Integer> sorted = new ArrayList<>(fused.keySet());
        sorted.sort((a, b) -> compareChunks(s, a, b, fused.get(a), fused.get(b)));
        List<Hit> hits = new ArrayList<>(); Map<String, Integer> perFile = new HashMap<>(); String fresh = new Status(this, s).state;
        for (int id : sorted) {
            Chunk c = s.chunks.get(id); Doc d = s.byPath.get(c.path);
            if (perFile.getOrDefault(d.path, 0) >= 2) continue;
            int line = c.startLine; double best = -1;
            for (Map.Entry<Integer, Double> e : lineScores.getOrDefault(id, Collections.emptyMap()).entrySet())
                if (e.getValue() > best || (e.getValue() == best && e.getKey() < line)) { line = e.getKey(); best = e.getValue(); }
            for (Symbol sym : d.symbols) if (c.symbolIds.contains(sym.id) && (sym.name.toLowerCase(Locale.ROOT).equals(raw)
                    || sym.qualifiedName.toLowerCase(Locale.ROOT).equals(raw) || sym.signature.toLowerCase(Locale.ROOT).equals(raw)
                    || (d.path.toLowerCase(Locale.ROOT).equals(raw) && isType(sym)))) { line = sym.line; break; }
            int matchingOffset = Math.max(c.startOffset, d.lineStarts[line - 1]);
            boolean located = false;
            for (String term : queryTerms) {
                int[] positions = c.positions.get(term); if (positions == null) continue;
                for (int at : positions) if (d.lineAt(at) == line && (!located || at < matchingOffset)) {
                    matchingOffset = at; located = true;
                }
            }
            hits.add(new Hit(d, c, line, matchingOffset, fused.get(id), lexical.getOrDefault(id, 0.0), structural.getOrDefault(id, 0.0), reasons.get(id), fresh));
            perFile.merge(d.path, 1, Integer::sum); if (hits.size() >= Math.min(max, 100)) break;
        }
        return immutable(hits);
    }
    private static int compareChunks(Snapshot s, int a, int b, double x, double y) {
        int n = Double.compare(y, x); if (n != 0) return n;
        Chunk ca = s.chunks.get(a), cb = s.chunks.get(b);
        n = ca.path.compareTo(cb.path); return n != 0 ? n : Integer.compare(a, b);
    }
    private static String normalizePrefix(String prefix) {
        String p = prefix == null ? "" : prefix.trim().replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) return null;
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        for (String part : p.split("/")) if (part.equals("..") || part.equals(".")) return null;
        return p;
    }
    private static boolean matches(String path, String prefix, Pattern glob) {
        return (prefix.isEmpty() || path.equals(prefix) || path.startsWith(prefix + "/")) && (glob == null || glob.matcher(path).matches());
    }
    String map(String pathPrefix, int maxFiles) {
        request(false, false); Snapshot s = snapshot; if (s == null) return "INDEX_NOT_READY";
        String prefix = normalizePrefix(pathPrefix); if (prefix == null) return "INVALID_SCOPE";
        StringBuilder out = new StringBuilder(); int n = 0;
        for (Doc d : s.docs) {
            if (!matches(d.path, prefix, null)) continue;
            if (n++ >= Math.max(0, Math.min(maxFiles, 100))) break;
            String line = "- " + d.path + " (" + d.lines + " lines, " + d.parserStatus + ")";
            List<String> names = new ArrayList<>(); for (Symbol sym : d.symbols) if (names.size() < 8) names.add(sym.name);
            if (!names.isEmpty()) line += " [" + String.join(", ", names) + "]";
            if (out.length() + line.length() + 1 > 6000) break;
            out.append(line).append('\n');
        }
        if (out.length() == 0 && !prefix.isEmpty()) {
            Set<String> candidates = new TreeSet<>(); for (Doc d : s.docs) { int slash = d.path.indexOf('/'); if (slash > 0 && candidates.size() < 8) candidates.add(d.path.substring(0, slash)); }
            return "SCOPE_NOT_FOUND: " + prefix + "; candidates=" + candidates;
        }
        return out.toString().trim();
    }
    String contextIfReady(String query, int maxChars) {
        request(false, false); Snapshot s = snapshot;
        if (s == null || s.version < revision.get()) return "";
        return context(s, query, maxChars);
    }
    String contextFor(String query, int maxChars) {
        request(false, false); return context(snapshot, query, maxChars);
    }
    private String context(Snapshot s, String query, int cap) {
        if (s == null || query == null || cap <= 0) return "";
        StringBuilder out = new StringBuilder();
        for (Hit h : searchIn(s, query, "", "", 5)) {
            String block = "### " + h.doc.path + ":" + h.startLine + "-" + h.endLine + " hash=" + h.sourceHash
                    + " " + h.freshness + " [untrusted repository data]\n" + h.snippet + "\n\n";
            if (out.length() + block.length() <= cap) out.append(block);
        }
        return out.toString().trim();
    }
    private static String snippet(Doc d, Chunk c, int line, int matchingOffset) {
        int start = Math.max(c.startLine, line - 2), end = Math.min(c.endLine, line + 4);
        StringBuilder out = new StringBuilder();
        for (int n = start; n <= end; n++) {
            int from = Math.max(c.startOffset, d.lineStarts[n - 1]);
            int to = Math.min(c.endOffset, n < d.lines ? d.lineStarts[n] : d.text.length());
            if (to < from) continue;
            int width = n == line ? 1400 : 192;
            int left = from, right = to;
            if (to - from > width) {
                if (n == line) left = Math.max(from, Math.min(matchingOffset - 200, to - width));
                right = Math.min(to, left + width);
                if (left > from && Character.isLowSurrogate(d.text.charAt(left))) left++;
                if (right < to && Character.isLowSurrogate(d.text.charAt(right))) right--;
            }
            String text = (left > from ? "[... ]" : "") + d.text.substring(left, right).replaceAll("[\\r\\n]+$", "")
                    + (right < to ? "[... ]" : "");
            String part = n + ": " + text + "\n";
            if (out.length() + part.length() > 2048) break;
            out.append(part);
        }
        return out.toString().trim();
    }
    private static int[] lineStarts(String text) {
        Ints starts = new Ints(); starts.add(0);
        for (int n = 0; n < text.length(); n++) {
            char c = text.charAt(n);
            if (c == '\r') {
                if (n + 1 < text.length() && text.charAt(n + 1) == '\n') n++;
                starts.add(n + 1);
            } else if (c == '\n') starts.add(n + 1);
        }
        return starts.array();
    }
    private static String ext(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT); }
    private static Set<String> set(String... v) { return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(v))); }
    private static <T> List<T> immutable(List<T> l) { return Collections.unmodifiableList(new ArrayList<>(l)); }
    private static <K,T> void add(Map<K,List<T>> map, K key, T value) { map.computeIfAbsent(key, k -> new ArrayList<>()).add(value); }
    private static <K,T> Map<K,List<T>> freezeLists(Map<K,List<T>> m) {
        Map<K,List<T>> copy = new HashMap<>(); for (Map.Entry<K,List<T>> e : m.entrySet()) copy.put(e.getKey(), immutable(e.getValue()));
        return Collections.unmodifiableMap(copy);
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static void hashPart(MessageDigest d, String s) { d.update(s.getBytes(StandardCharsets.UTF_8)); d.update((byte)0); }
    private static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 255)); return s.toString(); }
}
