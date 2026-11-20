package org.mobileagent.agent;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Android configuration/UI adapter. Protocol, directory, authorization and cache are pure Java. */
final class McpManager {
    static final class Server {
        String name, url, token;
        boolean enabled;
        long schemaTtlMs = McpDirectory.DEFAULT_TTL_MS;
        private String revision = "";
        Server(String name, String url, String token, boolean enabled) {
            this.name = name; this.url = url; this.token = token; this.enabled = enabled;
        }
    }
    static final class Tool {
        final Server server;
        final String toolName, exposedName, schemaHash, serverIdentity;
        final boolean readOnlyHint;
        final JSONObject definition;
        private final McpDirectory.Tool frozen;
        private final String definitionHash;
        Tool(McpDirectory.Tool tool) throws Exception {
            frozen = tool;
            // Legacy UI reads server.name. Do not leak credentials or endpoint through tool metadata.
            server = new Server(tool.serverName, "", "", true);
            toolName = tool.toolName; exposedName = tool.exposedName;
            schemaHash = tool.schemaHash; serverIdentity = tool.serverIdentity; readOnlyHint = tool.readOnlyHint;
            definition = tool.definition(); definitionHash = McpProtocol.hash(McpProtocol.boundedCanonical(definition));
        }
    }
    static final class Discovery {
        final List<McpDirectory.ServerEntry> candidates;
        final List<Tool> tools;
        final boolean needsServerSelection;
        Discovery(McpDirectory.Discovery discovery) throws Exception {
            candidates = discovery.candidates; needsServerSelection = discovery.needsServerSelection;
            List<Tool> loaded = new ArrayList<>();
            for (McpDirectory.Tool tool : discovery.tools) loaded.add(new Tool(tool));
            tools = Collections.unmodifiableList(loaded);
        }
    }

    private final File configFile;
    private final McpDirectory directory;

    McpManager(Context context) {
        this(context.getApplicationContext().getFilesDir(), new McpTransport.Http(), McpDirectory.DENY,
                new McpDirectory.Clock() { @Override public long now() { return System.currentTimeMillis(); } });
    }
    /** Test seam: only private fixture directories and mock transports are used by acceptance tests. */
    McpManager(File privateFilesDir, McpTransport transport, McpDirectory.AccessPolicy policy, McpDirectory.Clock clock) {
        configFile = new File(privateFilesDir, "mcp-servers.json");
        directory = new McpDirectory(new McpDirectory.ConfigSource() {
            @Override public List<McpDirectory.Config> current() throws Exception {
                List<McpDirectory.Config> configs = new ArrayList<>();
                for (Server server : readServers()) configs.add(config(server));
                return configs;
            }
        }, transport, new SchemaCache(new File(privateFilesDir, "mcp-schema-cache")), policy, clock);
        ensureConfig();
    }
    void setAccessPolicy(McpDirectory.AccessPolicy policy) { directory.setAccessPolicy(policy); }
    List<McpDirectory.ServerEntry> directory() throws Exception { return directory.directory(); }
    Discovery discover(String server, String query) throws Exception { return new Discovery(directory.discover(server, query)); }
    Discovery discover(String server, String query, String ownerRunId) throws Exception {
        return new Discovery(directory.discover(server, query, ownerRunId));
    }
    Discovery refresh(String server, String query, String ownerRunId) throws Exception {
        return new Discovery(directory.refresh(server, query, ownerRunId));
    }

