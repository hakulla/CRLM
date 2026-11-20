package org.mobileagent.agent;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

final class TaskBudget {
    static final class Reservation {
        private final long id;
        final String model;
        final long reserved;
        private boolean started;
        private boolean settled;
        Reservation(long id, String model, long reserved) {
            this.id = id;
            this.model = model;
            this.reserved = reserved;
        }
    }

    private long limit;
    private long charged;
    private long pending;
    private long sequence;
    private final Map<Long, Reservation> reservations = new HashMap<>();
    private final Map<String, Long> models = new HashMap<>();

    TaskBudget(long limit) {
        if (limit <= 0) throw new IllegalArgumentException("Mission budgets must be positive");
        this.limit = limit;
    }

    synchronized Reservation reserve(String model, int inputEstimate, int maxOutput) {
        if (inputEstimate < 0 || maxOutput < 0) throw new IllegalArgumentException("Budget estimates cannot be negative");
        long amount = (long) inputEstimate + maxOutput;
        if (amount > limit - charged - pending) {
            throw new IllegalStateException("TASK_BUDGET_EXHAUSTED: The cumulative amount of the mandate was insufficient and no requests were sent");
        }
        Reservation reservation = new Reservation(++sequence,
                model == null ? "unknown" : model, amount);
        reservations.put(reservation.id, reservation);
        pending += amount;
        return reservation;
    }

    synchronized void started(Reservation reservation) {
        require(reservation);
        reservation.started = true;
    }

    synchronized void settle(Reservation reservation, int input, int output, boolean usageKnown) {
        require(reservation);
        if (input < 0 || output < 0) throw new IllegalArgumentException("The actual amount used cannot be negative");
        long actual = usageKnown ? (long) input + output
                : reservation.started ? reservation.reserved : 0;
        pending -= reservation.reserved;
        charged += actual;
        Long previous = models.get(reservation.model);
        models.put(reservation.model, (previous == null ? 0 : previous) + actual);
        reservation.settled = true;
        reservations.remove(reservation.id);
    }

    synchronized long remaining() { return Math.max(0, limit - charged - pending); }

    synchronized long charged() { return charged; }

    synchronized long limit() { return limit; }

    /** Progress-aware extension: a productive run may need more than the initial batch. */
    synchronized void extend(long extra) {
        if (extra <= 0) throw new IllegalArgumentException("Budget increases must be positive");
        limit += extra;
    }

    synchronized JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("version", 1);
            json.put("limit", limit);
            json.put("charged", charged);
            json.put("uncertainReserved", pending);
            JSONObject counts = new JSONObject();
            for (Map.Entry<String, Long> entry : models.entrySet()) counts.put(entry.getKey(), entry.getValue());
            json.put("models", counts);
            return json;
        } catch (Exception e) { throw new IllegalStateException("Cannot sequence budget", e); }
    }

    static TaskBudget restore(JSONObject json, long defaultLimit) {
        if (json == null || json.length() == 0) return new TaskBudget(defaultLimit);
        if (json.optInt("version", -1) != 1) throw new IllegalArgumentException("Unknown mission budget version");
        TaskBudget budget = new TaskBudget(json.optLong("limit", defaultLimit));
        long charge = json.optLong("charged", 0);
        long unknown = json.optLong("uncertainReserved", 0);
        if (charge < 0 || unknown < 0) throw new IllegalArgumentException("Budget records damaged");
        budget.charged = charge + unknown;
        JSONObject counts = json.optJSONObject("models");
        if (counts != null) {
            java.util.Iterator<String> keys = counts.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long value = counts.optLong(key, -1);
                if (value < 0) throw new IllegalArgumentException("Model budget records are damaged");
                budget.models.put(key, value);
            }
        }
        return budget;
    }

    private void require(Reservation reservation) {
        if (reservation == null || reservation.settled
                || reservations.get(reservation.id) != reservation) {
            throw new IllegalStateException("Budget set aside for tasks that are not part of the task or that have been settled");
        }
    }
}
