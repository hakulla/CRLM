package org.mobileagent.agent;

import java.io.File;

public final class WorkStoreTest {
    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : ".", "workstore-test");
        delete(dir);
        dir.mkdirs();
        WorkStore store = new WorkStore(dir);

        WorkStore.Ticket first = store.reportError("c1",
                "Error reporting: Read files · File does not exist",
                "Tools inspect Failed: File does not exist",
                "FULL REPORT");
        if (first == null || !"error".equals(first.source) || !first.attention
                || !WorkStore.BLOCKED.equals(first.status)
                || !"FULL REPORT".equals(first.report)) {
            throw new AssertionError("bad error ticket");
        }
        if (store.needsAttention().size() != 1) {
            throw new AssertionError("pending count=" + store.needsAttention().size());
        }

        WorkStore.Ticket second = store.reportError("c1",
                "Error reporting: Read files · File does not exist",
                "Tools inspect Failed: File does not exist",
                "FULL REPORT 2");
        if (store.tickets.size() != 1 || !second.id.equals(first.id)
                || !"FULL REPORT 2".equals(second.report)) {
            throw new AssertionError("dedupe failed");
        }

        WorkStore.Ticket running = store.beginTicket("c2", "Tasks", "input", "run1");
        if (running == null || !WorkStore.RUNNING.equals(running.status)) {
            throw new AssertionError("ticket begin failed");
        }
        if (store.byStatus(WorkStore.RUNNING).size() != 1) {
            throw new AssertionError("running count=" + store.byStatus(WorkStore.RUNNING).size());
        }
        System.out.println("WORKSTORE_TEST_OK tickets=" + store.tickets.size()
                + " pending=" + store.needsAttention().size());
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        file.delete();
    }
}
