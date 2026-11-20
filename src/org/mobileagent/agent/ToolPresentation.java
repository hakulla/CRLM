package org.mobileagent.agent;

import org.json.JSONObject;

/** Pure presentation metadata for tool rows; no Activity or view access. */
final class ToolPresentation {
    private ToolPresentation() {
    }

    static String label(String name) {
        if ("inspect".equals(name)) return "View";
        if ("edit".equals(name)) return "Edit";
        if ("run".equals(name)) return "Execute the command.";
        if ("ask".equals(name)) return "Ask you.";
        if ("delegate".equals(name)) return "Sub-inquiries";
        if ("tool_search".equals(name)) return "Search Tool";
        if ("tool_load".equals(name)) return "Loading Tool";
        if ("update_plan".equals(name)) return "Update the plan";
        if ("glob".equals(name)) return "Find Files";
        if ("grep".equals(name)) return "Search Code";
        if ("edit_file".equals(name)) return "Edit File";
        if ("move_file".equals(name)) return "Move File";
        if ("delete_file".equals(name)) return "Delete File";
        if ("spawn_subagent".equals(name)) return "Sub-inquiries";
        if ("list_checkpoints".equals(name)) return "View Checkpoints";
        if ("restore_checkpoint".equals(name)) return "Restore Checkpoint";
        if ("clipboard_read".equals(name)) return "Read Clipboard";
        if ("clipboard_write".equals(name)) return "Writing Clipboard";
        if ("web_fetch".equals(name)) return "Fetch Web Page";
        if ("web_search".equals(name)) return "Search Web";
        if ("image_search".equals(name)) return "Search for pictures";
        if ("web_browser_read".equals(name)) return "Read the web page with the browser";
        if ("termux_run".equals(name)) return "Execute the command.";
        if ("build_apk".equals(name)) return "Build APK";
        if ("install_apk".equals(name)) return "Install APK";
        if ("list_files".equals(name)) return "List Files";
        if ("read_file".equals(name)) return "Read Files";
        if ("write_file".equals(name)) return "Writing files";
        if ("list_apps".equals(name)) return "Search applications";
        if ("open_app".equals(name)) return "Open Application";
        if ("ui_dump".equals(name)) return "Read Interface";
        if ("ui_tap".equals(name)) return "Click Interface";
        if ("ui_set_text".equals(name)) return "Enter Text";
        if ("ui_scroll".equals(name)) return "Slide Interface";
        if ("system_action".equals(name)) return "System Operations";
        if ("screenshot".equals(name)) return "Screenshot";
        if ("view_image".equals(name)) return "View Pictures";
        if ("browser_open".equals(name)) return "Open Internal Browser";
        if ("browser_screenshot".equals(name)) return "Intercept Web Image";
        if ("browser_eval".equals(name)) return "Execute Web Page JS";
        if ("remember".equals(name)) return "Writing Memory";
        if ("list_skills".equals(name)) return "View skills";
        if ("read_skill".equals(name)) return "Learning skills";
        if ("search_knowledge".equals(name)) return "Retrieving the knowledge base";
        if (name.startsWith("mcp_")) return "MCP · " + name.substring(4);
        return name;
    }

    /** Action label that can depend on the call arguments (inspect/edit modes). */
    static String label(String name, JSONObject args) {
        String mode = args == null ? "" : args.optString("mode", "").trim().toLowerCase();
        if ("inspect".equals(name)) {
            if ("list".equals(mode)) return "List Tables";
            if ("search".equals(mode)) return "Search Code";
            if ("glob".equals(mode)) return "Find Files";
            return "Read Files";
        }
        if ("edit".equals(name)) {
            return "write".equals(mode) ? "Writing files" : "Edit File";
        }
        return label(name);
    }

