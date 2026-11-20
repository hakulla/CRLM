package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Local configured-server directory and immutable tool snapshots. No implicit network search. */
final class McpDirectory {
    static final long DEFAULT_TTL_MS = 300000;
    static final String LEGACY_OWNER = "mcp-explicit-ui";
    interface ConfigSource { List<Config> current() throws Exception; }
    interface Clock { long now(); }
    enum Stage { DISCOVER, EXPOSE, CALL }
    interface AccessPolicy { boolean authorize(Binding binding) throws Exception; }
    static final AccessPolicy DENY = new AccessPolicy() {
        @Override public boolean authorize(Binding binding) { return false; }
    };

    static final class Config {
        final String name, endpoint, source, identity, configHash;
        // Only the transport receives this. Never present in public directory/Tool/cache/errors.
        final String credential;
        final boolean enabled;
        final long schemaTtlMs;
        Config(String name, String endpoint, String credential, boolean enabled, long ttl) throws Exception {
            this(name, endpoint, credential, enabled, ttl, "");
        }
        Config(String name, String endpoint, String credential, boolean enabled, long ttl, String revision) throws Exception {
            if (name == null || name.trim().isEmpty() || name.trim().length() > 128)
                throw McpProtocol.fail("MCP_CONFIG_INVALID");
            this.name = name.trim(); this.source = "user-configured";
            this.credential = credential == null ? "" : credential.trim();
            if (this.credential.indexOf('\r') >= 0 || this.credential.indexOf('\n') >= 0)
                throw McpProtocol.fail("MCP_CONFIG_INVALID");
            this.endpoint = endpoint == null ? "" : endpoint.trim();
            McpProtocol.unicode(this.name); McpProtocol.unicode(this.endpoint); McpProtocol.unicode(this.credential);
            URI uri;
            try { uri = new URI(this.endpoint).normalize(); }
            catch (Exception e) { throw McpProtocol.fail("MCP_CONFIG_INVALID"); }
            String scheme = uri.getScheme(), host = uri.getHost();
            if (scheme == null || host == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || uri.getUserInfo() != null || uri.getFragment() != null || ttl <= 0 || ttl > 86400000)
                throw McpProtocol.fail("MCP_CONFIG_INVALID");
            scheme = scheme.toLowerCase(Locale.ROOT); host = host.toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) port = -1;
            String path = uri.getRawPath(); if (path == null || path.isEmpty()) path = "/";
            String authority = host + (port == -1 ? "" : ":" + port);
            String canonicalEndpoint = scheme + "://" + authority + path
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            JSONArray identityTuple = new JSONArray(); identityTuple.put(source);
            identityTuple.put(this.name.toLowerCase(Locale.ROOT)); identityTuple.put(canonicalEndpoint);
            identity = McpProtocol.hash(McpProtocol.canonical(identityTuple));
            JSONArray configTuple = new JSONArray(); configTuple.put(identity); configTuple.put(this.endpoint);
            configTuple.put(enabled); configTuple.put(ttl); configTuple.put(revision == null ? "" : revision);
            // Persist only a one-way configuration fingerprint, never the bearer credential itself.
            configTuple.put(McpProtocol.hash(identity + "\n" + this.credential));
            configHash = McpProtocol.hash(McpProtocol.canonical(configTuple));
            this.enabled = enabled; this.schemaTtlMs = ttl;
        }
    }

    /** Token-free local candidate, not a permission to discover or execute. */
    static final class ServerEntry {
        final String name, serverIdentity, source, summary;
        final boolean enabled;
        final long lastDiscoveredAt;
        final int knownToolCount;
        ServerEntry(Config c, SchemaCache.Entry cached, String summary, int count) {
            name = c.name; serverIdentity = c.identity; source = c.source; enabled = c.enabled;
            this.summary = summary; knownToolCount = count;
            lastDiscoveredAt = cached == null ? 0 : cached.discoveredAt;
        }
    }
    static final class Binding {
        final Stage stage;
        final String ownerRunId, serverName, serverIdentity, configHash, schemaHash, cacheVersion, toolName;
        // Main must resolve resources/effects from these frozen inputs before approving CALL.
        final String argumentsJson, argumentsHash;
        Binding(Stage stage, String owner, Config c, Tool tool, String args) throws Exception {
            this.stage = stage; ownerRunId = owner; serverName = c.name; serverIdentity = c.identity;
            configHash = c.configHash; schemaHash = tool == null ? "" : tool.schemaHash;
            cacheVersion = tool == null ? "" : tool.cacheVersion; toolName = tool == null ? "" : tool.toolName;
            argumentsJson = args == null ? "" : args; argumentsHash = McpProtocol.hash(argumentsJson);
        }
    }
    static final class Tool {
        final String serverName, serverIdentity, schemaHash, toolName, exposedName, description, cacheVersion;
        final boolean readOnlyHint;
        private final String configHash, schemaJson;
        Tool(Config config, SchemaCache.Entry entry, JSONObject remote) throws Exception {
            serverName = config.name; serverIdentity = config.identity; configHash = config.configHash;
            cacheVersion = entry.version; toolName = remote.getString("name");
            exposedName = McpProtocol.exposedName(serverIdentity, toolName);
            JSONObject schema = remote.getJSONObject("inputSchema"); McpProtocol.schema(schema);
            schemaJson = McpProtocol.canonical(schema); schemaHash = McpProtocol.hash(schemaJson);
            description = remote.optString("description", "");
            JSONObject hints = remote.optJSONObject("annotations");
            readOnlyHint = hints != null && Boolean.TRUE.equals(hints.opt("readOnlyHint"));
        }
        JSONObject schema() throws Exception { return McpProtocol.json(schemaJson); }
        JSONObject definition() throws Exception {
            JSONObject function = new JSONObject(); function.put("name", exposedName);
            String text = "[MCP:" + serverName + "] " + (description.isEmpty() ? toolName : description);
            function.put("description", text.length() > 180 ? text.substring(0, 180) : text);
            function.put("parameters", schema());
            JSONObject definition = new JSONObject(); definition.put("type", "function"); definition.put("function", function);
            return definition;
        }
    }
    static final class Discovery {
        final List<ServerEntry> candidates;
        final List<Tool> tools;
        final boolean needsServerSelection;
        Discovery(List<ServerEntry> candidates, List<Tool> tools, boolean selection) {
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            this.tools = Collections.unmodifiableList(new ArrayList<>(tools)); needsServerSelection = selection;
        }
    }
    private static final class Session { boolean initialized; String id = ""; }

    private final ConfigSource source;
    private final McpTransport transport;
    private final SchemaCache cache;
    private final Clock clock;
    private volatile AccessPolicy policy;
    private final AtomicLong requestId = new AtomicLong(1);
    private final Map<String, McpTransport.Scope> owners = new HashMap<>();
    private final Map<String, String> observed = new HashMap<>();
    private final Map<String, String> exposedProvenance = new HashMap<>();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    McpDirectory(ConfigSource source, McpTransport transport, SchemaCache cache, AccessPolicy policy, Clock clock) {
        this.source = source; this.transport = transport; this.cache = cache;
        this.policy = policy == null ? DENY : policy; this.clock = clock;
    }
    void setAccessPolicy(AccessPolicy policy) { this.policy = policy == null ? DENY : policy; }

    private synchronized List<Config> current() throws Exception {
        List<Config> configs;
        try { configs = source.current(); }
        catch (McpProtocol.Failure e) { throw e; }
        catch (Exception e) { throw McpProtocol.fail("MCP_CONFIG_IO"); }
        Set<String> names = new HashSet<>(), identities = new HashSet<>(), keep = new HashSet<>();
        Map<String, String> next = new HashMap<>();
        for (Config c : configs) {
            if (!names.add(c.name.toLowerCase(Locale.ROOT))) throw McpProtocol.fail("MCP_DUPLICATE_SERVER");
            if (!identities.add(c.identity)) throw McpProtocol.fail("MCP_SERVER_IDENTITY_COLLISION");
            if (c.enabled) { keep.add(c.identity); next.put(c.identity, c.configHash); }
        }
        for (Map.Entry<String, String> old : observed.entrySet()) {
            if (!old.getValue().equals(next.get(old.getKey()))) {
                cache.invalidate(old.getKey());
                for (String key : sessions.keySet()) if (key.endsWith("\n" + old.getValue())) sessions.remove(key);
            }
        }
        cache.retain(keep); observed.clear(); observed.putAll(next);
        return configs;
    }
    void configurationChanged() throws Exception { current(); }

    List<ServerEntry> directory() throws Exception {
        List<ServerEntry> entries = new ArrayList<>();
        for (Config c : current()) {
            SchemaCache.Entry entry = c.enabled ? cache.get(c.identity, c.configHash, c.schemaTtlMs, clock.now()) : null;
            StringBuilder summary = new StringBuilder(); int count = 0;
            if (entry != null) {
                JSONArray tools = new JSONArray(entry.toolsJson); count = tools.length();
                for (int i = 0; i < Math.min(count, 8); i++) {
                    if (i > 0) summary.append(", "); summary.append(tools.getJSONObject(i).getString("name"));
                }
            }
            entries.add(new ServerEntry(c, entry, summary.toString(), count));
        }
        return Collections.unmodifiableList(entries);
    }

    Discovery discover(String server, String query) throws Exception { return discover(server, query, LEGACY_OWNER); }
    Discovery discover(String server, String query, String ownerRunId) throws Exception {
        String selected = server == null ? "" : server.trim();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() > 256) throw McpProtocol.fail("MCP_QUERY_TOO_LARGE");
        List<ServerEntry> entries = directory();
        if (selected.isEmpty()) {
            List<ServerEntry> candidates = new ArrayList<>();
            for (ServerEntry entry : entries)
                if (entry.enabled && (needle.isEmpty() || (entry.name + " " + entry.summary)
                        .toLowerCase(Locale.ROOT).contains(needle))) candidates.add(entry);
            if (candidates.isEmpty()) for (ServerEntry entry : entries) if (entry.enabled) candidates.add(entry);
            return new Discovery(candidates, Collections.<Tool>emptyList(), true);
        }
        Config c = selected(selected); McpTransport.Scope scope = scope(ownerRunId); scope.check();
        authorize(Stage.DISCOVER, scope, c, null, null);
        assertConfig(c);
        SchemaCache.Entry entry = cache.get(c.identity, c.configHash, c.schemaTtlMs, clock.now());
        if (entry == null) entry = refresh(c, scope);
        List<Tool> matches = new ArrayList<>();
        for (Tool tool : materialize(c, entry)) {
            if (!needle.isEmpty() && !(tool.toolName + " " + tool.description + " " + tool.exposedName)
                    .toLowerCase(Locale.ROOT).contains(needle)) continue;
            authorize(Stage.EXPOSE, scope, c, tool, null);
            validate(tool); scope.check(); matches.add(tool);
            if (matches.size() >= 30) break; // Cache all tools, expose a bounded matching subset.
        }
        return new Discovery(entries, matches, false);
    }

    /** Explicit human probe/full listing: discovery only, never grants call permission. */
    List<Tool> manualProbe(Config c) throws Exception {
        if (!c.enabled) throw McpProtocol.fail("MCP_SERVER_DISABLED");
        McpTransport.Scope scope = scope(LEGACY_OWNER); scope.check();
        // Unsaved UI edit is allowed to probe, but is deliberately NOT cached/authorized for calls.
        JSONArray remote = fetch(c, scope, false);
        SchemaCache.Entry transientEntry = new SchemaCache.Entry(c.identity, c.configHash,
                java.util.UUID.randomUUID().toString(), McpProtocol.hash(McpProtocol.canonical(remote)),
                McpProtocol.canonical(remote), clock.now(), c.schemaTtlMs);
        return materialize(c, transientEntry);
    }
    List<Tool> manualFetch(String name) throws Exception {
        Config c = selected(name); McpTransport.Scope scope = scope(LEGACY_OWNER);
        return materialize(c, refresh(c, scope));
    }
    Discovery refresh(String server, String query, String ownerRunId) throws Exception {
        Config c = selected(server); McpTransport.Scope scope = scope(ownerRunId);
        authorize(Stage.DISCOVER, scope, c, null, null); assertConfig(c);
        // Fail closed: a failed refresh cannot leave previously approved schema usable.
        cache.invalidate(c.identity); refresh(c, scope);
        return discover(server, query, ownerRunId);
    }

    String callTool(Tool tool, JSONObject arguments, String ownerRunId) throws Exception {
        if (tool == null) throw McpProtocol.fail("MCP_TOOL_STALE");
        McpTransport.Scope scope = scope(ownerRunId); scope.check();
        Config c = validate(tool);
        JSONObject args = arguments == null ? new JSONObject() : McpProtocol.json(McpProtocol.boundedCanonical(arguments));
        McpProtocol.arguments(tool.schema(), args);
        authorize(Stage.CALL, scope, c, tool, McpProtocol.canonical(args));
        validate(tool); scope.check();
        initialize(c, scope, true);
        validate(tool); // Re-read configuration and schema after handshake, before side effects.
        JSONObject params = new JSONObject(); params.put("name", tool.toolName); params.put("arguments", args);
        JSONObject result = rpc(c, "tools/call", params, scope, true);
        scope.check(); validate(tool);
        if (McpProtocol.containsCredential(result, c.credential)) throw McpProtocol.fail("MCP_SENSITIVE_RESULT_REJECTED");
        if (result.has("isError") && !(result.opt("isError") instanceof Boolean)) throw McpProtocol.fail("MCP_INVALID_RESULT");
        JSONArray content = result.optJSONArray("content");
        if (content == null) throw McpProtocol.fail("MCP_INVALID_RESULT");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject item = content.optJSONObject(i);
            if (item == null || !(item.opt("type") instanceof String)) throw McpProtocol.fail("MCP_INVALID_RESULT");
            if (text.length() > 0) text.append('\n');
            if ("text".equals(item.getString("type"))) {
                if (!(item.opt("text") instanceof String)) throw McpProtocol.fail("MCP_INVALID_RESULT");
                text.append(item.getString("text"));
            } else text.append('[').append(item.getString("type")).append(" Contents]");
        }
        if (result.optBoolean("isError", false)) throw McpProtocol.fail("MCP_TOOL_IS_ERROR");
        return text.length() == 0 ? result.toString() : text.toString();
    }

    private Config validate(Tool tool) throws Exception {
        Config c = selected(tool.serverName);
        if (!c.identity.equals(tool.serverIdentity) || !c.configHash.equals(tool.configHash))
            throw McpProtocol.fail("MCP_SERVER_CHANGED");
        SchemaCache.Entry entry = cache.get(c.identity, c.configHash, c.schemaTtlMs, clock.now());
        if (entry == null) throw McpProtocol.fail("TOOL_SCHEMA_STALE");
        if (!entry.version.equals(tool.cacheVersion)) throw McpProtocol.fail("TOOL_SCHEMA_CHANGED");
        for (Tool cached : materialize(c, entry))
            if (cached.toolName.equals(tool.toolName) && cached.schemaHash.equals(tool.schemaHash)) return c;
        throw McpProtocol.fail("TOOL_SCHEMA_CHANGED");
    }
    private Config selected(String name) throws Exception {
        if (name == null || name.trim().isEmpty()) throw McpProtocol.fail("MCP_SERVER_REQUIRED");
        for (Config c : current()) if (c.name.equalsIgnoreCase(name.trim())) {
            if (!c.enabled) throw McpProtocol.fail("MCP_SERVER_DISABLED"); return c;
        }
        throw McpProtocol.fail("MCP_UNKNOWN_SERVER");
    }
    private void assertConfig(Config expected) throws Exception {
        if (!selected(expected.name).configHash.equals(expected.configHash)) throw McpProtocol.fail("MCP_SERVER_CHANGED");
    }
    private void authorize(Stage stage, McpTransport.Scope scope, Config c, Tool tool, String args) throws Exception {
        scope.check();
        boolean allowed;
        try { allowed = policy.authorize(new Binding(stage, scope.ownerRunId, c, tool, args)); }
        catch (McpProtocol.Failure e) { throw e; }
        catch (Exception e) { throw McpProtocol.fail("MCP_AUTHORIZATION_FAILED"); }
        if (!allowed) throw McpProtocol.fail("MCP_" + stage.name() + "_DENIED");
        scope.check();
    }

    private SchemaCache.Entry refresh(Config c, McpTransport.Scope scope) throws Exception {
        cache.invalidate(c.identity);
        try {
            JSONArray remote = fetch(c, scope, true); scope.check(); assertConfig(c);
            return cache.put(c.identity, c.configHash, remote, c.schemaTtlMs, clock.now(), c.credential);
        } catch (McpProtocol.Failure e) { throw e; }
        catch (Exception e) { throw McpProtocol.fail("MCP_DISCOVERY_FAILED"); }
    }
    private JSONArray fetch(Config c, McpTransport.Scope scope, boolean configured) throws Exception {
        initialize(c, scope, configured);
        JSONArray all = new JSONArray(); Set<String> names = new HashSet<>(), cursors = new HashSet<>();
        String cursor = "";
        for (int page = 0; page < 10; page++) {
            JSONObject params = new JSONObject(); if (!cursor.isEmpty()) params.put("cursor", cursor);
            JSONObject result = rpc(c, "tools/list", params, scope, configured);
            JSONArray tools = result.optJSONArray("tools");
            if (tools == null) throw McpProtocol.fail("MCP_INVALID_TOOLS_LIST");
            if (McpProtocol.containsCredential(tools, c.credential)) throw McpProtocol.fail("MCP_SENSITIVE_CACHE_REJECTED");
            for (int i = 0; i < tools.length(); i++) {
                JSONObject tool = tools.optJSONObject(i);
                if (tool == null || !(tool.opt("name") instanceof String)
                        || tool.getString("name").isEmpty() || tool.getString("name").length() > 256)
                    throw McpProtocol.fail("MCP_INVALID_TOOL");
                String name = tool.getString("name");
                if (!names.add(name)) throw McpProtocol.fail("MCP_DUPLICATE_TOOL");
                if (tool.has("description") && !(tool.opt("description") instanceof String))
                    throw McpProtocol.fail("MCP_INVALID_TOOL");
                McpProtocol.schema(tool.optJSONObject("inputSchema"));
                if (tool.has("annotations")) {
                    JSONObject hints = tool.optJSONObject("annotations");
                    if (hints == null) throw McpProtocol.fail("MCP_INVALID_TOOL");
                    for (String hint : new String[]{"readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint"})
                        if (hints.has(hint) && !(hints.opt(hint) instanceof Boolean)) throw McpProtocol.fail("MCP_INVALID_TOOL");
                }
                if (all.length() >= McpProtocol.MAX_TOOLS) throw McpProtocol.fail("MCP_TOOL_LIMIT_EXCEEDED");
                all.put(tool);
            }
            if (result.has("nextCursor") && !(result.opt("nextCursor") instanceof String))
                throw McpProtocol.fail("MCP_INVALID_TOOLS_LIST");
            cursor = result.optString("nextCursor", "");
            if (cursor.isEmpty()) return sorted(all);
            if (cursor.length() > 1024 || !cursors.add(cursor)) throw McpProtocol.fail("MCP_PAGINATION_INVALID");
        }
        throw McpProtocol.fail("MCP_PAGE_LIMIT_EXCEEDED");
    }
    private JSONArray sorted(JSONArray tools) throws Exception {
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < tools.length(); i++) list.add(tools.getJSONObject(i));
        Collections.sort(list, (a, b) -> a.optString("name").compareTo(b.optString("name")));
        JSONArray out = new JSONArray(); for (JSONObject tool : list) out.put(tool); return out;
    }
    private List<Tool> materialize(Config c, SchemaCache.Entry entry) throws Exception {
        JSONArray array = new JSONArray(entry.toolsJson); List<Tool> tools = new ArrayList<>();
        Set<String> names = new HashSet<>();
        if (array.length() > McpProtocol.MAX_TOOLS) throw McpProtocol.fail("MCP_TOOL_LIMIT_EXCEEDED");
        for (int i = 0; i < array.length(); i++) {
            Tool tool = new Tool(c, entry, array.getJSONObject(i));
            if (!names.add(tool.toolName)) throw McpProtocol.fail("MCP_DUPLICATE_TOOL");
            String provenance = c.identity + "\n" + tool.toolName;
            synchronized (exposedProvenance) {
                String previous = exposedProvenance.get(tool.exposedName);
                if (previous != null && !previous.equals(provenance)) throw McpProtocol.fail("MCP_NAME_COLLISION");
                exposedProvenance.put(tool.exposedName, provenance);
            }
            tools.add(tool);
        }
        return tools;
    }

    private void initialize(Config c, McpTransport.Scope scope, boolean configured) throws Exception {
        String key = scope.ownerRunId + "\n" + c.configHash;
        Session candidate = new Session(), session = sessions.putIfAbsent(key, candidate);
        if (session == null) session = candidate;
        synchronized (session) {
            scope.check(); if (configured) assertConfig(c);
            if (session.initialized) return;
            JSONObject params = new JSONObject(); params.put("protocolVersion", "2024-11-05");
            params.put("capabilities", new JSONObject());
            JSONObject info = new JSONObject(); info.put("name", "phone-agent"); info.put("version", "0.19.1");
            params.put("clientInfo", info);
            JSONObject result = rpc(c, "initialize", params, scope, configured);
            JSONObject serverInfo = result.optJSONObject("serverInfo");
            if (!"2024-11-05".equals(result.opt("protocolVersion")) || result.optJSONObject("capabilities") == null
                    || serverInfo == null || !(serverInfo.opt("name") instanceof String)
                    || !(serverInfo.opt("version") instanceof String)) throw McpProtocol.fail("MCP_INITIALIZE_INVALID");
            JSONObject notify = new JSONObject(); notify.put("jsonrpc", "2.0"); notify.put("method", "notifications/initialized");
            post(c, notify, scope, configured); scope.check(); session.initialized = true;
        }
    }
    private JSONObject rpc(Config c, String method, JSONObject params, McpTransport.Scope scope, boolean configured)
            throws Exception {
        JSONObject request = new JSONObject(); request.put("jsonrpc", "2.0"); request.put("id", requestId.getAndIncrement());
        request.put("method", method); request.put("params", params); return post(c, request, scope, configured);
    }
    private JSONObject post(Config c, JSONObject request, McpTransport.Scope scope, boolean configured) throws Exception {
        scope.check(); if (configured) assertConfig(c);
        String key = scope.ownerRunId + "\n" + c.configHash;
        Session session = sessions.get(key);
        McpTransport.Response response;
        try { response = transport.exchange(c, request, session == null ? "" : session.id, scope); }
        catch (McpProtocol.Failure e) { throw e; }
        catch (Exception e) { scope.check(); throw McpProtocol.fail("MCP_TRANSPORT_ERROR"); }
        scope.check(); if (configured) assertConfig(c);
        if (response == null || response.status < 200 || response.status >= 300) throw McpProtocol.fail("MCP_HTTP_ERROR");
        JSONObject result = McpProtocol.response(response.body, response.contentType, request.has("id") ? request.get("id") : null);
        if (session != null && response.session != null && !response.session.isEmpty()) {
            if (response.session.length() > 256 || !response.session.matches("[\\x21-\\x7E]+"))
                throw McpProtocol.fail("MCP_SESSION_INVALID");
            session.id = response.session;
        }
        return result;
    }
    private synchronized McpTransport.Scope scope(String owner) throws Exception {
        if (owner == null || owner.isEmpty() || owner.length() > 256 || owner.indexOf('\n') >= 0)
            throw McpProtocol.fail("MCP_OWNER_REQUIRED");
        McpTransport.Scope scope = owners.get(owner);
        if (scope == null) { scope = new McpTransport.Scope(owner); owners.put(owner, scope); }
        return scope;
    }
    void cancelOwner(String ownerRunId) {
        McpTransport.Scope scope;
        synchronized (this) {
            scope = owners.get(ownerRunId);
            if (scope == null && ownerRunId != null) { scope = new McpTransport.Scope(ownerRunId); owners.put(ownerRunId, scope); }
        }
        if (scope != null) scope.cancel();
        for (String key : sessions.keySet()) if (key.startsWith(ownerRunId + "\n")) sessions.remove(key);
    }
    /** Call only after the run has drained; owner IDs must never be reused. */
    synchronized void releaseOwner(String ownerRunId) {
        McpTransport.Scope scope = owners.remove(ownerRunId); if (scope != null) scope.cancel();
        for (String key : sessions.keySet()) if (key.startsWith(ownerRunId + "\n")) sessions.remove(key);
    }
}
