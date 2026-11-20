package org.mobileagent.agent;

import java.util.List;

/** Turns tool observations into state transitions: continue, block, switch strategy or pause. */
final class StateMonitor {
    enum Type { CONTINUE, SWITCH, BLOCK, PAUSE }

    static final class Decision {
        final Type type;
        final String reason;
        final String actionKey;
        final String strategy;
        final List<String> suggested;

        Decision(Type type, String reason, String actionKey, String strategy,
                 List<String> suggested) {
            this.type = type;
            this.reason = reason == null ? "" : reason;
            this.actionKey = actionKey == null ? "" : actionKey;
            this.strategy = strategy == null ? "" : strategy;
            this.suggested = suggested;
        }

        boolean actionable() {
            return type != Type.CONTINUE;
        }
    }

    private final boolean webTask;
    private boolean capabilityChanged;
    private String lastActionKey = "";
    private String lastStrategy = "";
    private int sameAction;
    private int noGainStreak;
    private int noProgressStreak;

    StateMonitor(boolean capabilityChanged, boolean webTask) {
        this.capabilityChanged = capabilityChanged;
        this.webTask = webTask;
    }

    Decision observe(AgentState state, StrategyLedger ledger, CapabilityRegistry.Snapshot caps,
                     String actionKey, String strategy, boolean success, boolean infoGain) {
        List<String> alternatives = ledger == null ? null
                : ledger.alternatives(webTask, caps);
        if (capabilityChanged) {
            capabilityChanged = false;
        }
        if (StrategyLedger.isVisualCapture(actionKey)) {
            // The screenshot is itself new visual information; it can't be used to create a new visual message.'You can look at the page when it changes.'Repetition of action.
            infoGain = true;
        }
        if (actionKey != null && actionKey.equals(lastActionKey)) {
            sameAction++;
        } else {
            sameAction = 1;
            lastActionKey = actionKey == null ? "" : actionKey;
        }
        if (infoGain) {
            noGainStreak = 0;
            noProgressStreak = 0;
        } else {
            noGainStreak++;
            noProgressStreak++;
        }
        lastStrategy = strategy == null ? "" : strategy;
        if (sameAction >= 2 && !infoGain) {
            return new Decision(Type.BLOCK, "The same action repeats and no new information:"
                    + actionKey, actionKey, lastStrategy, alternatives);
        }
        if (noGainStreak >= 2) {
            return new Decision(Type.SWITCH, "Policy " + lastStrategy + " No continuous information gain",
                    actionKey, lastStrategy, alternatives);
        }
        if (noProgressStreak >= 3) {
            return new Decision(Type.SWITCH, "Continuous " + noProgressStreak + " No progress on the wheel.",
                    actionKey, lastStrategy, alternatives);
        }
        return new Decision(Type.CONTINUE, "", actionKey, lastStrategy, alternatives);
    }

    void resetAfterCorrection() {
        sameAction = 0;
        noGainStreak = 0;
        noProgressStreak = 0;
        lastActionKey = "";
    }
}
