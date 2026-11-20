package org.mobileagent.agent;

import android.content.Context;
import android.util.Log;

/**
 * Phone-side supervisor: one writer for the work ledger and its watchdog.
 * Evolution artifacts live in {@link EvolutionStore}; run reasoning lives in
 * {@link AgentState}. This class deliberately has no model calls and no
 * self-evolution policy.
 */
final class WorkSupervisor {
    private static final String TAG = "AgentWork";
    private static WorkSupervisor INSTANCE;

    static synchronized WorkSupervisor get(Context context) {
        if (INSTANCE == null) INSTANCE = new WorkSupervisor(context.getApplicationContext());
        return INSTANCE;
    }

    final WorkStore store;
    final EvolutionStore evolution;
    private volatile boolean started;

    private WorkSupervisor(Context context) {
        store = new WorkStore(context);
        evolution = new EvolutionStore(new Workspace(context).baseRoot(), store);
        start();
    }

    synchronized void start() {
        if (started) return;
        started = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(3000L);
                        store.tick();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Throwable error) {
                        Log.w(TAG, "supervisor tick failed", error);
                    }
                }
            }
        }, "mobileagent-work-supervisor");
        thread.setDaemon(true);
        thread.start();
    }

    WorkStore.Ticket begin(String conversationId, String title, String input, String runId) {
        return store.beginTicket(conversationId, title, input, runId);
    }

    void heartbeat(String ticketId, String runId, int step, int maxSteps) {
        store.heartbeat(ticketId, runId, step, maxSteps);
    }

    void block(String ticketId, String message) {
        store.block(ticketId, WorkStore.BLOCKED, message);
    }

    void fail(String ticketId, String message) {
        store.block(ticketId, WorkStore.FAILED, message);
    }

    void ready(String ticketId, String message) {
        if (ticketId == null) return;
        store.end(ticketId, WorkStore.READY, message);
    }

    void end(String ticketId, String status, String message) {
        store.end(ticketId, status, message);
    }

    boolean networkAllowed(String ticketId) {
        return WorkStore.isResearchScope(store.networkScope(ticketId));
    }

    void trigger(String source, String conversationId, String text) {
        store.trigger(source, conversationId, text);
    }

    void notifyAttention(String ticketId, String message) {
        store.effect("attention", ticketId, message);
    }

    String startWorker(String ticketId, String task) {
        return store.startWorker(ticketId, task);
    }

    void endWorker(String ticketId) {
        store.endWorker(ticketId);
    }
}
