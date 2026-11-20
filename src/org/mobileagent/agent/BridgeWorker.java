package org.mobileagent.agent;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Processing of a bridge request: reading inbox → Type of determination → Modem → Write outbox → Move done.  */
final class BridgeWorker {

    private BridgeWorker() {
    }

    static String handle(Context ctx, File req) {
        String id = idOf(req);
        File dir = req.getParentFile() == null ? null : req.getParentFile().getParentFile();
        if (dir == null) {
            return "bad-path";
        }
        File outbox = new File(dir, "outbox");
        File done = new File(dir, "done");
        String prompt = "";
        String payload = "{}";
        String kind = "";
        JSONObject request = null;
        try {
            String raw = readText(req);
            request = new JSONObject(raw);
            kind = request.optString("kind", "");
            prompt = request.optString("prompt", "");
            JSONObject p = request.optJSONObject("payload");
            if (p != null) {
                payload = p.toString();
            }
        } catch (Exception e) {
            writeResult(outbox, id, "Could not close temporary folder: %s" + e.getClass().getSimpleName(), "agent");
            moveOut(req, done);
            return "bad-json";
        }

        if ("debug".equals(kind)) {
            String debugResult = DebugBridge.handle(ctx, request);
            writeResult(outbox, id, debugResult, "debug");
            moveOut(req, done);
            return "debug";
        }

        String text = null;
        String source = "agent";
        if ("bridge_selftest".equals(kind)) {
            text = "Bridges are connected:APP → Agent The passage is normal.Agent Sides are automatically processed without manual intervention.";
        } else {
            String[] answer = BridgeModel.ask(ctx, prompt, payload);
            if (answer != null) {
                text = answer[0];
                source = answer[1];
            }
        }
        if (text == null || text.trim().isEmpty()) {
            text = "Agent Could not generate result for the time being: Check API Key Try again with the network, or when you configure a local model.";
            source = "agent";
        }
        boolean ok = writeResult(outbox, id, text, source);
        moveOut(req, done);
        return ok ? source : "write-failed";
    }

    private static boolean writeResult(File outbox, String id, String text, String source) {
        try {
            if (!outbox.exists() && !outbox.mkdirs()) {
                return false;
            }
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("text", text);
            o.put("source", source);
            o.put("ts", System.currentTimeMillis());
            File target = new File(outbox, id + ".json");
            File tmp = new File(outbox, id + ".json.tmp");
            writeText(tmp, o.toString());
            if (target.exists()) {
                target.delete();
            }
            return tmp.renameTo(target);
        } catch (Exception e) {
            return false;
        }
    }

    private static void moveOut(File req, File done) {
        try {
            if (!done.exists()) {
                done.mkdirs();
            }
            File dst = new File(done, req.getName());
            if (dst.exists()) {
                dst.delete();
            }
            if (req.renameTo(dst)) {
                return;
            }
            writeText(dst, readText(req));
            req.delete();
        } catch (Exception ignored) {
        }
    }

    static boolean isDebugRequest(File req) {
        return "debug".equals(field(req, "kind"));
    }

    static String appOf(File req) {
        return field(req, "app");
    }

    static String promptOf(File req) {
        return field(req, "prompt");
    }

    private static String field(File req, String key) {
        try {
            JSONObject o = new JSONObject(readText(req));
            return o.optString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String idOf(File f) {
        String name = f.getName();
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - 5);
        }
        return name;
    }

    static String readText(File file) throws Exception {
        InputStream in = new FileInputStream(file);
        try {
            byte[] buffer = new byte[(int) Math.min(262144L, Math.max(1024L, file.length()))];
            int len = in.read(buffer);
            return len <= 0 ? "" : new String(buffer, 0, len, "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void writeText(File file, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } finally {
            out.close();
        }
    }
}
