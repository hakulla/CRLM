package org.mobileagent.agent;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Owns self-evolution artifacts only: capability snapshots, proposals and the
 * review log. Run state belongs to AgentState; ticket lifecycle belongs to
 * WorkStore. This class never reads or writes either.
 */
final class EvolutionStore {
    private static final String TAG = "AgentEvolution";

    private final File agentRoot;
    private final WorkStore work;

    EvolutionStore(File baseRoot, WorkStore work) {
        agentRoot = new File(baseRoot, ".agent");
        this.work = work;
    }

    File proposalsDir() {
        return new File(agentRoot, "evolution/proposals");
    }

    List<File> proposals() {
        File[] files = proposalsDir().listFiles();
        List<File> result = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && !file.getName().startsWith(".")) result.add(file);
            }
        }
        Collections.sort(result, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return Long.compare(right.lastModified(), left.lastModified());
            }
        });
        return result;
    }

    boolean approveProposal(File file, boolean approve) {
        if (file == null || !file.isFile()) return false;
        File parent = new File(file.getParentFile(), approve ? "done" : "rejected");
        if (!parent.isDirectory()) parent.mkdirs();
        File target = new File(parent, file.getName());
        boolean moved = file.renameTo(target);
        if (!moved) {
            try {
                copy(file, target);
                file.delete();
                moved = true;
            } catch (Exception ignored) {
            }
        }
        if (moved) {
            work.trigger("evolution", "",
                    (approve ? "The evolutionary proposal has been approved:" : "The evolution proposal has been rejected:") + file.getName());
            work.effect(approve ? "proposal-approved" : "proposal-rejected", "",
                    file.getName());
        }
        return moved;
    }

    File snapshotEvolution(String label) {
        File snapshots = new File(agentRoot, "evolution/snapshots");
        if (!snapshots.isDirectory() && !snapshots.mkdirs()) return null;
        File target = new File(snapshots, "snap-" + System.currentTimeMillis());
        if (!target.mkdirs()) return null;
        try {
            for (String name : new String[]{"memory.md", "lessons.md", "rules.md"}) {
                File source = new File(agentRoot, name);
                if (source.isFile()) copy(source, new File(target, name));
            }
            for (String name : new String[]{"skills", "agents", "knowledge"}) {
                File source = new File(agentRoot, name);
                if (source.isDirectory()) copyTree(source, new File(target, name));
            }
            FileOutputStream output = new FileOutputStream(new File(target, "label.txt"), false);
            try {
                output.write((label == null ? "evolution" : label).getBytes("UTF-8"));
            } finally {
                output.close();
            }
            trimSnapshots(snapshots);
            work.trigger("evolution", "", "Created Capability Scatter:" + target.getName());
            return target;
        } catch (Exception error) {
            Log.w(TAG, "snapshot failed", error);
            return null;
        }
    }

    File latestSnapshot() {
        File[] files = new File(agentRoot, "evolution/snapshots").listFiles();
        if (files == null) return null;
        File latest = null;
        for (File file : files) {
            if (file.isDirectory() && file.getName().startsWith("snap-")) {
                if (latest == null || file.lastModified() > latest.lastModified()) latest = file;
            }
        }
        return latest;
    }

    boolean rollbackEvolution() {
        File snapshot = latestSnapshot();
        if (snapshot == null) return false;
        try {
            for (String name : new String[]{"memory.md", "lessons.md", "rules.md"}) {
                File source = new File(snapshot, name);
                if (source.isFile()) copy(source, new File(agentRoot, name));
            }
            for (String name : new String[]{"skills", "agents", "knowledge"}) {
                File source = new File(snapshot, name);
                File target = new File(agentRoot, name);
                if (source.isDirectory()) {
                    if (target.isDirectory()) deleteTree(target);
                    copyTree(source, target);
                }
            }
            work.trigger("evolution", "", "Back to the power snapshot:" + snapshot.getName());
            work.effect("rollback", "", snapshot.getName());
            return true;
        } catch (Exception error) {
            Log.w(TAG, "rollback failed", error);
            return false;
        }
    }

    void recordReview(String summary, String conversationId) {
        work.trigger("evolution", conversationId, summary);
    }

    private void trimSnapshots(File snapshots) {
        File[] files = snapshots.listFiles();
        if (files == null) return;
        List<File> dirs = new ArrayList<>();
        for (File file : files) {
            if (file.isDirectory() && file.getName().startsWith("snap-")) dirs.add(file);
        }
        Collections.sort(dirs, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return Long.compare(right.lastModified(), left.lastModified());
            }
        });
        for (int i = 10; i < dirs.size(); i++) deleteTree(dirs.get(i));
    }

    private static void copy(File source, File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        FileInputStream input = new FileInputStream(source);
        FileOutputStream output = new FileOutputStream(target, false);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        } finally {
            input.close();
            output.close();
        }
    }

    private static void copyTree(File source, File target) throws Exception {
        if (!target.isDirectory() && !target.mkdirs()) throw new Exception("mkdir " + target);
        File[] files = source.listFiles();
        if (files == null) return;
        for (File file : files) {
            File next = new File(target, file.getName());
            if (file.isDirectory()) copyTree(file, next);
            else copy(file, next);
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] files = file.listFiles();
        if (files != null) for (File child : files) deleteTree(child);
        file.delete();
    }
}
