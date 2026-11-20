package org.mobileagent.agent;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class ConversationStore {
    private final File directory;
    private final DurableStore.FaultInjector faults;
    /** Summary of sessions index: list/Start returning to read-only here, avoiding the resolution of all sessions in the main line process JSON.  */
    private JSONObject indexCache;

    static final class StoreException extends IllegalStateException {
        StoreException(String message, Throwable cause) { super(message, cause); }
    }

    private static ConversationStore sharedStore;

    /** One store for all session engines: shared index cache and synchronized writes. */
    static synchronized ConversationStore shared(Context context) {
        if (sharedStore == null) {
            sharedStore = new ConversationStore(context);
        }
        return sharedStore;
    }

    ConversationStore(Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), "conversations"));
    }

    /** Filesystem boundary for JVM tests; all serialization and writes remain production code. */
    ConversationStore(File directory) { this(directory, null); }
    ConversationStore(File directory, DurableStore.FaultInjector faults) {
        this.directory = directory.getAbsoluteFile();
        this.faults = faults;
        if (!this.directory.isDirectory() && !this.directory.mkdirs())
            throw new StoreException("Cannot create private conversation directory", new IOException(directory.toString()));
    }

    synchronized List<Conversation.Summary> list(Workspace baseWorkspace) {
        List<Conversation.Summary> summaries = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files == null) throw new StoreException("Cannot list conversation directory", new IOException(directory.toString()));
        checkRecoveryFiles(files);
        JSONObject idx = index();
        boolean dirty = false;
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".json") || tombstoneFor(file).exists()) {
                continue;
            }
            String id = file.getName().substring(0, file.getName().length() - ".json".length());
            JSONObject item = idx.optJSONObject(id);
            if (indexFresh(item, file) && item.has("hasContent")) {
                if (!item.optBoolean("hasContent", true)) {
                    continue;
                }
                summaries.add(new Conversation.Summary(id,
                        item.optString("title", Conversation.DEFAULT_TITLE),
                        workspaceLabel(baseWorkspace, item.optString("workspace", "")),
                        item.optString("model", ""),
                        item.optLong("updatedAt", 0L),
                        item.optLong("createdAt", 0L),
                        item.optBoolean("archived", false)));
                continue;
            }
            Conversation conversation = read(file);
            if (conversation == null) {
                continue;
            }
            boolean hasContent = hasContent(conversation);
            indexConversation(conversation, file);
            dirty = true;
            if (!hasContent) {
                // New sessions without any content (no message, no running) are drafts, but they are not.
                // Should not appear in the list of sessions, only after they are actually used.
                continue;
            }
            summaries.add(conversation.summary(
                    workspaceLabel(baseWorkspace, conversation.workspace)));
        }
        if (dirty) {
            writeIndex();
        }
        Collections.sort(summaries, new Comparator<Conversation.Summary>() {
            @Override
            public int compare(Conversation.Summary left, Conversation.Summary right) {
                int byCreated = Long.compare(right.createdAt, left.createdAt);
                if (byCreated != 0) {
                    return byCreated;
                }
                return Long.compare(right.updatedAt, left.updatedAt);
            }
        });
        return summaries;
    }

    synchronized Conversation load(String id) {
        if (!validId(id)) {
            return null;
        }
        File primary = fileFor(id);
        return tombstoneFor(primary).exists() ? null : read(primary);
    }

    synchronized Conversation findLatestInterrupted() {
        File[] files = directory.listFiles();
        if (files == null) throw new StoreException("Cannot list conversation directory", new IOException(directory.toString()));
        checkRecoveryFiles(files);
        List<File> candidates = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".json") && !tombstoneFor(file).exists()) {
                candidates.add(file);
            }
        }
        Collections.sort(candidates, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return Long.compare(right.lastModified(), left.lastModified());
            }
        });
        JSONObject idx = index();
        Conversation newest = null;
        boolean dirty = false;
        // Just the latest changes. 5 The break-up recovery must have occurred at a recent active session, and the following post was published in the blog of the blog.
        // This way even if the index is first created, it will not resolve all historical sessions on start-up.
        int limit = Math.min(candidates.size(), 5);
        for (int i = 0; i < limit; i++) {
            File file = candidates.get(i);
            String id = file.getName().substring(0, file.getName().length() - ".json".length());
            JSONObject item = idx.optJSONObject(id);
            if (indexFresh(item, file) && !item.optBoolean("runActive", false)) {
                continue;
            }
            Conversation conversation = read(file);
            if (conversation == null) {
                continue;
            }
            if (!indexFresh(item, file)) {
                indexConversation(conversation, file);
                dirty = true;
            }
            if (!conversation.runActive) {
                continue;
            }
            if (newest == null || conversation.updatedAt > newest.updatedAt) {
                newest = conversation;
            }
        }
        if (dirty) {
            writeIndex();
        }
        return newest;
    }

    synchronized Conversation loadLastOrCreate(AppPrefs prefs, Workspace baseWorkspace) {
        String lastId = prefs.lastConversationId();
        if (!lastId.isEmpty()) {
            Conversation last = load(lastId);
            if (last != null) {
                return last;
            }
        }
        List<Conversation.Summary> summaries = list(baseWorkspace);
        if (!summaries.isEmpty()) {
            Conversation newest = load(summaries.get(0).id);
            if (newest != null) {
                return newest;
            }
        }
        Conversation conversation = Conversation.fromPrefs(prefs);
        conversation.id = newId();
        conversation.title = Conversation.DEFAULT_TITLE;
        long now = System.currentTimeMillis();
        conversation.createdAt = now;
        conversation.updatedAt = now;
        save(conversation);
        return conversation;
    }

    synchronized Conversation create(AppPrefs prefs, Conversation template) {
        Conversation conversation = template == null
                ? Conversation.fromPrefs(prefs) : new Conversation();
        if (template != null) {
            conversation.copySettingsFrom(template);
        }
        conversation.id = newId();
        conversation.title = Conversation.DEFAULT_TITLE;
        long now = System.currentTimeMillis();
        conversation.createdAt = now;
        conversation.updatedAt = now;
        conversation.messages = new org.json.JSONArray();
        conversation.entries = new org.json.JSONArray();
        save(conversation);
        return conversation;
    }

    synchronized void save(Conversation conversation) {
        try { saveOrThrow(conversation); }
        catch (IOException e) { throw new StoreException("Conversation save BLOCKED", e); }
    }

    synchronized void saveOrThrow(Conversation conversation) throws IOException {
        if (conversation == null || !validId(conversation.id)) throw new IOException("Invalid conversation");
        File target = fileFor(conversation.id);
        if (tombstoneFor(target).exists()) throw new IOException("Deleted conversation cannot be overwritten");
        try {
            // Just a cheap head check: no more for'Can read.'Re-decognize the whole session.
            if (!target.exists()
                    && (DurableStore.backupFor(target).exists() || DurableStore.originalFor(target).exists()))
                throw new IOException("Primary missing; explicit recovery required: " + target);
            if (target.exists() && !looksLikeConversation(target))
                throw new IOException("Unreadable conversation; refuse overwrite: " + target);
            byte[] bytes = conversation.toJson().toString().getBytes(StandardCharsets.UTF_8);
            if (faults == null) DurableStore.write(target, bytes);
            else DurableStore.write(target, bytes, faults);
            indexConversation(conversation, target);
            writeIndex();
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Conversation serialization/migration BLOCKED", e); }
    }

    private static boolean looksLikeConversation(File file) {
        long length = file.length();
        if (length < 2) {
            return false;
        }
        try (FileInputStream input = new FileInputStream(file)) {
            int first = input.read();
            if (first != '{') {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long start = Math.max(0L, length - 32L);
            raf.seek(start);
            byte[] tail = new byte[(int) (length - start)];
            raf.readFully(tail);
            int last = tail.length - 1;
            while (last >= 0 && (tail[last] == '\n' || tail[last] == '\r'
                    || tail[last] == ' ' || tail[last] == '\t')) {
                last--;
            }
            return last >= 0 && tail[last] == '}';
        } catch (Exception e) {
            return false;
        }
    }

    synchronized void delete(String id) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid conversation id");
        File primary = fileFor(id);
        if (tombstoneFor(primary).exists() || read(primary) == null) return;
        // Logical deletion keeps recoverable originals and cannot resurrect via a stale save.
        try { DurableStore.write(tombstoneFor(primary), id.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException e) { throw new StoreException("Conversation deletion BLOCKED", e); }
        removeIndexEntry(id);
    }

    private File indexFile() {
        return new File(directory, ".index.bin");
    }

    private JSONObject index() {
        if (indexCache == null) {
            indexCache = readIndexFile();
        }
        return indexCache;
    }

    private JSONObject readIndexFile() {
        File file = indexFile();
        if (!file.isFile()) {
            return new JSONObject();
        }
        try {
            return ToolRegistry.parseObject(readText(file));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeIndex() {
        try {
            byte[] bytes = index().toString().getBytes(StandardCharsets.UTF_8);
            File temp = new File(directory, ".index.bin.tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }
            if (!temp.renameTo(indexFile())) {
                indexFile().delete();
                temp.renameTo(indexFile());
            }
        } catch (Exception ignored) {
            // The index is only an acceleration layer and failure does not affect the session body.
        }
    }

    /**
     * Whether the session has really been used. No message, no event, no title is still a draft, but only a draft.
     * Should not appear in the session list.
     */
    static boolean hasContent(Conversation conversation) {
        if (conversation == null) {
            return false;
        }
        if (conversation.archived) {
            return true;
        }
        if (conversation.messages != null && conversation.messages.length() > 0) {
            return true;
        }
        if (conversation.entries != null && conversation.entries.length() > 0) {
            return true;
        }
        String title = conversation.title;
        return title != null && title.length() > 0
                && !Conversation.DEFAULT_TITLE.equals(title);
    }

    private void indexConversation(Conversation conversation, File file) {
        if (conversation == null) {
            return;
        }
        try {
            JSONObject item = new JSONObject();
            item.put("title", conversation.title == null
                    ? Conversation.DEFAULT_TITLE : conversation.title);
            item.put("workspace", conversation.workspace == null ? "" : conversation.workspace);
            item.put("model", conversation.model == null ? "" : conversation.model);
            item.put("updatedAt", conversation.updatedAt);
            item.put("createdAt", conversation.createdAt);
            item.put("hasContent", hasContent(conversation));
            item.put("archived", conversation.archived);
            item.put("runActive", conversation.runActive);
            item.put("paused", conversation.paused);
            item.put("autoResumeCount", conversation.autoResumeCount);
            item.put("runStartedAt", conversation.runStartedAt);
            item.put("size", file.length());
            item.put("mtime", file.lastModified());
            index().put(conversation.id, item);
        } catch (Exception ignored) {
        }
    }

    private void removeIndexEntry(String id) {
        try {
            if (index().remove(id) != null) {
                writeIndex();
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean indexFresh(JSONObject item, File file) {
        return item != null
                && item.optLong("size", -1L) == file.length()
                && item.optLong("mtime", -1L) == file.lastModified();
    }

    private static File tombstoneFor(File primary) {
        return new File(primary.getParentFile(), primary.getName() + ".deleted");
    }

    private Conversation read(File file) {
        if (!file.exists()) {
            // DurableStore We'll turn the old master file wheel into a new one. .bak Replace new files; if the process is exactly two times rename The news is that the police are not taking the police to the police.
            // Here. .bak Autorecover (%1).bak is the last complete submission) to avoid the whole list of sessions being stuck to death.
            File backup = DurableStore.backupFor(file);
            if (backup.isFile()) {
                try {
                    DurableStore.platform().atomicReplace(backup, file);
                } catch (Exception e) {
                    throw new StoreException("Primary missing; recover before proceeding: " + file, e);
                }
            } else if (DurableStore.originalFor(file).exists()) {
                throw new StoreException("Primary missing; recover before proceeding: " + file,
                        new IOException("Recovery required"));
            }
            if (!file.exists()) {
                return null;
            }
        }
        try {
            if (!file.getCanonicalFile().equals(new File(directory.getCanonicalFile(), file.getName())))
                throw new IOException("Conversation symlink refused");
            JSONObject object = ToolRegistry.parseObject(readText(file));
            Conversation value = Conversation.fromJson(object);
            String expected = file.getName().replaceFirst("\\.json(?:\\.bak|\\.original)?$", "");
            if (!value.id.equals(expected)) throw new IOException("Conversation id/filename mismatch");
            return value;
        } catch (Exception e) {
            throw new StoreException("Conversation read/migration BLOCKED: " + file, e);
        }
    }

    private void checkRecoveryFiles(File[] files) {
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".json.bak") || name.endsWith(".json.original")) {
                String primary = name.replaceFirst("\\.(?:bak|original)$", "");
                File target = new File(directory, primary);
                if (!target.isFile() && !tombstoneFor(target).exists()) {
                    if (name.endsWith(".json.bak")) {
                        try {
                            DurableStore.platform().atomicReplace(file, target);
                            continue;
                        } catch (Exception ignored) {
                            // The recovery failed and the error was clearly reported.
                        }
                    }
                    throw new StoreException("Conversation recovery required: " + primary,
                            new IOException("Primary missing"));
                }
            }
        }
    }

    /** Read-only recovery preview; does not silently replace a corrupt/newer primary. */
    synchronized Conversation loadBackup(String id) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid conversation id");
        return read(DurableStore.backupFor(fileFor(id)));
    }

    private File fileFor(String id) {
        return new File(directory, id + ".json");
    }

    private static String workspaceLabel(Workspace baseWorkspace, String subPath) {
        String clean = subPath == null ? "" : subPath.trim();
        return clean.isEmpty() ? "/" : clean;
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{4,80}");
    }

    private static String newId() {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return "c-" + System.currentTimeMillis() + "-" + builder;
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString();
        }
    }
}
