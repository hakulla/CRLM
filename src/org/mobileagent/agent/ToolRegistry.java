package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** One immutable spec binds schema, effect, resources and the actual host executor. */
public final class ToolRegistry {
    /** Operational knobs that can be safely clamped/coerced instead of hard-failing the task. */
    private static final Set<String> LENIENT_INT_FIELDS = new HashSet<>(Arrays.asList(
            "context", "context_lines", "limit", "offset", "max_results", "max_files",
            "max_steps", "timeout_s", "max_workers", "max_items"));
    public interface Executor { Object execute(JSONObject args, List<String> resources) throws Exception; }
    public interface Effects { int resolve(JSONObject args); }
    public interface ResourceResolver { List<String> resolve(File canonicalRoot, JSONObject args) throws Exception; }
    public static final class ToolSpec {
        public final String name, description, schemaHash, bindingId;
        private final String schema;
        private final Effects effects;
        private final ResourceResolver resolver;
        private final Executor executor;
        private final long bindingRevision;
        private final boolean dryRunRead;
        public ToolSpec(String name, String description, JSONObject schema, Effects effects,
                        ResourceResolver resolver, Executor executor, String bindingId) {
            this(name, description, schema, effects, resolver, executor, bindingId, 0, false);
        }
        private ToolSpec(String name, String description, JSONObject schema, Effects effects,
                         ResourceResolver resolver, Executor executor, String bindingId,
                         long revision, boolean dryRunRead) {
            if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]*") || schema == null
                    || effects == null || resolver == null || bindingId == null || bindingId.isEmpty())
                throw new IllegalArgumentException("Incomplete tool spec");
            checkSchema(schema);
            if (!"object".equals(schema.optString("type"))) throw new IllegalArgumentException("Arguments must be object");
            this.name = name;
            this.description = description == null ? name : description;
            this.schema = canonical(schema);
            this.schemaHash = sha256(this.schema);
            this.effects = effects;
            this.resolver = resolver;
            this.executor = executor;
            this.bindingId = bindingId;
            this.bindingRevision = revision;
            this.dryRunRead = dryRunRead;
        }
        public JSONObject schema() { return parseObject(schema); }
        int effectsForDryRun(boolean dry) {
            if (dry && dryRunRead) return ToolPolicy.READ;
            try { return checkedEffects(effects.resolve(new JSONObject().put("dry_run", dry))); }
            catch (Exception e) { throw new IllegalArgumentException("Cannot resolve compatibility effects", e); }
        }
        private ToolSpec bind(Executor executor, long revision) {
            if (executor == null) throw new IllegalArgumentException("Missing executor");
            return new ToolSpec(name, description, schema(), effects, resolver, executor,
                    bindingId, revision, dryRunRead);
        }
    }
    public static final class Invocation {
        public final String name, canonicalRoot, argsHash, schemaHash, specFingerprint, resourcesHash;
        public final int effects;
        public final List<String> resources;
        private final String args;
        private final ToolRegistry owner;
        private Invocation(ToolRegistry owner, ToolSpec spec, File root, JSONObject args,
                           int effects, List<String> resources) throws IOException {
            this.owner = owner;
            this.name = spec.name;
            this.canonicalRoot = root.getCanonicalPath();
            this.args = canonical(args);
            this.argsHash = sha256(this.args);
            this.schemaHash = spec.schemaHash;
            this.specFingerprint = sha256(spec.bindingId + ":" + spec.bindingRevision + ":" + schemaHash);
            this.effects = effects;
            this.resources = Collections.unmodifiableList(new ArrayList<>(new TreeSet<>(resources)));
            this.resourcesHash = sha256(canonical(new JSONArray(this.resources)));
        }
        public JSONObject args() { return parseObject(args); }
        public boolean sameAs(Invocation other) {
            return other != null && owner == other.owner && name.equals(other.name)
                    && canonicalRoot.equals(other.canonicalRoot) && argsHash.equals(other.argsHash)
                    && schemaHash.equals(other.schemaHash) && specFingerprint.equals(other.specFingerprint)
                    && effects == other.effects && resourcesHash.equals(other.resourcesHash);
        }
    }
    /** Obtain a fresh state under the host's policy lock just before invoke. */
    public static final class PolicyState {
        public final String runId, mode, fingerprint;
        public final File root;
        public final ToolScope scope;
        public final long revision;
        public PolicyState(String runId, File root, ToolScope scope, String mode, long revision, String fingerprint) {
            if (runId == null || runId.isEmpty() || root == null || scope == null || scope.isInherit()
                    || fingerprint == null || fingerprint.isEmpty() || revision < 0)
                throw new IllegalArgumentException("Incomplete current policy");
            this.runId = runId; this.root = root; this.scope = scope; this.mode = mode;
            this.revision = revision; this.fingerprint = fingerprint;
        }
    }
    private final Map<String, ToolSpec> specs = new LinkedHashMap<>();
    private long revision;
    private static final ToolRegistry GLOBAL = builtins();
    public static ToolRegistry global() { return GLOBAL; }
    /** Prefer an engine-local registry so executor closures cannot cross conversations/engines. */
    public static ToolRegistry withBuiltins() { return builtins(); }
    public ToolRegistry() { }
    public synchronized ToolSpec get(String name) { return specs.get(name); }
    public synchronized void register(ToolSpec spec) {
        if (spec == null || spec.executor == null) throw new IllegalArgumentException("Registration requires executor");
        if (specs.containsKey(spec.name)) throw new IllegalArgumentException("Duplicate tool: " + spec.name);
        specs.put(spec.name, spec.bind(spec.executor, ++revision));
    }
    /** Bind the main engine's actual built-in implementation, not a second registry/name dispatcher. */
    public synchronized void bindExecutor(String name, Executor executor) {
        ToolSpec spec = specs.get(name);
        if (spec == null) throw new IllegalArgumentException("Unknown tool: " + name);
        specs.put(name, spec.bind(executor, ++revision));
    }
    /** Add host-resolved checkpoint/provider/command resources without losing built-in path bindings. */
    public synchronized void bindExecutor(String name, Executor executor, final ResourceResolver hostResources) {
        final ToolSpec old = specs.get(name);
        if (old == null || executor == null || hostResources == null) throw new IllegalArgumentException("Incomplete host binding");
        ResourceResolver combined = new ResourceResolver() {
            public List<String> resolve(File root, JSONObject args) throws Exception {
                List<String> resources = new ArrayList<>(old.resolver.resolve(root, parseObject(canonical(args))));
                List<String> extra = hostResources.resolve(root, parseObject(canonical(args)));
                if (extra == null) throw new IllegalArgumentException("Missing host resource binding");
                resources.addAll(extra);
                return resources;
            }
        };
        specs.put(name, new ToolSpec(old.name, old.description, old.schema(), old.effects, combined,
                executor, old.bindingId, ++revision, old.dryRunRead));
    }
    public synchronized void unregister(String name) { if (specs.remove(name) != null) ++revision; }
    public synchronized JSONArray schemas(Set<String> allowed) {
        JSONArray result = new JSONArray();
        for (ToolSpec spec : specs.values()) {
            if (allowed != null && !allowed.contains(spec.name)) continue;
            try {
                result.put(new JSONObject().put("type", "function").put("function", new JSONObject()
                        .put("name", spec.name).put("description", spec.description).put("parameters", spec.schema())));
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        return result;
    }
    public synchronized Invocation prepare(String name, String rawArgs, File root) throws Exception {
        return prepare(name, parseObject(rawArgs), root);
    }
    public synchronized Invocation prepare(String name, JSONObject args, File root) throws Exception {
        ToolSpec spec = specs.get(name);
        if (spec == null || spec.executor == null) throw new IllegalArgumentException("Unknown/unbound tool: " + name);
        JSONObject copy = parseObject(canonical(args));
        normalizeArguments(spec.schema(), copy, 0);
        validate(spec.schema(), copy, "$", 0);
        if (root == null || !root.isDirectory()) throw new IllegalArgumentException("Missing workspace root");
        File canonicalRoot = root.getCanonicalFile();
        int effects = checkedEffects(spec.effects.resolve(parseObject(canonical(copy))));
        List<String> resolved = spec.resolver.resolve(canonicalRoot, parseObject(canonical(copy)));
        if (resolved == null || resolved.isEmpty()) throw new IllegalArgumentException("Missing resource binding");
        for (String resource : resolved) {
            if (resource == null || resource.isEmpty()) throw new IllegalArgumentException("Empty resource");
        }
        return new Invocation(this, spec, canonicalRoot, copy, effects, resolved);
    }
    /** Validation-only path for the existing dispatch: schema/resources/binding, no executor required. */
    public synchronized Invocation validateOnly(String name, JSONObject args, File root) throws Exception {
        ToolSpec spec = specs.get(name);
        if (spec == null) throw new IllegalArgumentException("Unknown tool: " + name);
        JSONObject copy = parseObject(canonical(args));
        normalizeArguments(spec.schema(), copy, 0);
        validate(spec.schema(), copy, "$", 0);
        if (root == null || !root.isDirectory()) throw new IllegalArgumentException("Missing workspace root");
        File canonicalRoot = root.getCanonicalFile();
        int effects = checkedEffects(spec.effects.resolve(parseObject(canonical(copy))));
        List<String> resolved = spec.resolver.resolve(canonicalRoot, parseObject(canonical(copy)));
        if (resolved == null) throw new IllegalArgumentException("Missing resource binding");
        return new Invocation(this, spec, canonicalRoot, copy, effects, resolved);
    }

    /** Host-authorized execution of a freshly prepared call (approval/scope checked by the host). */
    public Object run(Invocation call) throws Exception {
        if (call == null || call.owner != this) throw new SecurityException("Foreign call");
        Executor executor;
        synchronized (this) {
            ToolSpec spec = specs.get(call.name);
            if (spec == null || spec.executor == null)
                throw new IllegalArgumentException("Unknown/unbound tool: " + call.name);
            executor = spec.executor;
        }
        return executor.execute(call.args(), call.resources);
    }

    /** Revalidates schema, resources (including symlink changes), binding and current approval. */
    public Object invoke(Invocation call, PolicyState state, ApprovalGrant grant) throws Exception {
        final Invocation current;
        final Executor executor;
        synchronized (this) {
            if (call == null || state == null || call.owner != this) throw new SecurityException("Foreign call");
            current = prepare(call.name, call.args(), state.root);
            if (!call.sameAs(current)) throw new SecurityException("Invocation changed; prepare/approve again");
            String verdict = ToolPolicy.authorize(state.mode, current, state.scope, state.runId,
                    state.revision, state.fingerprint, grant);
            if (verdict != null) throw new SecurityException(verdict);
            executor = specs.get(call.name).executor;
        }
        return executor.execute(current.args(), current.resources);
    }
    private static int checkedEffects(int value) {
        int known = ToolPolicy.READ | ToolPolicy.WRITE | ToolPolicy.PROCESS | ToolPolicy.NETWORK
                | ToolPolicy.SENSITIVE | ToolPolicy.DEVICE | ToolPolicy.INSTALL | ToolPolicy.META;
        if (value == 0 || (value & ~known) != 0) throw new IllegalArgumentException("Missing/unknown effects");
        return value;
    }
    public static Effects fixedEffects(final int effects) {
        checkedEffects(effects);
        return new Effects() { public int resolve(JSONObject args) { return effects; } };
    }
    /** MCP must supply trusted server identity + schema/effects/resolver/actual executor. No prefix grants. */
    public synchronized void registerMcp(String name, String description, JSONObject schema, int effects,
                                         final String serverIdentity, final ResourceResolver resolver,
                                         Executor executor) {
        if (name == null || !name.startsWith("mcp_") || serverIdentity == null || serverIdentity.isEmpty()
                || resolver == null) throw new IllegalArgumentException("Missing MCP binding");
        final String schemaHash = sha256(canonical(schema));
        register(new ToolSpec(name, description, schema, fixedEffects(effects | ToolPolicy.NETWORK),
                new ResourceResolver() {
                    public List<String> resolve(File root, JSONObject args) throws Exception {
                        List<String> resources = new ArrayList<>();
                        resources.add("mcp-server:" + serverIdentity + ":schema:" + schemaHash);
                        List<String> extra = resolver.resolve(root, args);
                        if (extra == null) throw new IllegalArgumentException("Missing MCP resources");
                        resources.addAll(extra);
                        return resources;
                    }
                }, executor, "mcp:" + serverIdentity + ":" + schemaHash));
    }
    public static String canonical(Object value) { return canonical(value, 0); }
    private static String canonical(Object value, int depth) {
        if (depth > 64) throw new IllegalArgumentException("JSON nesting too deep");
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            TreeSet<String> keys = new TreeSet<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            StringBuilder out = new StringBuilder("{");
            for (String key : keys) {
                if (out.length() > 1) out.append(',');
                out.append(quote(key)).append(':').append(canonical(object.opt(key), depth + 1));
            }
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) out.append(',');
                out.append(canonical(array.opt(i), depth + 1));
            }
            return out.append(']').toString();
        }
        if (value instanceof String) return quote((String) value);
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) {
            try {
                BigDecimal number = new BigDecimal(value.toString()).stripTrailingZeros();
                if (number.precision() > 10000 || Math.abs((long) number.scale()) > 10000)
                    throw new IllegalArgumentException("JSON number exceeds canonical range");
                return number.toPlainString();
            } catch (NumberFormatException e) { throw new IllegalArgumentException("Non-finite JSON number", e); }
        }
        throw new IllegalArgumentException("Not a JSON value");
    }
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
                    throw new IllegalArgumentException("Unpaired JSON surrogate");
                out.append(c).append(value.charAt(++i));
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Unpaired JSON surrogate");
            else if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) {
                out.append("\\u00").append(Character.forDigit((c >>> 4) & 15, 16)).append(Character.forDigit(c & 15, 16));
            } else out.append(c);
        }
        return out.append('"').toString();
    }
    public static String sha256(String value) {
        if (value == null) throw new IllegalArgumentException("Missing hash input");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : hash) { out.append(Character.forDigit((b >>> 4) & 15, 16)); out.append(Character.forDigit(b & 15, 16)); }
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    /** org.json is permissive on Android: verify strict grammar and duplicate keys before decoding. */
    public static JSONObject parseObject(String text) {
        if (text == null) throw new IllegalArgumentException("Missing JSON");
        new StrictJson(text).check();
        try {
            Object value = new org.json.JSONTokener(text).nextValue();
            if (!(value instanceof JSONObject)) throw new IllegalArgumentException("Arguments must be JSON object");
            canonical(value); // reject non-finite/unpaired/unrepresentable values before use
            return (JSONObject) value;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid JSON object", e); }
    }
    private static void checkSchema(JSONObject schema) {
        checkSchema(schema, 0);
    }
    private static void checkSchema(JSONObject schema, int depth) {
        if (schema == null || depth > 32) throw new IllegalArgumentException("Missing/deep schema");
        Set<String> supported = new TreeSet<>(Arrays.asList("type", "description", "properties", "required",
                "additionalProperties", "items", "enum", "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems"));
        Iterator<String> keys = schema.keys();
        while (keys.hasNext()) if (!supported.contains(keys.next())) throw new IllegalArgumentException("Unsupported schema keyword");
        String type = schema.optString("type", "");
        if (!Arrays.asList("object", "array", "string", "integer", "number", "boolean").contains(type))
            throw new IllegalArgumentException("Missing/unsupported schema type");
        for (String bound : Arrays.asList("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems")) {
            if (!schema.has(bound)) continue;
            if (!(schema.opt(bound) instanceof Number)) throw new IllegalArgumentException("Invalid schema bound");
            BigDecimal number = new BigDecimal(schema.opt(bound).toString());
            if (bound.endsWith("Length") || bound.endsWith("Items")) {
                if (number.signum() < 0 || number.stripTrailingZeros().scale() > 0)
                    throw new IllegalArgumentException("Invalid length bound");
                if (bound.endsWith("Length") && !"string".equals(type) || bound.endsWith("Items") && !"array".equals(type))
                    throw new IllegalArgumentException("Inapplicable schema bound");
            } else if (!"integer".equals(type) && !"number".equals(type)) throw new IllegalArgumentException("Inapplicable numeric bound");
        }
        for (String pair : Arrays.asList("minimum:maximum", "minLength:maxLength", "minItems:maxItems")) {
            String[] bounds = pair.split(":");
            if (schema.has(bounds[0]) && schema.has(bounds[1])
                    && schema.optDouble(bounds[0]) > schema.optDouble(bounds[1])) throw new IllegalArgumentException("Inverted schema range");
        }
        if (schema.has("enum") && (schema.optJSONArray("enum") == null || schema.optJSONArray("enum").length() == 0))
            throw new IllegalArgumentException("Invalid enum");
        if ("object".equals(type)) {
            if (!Boolean.FALSE.equals(schema.opt("additionalProperties"))) throw new IllegalArgumentException("Object schema must reject extra arguments");
            JSONObject props = schema.optJSONObject("properties");
            JSONArray required = schema.optJSONArray("required");
            if (props == null || required == null) throw new IllegalArgumentException("Missing properties/required schema");
            Set<String> seen = new TreeSet<>();
            for (int i = 0; i < required.length(); i++) {
                Object name = required.opt(i);
                if (!(name instanceof String) || !props.has((String) name) || !seen.add((String) name))
                    throw new IllegalArgumentException("Invalid required schema");
            }
            Iterator<String> names = props.keys();
            while (names.hasNext()) checkSchema(props.optJSONObject(names.next()), depth + 1);
        } else if ("array".equals(type)) checkSchema(schema.optJSONObject("items"), depth + 1);
    }
    /**
     * Model output often over-shoots numeric knobs (context=4000, limit=99999).
     * These are safe to clamp to the schema's own bounds; a hard validation
     * failure here wastes a round and can strand the whole run.
     */
    private static void normalizeArguments(JSONObject schema, JSONObject args, int depth) {
        if (depth > 64 || schema == null || args == null) return;
        JSONObject properties = schema.optJSONObject("properties");
        if (properties == null) return;
        List<String> drop = new ArrayList<>();
        Iterator<String> names = args.keys();
        while (names.hasNext()) {
            String name = names.next();
            JSONObject prop = properties.optJSONObject(name);
            if (prop == null) continue;
            Object value = args.opt(name);
            if (value instanceof JSONObject) {
                normalizeArguments(prop, (JSONObject) value, depth + 1);
                continue;
            }
            if (value instanceof JSONArray) {
                JSONObject itemSchema = prop.optJSONObject("items");
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.opt(i);
                    if (item instanceof JSONObject) {
                        normalizeArguments(itemSchema, (JSONObject) item, depth + 1);
                    }
                }
                continue;
            }
            if (!LENIENT_INT_FIELDS.contains(name) || !"integer".equals(prop.optString("type"))) {
                continue;
            }
            BigDecimal number = null;
            if (value instanceof Number) {
                number = new BigDecimal(value.toString());
            } else if (value instanceof String) {
                try {
                    number = new BigDecimal(((String) value).trim());
                } catch (Exception ignored) {
                }
            }
            if (number == null) {
                drop.add(name);
                continue;
            }
            BigDecimal min = prop.has("minimum")
                    ? new BigDecimal(prop.opt("minimum").toString()) : null;
            BigDecimal max = prop.has("maximum")
                    ? new BigDecimal(prop.opt("maximum").toString()) : null;
            BigDecimal clamped = number;
            if (min != null && clamped.compareTo(min) < 0) clamped = min;
            if (max != null && clamped.compareTo(max) > 0) clamped = max;
            try {
                args.put(name, clamped.intValue());
            } catch (Exception ignored) {
            }
        }
        for (String name : drop) {
            args.remove(name);
        }
    }

    private static void validate(JSONObject schema, Object value, String path, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Arguments too deep");
        String type = schema.optString("type");
        boolean correct = "object".equals(type) ? value instanceof JSONObject
                : "array".equals(type) ? value instanceof JSONArray : "string".equals(type) ? value instanceof String
                : "boolean".equals(type) ? value instanceof Boolean : value instanceof Number;
        if (!correct) throw new IllegalArgumentException("Wrong argument type: " + path);
        if (value instanceof Number) {
            BigDecimal number = new BigDecimal(value.toString());
            if ("integer".equals(type) && number.stripTrailingZeros().scale() > 0) throw new IllegalArgumentException("Expected integer: " + path);
            if (schema.has("minimum") && number.compareTo(new BigDecimal(schema.opt("minimum").toString())) < 0
                    || schema.has("maximum") && number.compareTo(new BigDecimal(schema.opt("maximum").toString())) > 0)
                throw new IllegalArgumentException("Parameters " + path + " Beyond scope (permissible) "
                        + (schema.has("minimum") ? schema.opt("minimum") : "-∞") + "~"
                        + (schema.has("maximum") ? schema.opt("maximum") : "∞")
                        + ", Copy that. " + value + ")");
        }
        JSONArray choices = schema.optJSONArray("enum");
        if (choices != null) {
            boolean found = false;
            for (int i = 0; i < choices.length(); i++) if (canonical(value).equals(canonical(choices.opt(i)))) found = true;
            if (!found) throw new IllegalArgumentException("Unknown enum value: " + path);
        }
        if (value instanceof String) bounds(schema, ((String) value).length(), "minLength", "maxLength", path);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            bounds(schema, array.length(), "minItems", "maxItems", path);
            for (int i = 0; i < array.length(); i++) validate(schema.optJSONObject("items"), array.opt(i), path + "[" + i + "]", depth + 1);
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value, properties = schema.optJSONObject("properties");
            JSONArray required = schema.optJSONArray("required");
            for (int i = 0; i < required.length(); i++) if (!object.has(required.optString(i)))
                throw new IllegalArgumentException("Missing required argument: " + path + "." + required.optString(i));
            Iterator<String> names = object.keys();
            while (names.hasNext()) {
                String name = names.next();
                if (!properties.has(name)) throw new IllegalArgumentException("Unknown argument: " + path + "." + name);
                validate(properties.optJSONObject(name), object.opt(name), path + "." + name, depth + 1);
            }
        }
    }
    private static void bounds(JSONObject schema, int size, String min, String max, String path) {
        if (schema.has(min) && size < schema.optDouble(min) || schema.has(max) && size > schema.optDouble(max))
            throw new IllegalArgumentException("Arguments Length " + path + " Beyond scope (permissible) "
                    + (schema.has(min) ? schema.opt(min) : "0") + "~"
                    + (schema.has(max) ? schema.opt(max) : "∞")
                    + ", Copy that. " + size + ")");
    }
    public static String pathResource(File root, String path) throws IOException {
        if (path == null || !path.equals(path.trim()) || path.indexOf('\0') >= 0
                || path.indexOf('\\') >= 0)
            throw new IllegalArgumentException("The path is illegal (can not be empty, with a backslash or NUL): " + path);
        File base = root.getCanonicalFile();
        File target = new File(path).isAbsolute()
                ? new File(path) : new File(base, path.isEmpty() ? "." : path);
        target = target.getCanonicalFile();
        if (!target.equals(base) && !target.getPath().startsWith(base.getPath() + File.separator))
            throw new IllegalArgumentException("The path must be within the working area:" + path);
        return "file:" + target.getPath();
    }
    private static ResourceResolver builtinResources(final String name) {
        return new ResourceResolver() {
            public List<String> resolve(File root, JSONObject args) throws Exception {
                List<String> out = new ArrayList<>();
                out.add("workspace:" + root.getCanonicalPath());
                if ("ui_tap".equals(name)) {
                    if (args.has("x") != args.has("y") || args.optString("text", "").trim().isEmpty() && !args.has("x"))
                        throw new IllegalArgumentException("ui_tap needs nonempty text or complete x/y coordinates");
                }
                boolean path = false;
                for (String key : Arrays.asList("path", "from", "to", "project")) {
                    if (args.has(key)) { out.add(pathResource(root, args.getString(key))); path = true; }
                }
                if (!path) out.add(pathResource(root, "."));
                if (args.has("command")) out.add("opaque-shell:" + sha256(args.getString("command")));
                if ("web_fetch".equals(name)) {
                    URI uri = new URI(args.getString("url"));
                    if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                        throw new IllegalArgumentException("Expected HTTP(S) URL");
                    if (uri.getHost() == null || uri.getUserInfo() != null)
                        throw new IllegalArgumentException("Expected public URL without credentials");
                    out.add("network:" + uri.normalize().toASCIIString());
                }
                if ("web_search".equals(name)) {
                    String query = args.getString("query");
                    if (query == null || query.trim().isEmpty())
                        throw new IllegalArgumentException("Missing search query");
                    out.add("network:bing-search:" + sha256(query.trim()));
                }
                if ("mcp_discover".equals(name)) out.add("mcp-discovery:" + args.optString("server", "") + ":" + args.optString("query", ""));
                if ("remember".equals(name)) out.add(pathResource(root, ".agent/memory.md"));
                if ("read_skill".equals(name)) {
                    String skill = args.getString("name");
                    if (!skill.matches("[A-Za-z0-9_\\-\\u4e00-\\u9fff]+")) throw new IllegalArgumentException("Invalid skill name");
                    out.add(pathResource(root, ".agent/skills/" + skill + "/SKILL.md"));
                }
                if ("list_skills".equals(name)) out.add(pathResource(root, ".agent/skills"));
                if ("search_knowledge".equals(name)) out.add(pathResource(root, ".agent/knowledge"));
                if (name.contains("checkpoint")) out.add("checkpoint:" + root.getCanonicalPath() + ":" + args.optString("id", "latest"));
                if (Arrays.asList("ui_dump", "ui_tap", "ui_set_text", "ui_scroll", "system_action", "screenshot",
                        "open_app", "list_apps", "clipboard_read", "clipboard_write", "install_apk", "self_check").contains(name))
                    out.add("device:" + name);
                if ("build_apk".equals(name) || "self_check".equals(name)) out.add("opaque-process:" + name);
                if ("spawn_subagent".equals(name)) out.add("network:configured-model-provider");
                return out;
            }
        };
    }
    private void builtin(String name, String description, int effects, String required, String... fields) {
        try {
            JSONObject props = new JSONObject();
            for (String field : fields) {
                String[] pieces = field.split(":");
                JSONObject property = new JSONObject().put("type", pieces[1]);
                if ("string".equals(pieces[1]) && !Arrays.asList("content", "new_text", "text").contains(pieces[0])
                        && Arrays.asList(required.split(" ")).contains(pieces[0])) property.put("minLength", 1);
                if (pieces.length > 2) {
                    if ("integer".equals(pieces[1])) property.put("minimum", Integer.parseInt(pieces[2])).put("maximum", Integer.parseInt(pieces[3]));
                    else property.put("enum", new JSONArray(Arrays.asList(pieces[2].split("\\|"))));
                }
                props.put(pieces[0], property);
            }
            JSONArray req = new JSONArray();
            if (!required.isEmpty()) for (String key : required.split(" ")) req.put(key);
            JSONObject schema = new JSONObject().put("type", "object").put("properties", props)
                    .put("required", req).put("additionalProperties", false);
            Effects resolver = "apply_patch".equals(name) ? new Effects() {
                public int resolve(JSONObject args) { return Boolean.TRUE.equals(args.opt("dry_run")) ? ToolPolicy.READ : ToolPolicy.WRITE; }
            } : fixedEffects(effects);
            specs.put(name, new ToolSpec(name, description, schema, resolver, builtinResources(name), null,
                    "builtin:0.19.1:" + name, ++revision, "apply_patch".equals(name)));
        } catch (Exception e) { throw new IllegalStateException("Invalid builtin " + name, e); }
    }
    private static ToolRegistry builtins() {
        ToolRegistry r = new ToolRegistry();
        r.builtin("list_files", "The project is a workspace file.path Default root directory.", ToolPolicy.READ, "", "path:string");
        r.builtin("read_file", "Read text and return line numbers,offset From1Here we go.limit Default200. ", ToolPolicy.READ, "path", "path:string", "offset:integer:1:2147483647", "limit:integer:1:800");
        r.builtin("write_file", "Create or overwhelm text;content And will fill it with,append=true Add, long content fraction.", ToolPolicy.WRITE, "path content", "path:string", "content:string", "append:boolean");
        r.builtin("edit_file", "Exact Replace old_text I'll fill it in. new_text, An empty string indicates that it is explicitly deleted;replace_all Defaultfalse. ", ToolPolicy.WRITE, "path old_text new_text", "path:string", "old_text:string", "new_text:string", "replace_all:boolean");
        r.builtin("move_file", "Move or rename files,overwrite Defaultfalse. ", ToolPolicy.WRITE, "from to", "from:string", "to:string", "overwrite:boolean");
        r.builtin("delete_file", "The deletion of individual files and the establishment of checkpoints must be clearly requested by the user.", ToolPolicy.WRITE, "path", "path:string");
        r.builtin("apply_patch", "Apply unified diff; But only for the hair. @@ hunk(Press path Auto-Suppense ---/+++), Or complete. --- a/<path> / +++ b/<path> Head;dry_run=true Checks the preview only.", ToolPolicy.WRITE, "path patch", "path:string", "patch:string", "dry_run:boolean");
        r.builtin("glob", "By Filecard pattern Find files,path Default root directory.", ToolPolicy.READ, "pattern", "pattern:string", "path:string", "max_results:integer:1:1000");
        r.builtin("grep", "Search for regular or keywords, return path, line number, match and0-3Line context.", ToolPolicy.READ, "query", "query:string", "path:string", "glob:string", "ignore_case:boolean", "context:integer:0:3", "max_results:integer:1:1000");
        r.builtin("repo_index", "Update local code index,rebuild=true Forced reconstruction.", ToolPolicy.READ, "", "rebuild:boolean");
        r.builtin("repo_search", "Retrieval Code Index, return file:line The blogger says:max_results Default6. ", ToolPolicy.READ, "query", "query:string", "path:string", "max_results:integer:1:100");
        r.builtin("repo_map", "Returns the code map of the file, line number and symbol composition.", ToolPolicy.READ, "", "path:string", "max_files:integer:1:1000");
        r.builtin("list_checkpoints", "The most recent document check points are listed.limit Default20. ", ToolPolicy.READ, "", "limit:integer:1:200");
        r.builtin("restore_checkpoint", "(b) Restoration of checkpoints and coverage of target documents;id The last one has been omitted.", ToolPolicy.WRITE, "", "id:string");
        r.builtin("list_skills", "List .agent/skills The skills and descriptions are available.", ToolPolicy.READ, "");
        r.builtin("read_skill", "Read in the specified skills directory SKILL.md. ", ToolPolicy.READ, "name", "name:string");
        r.builtin("search_knowledge", "Press query Retrieval Local .agent/knowledge Knowledge base.", ToolPolicy.READ, "query", "query:string");
        r.builtin("remember", "The long-term memory of stability preferences or important facts is written.", ToolPolicy.WRITE, "note", "note:string");
        r.builtin("shell_run", "Yes. App Internal shell(/system/bin/sh + toybox, None git/python/node/bash)Execute orders; support routine documents/Text/Pipes/Redirection;timeout_s Default60, Max1800sec.", ToolPolicy.PROCESS, "command", "command:string", "timeout_s:integer:1:1800");
        r.builtin("termux_run", "Yes. Termux Implementation shell; Advanced tool only for user-specific requirements/Package management;timeout_s Default180, Max1800sec.", ToolPolicy.PROCESS, "command", "command:string", "timeout_s:integer:1:1800");
        r.builtin("build_apk", "Local Termux Tool Chain Build project Medium Android Project.", ToolPolicy.PROCESS, "project", "project:string");
        r.builtin("self_check", "Diagnosis Agent Local environment, tool chain and sensitive configuration, not automatically repaired.", ToolPolicy.PROCESS | ToolPolicy.SENSITIVE, "");
        r.builtin("web_fetch", "Fetches web pages and returns titles, body text and page links; support HTTP Auto Upgrade HTTPS, gzip, GBK/UTF-8; Auto-use static body when empty WebView Implementation JS, The user also wrote that the user was not able to access the device, but that the device was still not working and was being read on the login mobile browser when the device was on.", ToolPolicy.NETWORK, "url", "url:string");
        r.builtin("download_file", "Download https File to workspace, supporting break-up, progress, options sha256/size verification;for models,APK, ZIP, PDF, Pictures, etc., replacement termux It's... curl/wget/aria2. ", ToolPolicy.NETWORK, "url path", "url:string", "path:string", "sha256:string", "size:integer:0:2147483647");
        r.builtin("web_search", "Use Bing Search web pages, return title,URL and summary, maximum 6 bars; calling in body required web_fetch. ", ToolPolicy.NETWORK, "query", "query:string");
        r.builtin("image_search", "Use Bing Images Search for public pictures, download most automatically 4 A list of candidates and an attachment to the model/feed; Check the main subject after downloading/Style/Clearness, change of candidate if you don't match.", ToolPolicy.NETWORK, "query", "query:string", "count:integer:1:4");
        r.builtin("mcp_discover", "Press query/server Found MCP tools; empty query Column server only.", ToolPolicy.NETWORK, "", "query:string", "server:string");
        r.builtin("spawn_subagent", "The only research agency whose permission to start is limited by the father is the researcher who is a lawyer for the investigation.max_steps Yes1-12(Default 8). ", ToolPolicy.READ | ToolPolicy.NETWORK, "task", "task:string", "max_steps:integer:1:12");
        r.builtin("list_apps", "Search can start applications,query This can be a name or a business clip.", ToolPolicy.READ | ToolPolicy.SENSITIVE, "", "query:string");
        r.builtin("ui_dump", "Reads the accessible tree, text and coordinates of the current interface.", ToolPolicy.READ | ToolPolicy.SENSITIVE, "");
        r.builtin("screenshot", "The screen of a mobile phone is intercepted and the user needs to open the screen for reading.", ToolPolicy.SENSITIVE, "");
        r.builtin("view_image", "Read photo files in the workspace (png/jpg/webp/gif, ≤4MB)And you can attach it to the model as a picture to see the contrast and the animate.", ToolPolicy.READ, "path", "path:string");
        r.builtin("browser_open", "Open page with application in-house browser (http(s) Website or Local html Path) returns title, text and JS console output; suitable to see the results of the web page that you generate.", ToolPolicy.READ, "url", "url:string", "timeout_ms:integer");
        r.builtin("browser_screenshot", "Intercepts the current page of the inner browser and appends it to the model as a picture to see the rendering and to the lateral.", ToolPolicy.READ, "");
        r.builtin("browser_eval", "Execute on an open inline browser page JavaScript and returns the results; for inspection window.THREE, window.__errors, canvas Dimensions/WebGL Status, avoid blinding.", ToolPolicy.READ, "js", "js:string");
        r.builtin("clipboard_read", "Reads the text of the Clipboard.", ToolPolicy.SENSITIVE, "");
        r.builtin("clipboard_write", "I'll fill it in. text Writes a clipboard, emptying the blank string clearly.", ToolPolicy.DEVICE, "text", "text:string");
        r.builtin("open_app", "Opens the application by name or package name, and only candidates are selected for multiple matches.", ToolPolicy.DEVICE, "query", "query:string");
        r.builtin("return_to_app", "Cut back.CRLM This application is used to maintain the current dialogue (removed from other applications and called after content).", ToolPolicy.DEVICE, "");
        r.builtin("ui_tap", "Press text Match the click control or provide full screen coordinates x/y. ", ToolPolicy.DEVICE, "", "text:string", "x:integer:0:100000", "y:integer:0:100000");
        r.builtin("ui_set_text", "Press target Match input box and enter required text. ", ToolPolicy.DEVICE, "target text", "target:string", "text:string");
        r.builtin("ui_scroll", "Press up/down/left/right Slide a screen in the direction.", ToolPolicy.DEVICE, "direction", "direction:string:up|down|left|right");
        r.builtin("system_action", "Implementation back/home/recents System navigation.", ToolPolicy.DEVICE, "action", "action:string:back|home|recents");
        r.builtin("install_apk", "Submission to Workspace APK Installation, still requiring system user confirmation.", ToolPolicy.INSTALL, "path", "path:string");
        r.builtin("inspect", "Integrated observation tools:mode=read Reading documents,list Columns,search Search the code, search the code.glob looking for documents;substitute read_file/list_files/grep/glob. context Yes. search Number of context rows (in0-3, Not text context.read Split pages, return the first paragraph to the next paragraph offset; If you need to read the whole text, you can read it.'End of file'. intent Write what's next./What? Why? Don't repeat the results of the previous step.", ToolPolicy.READ, "mode", "mode:string:read|list|search|glob", "path:string", "pattern:string", "query:string", "offset:integer:1:2147483647", "limit:integer:1:2000", "ignore_case:boolean", "context:integer:0:3", "max_results:integer:1:1000", "intent:string");
        r.builtin("edit", "Harmonization of tools:mode=write Write Files (append Optionalreplace precise replacement;complex patches should be loaded as needed apply_patch. intent Writes what the next change is, why; do not repeat the previous one. Example:{\"mode\":\"replace\",\"path\":\"a.java\",\"old_text\":\"int x=1;\",\"new_text\":\"int x=2;\"}", ToolPolicy.WRITE, "mode path", "mode:string:write|replace", "path:string", "content:string", "old_text:string", "new_text:string", "append:boolean", "replace_all:boolean", "intent:string");
        r.builtin("run", "Yes. App Internal shell Execute the order. /system/bin/sh + toybox; And when the tool chain is ready, git/bash/java Wait, see the implementation of the system alert).timeout_s Default60, Max1800sec; a prolonged non-output is sentenced to freezing and suspension.intent Write what you want to do next, why; do not repeat the results of the previous step. Do not use this tool to execute cat/head/tail/awk/sed reading and writing source files;source search/Read it all. inspect(mode=search/read), The end of the pipe. head Only for cutting off non-source output.", ToolPolicy.PROCESS, "command", "command:string", "timeout_s:integer:1:1800", "max_lines:integer:0:2000", "summary_only:boolean", "intent:string");
        r.builtin("ask", "Ask the user and suspend the task for answer. Do not use the tool for misreporting ask: Change Parameters By Yourself/Change tools to continue; questions are only asked if user authorization, login, password or irreversible selection is required.options Listed in your order of recommendation, the first is your recommendation; multiple options are to be replaced or replaced by | Separate, not all of them into a string;multi=true This means that you can choose more than one.questions=[{question, options:[...], multi}]. The user can also enter the user without choosing, but by entering it. Example:{\"question\":\"Which plan?\",\"options\":\"A Programme\\nB Programme\"}", ToolPolicy.META, "question", "question:string", "options:string", "multi:boolean", "questions:string");
        r.builtin("delegate", "The government has been working with the government to ensure that the government is not in a position to do so.max_steps Yes1-12(Default 8); For independent information/Code survey, main dialogue only concludes.intent Write what it wants to look at next, why; do not repeat the results of the previous step.", ToolPolicy.READ | ToolPolicy.NETWORK, "task", "task:string", "max_steps:integer:1:12", "intent:string");
        r.builtin("tool_search", "Search for adapter (webpage)/Download/Equipment/Build/MCP/Memory/Index, etc.), return name, use and risk; reuse tool_load Load.", ToolPolicy.META, "query", "query:string", "limit:integer:1:10");
        r.builtin("tool_load", "Load a adapter to this round of tasks, which can be called upon when loaded; automatically unloaded after the assignment.", ToolPolicy.META, "name", "name:string");
        r.builtin("context_search", "Check the original text in the files of earlier conversations by keyword (in the context summary)[Archive Index]Gives an archive file and line number.query Split multiple keywords into spaces to include them simultaneously; return a line-numbered clip; see full text inspect Read Archive jsonl And then, if history is archived, you can look at it, and don't guess.", ToolPolicy.READ, "query", "query:string", "limit:integer:1:20");
        r.builtin("ask_user", "Old name, equal value ask; Reservations compatible.", ToolPolicy.META, "question", "question:string", "options:string");
        r.builtin("update_plan", "Provide complete steps Plan, every step includes title and pending/in_progress/completed Status.", ToolPolicy.META, "");
        try {
            ToolSpec old = r.get("update_plan");
            JSONObject step = new JSONObject().put("type", "object").put("additionalProperties", false)
                    .put("properties", new JSONObject().put("title", new JSONObject().put("type", "string").put("minLength", 1))
                            .put("status", new JSONObject().put("type", "string").put("enum", new JSONArray(Arrays.asList("pending", "in_progress", "completed")))))
                    .put("required", new JSONArray(Arrays.asList("title", "status")));
            JSONObject schema = old.schema();
            schema.getJSONObject("properties").put("steps", new JSONObject().put("type", "array").put("items", step).put("minItems", 1).put("maxItems", 100));
            schema.put("required", new JSONArray(Arrays.asList("steps")));
            r.specs.put("update_plan", new ToolSpec(old.name, old.description, schema, old.effects, old.resolver,
                    null, old.bindingId, ++r.revision, false));
        } catch (Exception e) { throw new IllegalStateException(e); }
        return r;
    }
    private static final class StrictJson {
        private final String text;
        private int pos;
        StrictJson(String text) { this.text = text; }
        void check() { value(0); ws(); if (pos != text.length()) fail(); }
        void ws() { while (pos < text.length() && " \n\r\t".indexOf(text.charAt(pos)) >= 0) pos++; }
        boolean take(char c) { ws(); if (pos < text.length() && text.charAt(pos) == c) { pos++; return true; } return false; }
        void need(char c) { if (!take(c)) fail(); }
        void value(int depth) {
            if (depth > 64) fail();
            ws(); if (pos >= text.length()) fail();
            char c = text.charAt(pos);
            if (c == '{') {
                pos++; Set<String> keys = new TreeSet<>();
                if (take('}')) return;
                do {
                    ws(); int start = pos; string();
                    String key;
                    try { key = (String) new org.json.JSONTokener(text.substring(start, pos)).nextValue(); }
                    catch (Exception e) { throw new IllegalArgumentException("Bad key", e); }
                    if (!keys.add(key)) throw new IllegalArgumentException("Duplicate JSON key: " + key);
                    need(':'); value(depth + 1);
                } while (take(','));
                need('}'); return;
            }
            if (c == '[') { pos++; if (take(']')) return; do { value(depth + 1); } while (take(',')); need(']'); return; }
            if (c == '"') { string(); return; }
            for (String literal : Arrays.asList("true", "false", "null")) {
                if (text.startsWith(literal, pos)) { pos += literal.length(); return; }
            }
            int start = pos;
            if (pos < text.length() && text.charAt(pos) == '-') pos++;
            if (pos < text.length() && text.charAt(pos) == '0') pos++;
            else { if (!digit19()) fail(); while (digit()) pos++; }
            if (pos < text.length() && text.charAt(pos) == '.') { pos++; if (!digit()) fail(); while (digit()) pos++; }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++; if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
                if (!digit()) fail(); while (digit()) pos++;
            }
            if (pos == start) fail();
            String raw = text.substring(start, pos);
            try {
                Object decoded = new org.json.JSONTokener(raw).nextValue();
                if (!(decoded instanceof Number) || new BigDecimal(raw).compareTo(new BigDecimal(decoded.toString())) != 0)
                    throw new IllegalArgumentException("Lossy JSON number: " + raw);
            } catch (Exception e) { throw new IllegalArgumentException("Unrepresentable JSON number", e); }
        }
        boolean digit19() { if (pos < text.length() && text.charAt(pos) >= '1' && text.charAt(pos) <= '9') { pos++; return true; } return false; }
        boolean digit() { return pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9'; }
        void string() {
            ws(); if (pos >= text.length() || text.charAt(pos++) != '"') fail();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') return;
                if (c < 32) fail();
                if (c == '\\') {
                    if (pos >= text.length()) fail();
                    c = text.charAt(pos++);
                    if (c == 'u') {
                        for (int i = 0; i < 4; i++) if (pos >= text.length() || Character.digit(text.charAt(pos++), 16) < 0) fail();
                    } else if ("\"\\/bfnrt".indexOf(c) < 0) fail();
                }
            }
            fail();
        }
        void fail() { throw new IllegalArgumentException("Invalid strict JSON at " + pos); }
    }
}
