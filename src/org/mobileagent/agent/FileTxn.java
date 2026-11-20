package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Per-file commits with a durable private manifest, not global multi-file atomicity.
 * Scope checks reduce risk; ordinary pathname opens cannot eliminate hostile TOCTOU.
 */
final class FileTxn {
    interface Checkpointer { String checkpoint(File file) throws Exception; }
    interface CancelGuard { void check() throws Exception; }
    interface FileOps {
        byte[] read(File file) throws IOException;
        void writeSynced(File file, byte[] bytes) throws IOException;
        void replace(File stage, File target) throws IOException;
        void delete(File target) throws IOException;
        void syncDirectory(File dir) throws IOException;
        boolean isLink(File file) throws IOException;
    }
    /** JVM-only adapter. Reflective Java-8 directory/link APIs never run on Android. */
    static class JvmFileOps implements FileOps {
        public byte[] read(File f) throws IOException {
            try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return out.toByteArray();
            }
        }
        public void writeSynced(File f, byte[] bytes) throws IOException {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(bytes); out.flush(); out.getFD().sync();
            }
        }
        public void replace(File stage, File target) throws IOException {
            if (!stage.renameTo(target)) throw new IOException("ATOMIC_REPLACE_FAILED");
        }
        public void delete(File target) throws IOException {
            if (!target.delete()) throw new IOException("DELETE_FAILED");
        }
        public boolean isLink(File file) throws IOException {
            try {
                Class<?> path = Class.forName("java.nio.file.Path");
                Object p = File.class.getMethod("toPath").invoke(file);
                return (Boolean) Class.forName("java.nio.file.Files")
                        .getMethod("isSymbolicLink", path).invoke(null, p);
            } catch (Exception e) { throw new IOException("JVM_LINK_INSPECTION_FAILED", e); }
        }
        public void syncDirectory(File dir) throws IOException {
            try {
                Class<?> path = Class.forName("java.nio.file.Path");
                Class<?> attrs = Class.forName("[Ljava.nio.file.attribute.FileAttribute;");
                Object p = File.class.getMethod("toPath").invoke(dir);
                Object empty = java.lang.reflect.Array.newInstance(Class.forName("java.nio.file.attribute.FileAttribute"), 0);
                java.nio.channels.FileChannel channel = (java.nio.channels.FileChannel)
                        java.nio.channels.FileChannel.class.getMethod("open", path, Set.class, attrs)
                                .invoke(null, p, Collections.emptySet(), empty);
                try { channel.force(true); } finally { channel.close(); }
            } catch (Exception e) { throw new IOException("JVM_DIRECTORY_SYNC_FAILED", e); }
        }
    }
    /** SDK21+ android.system.Os; no Android API26 java.nio.file dependency. */
    static class AndroidFileOps extends JvmFileOps {
        public boolean isLink(File f) throws IOException {
            try { return android.system.OsConstants.S_ISLNK(android.system.Os.lstat(f.getPath()).st_mode); }
            catch (android.system.ErrnoException e) {
                if (e.errno == android.system.OsConstants.ENOENT) return false;
                throw new IOException("LSTAT_FAILED", e);
            }
        }
        public void replace(File stage, File target) throws IOException {
            try {
                android.system.Os.rename(stage.getPath(), target.getPath());
                return;
            } catch (android.system.ErrnoException e) {
                if (e.errno != android.system.OsConstants.EXDEV) {
                    throw new IOException("ATOMIC_REPLACE_FAILED", e);
                }
            }
            // Cross file system (app Private stage → /sdcard Workspace: Copy to target and directory temporary files,
            // fsync Then it's the same catalog atom. rename; The old target is rename The previous text remains unchanged.
            File temp = null;
            try {
                temp = File.createTempFile("." + target.getName() + "-", ".stage",
                        target.getParentFile());
                try (FileInputStream in = new FileInputStream(stage);
                     FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        out.write(buffer, 0, count);
                    }
                    out.flush();
                    out.getFD().sync();
                }
                android.system.Os.rename(temp.getPath(), target.getPath());
                temp = null;
                stage.delete();
            } catch (android.system.ErrnoException e) {
                throw new IOException("CROSS_DEVICE_REPLACE_FAILED", e);
            } finally {
                if (temp != null) {
                    temp.delete();
                }
            }
        }
        public void syncDirectory(File dir) throws IOException {
            java.io.FileDescriptor fd = null;
            try {
                if (!dir.isDirectory()) throw new IOException("NOT_DIRECTORY");
                fd = android.system.Os.open(dir.getPath(), android.system.OsConstants.O_RDONLY, 0);
                android.system.Os.fsync(fd);
            } catch (android.system.ErrnoException e) { throw new IOException("DIRECTORY_SYNC_FAILED", e); }
            finally {
                if (fd != null) try { android.system.Os.close(fd); }
                catch (android.system.ErrnoException e) { throw new IOException("DIRECTORY_CLOSE_FAILED", e); }
            }
        }
    }
    enum State { SUCCEEDED, UNKNOWN, RECOVERY_REQUIRED, NOT_APPLIED, CONFLICT }
    enum Operation { CREATE, WRITE, DELETE, MOVE, RESTORE, MANIFEST }
    static final class Snapshot {
        final String path, hash;
        final boolean exists;
        private final byte[] bytes;
        Snapshot(String path, boolean exists, byte[] bytes) {
            this.path=path; this.exists=exists; this.bytes=bytes.clone();
            hash=exists ? sha256(bytes) : "NEW";
        }
        byte[] bytes() { return bytes.clone(); }
    }
    static final class Mutation {
        final String path, expectedHash;
        private final byte[] data; // null means delete
        Mutation(String path, byte[] data, String expectedHash) {
            this.path=path; this.data=data == null ? null : data.clone(); this.expectedHash=expectedHash;
        }
    }
    static final class Change {
        final String path, beforeHash, afterHash, expectedAfterHash;
        Change(String p, String b, String a, String expected) {
            path=p; beforeHash=b; afterHash=a; expectedAfterHash=expected;
        }
    }
    static final class Result {
        final String beforeHash, afterHash, checkpointId, txnId;
        final long bytes;
        final State state;
        final String errorCode;
        final List<Change> changes;
        Result(String id, State state, String code, List<Change> changes, String checkpoint, long bytes) {
            txnId=id; this.state=state; errorCode=code;
            this.changes=Collections.unmodifiableList(new ArrayList<>(changes));
            beforeHash=changes.isEmpty() ? "UNKNOWN" : changes.get(0).beforeHash;
            afterHash=changes.isEmpty() ? "UNKNOWN" : changes.get(0).afterHash;
            checkpointId=checkpoint; this.bytes=bytes;
        }
    }
    private static final Map<String,Object> LOCKS = new ConcurrentHashMap<>();
    static final class Store {
        final File root, privateDir;
        private final FileOps ops;
        private final Checkpointer checkpointer;
        private final CancelGuard guard;
        private final Object lock;
        Store(File root, File privateJournalDir) throws IOException {
            this(root, privateJournalDir, new AndroidFileOps(), null, null);
        }
        Store(File root, File privateJournalDir, FileOps ops, Checkpointer checkpointer, CancelGuard guard) throws IOException {
            if (root == null || privateJournalDir == null || ops == null) throw new IOException("STORE_ARGUMENT");
            this.root=root.getCanonicalFile(); this.ops=ops;
            if (!this.root.isDirectory()) throw new IOException("ROOT_NOT_DIRECTORY");
            // Caller must supply its app-private directory; never derive one in the workspace.
            File absolutePrivate = privateJournalDir.getAbsoluteFile();
            validateChain(absolutePrivate, ops);
            this.privateDir=absolutePrivate.getCanonicalFile();
            if (inside(this.root, this.privateDir) || this.root.equals(this.privateDir)
                    || inside(this.privateDir, this.root)) throw new IOException("JOURNAL_NOT_PRIVATE_SEPARATE");
            if (!this.privateDir.isDirectory() && !this.privateDir.mkdirs()) throw new IOException("JOURNAL_DIRECTORY");
            this.checkpointer=checkpointer; this.guard=guard;
            Object candidate=new Object(); Object prior=LOCKS.putIfAbsent(this.root.getPath(), candidate);
            lock=prior == null ? candidate : prior;
        }
        File resolve(String path) throws IOException {
            if (path == null || path.isEmpty() || new File(path).isAbsolute() || path.indexOf('\\') >= 0
                    || path.indexOf('\u0000') >= 0) throw new IOException("SCOPE");
            for (String part : path.split("/", -1))
                if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("SCOPE");
            File f=new File(root,path).getAbsoluteFile();
            // Root itself is canonical; inspect every component below it, including dangling links.
            File cursor=root;
            for (String part : path.split("/")) {
                cursor=new File(cursor,part);
                if (ops.isLink(cursor)) throw new IOException("SYMLINK");
            }
            if (!inside(root, f.getCanonicalFile()) || !f.equals(f.getCanonicalFile())) throw new IOException("SCOPE");
            if (f.exists() && !f.isFile()) throw new IOException("NOT_REGULAR_FILE");
            return f;
        }
        Snapshot snapshot(String path) throws IOException {
            synchronized(lock) { return readSnapshot(path); }
        }
        Snapshot snapshot(File target) throws IOException { return snapshot(relative(target)); }
        private String relative(File f) throws IOException {
            String p=f.getAbsolutePath(), r=root.getPath()+File.separator;
            if (!p.startsWith(r)) throw new IOException("SCOPE");
            return p.substring(r.length());
        }
        private Snapshot readSnapshot(String p) throws IOException {
            File f=resolve(p);
            boolean exists=f.exists();
            byte[] bytes=exists ? ops.read(f) : new byte[0];
            resolve(p); // Recheck after open; not a claim of descriptor-relative TOCTOU immunity.
            return new Snapshot(p, exists, bytes);
        }
        Result commit(String path, byte[] data, String expectedHash) throws Exception {
            if (data == null) throw new IOException("NULL_WRITE");
            return commit(Collections.singletonList(new Mutation(path,data,expectedHash)),
                    "NEW".equals(expectedHash) ? Operation.CREATE : Operation.WRITE);
        }
        Result commit(File target, byte[] data, String expectedHash) throws Exception {
            return commit(relative(target),data,expectedHash);
        }
        Result delete(String path, String expectedHash) throws Exception {
            return commit(Collections.singletonList(new Mutation(path,null,expectedHash)), Operation.DELETE);
        }
        Result move(String from, String to, String expectedFrom, String expectedTo) throws Exception {
            synchronized(lock) {
                if (from.equals(to)) throw new IOException("SAME_MOVE_PATH");
                Snapshot source=readSnapshot(from);
                requireHash(expectedFrom,source.hash);
                if (!source.exists) throw new IOException("MOVE_SOURCE_MISSING");
                return commit(Arrays.asList(new Mutation(to,source.bytes(),expectedTo),
                        new Mutation(from,null,expectedFrom)), Operation.MOVE);
            }
        }
        Result commit(List<Mutation> mutations, Operation operation) throws Exception {
            synchronized(lock) {
                check();
                if (mutations == null || mutations.isEmpty() || operation == null) throw new IOException("EMPTY_MANIFEST");
                String id="txn-"+UUID.randomUUID().toString();
                JSONObject intent=new JSONObject(); JSONArray entries=new JSONArray();
                List<Mutation> frozen=new ArrayList<>(mutations);
                Set<String> paths=new HashSet<>(); long bytes=0; String cp="";
                // Validate all scope and expected hashes before checkpoint/staging.
                List<Snapshot> snapshots=new ArrayList<>();
                for (Mutation m : frozen) {
                    check();
                    if (!paths.add(m.path)) throw new IOException("DUPLICATE_PATH");
                    Snapshot s=readSnapshot(m.path); requireHash(m.expectedHash,s.hash); snapshots.add(s);
                    if (!resolve(m.path).getParentFile().isDirectory()) throw new IOException("PARENT_MISSING");
                    if (operation == Operation.CREATE && (s.exists || m.data == null)) throw new IOException("CREATE_PRECONDITION");
                    if (operation == Operation.WRITE && m.data == null) throw new IOException("WRITE_PRECONDITION");
                    if (operation == Operation.DELETE && m.data != null) throw new IOException("DELETE_PRECONDITION");
                }
                if (operation == Operation.MOVE && (frozen.size() != 2 || frozen.get(0).data == null
                        || frozen.get(1).data != null || !snapshots.get(1).exists
                        || !sha256(frozen.get(0).data).equals(snapshots.get(1).hash))) throw new IOException("MOVE_PRECONDITION");
                for (int i=0; i<frozen.size(); i++) {
                    check(); Mutation m=frozen.get(i); Snapshot s=snapshots.get(i);
                    String checkpoint=checkpointer == null ? "" : checkpointer.checkpoint(resolve(m.path));
                    cp=checkpoint == null ? "" : checkpoint;
                    requireHash(s.hash,readSnapshot(m.path).hash);
                    String backup=id+"-"+i+".before", stage=id+"-"+i+".stage";
                    if (s.exists) ops.writeSynced(privateFile(backup),s.bytes());
                    if (m.data != null) { ops.writeSynced(privateFile(stage),m.data); bytes+=m.data.length; }
                    JSONObject e=new JSONObject();
                    e.put("path",m.path).put("exists",s.exists).put("before",s.hash)
                            .put("expectedAfter",m.data == null ? "NEW" : sha256(m.data))
                            .put("checkpoint",cp).put("backup",s.exists ? backup : "")
                            .put("stage",m.data == null ? "" : stage).put("operation",m.data == null ? "DELETE" : "WRITE");
                    entries.put(e);
                }
                ops.syncDirectory(privateDir);
                intent.put("version",1).put("id",id).put("root",root.getPath()).put("operation",operation.name())
                        .put("state","PREPARED").put("entries",entries);
                atomic(privateFile(id+".json"),intent.toString().getBytes(StandardCharsets.UTF_8));
                boolean attempted=false;
                try {
                    // Revalidate the whole manifest after checkpoint + stage + intent, then each file at commit.
                    for (Snapshot s : snapshots) requireHash(s.hash,readSnapshot(s.path).hash);
                    for (int i=0; i<frozen.size(); i++) {
                        check(); Mutation m=frozen.get(i); Snapshot s=snapshots.get(i);
                        File target=resolve(m.path); requireHash(s.hash,readSnapshot(m.path).hash);
                        check(); target=resolve(m.path);
                        requireHash(s.hash,readSnapshot(m.path).hash);
                        attempted=true;
                        if (m.data == null) { if (s.exists) ops.delete(target); }
                        else {
                            File stage=privateFile(entries.getJSONObject(i).getString("stage"));
                            if (!sha256(ops.read(stage)).equals(entries.getJSONObject(i).getString("expectedAfter")))
                                throw new IOException("STAGE_HASH_CONFLICT");
                            // Stage inspection can invoke slow/injected I/O: revalidate afterwards too.
                            check(); target=resolve(m.path);
                            requireHash(s.hash,readSnapshot(m.path).hash);
                            // No pre-delete, and absolutely no copy-over-original fallback.
                            ops.replace(stage,target);
                        }
                        ops.syncDirectory(target.getParentFile()); ops.syncDirectory(privateDir);
                        requireHash(entries.getJSONObject(i).getString("expectedAfter"),readSnapshot(m.path).hash);
                        intent.put("state","COMMITTING").put("committedCount",i+1);
                        atomic(privateFile(id+".json"),intent.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    // Recheck every committed fact before terminal success.
                    for (int i=0; i<entries.length(); i++) {
                        JSONObject e=entries.getJSONObject(i);
                        requireHash(e.getString("expectedAfter"),readSnapshot(e.getString("path")).hash);
                    }
                    intent.put("state","SUCCEEDED");
                    atomic(privateFile(id+".json"),intent.toString().getBytes(StandardCharsets.UTF_8));
                    return facts(intent,State.SUCCEEDED,"",cp,bytes);
                } catch (Exception e) {
                    // The durable pending intent remains recoverable. Never claim rollback or success.
                    String detail = e.getMessage() == null ? "" : e.getMessage().trim();
                    if (detail.length() > 120) detail = detail.substring(0, 120);
                    String code = detail.isEmpty() ? "COMMIT_INTERRUPTED"
                            : "COMMIT_INTERRUPTED:" + detail;
                    return facts(intent,attempted ? State.RECOVERY_REQUIRED : State.UNKNOWN,
                            code,cp,bytes);
                }
            }
        }
        List<Result> recover() throws Exception {
            synchronized(lock) {
                List<Result> reports=new ArrayList<>(); File[] files=privateDir.listFiles();
                if (files == null) throw new IOException("JOURNAL_LIST_FAILED");
                Arrays.sort(files);
                for (File file : files) {
                    if (!file.getName().matches("txn-[0-9a-f-]+\\.json")) continue;
                    try {
                        JSONObject intent=load(file.getName());
                        JSONArray entries=intent.getJSONArray("entries");
                        // load() validates ALL scopes before facts() reads ANY target.
                        Result facts=facts(intent,State.UNKNOWN,"","",0);
                        boolean allBefore=true, allAfter=true, conflict=false;
                        for (Change c : facts.changes) {
                            allBefore &= c.afterHash.equals(c.beforeHash);
                            allAfter &= c.afterHash.equals(c.expectedAfterHash);
                            conflict |= !c.afterHash.equals(c.beforeHash) && !c.afterHash.equals(c.expectedAfterHash);
                        }
                        String terminal=intent.getString("state");
                        if (allAfter && terminal.equals("SUCCEEDED")) ops.syncDirectory(privateDir);
                        State state=conflict ? State.CONFLICT : allAfter
                                ? (terminal.equals("SUCCEEDED") ? State.SUCCEEDED : State.RECOVERY_REQUIRED)
                                : allBefore && !terminal.equals("SUCCEEDED") ? State.NOT_APPLIED : State.RECOVERY_REQUIRED;
                        reports.add(new Result(intent.getString("id"),state,"RECOVERED_FACTS",facts.changes,"",0));
                    } catch (Exception e) {
                        reports.add(new Result(file.getName(),State.UNKNOWN,"INVALID_OR_UNREADABLE_INTENT",
                                Collections.<Change>emptyList(),"",0));
                    }
                }
                return reports; // No unknown side effects are replayed, no conflicting user data overwritten.
            }
        }
        Result restore(String txnId, Map<String,String> expectedCurrent) throws Exception {
            synchronized(lock) {
                if (txnId == null || !txnId.matches("txn-[0-9a-f-]+")) throw new IOException("TXN_ID");
                JSONObject intent=load(txnId+".json"); JSONArray entries=intent.getJSONArray("entries");
                List<Mutation> list=new ArrayList<>();
                for (int i=0; i<entries.length(); i++) {
                    check(); JSONObject e=entries.getJSONObject(i); String p=e.getString("path");
                    String expected=expectedCurrent == null ? null : expectedCurrent.get(p);
                    requireHash(expected,readSnapshot(p).hash);
                    byte[] data=e.getBoolean("exists") ? ops.read(privateFile(e.getString("backup"))) : null;
                    if (data != null && !sha256(data).equals(e.getString("before"))) throw new IOException("BACKUP_HASH_CONFLICT");
                    list.add(new Mutation(p,data,expected));
                }
                return commit(list,Operation.RESTORE);
            }
        }
        private JSONObject load(String name) throws Exception {
            JSONObject j=new JSONObject(new String(ops.read(privateFile(name)),StandardCharsets.UTF_8));
            if (j.getInt("version") != 1 || !j.getString("root").equals(root.getPath())
                    || !name.equals(j.getString("id")+".json")) throw new IOException("INTENT_SCOPE");
            Operation.valueOf(j.getString("operation"));
            String state=j.getString("state");
            if (!state.equals("PREPARED") && !state.equals("COMMITTING") && !state.equals("SUCCEEDED"))
                throw new IOException("INTENT_STATE");
            JSONArray es=j.getJSONArray("entries"); Set<String> paths=new HashSet<>();
            if (es.length() == 0) throw new IOException("INTENT_EMPTY");
            for (int i=0; i<es.length(); i++) {
                JSONObject e=es.getJSONObject(i); String p=e.getString("path"); resolve(p);
                if (!paths.add(p) || !validHash(e.getString("before")) || !validHash(e.getString("expectedAfter")))
                    throw new IOException("INTENT_HASH");
                if (e.getBoolean("exists") == e.getString("before").equals("NEW")) throw new IOException("INTENT_EXISTS");
                String op=e.getString("operation");
                if (!op.equals("WRITE") && !op.equals("DELETE")) throw new IOException("INTENT_OPERATION");
                String backup=e.getString("backup"), stage=e.getString("stage"), id=j.getString("id");
                if (!backup.equals(e.getBoolean("exists") ? id+"-"+i+".before" : "")
                        || !stage.equals(op.equals("WRITE") ? id+"-"+i+".stage" : "")
                        || (op.equals("DELETE") && !e.getString("expectedAfter").equals("NEW"))
                        || (op.equals("WRITE") && e.getString("expectedAfter").equals("NEW")))
                    throw new IOException("INTENT_PRIVATE_PATH");
                if (!backup.isEmpty()) privateFile(backup);
                if (!stage.isEmpty()) privateFile(stage);
            }
            return j;
        }
        private Result facts(JSONObject intent, State state, String code, String checkpoint, long bytes) throws Exception {
            JSONArray es=intent.getJSONArray("entries"); List<Change> changes=new ArrayList<>();
            // Validate the complete scope before reading, even on a failure result.
            boolean safe=true;
            try { for (int i=0; i<es.length(); i++) resolve(es.getJSONObject(i).getString("path")); }
            catch (IOException failure) { safe=false; state=State.UNKNOWN; code="SCOPE_CHANGED"; }
            for (int i=0; i<es.length(); i++) {
                JSONObject e=es.getJSONObject(i); String current="UNKNOWN";
                if (safe) try { current=readSnapshot(e.getString("path")).hash; }
                catch (IOException failure) { current="UNKNOWN"; }
                changes.add(new Change(e.getString("path"),e.getString("before"),current,e.getString("expectedAfter")));
                if (state == State.SUCCEEDED && !current.equals(e.getString("expectedAfter"))) {
                    state=State.UNKNOWN; code="FINAL_FACT_UNCONFIRMED";
                }
            }
            return new Result(intent.getString("id"),state,code,changes,checkpoint,bytes);
        }
        private void check() throws Exception { if (guard != null) guard.check(); }
        private File privateFile(String name) throws IOException {
            if (name == null || !name.matches("[a-zA-Z0-9._-]+")) throw new IOException("PRIVATE_PATH");
            validateChain(privateDir,ops);
            File f=new File(privateDir,name);
            if (ops.isLink(f) || !f.getCanonicalFile().equals(f.getAbsoluteFile())) throw new IOException("PRIVATE_LINK");
            return f;
        }
        private void atomic(File target, byte[] bytes) throws IOException {
            File stage=privateFile(target.getName()+"."+UUID.randomUUID()+".tmp");
            ops.writeSynced(stage,bytes); ops.replace(stage,target); ops.syncDirectory(privateDir);
        }
    }
    private static boolean inside(File root, File file) {
        return file.getPath().startsWith(root.getPath()+File.separator);
    }
    private static void validateChain(File f, FileOps ops) throws IOException {
        List<File> chain=new ArrayList<>();
        for (File c=f; c != null; c=c.getParentFile()) chain.add(c);
        for (int i=chain.size()-1; i>=0; i--) if (ops.isLink(chain.get(i))) throw new IOException("PRIVATE_LINK");
    }
    private static void requireHash(String expected, String actual) throws IOException {
        if (!validHash(expected) || !expected.equals(actual)) throw new IOException("VERSION_CONFLICT");
    }
    static boolean validHash(String hash) { return "NEW".equals(hash) || hash != null && hash.matches("[0-9a-f]{64}"); }
    static String sha256(byte[] bytes) {
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out=new StringBuilder();
            for (byte b : hash) out.append(String.format(Locale.ROOT,"%02x",b & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException("SHA256_UNAVAILABLE",e); }
    }
    static String hash(File file) {
        if (file == null) throw new IllegalArgumentException("NULL_FILE");
        if (!file.exists()) return "NEW";
        if (!file.isFile()) throw new IllegalArgumentException("NOT_REGULAR_FILE");
        try { return sha256(new JvmFileOps().read(file)); }
        catch (IOException e) { throw new IllegalStateException("HASH_READ_FAILED",e); }
    }
    /** Source compatibility only: the old signature cannot specify a private journal. Fail closed. */
    static Result commit(File root, File target, byte[] data, String expected, Checkpointer cp) throws Exception {
        throw new IOException("PRIVATE_STORE_REQUIRED");
    }
    static List<String> recover(File root) { throw new IllegalStateException("PRIVATE_STORE_REQUIRED"); }
    private FileTxn() { }
}