    static String icon(String name) {
        if ("inspect".equals(name) || "edit".equals(name)) return "ic_file";
        if ("run".equals(name)) return "ic_terminal";
        if ("ask".equals(name) || "delegate".equals(name)
                || "tool_search".equals(name) || "tool_load".equals(name)) {
            return "ic_sparkle";
        }
        if ("update_plan".equals(name)) return "ic_grid";
        if ("glob".equals(name) || "grep".equals(name) || "edit_file".equals(name)
                || "clipboard_read".equals(name) || "clipboard_write".equals(name)
                || "web_fetch".equals(name) || "web_search".equals(name)
                || "image_search".equals(name) || "web_browser_read".equals(name)
                || "move_file".equals(name)
                || "delete_file".equals(name)) {
            return "ic_file";
        }
        if ("spawn_subagent".equals(name)) return "ic_sparkle";
        if ("list_checkpoints".equals(name) || "restore_checkpoint".equals(name)) {
            return "ic_archive";
        }
        if ("termux_run".equals(name) || "build_apk".equals(name)
                || "install_apk".equals(name)) {
            return "ic_terminal";
        }
        if ("list_files".equals(name) || "read_file".equals(name)
                || "write_file".equals(name)) {
            return "ic_file";
        }
        if (name.startsWith("ui_") || "open_app".equals(name)
                || "list_apps".equals(name) || "system_action".equals(name)) {
            return "ic_device";
        }
        return "ic_sparkle";
    }

    static String argumentSummary(String name, JSONObject args) {
        if (args == null) {
            return "";
        }
        String[] keys;
        if ("inspect".equals(name)) {
            String mode = args.optString("mode", "read").trim().toLowerCase();
            if ("search".equals(mode)) {
                keys = new String[]{"query", "pattern", "path"};
            } else if ("glob".equals(mode)) {
                keys = new String[]{"pattern", "path"};
            } else {
                keys = new String[]{"path"};
            }
        } else if ("edit".equals(name)) {
            keys = new String[]{"path"};
        } else if ("run".equals(name)) {
            keys = new String[]{"command"};
        } else if ("ask".equals(name)) {
            keys = new String[]{"question"};
        } else if ("delegate".equals(name)) {
            keys = new String[]{"task"};
        } else if ("tool_search".equals(name)) {
            keys = new String[]{"query"};
        } else if ("tool_load".equals(name)) {
            keys = new String[]{"name"};
        } else if ("termux_run".equals(name)) {
            keys = new String[]{"command"};
        } else if ("build_apk".equals(name)) {
            keys = new String[]{"project"};
        } else if ("glob".equals(name)) {
            keys = new String[]{"pattern"};
        } else if ("grep".equals(name)) {
            keys = new String[]{"query"};
        } else if ("edit_file".equals(name) || "delete_file".equals(name)) {
            keys = new String[]{"path"};
        } else if ("move_file".equals(name)) {
            keys = new String[]{"from", "to"};
        } else if ("spawn_subagent".equals(name)) {
            keys = new String[]{"task"};
        } else if ("restore_checkpoint".equals(name)) {
            keys = new String[]{"id"};
        } else if ("clipboard_write".equals(name)) {
            keys = new String[]{"text"};
        } else if ("web_fetch".equals(name)) {
            keys = new String[]{"url"};
        } else if ("web_search".equals(name)) {
            keys = new String[]{"query"};
        } else if ("web_browser_read".equals(name)) {
            keys = new String[]{"url"};
        } else if ("read_file".equals(name) || "write_file".equals(name)
                || "list_files".equals(name) || "install_apk".equals(name)) {
            keys = new String[]{"path"};
        } else if ("open_app".equals(name) || "list_apps".equals(name)) {
            keys = new String[]{"query"};
        } else if ("ui_tap".equals(name) || "ui_set_text".equals(name)) {
            keys = new String[]{"target", "text"};
        } else {
            keys = new String[]{"name", "query", "text", "project"};
        }
        for (String key : keys) {
            String value = args.optString(key, "").trim();
            if (!value.isEmpty()) {
                if ("path".equals(key)) {
                    value = shortPath(value);
                }
                value = value.replace('\n', ' ');
                return value.length() <= 72 ? value : value.substring(0, 72) + "...";
            }
        }
        return "";
    }

    /** Step rows show the file name; the full path stays in the detail sheet. */
    private static String shortPath(String path) {
        String value = path == null ? "" : path.replace('\\', '/').trim();
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        int slash = value.lastIndexOf('/');
        String name = slash >= 0 ? value.substring(slash + 1) : value;
        return name.isEmpty() ? value : name;
    }
}
