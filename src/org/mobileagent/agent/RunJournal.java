package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Durable intent precedes execution. Pending calls become UNKNOWN, never replayed. */
final class RunJournal {
    interface Appender { void appendSynced(File file, byte[] bytes) throws IOException; }
    static final class DurableAppender implements Appender {
        public void appendSynced(File file, byte[] bytes) throws IOException {
            try (FileOutputStream out=new FileOutputStream(file,true)) {
                out.write(bytes); out.flush(); out.getFD().sync();
            }
        }
    }
    private static final Map<String,Object> LOCKS=new ConcurrentHashMap<>();
    static final class Store {
        private final FileTxn.Store scopeStore;
        private final FileTxn.FileOps ops;
        private final Appender appender;
        private final File dir;
        private final String runId;
        private final Object lock;
        private final VerificationLedger.HashProvider provider;
        private final Collection<VerificationLedger.Verifier> trusted;
        private JSONObject state;
        private VerificationLedger ledger;
        private boolean poisoned;
        Store(File root, File privateDir, String runId, FileTxn.FileOps ops,
              Appender appender, VerificationLedger.HashProvider provider,
              Collection<VerificationLedger.Verifier> trusted) throws Exception {
            token(runId);
            scopeStore=new FileTxn.Store(root,privateDir,ops,null,null);
            dir=scopeStore.privateDir; this.runId=runId; this.ops=ops;
            this.appender=appender == null ? new DurableAppender() : appender;
            this.provider=provider; this.trusted=new ArrayList<>(trusted);
            String key=dir.getPath()+"/"+runId;
            Object fresh=new Object(), old=LOCKS.putIfAbsent(key,fresh); lock=old == null ? fresh : old;
            synchronized(lock) { load(); }
        }
        private void load() throws Exception {
            JSONObject latest=null;
            File log=file(runId+".jsonl"), snapshot=file(runId+".json");
            if (snapshot.exists()) latest=validate(new JSONObject(new String(ops.read(snapshot),StandardCharsets.UTF_8)));
            if (log.exists()) {
                String text=new String(ops.read(log),StandardCharsets.UTF_8);
                // Torn/corrupt append is an explicit failure, not permission to replay an uncertain call.
                if (!text.isEmpty() && !text.endsWith("\n")) throw new IOException("TORN_RUN_APPEND");
                long previous=0;
                for (String line : text.split("\n")) {
                    if (line.isEmpty()) throw new IOException("EMPTY_RUN_EVENT");
                    JSONObject event=validate(new JSONObject(line)); long seq=event.getLong("seq");
                    if (seq != previous+1) throw new IOException("RUN_SEQUENCE");
                    previous=seq;
                    if (latest == null || seq > latest.getLong("seq")) latest=event;
                    else if (seq == latest.getLong("seq") && !event.toString().equals(latest.toString()))
                        throw new IOException("RUN_STATE_CONFLICT");
                }
                if (latest != null && latest.getLong("seq") > previous) throw new IOException("RUN_APPEND_MISSING");
            } else if (latest != null) throw new IOException("RUN_APPEND_MISSING");
            if (latest == null) {
                ledger=new VerificationLedger(runId,provider,trusted);
                state=new JSONObject().put("version",1).put("runId",runId).put("seq",0)
                        .put("calls",new JSONObject()).put("ledger",ledger.toJson());
            } else {
                state=latest;
                ledger=VerificationLedger.fromJson(state.getJSONObject("ledger"),provider,trusted);
                JSONObject calls=state.getJSONObject("calls"); boolean pending=false;
                for (Iterator<String> it=calls.keys(); it.hasNext();) {
                    JSONObject call=calls.getJSONObject(it.next());
                    if (call.getString("status").equals("PENDING")) {
                        call.put("status","UNKNOWN").put("errorCode","INTERRUPTED"); pending=true;
                    }
                }
                if (pending) persist("RECOVER");
            }
        }
        private JSONObject validate(JSONObject j) throws Exception {
            if (j.getInt("version") != 1 || !runId.equals(j.getString("runId")) || j.getLong("seq") <= 0
                    || !runId.equals(j.getJSONObject("ledger").getString("runId"))) throw new IOException("RUN_SCOPE");
            JSONObject calls=j.getJSONObject("calls");
            // Validate all scopes before any ledger rehash reads filesystem content.
            for (Iterator<String> it=calls.keys(); it.hasNext();) {
                String callId=it.next(); token(callId); JSONObject c=calls.getJSONObject(callId);
                token(c.getString("tool")); hash(c.getString("argsHash"));
                status(c.getString("status"),true); token(c.getString("errorCode"),true);
                JSONArray scope=c.getJSONArray("scope"); JSONObject before=c.getJSONObject("stateBefore");
                Set<String> paths=new LinkedHashSet<>();
                for (int i=0; i<scope.length(); i++) {
                    String path=scope.getString(i); scopeStore.resolve(path);
                    if (!paths.add(path) || !before.has(path) || !FileTxn.validHash(before.getString(path)))
                        throw new IOException("RUN_STATE_BEFORE");
                }
                if (before.length() != paths.size()) throw new IOException("RUN_STATE_BEFORE");
            }
            JSONObject ledger=j.getJSONObject("ledger");
            JSONArray entries=ledger.getJSONArray("entries"), tickets=ledger.getJSONArray("tickets"), receipts=ledger.getJSONArray("receipts");
            for (int i=0; i<entries.length(); i++) scopeStore.resolve(entries.getJSONObject(i).getString("path"));
            for (int i=0; i<tickets.length(); i++) scopeHashes(tickets.getJSONObject(i).getJSONObject("hashes"));
            for (int i=0; i<receipts.length(); i++) {
                JSONObject receipt=receipts.getJSONObject(i);
                scopeHashes(receipt.getJSONObject("snapshot").getJSONObject("hashes"));
                scopeHashes(receipt.getJSONObject("currentHashes"));
                for (String key : Arrays.asList("paths","dependencies")) {
                    JSONArray paths=receipt.getJSONArray(key);
                    for (int k=0; k<paths.length(); k++) scopeStore.resolve(paths.getString(k));
                }
                String artifact=receipt.getString("artifactPath");
                if (!artifact.isEmpty()) scopeStore.resolve(artifact);
            }
            return j;
        }
        private void scopeHashes(JSONObject hashes) throws Exception {
            for (Iterator<String> it=hashes.keys(); it.hasNext();) scopeStore.resolve(it.next());
        }
        VerificationLedger ledger() { return ledger; }
        String runId() { return runId; }
        /** Call before dispatching ANY side effect; an exception must prevent dispatch. */
        void begin(String callId, String tool, String argsHash, List<String> scope,
                   Map<String,String> stateBefore) throws Exception {
            synchronized(lock) {
                healthy(); token(callId); token(tool); hash(argsHash);
                JSONObject calls=state.getJSONObject("calls");
                if (calls.has(callId)) throw new IOException("CALL_ALREADY_RECORDED");
                Set<String> unique=new LinkedHashSet<>(scope);
                if (unique.size() != scope.size() || !unique.equals(stateBefore.keySet())) throw new IOException("STATE_SCOPE");
                JSONObject before=new JSONObject();
                for (String p : scope) {
                    String h=stateBefore.get(p); scopeStore.resolve(p);
                    if (!FileTxn.validHash(h) || !h.equals(scopeStore.snapshot(p).hash)) throw new IOException("STATE_HASH_CONFLICT");
                    before.put(p,h);
                }
                calls.put(callId,new JSONObject().put("tool",tool).put("argsHash",argsHash)
                        .put("scope",new JSONArray(scope)).put("stateBefore",before)
                        .put("status","PENDING").put("errorCode",""));
                persist("BEGIN");
            }
        }
        void complete(String callId, String status, String errorCode) throws Exception {
            synchronized(lock) {
                healthy(); token(callId); status(status,false); token(errorCode,true);
                JSONObject calls=state.getJSONObject("calls");
                if (!calls.has(callId)) throw new IOException("CALL_NOT_RECORDED");
                String old=calls.getJSONObject(callId).getString("status");
                if (!old.equals("PENDING") && !old.equals("UNKNOWN") && !old.equals("RECOVERY_REQUIRED"))
                    throw new IOException("CALL_ALREADY_TERMINAL");
                calls.getJSONObject(callId).put("status",status).put("errorCode",errorCode);
                persist("END");
            }
        }
        void saveLedger(VerificationLedger sameRunLedger) throws Exception {
            synchronized(lock) {
                healthy();
                if (!runId.equals(sameRunLedger.runId())) throw new IOException("LEDGER_RUN_MISMATCH");
                ledger=sameRunLedger; persist("LEDGER");
            }
        }
        JSONObject calls() throws Exception {
            synchronized(lock) { healthy(); return new JSONObject(state.getJSONObject("calls").toString()); }
        }
        private void persist(String event) throws Exception {
            healthy();
            try {
                // A second handle must not overwrite a newer same-run journal under the shared lock.
                File existing=file(runId+".jsonl");
                long diskSequence=0;
                if (existing.exists()) {
                    String log=new String(ops.read(existing),StandardCharsets.UTF_8);
                    if (!log.isEmpty()) {
                        if (!log.endsWith("\n")) throw new IOException("TORN_RUN_APPEND");
                        int previous=log.lastIndexOf('\n',log.length()-2);
                        diskSequence=new JSONObject(log.substring(previous+1,log.length()-1)).getLong("seq");
                    }
                }
                if (diskSequence != state.getLong("seq")) throw new IOException("STALE_RUN_HANDLE");
                state.put("seq",state.getLong("seq")+1).put("event",event).put("ledger",ledger.toJson());
                byte[] bytes=state.toString().getBytes(StandardCharsets.UTF_8);
                appender.appendSynced(file(runId+".jsonl"),(state.toString()+"\n").getBytes(StandardCharsets.UTF_8));
                ops.syncDirectory(dir);
                File stage=file(runId+"."+UUID.randomUUID()+".tmp");
                ops.writeSynced(stage,bytes); ops.replace(stage,file(runId+".json")); ops.syncDirectory(dir);
            } catch (Exception e) { poisoned=true; throw new IOException("RUN_DURABILITY_FAILED",e); }
        }
        private void healthy() throws IOException { if (poisoned) throw new IOException("RUN_REOPEN_REQUIRED"); }
        private File file(String name) throws IOException {
            File f=new File(dir,name);
            if (ops.isLink(dir) || ops.isLink(f) || !dir.getCanonicalFile().equals(dir)
                    || !f.getCanonicalFile().equals(f.getAbsoluteFile())) throw new IOException("RUN_PRIVATE_LINK");
            return f;
        }
    }
    private static void token(String value) throws IOException { token(value,false); }
    private static void token(String value, boolean empty) throws IOException {
        if (empty && "".equals(value)) return;
        if (value == null || !value.matches("[a-zA-Z0-9_.-]{1,100}") || value.equals(".") || value.equals(".."))
            throw new IOException("JOURNAL_TOKEN");
    }
    private static void hash(String h) throws IOException {
        if (h == null || !h.matches("[0-9a-f]{64}")) throw new IOException("ARGS_HASH");
    }
    private static void status(String s, boolean pending) throws IOException {
        if (pending && "PENDING".equals(s)) return;
        if (!Arrays.asList("SUCCEEDED","FAILED","BLOCKED","CANCELLED","UNKNOWN","RECOVERY_REQUIRED").contains(s))
            throw new IOException("CALL_STATUS");
    }
    /** Legacy diagnostic record. Drops ALL detail, has no authority as execution/evidence intent. */
    static void record(File dir, String runId, String event, String callId, String tool, String status, String detail) {
        try {
            token(runId); token(event); token(callId,true); token(tool); token(status);
            if (dir == null || !dir.isDirectory() || !dir.getAbsoluteFile().equals(dir.getCanonicalFile()))
                throw new IOException("PRIVATE_JOURNAL_REQUIRED");
            File file=new File(dir,runId+".diagnostic.jsonl");
            FileTxn.FileOps ops="Dalvik".equals(System.getProperty("java.vm.name"))
                    ? new FileTxn.AndroidFileOps() : new FileTxn.JvmFileOps();
            if (ops.isLink(file) || !file.getCanonicalFile().equals(file.getAbsoluteFile())) throw new IOException("JOURNAL_LINK");
            JSONObject item=new JSONObject().put("runId",runId).put("event",event).put("callId",callId)
                    .put("tool",tool).put("status",status); // Never persist arbitrary detail, keys or raw args.
            new DurableAppender().appendSynced(file,(item.toString()+"\n").getBytes(StandardCharsets.UTF_8));
            ops.syncDirectory(dir);
        } catch (Exception e) { throw new IllegalStateException("JOURNAL_WRITE_FAILED",e); }
    }
    private RunJournal() { }
}
