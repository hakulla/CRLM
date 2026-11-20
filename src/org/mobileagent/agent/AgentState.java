package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** First-class run state: goal, capability fingerprint, evidence, strategy ledger and switches. */
final class AgentState {
    final File file;
    String conversationId;
    String goal = "";
    String status = "idle";
    String capabilityFingerprint = "";
    String lastError = "";
    long updatedAt;
    int rounds;
    boolean capabilitiesChanged;
    boolean retry;
    JSONArray plan = new JSONArray();
    JSONArray evidence = new JSONArray();
    JSONArray switches = new JSONArray();
    JSONObject ledger = new JSONObject();
    List<ExperienceStore.Record> recalled = new ArrayList<>();

    private AgentState(File file, String conversationId) {
        this.file = file;
        this.conversationId = conversationId;
    }

    static AgentState load(File root, String conversationId, CapabilityRegistry.Snapshot caps) {
        String id = sanitize(conversationId);
        File dir = new File(root, ".agent/state");
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        File file = new File(dir, id + ".json");
        AgentState state = new AgentState(file, conversationId == null ? "" : conversationId);
        if (file.isFile()) {
            FileInputStream input = null;
            try {
                input = new FileInputStream(file);
                StringBuilder raw = new StringBuilder();
                InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    raw.append(buffer, 0, read);
                }
                JSONObject object = new JSONObject(raw.toString());
                state.goal = object.optString("goal", "");
                state.status = object.optString("status", "idle");
                state.capabilityFingerprint = object.optString("caps", "");
                state.lastError = object.optString("error", "");
                state.updatedAt = object.optLong("updated", 0L);
                state.rounds = object.optInt("rounds", 0);
                state.plan = object.optJSONArray("plan") == null
                        ? new JSONArray() : object.optJSONArray("plan");
                state.evidence = object.optJSONArray("evidence") == null
                        ? new JSONArray() : object.optJSONArray("evidence");
                state.switches = object.optJSONArray("switches") == null
                        ? new JSONArray() : object.optJSONArray("switches");
                state.ledger = object.optJSONObject("ledger") == null
                        ? new JSONObject() : object.optJSONObject("ledger");
            } catch (Exception ignored) {
            } finally {
                if (input != null) {
                    try {
                        input.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (state.capabilityFingerprint.isEmpty() && caps != null) {
            state.capabilityFingerprint = caps.fingerprint;
        }
        return state;
    }

    void begin(String goal, CapabilityRegistry.Snapshot caps, boolean retry) {
        this.goal = goal == null ? "" : goal;
        this.retry = retry;
        this.status = "running";
        this.updatedAt = System.currentTimeMillis();
        if (!retry) {
            this.plan = new JSONArray();
            this.evidence = new JSONArray();
            this.switches = new JSONArray();
            this.ledger = new JSONObject();
            this.rounds = 0;
            this.lastError = "";
        }
        if (caps != null) {
            this.capabilityFingerprint = caps.fingerprint;
        }
    }

    void setPlan(JSONArray value) {
        this.plan = value == null ? new JSONArray() : value;
        this.updatedAt = System.currentTimeMillis();
    }

    void setLedger(JSONObject value) {
        this.ledger = value == null ? new JSONObject() : value;
    }

    void noteEvidence(String kind, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            evidence.put(new JSONObject().put("kind", kind == null ? "" : kind)
                    .put("value", cut(value, 220)).put("at", System.currentTimeMillis()));
            while (evidence.length() > 12) {
                evidence.remove(0);
            }
            updatedAt = System.currentTimeMillis();
        } catch (Exception ignored) {
        }
    }

    void noteError(String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        lastError = cut(value, 400);
        noteEvidence("error", value);
    }

    void noteSwitch(String reason, List<String> alternatives) {
        try {
            JSONArray next = new JSONArray();
            if (alternatives != null) {
                for (String value : alternatives) {
                    next.put(value);
                }
            }
            switches.put(new JSONObject().put("reason", cut(reason, 180))
                    .put("next", next)
                    .put("at", System.currentTimeMillis()));
            while (switches.length() > 8) {
                switches.remove(0);
            }
            updatedAt = System.currentTimeMillis();
        } catch (Exception ignored) {
        }
    }

    /** The single mapping from run flags to the persisted outcome vocabulary. */
    static String outcome(boolean stopped, boolean paused, boolean failed) {
        return stopped ? "stopped" : failed ? "failed" : paused ? "paused" : "completed";
    }

    void finish(String outcome, int rounds) {
        this.status = outcome == null ? "unknown" : outcome;
        this.rounds = rounds;
        this.updatedAt = System.currentTimeMillis();
    }

    String renderBrief(StrategyLedger ledgerValue, CapabilityRegistry.Snapshot caps,
                       List<ExperienceStore.Record> experience) {
        StringBuilder out = new StringBuilder();
        out.append("\n\n[State of operation (whichever is this article)]\n");
        out.append("- Objectives:").append(cut(goal, 240)).append('\n');
        if (caps != null) {
            out.append("- Current capacity:").append(caps.render).append('\n');
            if (!caps.browserRead) {
                out.append("- Note:webview/browser_open is an application-in-house browser, not dependent Chrome or a11y; ")
                        .append("sysbrowser=off Only means that the system browser is not automated, without affecting the built-in browser and the screenshot.\n");
            }
        }
        out.append("- Status:").append(status).append(" · Wheel ").append(rounds);
        if (retry) {
            out.append(" · This round is retried.");
        }
        if (capabilitiesChanged) {
            out.append(" · The power prints have changed, and the old failure is invalidated.");
        }
        out.append('\n');
        if (plan.length() > 0) {
            out.append("- Planned:");
            for (int i = 0; i < plan.length() && i < 6; i++) {
                JSONObject step = plan.optJSONObject(i);
                if (step == null) {
                    continue;
                }
                if (i > 0) {
                    out.append("; ");
                }
                out.append(step.optString("title", "")).append("[")
                        .append(step.optString("status", "")).append("]");
            }
            out.append('\n');
        }
        if (ledgerValue != null) {
            out.append("- Policy book:").append(ledgerValue.render()).append('\n');
        }
        if (evidence.length() > 0) {
            out.append("- Recent evidence:");
            int from = Math.max(0, evidence.length() - 3);
            for (int i = from; i < evidence.length(); i++) {
                JSONObject item = evidence.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                if (i > from) {
                    out.append("; ");
                }
                out.append(item.optString("kind", "")).append(": ")
                        .append(cut(item.optString("value", ""), 80));
            }
            out.append('\n');
        }
        if (switches.length() > 0) {
            JSONObject last = switches.optJSONObject(switches.length() - 1);
            if (last != null) {
                out.append("- Last Switch:").append(cut(last.optString("reason", ""), 120))
                        .append(" → ").append(last.opt("next") == null
                                ? "" : cut(last.opt("next").toString(), 100)).append('\n');
            }
        }
        List<ExperienceStore.Record> recall = experience == null ? recalled : experience;
        out.append("- Historical experience:").append(ExperienceStore.render(recall)).append('\n');
        out.append("- Rule: Old conclusions do not represent current capabilities; do not repeat failure strategies; web-page tasks are prioritized web_fetch/Login browser, not used termux Find web files.");
        String text = out.toString();
        return text.length() <= 1500 ? text : text.substring(0, 1500) + "...";
    }

    void save() {
        if (file == null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        File temp = new File(file.getPath() + ".tmp");
        OutputStreamWriter writer = null;
        try {
            JSONObject object = new JSONObject();
            object.put("version", 1);
            object.put("conversation", conversationId);
            object.put("goal", goal);
            object.put("status", status);
            object.put("caps", capabilityFingerprint);
            object.put("error", lastError);
            object.put("updated", updatedAt);
            object.put("rounds", rounds);
            object.put("plan", plan);
            object.put("evidence", evidence);
            object.put("switches", switches);
            object.put("ledger", ledger);
            writer = new OutputStreamWriter(new FileOutputStream(temp),
                    StandardCharsets.UTF_8);
            writer.write(object.toString());
        } catch (Exception ignored) {
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
        }
        try {
            if (file.exists()) {
                file.delete();
            }
            if (!temp.renameTo(file)) {
                temp.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "default";
        }
        String clean = value.replaceAll("[^A-Za-z0-9_-]", "_");
        return clean.length() > 80 ? clean.substring(0, 80) : clean;
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }
}
