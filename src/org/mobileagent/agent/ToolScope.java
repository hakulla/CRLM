package org.mobileagent.agent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/** Explicit capability sets. INHERIT must be resolved before use; no MCP wildcards. */
public final class ToolScope {
    public static final int ALL = 0, NONE = 1, SET = 2, INHERIT = 3;
    private final int kind;
    private final Set<String> names;
    private ToolScope(int kind, Set<String> names) {
        this.kind = kind;
        this.names = Collections.unmodifiableSet(new TreeSet<>(names));
    }
    public static ToolScope all() { return new ToolScope(ALL, Collections.<String>emptySet()); }
    public static ToolScope none() { return new ToolScope(NONE, Collections.<String>emptySet()); }
    public static ToolScope inherit() { return new ToolScope(INHERIT, Collections.<String>emptySet()); }
    public static ToolScope of(Set<String> values) {
        if (values == null || values.isEmpty()) return none();
        for (String name : values) {
            if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("Invalid exact tool name: " + name);
        }
        return new ToolScope(SET, values);
    }
    public ToolScope resolve(ToolScope parent) {
        if (parent == null || parent.kind == INHERIT) throw new IllegalArgumentException("Unresolved parent scope");
        return kind == INHERIT ? parent : intersect(parent);
    }
    public ToolScope intersect(ToolScope other) {
        if (other == null || kind == INHERIT || other.kind == INHERIT)
            throw new IllegalArgumentException("Resolve INHERIT before intersection");
        if (kind == NONE || other.kind == NONE) return none();
        if (kind == ALL) return other;
        if (other.kind == ALL) return this;
        Set<String> result = new LinkedHashSet<>(names);
        result.retainAll(other.names);
        return of(result);
    }
    public boolean allows(String name) {
        return name != null && (kind == ALL || kind == SET && names.contains(name));
    }
    public boolean isAll() { return kind == ALL; }
    public boolean isEmpty() { return kind == NONE; }
    public boolean isInherit() { return kind == INHERIT; }
    public Set<String> names() { return names; }
    public Set<String> schemaFilter() { return kind == ALL ? null : names; }
    public String fingerprint() { return ToolRegistry.sha256(kind + ":" + names.toString()); }
    public String label() {
        return kind == ALL ? "All Tools" : kind == INHERIT ? "Succession (to be resolved)"
                : kind == NONE ? "No tool enabled" : "Enabled " + names.size() + " A tool";
    }
    public JSONObject toJson() {
        try {
            return new JSONObject().put("version", 1).put("mode",
                    kind == ALL ? "all" : kind == NONE ? "none" : kind == SET ? "set" : "inherit")
                    .put("names", new JSONArray(names));
        } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    public static ToolScope fromJson(JSONObject json) {
        if (json == null || !(json.opt("version") instanceof Number)
                || ((Number) json.opt("version")).doubleValue() != 1)
            throw new IllegalArgumentException("Unsupported tool scope version");
        java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!"version".equals(key) && !"mode".equals(key) && !"names".equals(key))
                throw new IllegalArgumentException("Unknown tool scope field: " + key);
        }
        if (!(json.opt("mode") instanceof String)) throw new IllegalArgumentException("Invalid scope mode");
        String mode = json.optString("mode", "");
        JSONArray array = json.optJSONArray("names");
        if (array == null) throw new IllegalArgumentException("Missing scope names");
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (!(value instanceof String) || !names.add((String) value))
                throw new IllegalArgumentException("Invalid/duplicate scope name");
        }
        if ("set".equals(mode)) return of(names);
        if (!names.isEmpty()) throw new IllegalArgumentException("Names require SET");
        if ("all".equals(mode)) return all();
        if ("none".equals(mode)) return none();
        if ("inherit".equals(mode)) return inherit();
        throw new IllegalArgumentException("Unknown scope mode: " + mode);
    }
    /** Historical 0.19.1 built-ins, deliberately frozen; future registrations never join. */
    public static ToolScope legacy0191() {
        Set<String> values = new LinkedHashSet<>();
        Collections.addAll(values, ("list_files read_file write_file list_apps open_app ui_tap ui_set_text "
                + "ui_scroll system_action ui_dump screenshot termux_run self_check build_apk install_apk "
                + "update_plan glob grep edit_file move_file delete_file spawn_subagent list_checkpoints "
                + "restore_checkpoint clipboard_read clipboard_write web_fetch remember list_skills read_skill "
                + "search_knowledge mcp_discover repo_index repo_search repo_map apply_patch").split(" "));
        return of(values);
    }
}
