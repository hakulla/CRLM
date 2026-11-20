package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.*;

/** Evidence is a receipt from an engine-installed verifier, not command-name inference. */
final class VerificationLedger {
    static final int V1_READ=1, V2_BUILD=2, V3_TEST=3, V4_FUNCTION=4;
    interface HashProvider { String currentHash(String path) throws Exception; }
    interface Verifier {
        String id();
        int maxLevel();
        // Must validate execution provenance/structured exit/artifact, not an LLM success string.
        boolean authentic(Evidence evidence) throws Exception;
    }
    static final class Snapshot {
        final String id, project;
        final long generation;
        final Map<String,String> hashes;
        final Map<String,Integer> requiredLevels;
        Snapshot(String id, String project, long generation, Map<String,String> hashes, Map<String,Integer> requiredLevels) {
            this.id=id; this.project=project; this.generation=generation;
            this.hashes=Collections.unmodifiableMap(new LinkedHashMap<>(hashes));
            this.requiredLevels=Collections.unmodifiableMap(new LinkedHashMap<>(requiredLevels));
        }
    }
    static final class Evidence {
        final String snapshotId, verifierId, project, artifactPath, artifactHash;
        final int level, exitCode, testsRun, testsFailed;
        final Map<String,String> expectedHashes, currentHashes;
        final Map<String,Integer> requiredLevels;
        final List<String> paths, dependencies;
        Evidence(Snapshot snapshot, String verifierId, int level, Map<String,String> currentHashes,
                 Collection<String> paths, Collection<String> dependencies, int exitCode,
                 String artifactPath, String artifactHash, int testsRun, int testsFailed) {
            snapshotId=snapshot.id; project=snapshot.project; this.verifierId=verifierId;
            this.level=level; this.exitCode=exitCode; this.testsRun=testsRun; this.testsFailed=testsFailed;
            expectedHashes=snapshot.hashes; requiredLevels=snapshot.requiredLevels;
            this.currentHashes=Collections.unmodifiableMap(new LinkedHashMap<>(currentHashes));
            this.paths=Collections.unmodifiableList(new ArrayList<>(paths));
            this.dependencies=Collections.unmodifiableList(new ArrayList<>(dependencies));
            this.artifactPath=artifactPath == null ? "" : artifactPath;
            this.artifactHash=artifactHash == null ? "" : artifactHash;
        }
    }
    private static final class Entry {
        final String hash, project;
        final int required;
        int level;
        Entry(String h, String p, int r) { hash=h; project=p; required=r; }
    }
    private final String runId;
    private final HashProvider provider;
    private final Map<String,Verifier> verifiers=new LinkedHashMap<>();
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private final Map<String,Boolean> projectTests=new LinkedHashMap<>();
    private final Map<String,Long> generations=new LinkedHashMap<>();
    private final Map<String,Snapshot> tickets=new LinkedHashMap<>();
    private final List<Evidence> receipts=new ArrayList<>();
    VerificationLedger() { this("legacy",null,Collections.<Verifier>emptyList()); }
    VerificationLedger(String runId, HashProvider provider, Collection<Verifier> trustedVerifiers) {
        if (runId == null || !runId.matches("[a-zA-Z0-9_-]{1,100}")) throw new IllegalArgumentException("RUN_ID");
        this.runId=runId; this.provider=provider;
        for (Verifier v : trustedVerifiers) {
            if (v == null || v.id() == null || !v.id().matches("[a-zA-Z0-9_.-]{1,100}")
                    || v.maxLevel() < 1 || v.maxLevel() > 4 || verifiers.put(v.id(),v) != null)
                throw new IllegalArgumentException("VERIFIER_REGISTRY");
        }
    }
    String runId() { return runId; }
    synchronized void project(String project, boolean hasTests) {
        validPath(project,true);
        if (!Objects.equals(projectTests.get(project),hasTests)) {
            projectTests.put(project,hasTests); invalidate(Collections.singleton(project));
        }
    }
    synchronized void changed(String path, String afterHash) {
        changed(path,afterHash,defaultProject(path),minimum(path),false);
    }
    synchronized void changed(String path, String afterHash, String project, int requiredLevel, boolean hasTests) {
        validPath(path,false); validPath(project,true);
        if (!FileTxn.validHash(afterHash)) throw new IllegalArgumentException("CHANGE_HASH");
        if (provider != null && !afterHash.equals(actual(path))) throw new IllegalStateException("CHANGE_HASH_MISMATCH");
        if (requiredLevel < 1 || requiredLevel > 4) throw new IllegalArgumentException("REQUIRED_LEVEL");
        if (hasTests) projectTests.put(project,true);
        Set<String> affected=new LinkedHashSet<>(); affected.add(project);
        for (Evidence e : receipts) if (e.expectedHashes.containsKey(path)) affected.add(e.project);
        for (Snapshot s : tickets.values()) if (s.hashes.containsKey(path)) affected.add(s.project);
        invalidate(affected);
        entries.put(path,new Entry(afterHash,project,Math.max(minimum(path),requiredLevel)));
    }
    private void invalidate(Set<String> projects) {
        Set<String> affected=new LinkedHashSet<>(projects);
        boolean expanded;
        do {
            expanded=false;
            for (Evidence e : receipts) for (String p : e.expectedHashes.keySet()) {
                Entry dependency=entries.get(p);
                String owner=dependency == null ? defaultProject(p) : dependency.project;
                if (affected.contains(owner)) expanded |= affected.add(e.project);
            }
            for (Snapshot s : tickets.values()) for (String p : s.hashes.keySet()) {
                Entry dependency=entries.get(p);
                String owner=dependency == null ? defaultProject(p) : dependency.project;
                if (affected.contains(owner)) expanded |= affected.add(s.project);
            }
        } while (expanded);
        for (String project : affected) generations.put(project,generation(project)+1);
        for (Entry e : entries.values()) if (affected.contains(e.project)) e.level=0;
        for (Iterator<Evidence> it=receipts.iterator(); it.hasNext();) {
            if (affected.contains(it.next().project)) it.remove();
        }
        for (Iterator<Snapshot> it=tickets.values().iterator(); it.hasNext();) {
            if (affected.contains(it.next().project)) it.remove();
        }
    }
    private long generation(String p) { Long n=generations.get(p); return n == null ? 0 : n; }
    synchronized Snapshot snapshot(Iterable<String> paths) {
        List<String> list=new ArrayList<>(); String project=null;
        for (String p : paths) {
            Entry e=entries.get(p);
            if (e == null) throw new IllegalArgumentException("UNTRACKED_PATH");
            if (project != null && !project.equals(e.project)) throw new IllegalArgumentException("MIXED_PROJECT");
            project=e.project; list.add(p);
        }
        if (project == null) throw new IllegalArgumentException("EMPTY_SNAPSHOT");
        return snapshot(project,list);
    }
    /** Include the complete dependency scope, not just the edited files, in this snapshot. */
    synchronized Snapshot snapshot(String project, Iterable<String> paths) {
        validPath(project,true);
        if (provider == null) throw new IllegalStateException("HASH_PROVIDER_REQUIRED");
        Map<String,String> hashes=new LinkedHashMap<>();
        for (String p : paths) {
            validPath(p,false); String h=actual(p);
            Entry e=entries.get(p);
            if (e != null && !e.hash.equals(h)) throw new IllegalStateException("STALE_CHANGE_HASH");
            if (hashes.put(p,h) != null) throw new IllegalArgumentException("DUPLICATE_PATH");
        }
        if (hashes.isEmpty()) throw new IllegalArgumentException("EMPTY_SNAPSHOT");
        Map<String,Integer> requirements=new LinkedHashMap<>();
        for (String p : hashes.keySet()) requirements.put(p,entries.containsKey(p) ? requiredLevel(p) : minimum(p));
        Snapshot s=new Snapshot(UUID.randomUUID().toString(),project,generation(project),hashes,requirements);
        tickets.put(s.id,s); return s;
    }
    synchronized boolean acceptEvidence(Evidence e) {
        if (provider == null || e == null) return false;
        Snapshot s=tickets.get(e.snapshotId); Verifier v=verifiers.get(e.verifierId);
        if (s == null || v == null || !s.project.equals(e.project) || s.generation != generation(e.project)
                || e.level < 1 || e.level > v.maxLevel() || e.exitCode != 0
                || !s.hashes.equals(e.expectedHashes) || !s.hashes.equals(e.currentHashes)
                || !s.requiredLevels.equals(e.requiredLevels) || e.paths.isEmpty()) return false;
        Set<String> all=new LinkedHashSet<>(e.paths);
        if (all.size() != e.paths.size()) return false;
        for (String p : e.dependencies) if (!all.add(p)) return false;
        if (!all.equals(s.hashes.keySet())) return false;
        try {
            for (String p : all) if (!s.hashes.get(p).equals(actual(p))) return false;
            for (String p : e.paths) {
                Entry entry=entries.get(p);
                if (entry == null || !entry.project.equals(e.project) || !entry.hash.equals(s.hashes.get(p))
                        || !Objects.equals(s.requiredLevels.get(p),requiredLevel(p))) return false;
            }
            if (e.level >= V2_BUILD) {
                validPath(e.artifactPath,false);
                if (e.artifactHash.equals("NEW") || !FileTxn.validHash(e.artifactHash)
                        || !e.artifactHash.equals(actual(e.artifactPath))) return false;
            }
            if (e.level >= V3_TEST && (e.testsRun <= 0 || e.testsFailed != 0)) return false;
            if (!v.authentic(e)) return false;
        } catch (Exception failure) { return false; } // Rejection, never an evidence grant.
        tickets.remove(s.id); receipts.add(e);
        for (String p : e.paths) entries.get(p).level=Math.max(entries.get(p).level,e.level);
        return true;
    }
    private String actual(String path) {
        try {
            String h=provider.currentHash(path);
            if (!FileTxn.validHash(h)) throw new IOException("UNKNOWN_HASH");
            return h;
        } catch (Exception e) { throw new IllegalStateException("HASH_CHECK_FAILED",e); }
    }
    /** Legacy string/boolean APIs carry neither hashes nor execution provenance. No grants. */
    synchronized void evidence(Iterable<String> paths, int level, String verifier, boolean success) { }
    synchronized void evidenceAll(int level, String verifier, boolean success) { }
    synchronized int requiredLevel(String path) {
        Entry e=entries.get(path);
        if (e == null) return 0;
        return Math.max(e.required,Boolean.TRUE.equals(projectTests.get(e.project)) ? V3_TEST : V1_READ);
    }
    synchronized boolean covered(String path) { return entries.containsKey(path) && level(path) >= requiredLevel(path); }
    synchronized int level(String path) {
        Entry e=entries.get(path);
        if (e == null) return 0;
        // Check current hashes AND artifacts/dependencies whenever completion is queried.
        if (provider != null) {
            Set<String> stale=new LinkedHashSet<>();
            for (Evidence receipt : receipts) {
                try {
                    for (Map.Entry<String,String> h : receipt.expectedHashes.entrySet())
                        if (!h.getValue().equals(actual(h.getKey()))) stale.add(receipt.project);
                    if (receipt.level >= V2_BUILD && !receipt.artifactHash.equals(actual(receipt.artifactPath))) stale.add(receipt.project);
                } catch (Exception failure) { stale.add(receipt.project); }
            }
            if (!stale.isEmpty()) invalidate(stale);
        }
        return e.level;
    }
    synchronized List<String> unverifiedRequired() {
        List<String> values=new ArrayList<>();
        for (String p : entries.keySet()) if (!covered(p)) values.add(p);
        return values;
    }
    synchronized List<String> unverified() { return unverifiedRequired(); }
    synchronized List<String> paths() { return new ArrayList<>(entries.keySet()); }
    synchronized String summary() {
        if (entries.isEmpty()) return "No document changes to this task";
        StringBuilder out=new StringBuilder();
        for (String p : entries.keySet()) out.append(p).append(':').append(levelName(level(p)))
                .append(" / required V").append(requiredLevel(p)).append(';');
        return out.toString();
    }
    synchronized JSONObject toJson() throws Exception {
        // Refresh stale receipts before persisting a completion claim.
        unverifiedRequired();
        JSONObject j=new JSONObject().put("version",1).put("runId",runId);
        JSONArray es=new JSONArray(), ss=new JSONArray(), rs=new JSONArray();
        for (Map.Entry<String,Entry> item : entries.entrySet()) {
            Entry e=item.getValue();
            es.put(new JSONObject().put("path",item.getKey()).put("hash",e.hash).put("project",e.project).put("required",e.required));
        }
        for (Snapshot s : tickets.values()) ss.put(snapshotJson(s));
        for (Evidence e : receipts) {
            Snapshot s=new Snapshot(e.snapshotId,e.project,generation(e.project),e.expectedHashes,e.requiredLevels);
            rs.put(new JSONObject().put("snapshot",snapshotJson(s)).put("verifierId",e.verifierId).put("level",e.level)
                    .put("currentHashes",mapJson(e.currentHashes)).put("paths",new JSONArray(e.paths))
                    .put("dependencies",new JSONArray(e.dependencies)).put("exitCode",e.exitCode)
                    .put("artifactPath",e.artifactPath).put("artifactHash",e.artifactHash)
                    .put("testsRun",e.testsRun).put("testsFailed",e.testsFailed));
        }
        JSONObject tests=new JSONObject(), gens=new JSONObject();
        for (String p : projectTests.keySet()) tests.put(p,projectTests.get(p));
        for (String p : generations.keySet()) gens.put(p,generations.get(p));
        return j.put("entries",es).put("tickets",ss).put("receipts",rs).put("projectTests",tests).put("generations",gens);
    }
    static VerificationLedger fromJson(JSONObject j) throws Exception {
        return fromJson(j,null,Collections.<Verifier>emptyList());
    }
    static VerificationLedger fromJson(JSONObject j, HashProvider provider, Collection<Verifier> trusted) throws Exception {
        if (j.getInt("version") != 1) throw new IOException("LEDGER_VERSION");
        VerificationLedger l=new VerificationLedger(j.getString("runId"),provider,trusted);
        JSONObject tests=j.getJSONObject("projectTests"), gens=j.getJSONObject("generations");
        for (Iterator<String> it=tests.keys(); it.hasNext();) { String p=it.next(); validPath(p,true); l.projectTests.put(p,tests.getBoolean(p)); }
        for (Iterator<String> it=gens.keys(); it.hasNext();) {
            String p=it.next(); validPath(p,true); long n=gens.getLong(p);
            if (n < 0) throw new IOException("GENERATION"); l.generations.put(p,n);
        }
        JSONArray es=j.getJSONArray("entries");
        for (int i=0; i<es.length(); i++) {
            JSONObject e=es.getJSONObject(i); String p=e.getString("path"), project=e.getString("project"), h=e.getString("hash");
            validPath(p,false); validPath(project,true); int r=e.getInt("required");
            if (!FileTxn.validHash(h) || r < minimum(p) || r > 4 || l.entries.containsKey(p)) throw new IOException("LEDGER_ENTRY");
            l.entries.put(p,new Entry(h,project,r));
        }
        JSONArray tickets=j.getJSONArray("tickets");
        for (int i=0; i<tickets.length(); i++) { Snapshot s=readSnapshot(tickets.getJSONObject(i)); l.tickets.put(s.id,s); }
        JSONArray receipts=j.getJSONArray("receipts");
        for (int i=0; i<receipts.length(); i++) {
            JSONObject e=receipts.getJSONObject(i); Snapshot s=readSnapshot(e.getJSONObject("snapshot"));
            Evidence evidence=new Evidence(s,e.getString("verifierId"),e.getInt("level"),readMap(e.getJSONObject("currentHashes")),
                    strings(e.getJSONArray("paths")),strings(e.getJSONArray("dependencies")),e.getInt("exitCode"),
                    e.getString("artifactPath"),e.getString("artifactHash"),e.getInt("testsRun"),e.getInt("testsFailed"));
            l.tickets.put(s.id,s);
            if (provider != null) l.acceptEvidence(evidence); // Re-authenticate and rehash; no persisted-level shortcut.
            else {
                l.tickets.remove(s.id);
                l.receipts.add(evidence); // Preserve receipts for later trusted reopening, but grant no levels.
            }
        }
        return l;
    }
    private static JSONObject snapshotJson(Snapshot s) throws Exception {
        JSONObject required=new JSONObject();
        for (String p : s.requiredLevels.keySet()) required.put(p,s.requiredLevels.get(p));
        return new JSONObject().put("id",s.id).put("project",s.project).put("generation",s.generation)
                .put("hashes",mapJson(s.hashes)).put("requiredLevels",required);
    }
    private static Snapshot readSnapshot(JSONObject j) throws Exception {
        String p=j.getString("project"); validPath(p,true);
        String id=j.getString("id"); long generation=j.getLong("generation");
        if (!id.matches("[0-9a-f-]{36}") || generation < 0) throw new IOException("SNAPSHOT_ID");
        Map<String,String> hashes=readMap(j.getJSONObject("hashes"));
        JSONObject levels=j.getJSONObject("requiredLevels"); Map<String,Integer> required=new LinkedHashMap<>();
        for (Iterator<String> it=levels.keys(); it.hasNext();) {
            String path=it.next(); int n=levels.getInt(path);
            if (!hashes.containsKey(path) || n < minimum(path) || n > 4) throw new IOException("SNAPSHOT_REQUIREMENT");
            required.put(path,n);
        }
        if (!required.keySet().equals(hashes.keySet())) throw new IOException("SNAPSHOT_REQUIREMENT");
        return new Snapshot(id,p,generation,hashes,required);
    }
    private static JSONObject mapJson(Map<String,String> map) throws Exception {
        JSONObject j=new JSONObject(); for (String p : map.keySet()) j.put(p,map.get(p)); return j;
    }
    private static Map<String,String> readMap(JSONObject j) throws Exception {
        Map<String,String> m=new LinkedHashMap<>();
        for (Iterator<String> it=j.keys(); it.hasNext();) {
            String p=it.next(); validPath(p,false); String h=j.getString(p);
            if (!FileTxn.validHash(h)) throw new IOException("HASH"); m.put(p,h);
        }
        return m;
    }
    private static List<String> strings(JSONArray j) throws Exception {
        List<String> s=new ArrayList<>(); for (int i=0; i<j.length(); i++) { String p=j.getString(i); validPath(p,false); s.add(p); } return s;
    }
    private static String defaultProject(String path) {
        validPath(path,false); int slash=path.indexOf('/'); return slash < 0 ? "." : path.substring(0,slash);
    }
    private static int minimum(String path) { return path.endsWith(".java") || path.endsWith(".py") ? V2_BUILD : V1_READ; }
    private static void validPath(String p, boolean project) {
        if (project && ".".equals(p)) return;
        if (p == null || p.isEmpty() || p.startsWith("/") || p.indexOf('\\') >= 0 || p.indexOf('\u0000') >= 0)
            throw new IllegalArgumentException("LEDGER_PATH");
        for (String part : p.split("/",-1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("LEDGER_PATH");
    }
    static String levelName(int level) {
        return level >= 4 ? "Functional validation" : level >= 3 ? "Test passed." : level >= 2 ? "Build Through" : level >= 1 ? "Read Back" : "Not validated";
    }
}