    private static McpDirectory.Config config(Server server) throws Exception {
        return new McpDirectory.Config(server.name, server.url, server.token, server.enabled, server.schemaTtlMs, server.revision);
    }
    private static String semanticConfigHash(Server server) throws Exception {
        return new McpDirectory.Config(server.name, server.url, server.token, server.enabled, server.schemaTtlMs).configHash;
    }
    List<Server> loadServers() {
        try { return readServers(); } catch (Exception ignored) { return new ArrayList<>(); }
    }
    private List<Server> readServers() throws Exception {
        String text = SchemaCache.readBounded(configFile, McpProtocol.MAX_BODY);
        JSONObject wrapper = McpProtocol.json("{\"servers\":" + text + "}");
        JSONArray array = wrapper.optJSONArray("servers");
        if (array == null || array.length() > 500) throw McpProtocol.fail("MCP_CONFIG_INVALID");
        List<Server> servers = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null || !(object.opt("name") instanceof String) || !(object.opt("url") instanceof String)
                    || (object.has("token") && !(object.opt("token") instanceof String))
                    || (object.has("enabled") && !(object.opt("enabled") instanceof Boolean))
                    || (object.has("mcpRevision") && !(object.opt("mcpRevision") instanceof String))
                    || (object.has("schemaTtlMs") && !McpProtocol.integer(object.opt("schemaTtlMs"))))
                throw McpProtocol.fail("MCP_CONFIG_INVALID");
            Server server = new Server(object.getString("name").trim(), object.getString("url").trim(),
                    object.optString("token", ""), object.optBoolean("enabled", true));
            server.schemaTtlMs = object.optLong("schemaTtlMs", McpDirectory.DEFAULT_TTL_MS);
            server.revision = object.optString("mcpRevision", "");
            servers.add(server);
        }
        return servers;
    }
    void saveServers(List<Server> servers) {
        try {
            List<Server> previous = readServers();
            if (servers.size() > 500) throw McpProtocol.fail("MCP_CONFIG_INVALID");
            JSONArray array = new JSONArray(); java.util.Set<String> names = new java.util.HashSet<>();
            for (Server server : servers) {
                McpDirectory.Config c = config(server);
                if (!names.add(c.name.toLowerCase(java.util.Locale.ROOT))) throw McpProtocol.fail("MCP_DUPLICATE_SERVER");
                String revision = "";
                for (Server old : previous) if (old.name.equalsIgnoreCase(server.name)
                        && semanticConfigHash(old).equals(semanticConfigHash(server))) revision = old.revision;
                // A generation survives separate UI/engine managers, including disable/re-enable or endpoint revert.
                if (revision.isEmpty()) revision = java.util.UUID.randomUUID().toString();
                JSONObject object = new JSONObject(); object.put("name", server.name); object.put("url", server.url);
                object.put("token", server.token == null ? "" : server.token); object.put("enabled", server.enabled);
                object.put("schemaTtlMs", server.schemaTtlMs); object.put("mcpRevision", revision); array.put(object);
            }
            writeFile(configFile, array.toString(2)); directory.configurationChanged();
        } catch (Exception ignored) { /* Existing void UI contract. No credential-bearing error logging. */ }
    }
    void upsert(String originalName, Server server) {
        List<Server> servers = loadServers(); boolean replaced = false;
        for (int i = 0; i < servers.size(); i++) if (servers.get(i).name.equalsIgnoreCase(originalName)) {
            servers.set(i, server); replaced = true; break;
        }
        if (!replaced) servers.add(server); saveServers(servers);
    }
    void delete(String name) {
        List<Server> kept = new ArrayList<>();
        for (Server server : loadServers()) if (!server.name.equalsIgnoreCase(name)) kept.add(server);
        saveServers(kept);
    }
    boolean hasEnabledServers() {
        for (Server server : loadServers()) if (server.enabled) return true;
        return false;
    }
    List<String> enabledServerNames() {
        List<String> names = new ArrayList<>();
        for (Server server : loadServers()) if (server.enabled) names.add(server.name);
        return names;
    }
    /** Explicit human full probe only. Automatic agents must use discover(server, query, ownerRunId). */
    List<Tool> fetchTools(List<String> errors) {
        List<Tool> tools = new ArrayList<>();
        for (String name : enabledServerNames()) {
            try { for (McpDirectory.Tool tool : directory.manualFetch(name)) tools.add(new Tool(tool)); }
            catch (Exception e) { errors.add(safeMessage(e)); }
        }
        return tools;
    }
    /** Compatibility signature; null/empty no longer means automatic discover-all. */
    List<Tool> fetchToolsFor(List<String> onlyServers, List<String> errors) {
        List<Tool> tools = new ArrayList<>();
        if (onlyServers == null || onlyServers.isEmpty()) { errors.add("MCP_SERVER_REQUIRED"); return tools; }
        for (String server : onlyServers) {
            try { tools.addAll(discover(server, "").tools); }
            catch (Exception e) { errors.add(safeMessage(e)); }
        }
        return tools;
    }
    String probe(Server server) {
        try {
            List<McpDirectory.Tool> tools = directory.manualProbe(config(server));
            StringBuilder text = new StringBuilder("The connection was successful.").append(tools.size()).append(" A tool");
            for (int i = 0; i < Math.min(tools.size(), 8); i++) text.append(i == 0 ? ": " : ", ").append(tools.get(i).toolName);
            return text.toString();
        } catch (Exception e) { return "Connection failed:" + safeMessage(e); }
    }
    String callTool(Tool tool, JSONObject arguments) throws Exception {
        return callTool(tool, arguments, McpDirectory.LEGACY_OWNER);
    }
    String callTool(Tool tool, JSONObject arguments, String ownerRunId) throws Exception {
        if (tool == null || !tool.definitionHash.equals(McpProtocol.hash(McpProtocol.boundedCanonical(tool.definition))))
            throw McpProtocol.fail("MCP_TOOL_SNAPSHOT_MUTATED");
        return directory.callTool(tool.frozen, arguments, ownerRunId);
    }
    void cancelOwner(String ownerRunId) { directory.cancelOwner(ownerRunId); }
    void releaseOwner(String ownerRunId) { directory.releaseOwner(ownerRunId); }
    private static String safeMessage(Exception e) {
        return e instanceof McpProtocol.Failure ? ((McpProtocol.Failure) e).code : "MCP_OPERATION_FAILED";
    }
    private void ensureConfig() {
        if (!configFile.exists()) try { writeFile(configFile, "[]"); } catch (Exception ignored) { }
    }
    private static void writeFile(File target, String text) throws Exception {
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw McpProtocol.fail("MCP_CONFIG_IO");
        File tmp = new File(parent, target.getName() + "." + java.util.UUID.randomUUID() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                tmp.setReadable(false, false); tmp.setWritable(false, false);
                tmp.setReadable(true, true); tmp.setWritable(true, true);
                out.write(text.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            }
            if (!tmp.renameTo(target)) throw McpProtocol.fail("MCP_CONFIG_IO");
        } finally { if (tmp.exists()) tmp.delete(); }
    }
}
