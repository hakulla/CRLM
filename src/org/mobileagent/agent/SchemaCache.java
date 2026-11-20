package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One bounded private file per server. Does not persist credentials, sessions or grants. */
final class SchemaCache {
    static final class Entry {
        final String identity, configHash, version, catalogHash, toolsJson;
        final long discoveredAt, ttlMs;
        Entry(String identity, String configHash, String version, String catalogHash,
              String toolsJson, long discoveredAt, long ttlMs) {
            this.identity = identity; this.configHash = configHash; this.version = version;
            this.catalogHash = catalogHash; this.toolsJson = toolsJson;
            this.discoveredAt = discoveredAt; this.ttlMs = ttlMs;
        }
        boolean fresh(long now, long ttl) {
            return ttl == ttlMs && now >= discoveredAt && now - discoveredAt < ttlMs;
        }
    }

    private final File privateDir;
    private final Map<String, Entry> memory = new HashMap<>();
    SchemaCache(File privateDir) { this.privateDir = privateDir; }

    synchronized Entry get(String identity, String configHash, long ttl, long now) throws Exception {
        // UI and AgentEngine construct separate managers. Disk is the authoritative epoch;
        // another instance may have deleted/replaced this file while our memory was warm.
        Entry entry = read(identity);
        if (entry == null) { memory.remove(identity); return null; }
        if (!configHash.equals(entry.configHash) || !entry.fresh(now, ttl)) {
            invalidate(identity); return null;
        }
        memory.put(identity, entry); return entry;
    }

    synchronized Entry put(String identity, String configHash, JSONArray tools, long ttl, long now,
                           String credential) throws Exception {
        String text = McpProtocol.canonical(tools);
        String catalogHash = McpProtocol.hash(text);
        // A refresh, even with identical schema, is a new authorization epoch after expiry.
        Entry entry = new Entry(identity, configHash, UUID.randomUUID().toString(), catalogHash, text, now, ttl);
        JSONObject object = new JSONObject();
        object.put("format", 1); object.put("identity", identity); object.put("configHash", configHash);
        object.put("version", entry.version); object.put("catalogHash", catalogHash);
        object.put("discoveredAt", now); object.put("ttlMs", ttl); object.put("tools", tools);
        JSONObject schemaHashes = new JSONObject();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            schemaHashes.put(tool.getString("name"), McpProtocol.hash(McpProtocol.canonical(tool.getJSONObject("inputSchema"))));
        }
        object.put("schemaHashes", schemaHashes);
        String serialized = McpProtocol.canonical(object);
        if (serialized.getBytes(StandardCharsets.UTF_8).length > McpProtocol.MAX_BODY)
            throw McpProtocol.fail("TOOL_SCHEMA_TOO_LARGE");
        if (McpProtocol.containsCredential(tools, credential)) throw McpProtocol.fail("MCP_SENSITIVE_CACHE_REJECTED");
        if (!privateDir.isDirectory() && !privateDir.mkdirs()) throw McpProtocol.fail("MCP_CACHE_IO");
        File target = file(identity), temporary = new File(privateDir, target.getName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                temporary.setReadable(false, false); temporary.setWritable(false, false);
                temporary.setReadable(true, true); temporary.setWritable(true, true);
                out.write(serialized.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            }
            if (!temporary.renameTo(target)) throw McpProtocol.fail("MCP_CACHE_IO");
        } finally { if (temporary.exists()) temporary.delete(); }
        memory.put(identity, entry); return entry;
    }

    synchronized void retain(Set<String> identities) throws Exception {
        for (String identity : new HashSet<>(memory.keySet())) if (!identities.contains(identity)) invalidate(identity);
        File[] files = privateDir.listFiles();
        Set<String> names = new HashSet<>();
        for (String identity : identities) names.add(file(identity).getName());
        if (files != null) for (File file : files)
            if (!names.contains(file.getName()) && file.getName().endsWith(".json"))
                file.delete(); // Never delete another manager's in-flight temporary file.
    }

    synchronized void invalidate(String identity) throws Exception {
        memory.remove(identity);
        File target = file(identity);
        if (target.exists() && !target.delete()) throw McpProtocol.fail("MCP_CACHE_IO");
    }

    private File file(String identity) throws Exception { return new File(privateDir, McpProtocol.hash(identity) + ".json"); }

    private Entry read(String identity) throws Exception {
        File target = file(identity);
        if (!target.exists()) return null;
        try {
            JSONObject object = McpProtocol.json(readBounded(target, McpProtocol.MAX_BODY));
            if (object.getInt("format") != 1 || !identity.equals(object.getString("identity")))
                throw McpProtocol.fail("MCP_CACHE_INVALID");
            JSONArray tools = object.getJSONArray("tools");
            JSONObject hashes = object.getJSONObject("schemaHashes");
            Set<String> names = new HashSet<>();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject tool = tools.getJSONObject(i); String name = tool.getString("name");
                McpProtocol.schema(tool.optJSONObject("inputSchema"));
                if (!names.add(name) || !McpProtocol.hash(McpProtocol.canonical(tool.getJSONObject("inputSchema")))
                        .equals(hashes.getString(name))) throw McpProtocol.fail("MCP_CACHE_INVALID");
            }
            if (hashes.length() != tools.length() || !McpProtocol.integer(object.opt("discoveredAt"))
                    || !McpProtocol.integer(object.opt("ttlMs")) || object.getLong("ttlMs") <= 0
                    || object.getLong("ttlMs") > 86400000 || object.getString("version").isEmpty())
                throw McpProtocol.fail("MCP_CACHE_INVALID");
            String text = McpProtocol.canonical(tools), hash = McpProtocol.hash(text);
            if (!hash.equals(object.getString("catalogHash"))) throw McpProtocol.fail("MCP_CACHE_INVALID");
            return new Entry(identity, object.getString("configHash"), object.getString("version"),
                    hash, text, object.getLong("discoveredAt"), object.getLong("ttlMs"));
        } catch (Exception e) {
            invalidate(identity); return null; // Corrupt cache is never a usable empty schema.
        }
    }

    static String readBounded(File file, int max) throws Exception {
        if (file.length() > max) throw McpProtocol.fail("MCP_PAYLOAD_TOO_LARGE");
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (out.size() + n > max) throw McpProtocol.fail("MCP_PAYLOAD_TOO_LARGE");
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
