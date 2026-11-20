package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Bounded, strict JSON-RPC/SSE and schema boundary. No Android dependencies. */
final class McpProtocol {
    static final int MAX_BODY = 1024 * 1024;
    static final int MAX_SCHEMA = 64 * 1024;
    static final int MAX_TOOLS = 500;
    static final int MAX_DEPTH = 32;

    static final class Failure extends Exception {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }

    static Failure fail(String code) { return new Failure(code); }

    static JSONObject json(String text) throws Exception {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_BODY)
            throw fail("MCP_PAYLOAD_TOO_LARGE");
        new StrictJson(text).validate();
        try { return new JSONObject(text); }
        catch (Exception e) { throw fail("MCP_INVALID_JSON"); }
    }

    static JSONObject response(String body, String contentType, Object expectedId) throws Exception {
        if (body == null) body = "";
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY)
            throw fail("MCP_PAYLOAD_TOO_LARGE");
        if (body.trim().isEmpty()) {
            if (expectedId == null) return null;
            throw fail("MCP_RESPONSE_MISSING");
        }
        List<JSONObject> events = new ArrayList<>();
        String media = contentType == null ? "" : contentType.split(";", 2)[0].trim();
        if ("application/json".equalsIgnoreCase(media)) {
            events.add(json(body));
        } else if ("text/event-stream".equalsIgnoreCase(media)) {
            StringBuilder data = new StringBuilder();
            String[] lines = body.split("\\r\\n|\\n|\\r", -1);
            for (int i = 0; i <= lines.length; i++) {
                String line = i == lines.length ? "" : lines[i];
                if (line.startsWith("data:")) {
                    String value = line.substring(5);
                    if (value.startsWith(" ")) value = value.substring(1);
                    if (data.length() > 0) data.append('\n');
                    data.append(value);
                } else if (line.isEmpty() && data.length() > 0) {
                    events.add(json(data.toString()));
                    data.setLength(0);
                    if (events.size() > 512) throw fail("MCP_LIMIT_EXCEEDED");
                }
            }
        } else {
            throw fail("MCP_CONTENT_TYPE_UNSUPPORTED");
        }
        JSONObject matched = null;
        boolean wrongId = false;
        for (JSONObject event : events) {
            if (!"2.0".equals(event.opt("jsonrpc"))) throw fail("MCP_INVALID_RPC");
            if (!event.has("id")) {
                if (!(event.opt("method") instanceof String)
                        || ((String) event.opt("method")).isEmpty()
                        || event.has("result") || event.has("error")
                        || (event.has("params") && !(event.opt("params") instanceof JSONObject)))
                    throw fail("MCP_INVALID_NOTIFICATION");
                continue;
            }
            Object id = event.opt("id");
            if (!(id instanceof String) && !(id instanceof Number)) throw fail("MCP_INVALID_RPC");
            if (event.has("method") || event.has("result") == event.has("error"))
                throw fail("MCP_INVALID_RPC");
            if (event.has("result") && !(event.opt("result") instanceof JSONObject))
                throw fail("MCP_INVALID_RPC");
            if (event.has("error")) {
                JSONObject error = event.optJSONObject("error");
                if (error == null || !integer(error.opt("code"))
                        || !(error.opt("message") instanceof String)) throw fail("MCP_INVALID_RPC");
            }
            if (expectedId == null) throw fail("MCP_UNEXPECTED_RESPONSE");
            if (!sameId(id, expectedId)) { wrongId = true; continue; }
            if (matched != null) throw fail("MCP_DUPLICATE_RESPONSE");
            matched = event;
        }
        if (expectedId == null) return null;
        if (matched == null) throw fail(wrongId ? "MCP_REQUEST_ID_MISMATCH" : "MCP_RESPONSE_MISSING");
        if (matched.has("error")) throw fail("MCP_REMOTE_RPC_ERROR");
        return matched.getJSONObject("result");
    }

    private static boolean sameId(Object a, Object b) {
        if (a instanceof String || b instanceof String) return a instanceof String && a.equals(b);
        return integer(a) && integer(b) && ((Number) a).longValue() == ((Number) b).longValue();
    }

    static boolean integer(Object value) {
        return value instanceof Number && Double.isFinite(((Number) value).doubleValue())
                && ((Number) value).doubleValue() == ((Number) value).longValue();
    }

    static String boundedCanonical(Object value) throws Exception {
        guard(value, 0, new int[]{0}, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>()));
        String text = canonical(value);
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BODY) throw fail("MCP_PAYLOAD_TOO_LARGE");
        return text;
    }

    private static void guard(Object value, int depth, int[] nodes, Set<Object> ancestors) throws Exception {
        if (depth > MAX_DEPTH || ++nodes[0] > 20000) throw fail("MCP_LIMIT_EXCEEDED");
        if (value instanceof String && ((String) value).length() > MAX_BODY) throw fail("MCP_PAYLOAD_TOO_LARGE");
        if (value instanceof JSONObject || value instanceof JSONArray) {
            if (!ancestors.add(value)) throw fail("MCP_INVALID_JSON");
            if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value; Iterator<String> keys = object.keys();
                while (keys.hasNext()) guard(object.get(keys.next()), depth + 1, nodes, ancestors);
            } else {
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) guard(array.get(i), depth + 1, nodes, ancestors);
            }
            ancestors.remove(value);
        }
    }

    static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = object.keys();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            for (String key : keys) {
                if (out.length() > 1) out.append(',');
                out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));
            }
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) out.append(',');
                out.append(canonical(array.get(i)));
            }
            return out.append(']').toString();
        }
        if (value instanceof String) return JSONObject.quote((String) value);
        return String.valueOf(value);
    }

    static void unicode(String value) throws Failure {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw fail("MCP_INVALID_JSON");
            } else if (Character.isLowSurrogate(c)) throw fail("MCP_INVALID_JSON");
        }
    }

    static String hash(String value) throws Exception {
        unicode(value);
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    // Full 256-bit digest, length-delimited canonical tuple, provider-safe <=64 characters.
    // Directory also detects duplicate digest/provenance; never truncates names or adds counters.
    static String exposedName(String identity, String name) throws Exception {
        unicode(identity); unicode(name);
        JSONArray tuple = new JSONArray(); tuple.put(identity); tuple.put(name);
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(canonical(tuple).getBytes(StandardCharsets.UTF_8));
        String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        StringBuilder out = new StringBuilder("mcp_");
        int bits = 0, buffer = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 255); bits += 8;
            while (bits >= 5) { bits -= 5; out.append(alphabet.charAt((buffer >>> bits) & 31)); }
        }
        if (bits > 0) out.append(alphabet.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    static boolean containsCredential(Object value, String credential) throws Exception {
        if (credential == null || credential.isEmpty()) return false;
        if (value instanceof String) return ((String) value).contains(credential);
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value; Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.contains(credential) || containsCredential(object.get(key), credential)) return true;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) if (containsCredential(array.get(i), credential)) return true;
        }
        return false;
    }

    static void schema(JSONObject schema) throws Exception {
        if (schema == null) throw fail("TOOL_SCHEMA_UNAVAILABLE");
        String text = boundedCanonical(schema);
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_SCHEMA)
            throw fail("TOOL_SCHEMA_TOO_LARGE");
        if (!"object".equals(schema.opt("type"))) throw fail("TOOL_SCHEMA_UNAVAILABLE");
        validateSchema(schema, 0, new int[]{0});
    }

    private static void validateSchema(JSONObject s, int depth, int[] count) throws Exception {
        if (depth > 16 || ++count[0] > 2000) throw fail("TOOL_SCHEMA_LIMIT_EXCEEDED");
        Object type = s.opt("type");
        if (!(type instanceof String) || !java.util.Arrays.asList(
                "object", "array", "string", "integer", "number", "boolean", "null").contains(type))
            throw fail("TOOL_SCHEMA_UNAVAILABLE");
        Set<String> allowed = new HashSet<>(java.util.Arrays.asList("type", "properties", "required",
                "additionalProperties", "items", "enum", "const", "description", "title", "default",
                "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength",
                "minItems", "maxItems", "uniqueItems"));
        Iterator<String> keys = s.keys();
        while (keys.hasNext()) if (!allowed.contains(keys.next())) throw fail("TOOL_SCHEMA_UNSUPPORTED");
        for (String key : new String[]{"description", "title"})
            if (s.has(key) && !(s.opt(key) instanceof String)) throw fail("TOOL_SCHEMA_UNAVAILABLE");
        for (String key : new String[]{"minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum"})
            if (s.has(key) && (!(s.opt(key) instanceof Number)
                    || !Double.isFinite(((Number) s.opt(key)).doubleValue())
                    || !("number".equals(type) || "integer".equals(type))))
                throw fail("TOOL_SCHEMA_UNAVAILABLE");
        for (String key : new String[]{"minLength", "maxLength", "minItems", "maxItems"})
            if (s.has(key) && (!integer(s.opt(key)) || s.getLong(key) < 0
                    || (key.endsWith("Length") ? !"string".equals(type) : !"array".equals(type))))
                throw fail("TOOL_SCHEMA_UNAVAILABLE");
        if (s.has("uniqueItems") && (!(s.opt("uniqueItems") instanceof Boolean) || !"array".equals(type)))
            throw fail("TOOL_SCHEMA_UNAVAILABLE");
        if (s.has("enum")) {
            JSONArray values = s.optJSONArray("enum");
            if (values == null || values.length() == 0 || values.length() > 256)
                throw fail("TOOL_SCHEMA_UNAVAILABLE");
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < values.length(); i++)
                if (!seen.add(canonical(values.get(i)))) throw fail("TOOL_SCHEMA_DUPLICATE");
        }
        JSONObject properties = s.optJSONObject("properties");
        if (s.has("properties") && (properties == null || !"object".equals(type)))
            throw fail("TOOL_SCHEMA_UNAVAILABLE");
        if (properties != null) {
            Iterator<String> names = properties.keys();
            while (names.hasNext()) {
                String name = names.next();
                if (name.isEmpty() || name.length() > 256 || properties.optJSONObject(name) == null)
                    throw fail("TOOL_SCHEMA_UNAVAILABLE");
                validateSchema(properties.getJSONObject(name), depth + 1, count);
            }
        }
        if (s.has("required")) {
            JSONArray required = s.optJSONArray("required");
            if (required == null || !"object".equals(type)) throw fail("TOOL_SCHEMA_UNAVAILABLE");
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < required.length(); i++) {
                Object key = required.get(i);
                if (!(key instanceof String) || properties == null || !properties.has((String) key)
                        || !seen.add((String) key)) throw fail("TOOL_SCHEMA_UNAVAILABLE");
            }
        }
        if (s.has("additionalProperties")) {
            Object extra = s.get("additionalProperties");
            if (!"object".equals(type) || (!(extra instanceof Boolean) && !(extra instanceof JSONObject)))
                throw fail("TOOL_SCHEMA_UNAVAILABLE");
            if (extra instanceof JSONObject) validateSchema((JSONObject) extra, depth + 1, count);
        }
        if ("array".equals(type)) {
            JSONObject items = s.optJSONObject("items");
            if (items == null) throw fail("TOOL_SCHEMA_UNAVAILABLE");
            validateSchema(items, depth + 1, count);
        } else if (s.has("items")) throw fail("TOOL_SCHEMA_UNAVAILABLE");
    }

    static void arguments(JSONObject schema, JSONObject args) throws Exception {
        json(canonical(args));
        validateValue(schema, args, 0);
    }

    private static void validateValue(JSONObject s, Object value, int depth) throws Exception {
        if (depth > 16) throw fail("MCP_ARGUMENTS_INVALID");
        String type = s.getString("type");
        boolean valid = "object".equals(type) ? value instanceof JSONObject
                : "array".equals(type) ? value instanceof JSONArray
                : "string".equals(type) ? value instanceof String
                : "boolean".equals(type) ? value instanceof Boolean
                : "null".equals(type) ? value == JSONObject.NULL
                : "integer".equals(type) ? integer(value)
                : value instanceof Number && Double.isFinite(((Number) value).doubleValue());
        if (!valid) throw fail("MCP_ARGUMENTS_INVALID");
        if (s.has("const") && !canonical(s.get("const")).equals(canonical(value)))
            throw fail("MCP_ARGUMENTS_INVALID");
        if (s.has("enum")) {
            JSONArray values = s.getJSONArray("enum"); boolean found = false;
            for (int i = 0; i < values.length(); i++)
                if (canonical(values.get(i)).equals(canonical(value))) found = true;
            if (!found) throw fail("MCP_ARGUMENTS_INVALID");
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value, properties = s.optJSONObject("properties");
            JSONArray required = s.optJSONArray("required");
            if (required != null) for (int i = 0; i < required.length(); i++)
                if (!object.has(required.getString(i))) throw fail("MCP_ARGUMENTS_INVALID");
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (properties != null && properties.has(key))
                    validateValue(properties.getJSONObject(key), object.get(key), depth + 1);
                else if (Boolean.FALSE.equals(s.opt("additionalProperties"))) throw fail("MCP_ARGUMENTS_INVALID");
                else if (s.opt("additionalProperties") instanceof JSONObject)
                    validateValue(s.getJSONObject("additionalProperties"), object.get(key), depth + 1);
            }
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            bounds(s, "minItems", "maxItems", array.length());
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < array.length(); i++) {
                validateValue(s.getJSONObject("items"), array.get(i), depth + 1);
                if (s.optBoolean("uniqueItems", false) && !seen.add(canonical(array.get(i))))
                    throw fail("MCP_ARGUMENTS_INVALID");
            }
        }
        if (value instanceof String) {
            String text = (String) value;
            bounds(s, "minLength", "maxLength", text.codePointCount(0, text.length()));
        }
        if (value instanceof Number) {
            double n = ((Number) value).doubleValue();
            bounds(s, "minimum", "maximum", n);
            if ((s.has("exclusiveMinimum") && n <= s.getDouble("exclusiveMinimum"))
                    || (s.has("exclusiveMaximum") && n >= s.getDouble("exclusiveMaximum")))
                throw fail("MCP_ARGUMENTS_INVALID");
        }
    }

    private static void bounds(JSONObject s, String min, String max, double n) throws Exception {
        if ((s.has(min) && n < s.getDouble(min)) || (s.has(max) && n > s.getDouble(max)))
            throw fail("MCP_ARGUMENTS_INVALID");
    }

    /** Strict lexical gate before org.json (which otherwise accepts duplicate keys/permissive JSON). */
    private static final class StrictJson {
        final String text; int pos, nodes;
        StrictJson(String text) { this.text = text; }
        void validate() throws Exception {
            space(); if (pos >= text.length() || text.charAt(pos) != '{') bad();
            value(0); space(); if (pos != text.length()) bad();
        }
        void value(int depth) throws Exception {
            if (depth > MAX_DEPTH || ++nodes > 20000) throw fail("MCP_LIMIT_EXCEEDED");
            space(); if (pos >= text.length()) bad(); char c = text.charAt(pos);
            if (c == '{') {
                pos++; space(); Set<String> names = new HashSet<>();
                if (take('}')) return;
                do {
                    space(); String key = string();
                    if (!names.add(key)) throw fail("MCP_DUPLICATE_KEY");
                    space(); if (!take(':')) bad(); value(depth + 1); space();
                    if (take('}')) return;
                    if (!take(',')) bad();
                } while (true);
            } else if (c == '[') {
                pos++; space(); if (take(']')) return;
                do {
                    value(depth + 1); space(); if (take(']')) return;
                    if (!take(',')) bad();
                } while (true);
            } else if (c == '"') string();
            else if (text.startsWith("true", pos)) pos += 4;
            else if (text.startsWith("false", pos)) pos += 5;
            else if (text.startsWith("null", pos)) pos += 4;
            else number();
        }
        String string() throws Exception {
            int start = pos;
            if (!take('"')) { bad(); return ""; }
            StringBuilder decoded = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') { String value = decoded.toString(); unicode(value); return value; }
                if (c < 32) bad();
                if (c == '\\') {
                    if (pos >= text.length()) bad(); c = text.charAt(pos++);
                    if (c == 'u') {
                        if (pos + 4 > text.length()) bad();
                        int n = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = Character.digit(text.charAt(pos++), 16);
                            if (digit < 0) bad(); n = n * 16 + digit;
                        }
                        decoded.append((char) n);
                    } else {
                        String from = "\"\\/bfnrt", to = "\"\\/\b\f\n\r\t";
                        int index = from.indexOf(c); if (index < 0) bad();
                        decoded.append(to.charAt(index));
                    }
                } else decoded.append(c);
                if (pos - start > MAX_BODY) throw fail("MCP_LIMIT_EXCEEDED");
            }
            bad(); return "";
        }
        void number() throws Exception {
            int start = pos; take('-');
            if (!take('0')) {
                if (pos >= text.length() || text.charAt(pos) < '1' || text.charAt(pos) > '9') bad();
                while (digit()) pos++;
            }
            if (take('.')) { if (!digit()) bad(); while (digit()) pos++; }
            if (take('e') || take('E')) {
                if (!take('+')) take('-'); if (!digit()) bad(); while (digit()) pos++;
            }
            try { if (!Double.isFinite(Double.parseDouble(text.substring(start, pos)))) bad(); }
            catch (NumberFormatException e) { bad(); }
        }
        boolean digit() { return pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9'; }
        boolean take(char c) { if (pos < text.length() && text.charAt(pos) == c) { pos++; return true; } return false; }
        void space() { while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0) pos++; }
        void bad() throws Failure { throw fail("MCP_INVALID_JSON"); }
    }
}
