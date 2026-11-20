package org.mobileagent.agent;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Capability pages: agents, skills, knowledge and MCP configuration. */
final class CapabilitiesPage {
    interface Host {
        void toast(CharSequence text);
        void confirm(String title, String message, String action, boolean danger, Runnable run);
        int dp(float value);
        GradientDrawable rounded(int color, float radiusDp);
        ImageView icon(String name, int color);
        LinearLayout.LayoutParams iconSize(int size, int rightMargin);
        void showChoiceSheet(String title, String[] labels, String[] values,
                             String currentValue, AgentKit.ValueRow row, String[] result);
        void showChoiceSheet(String title, String[] labels, String[] values,
                             String currentValue, AgentKit.ValueRow row, String[] result,
                             Runnable after);
        void openBaseEditor(String path);
        void showMemory();
        void showAllowedTools();
    }

    private final Activity activity;
    private final AgentEngine engine;
    private final Host host;

    CapabilitiesPage(Activity activity, AgentEngine engine, Host host) {
        this.activity = activity;
        this.engine = engine;
        this.host = host;
    }

    void showMcp() {
        showMcpDialog();
    }

    void show() {
        final Capabilities caps = new Capabilities(engine.baseWorkspace());
        final Dialog[] holder = new Dialog[1];
        LinearLayout content = AgentKit.column(activity);
        content.setPadding(host.dp(16), host.dp(6), host.dp(16), host.dp(26));
        content.addView(AgentKit.hint(activity,
                        "Rules, Memory, skills and knowledge base are in the workspace. .agent I'm not sure if you're going to be able to do this.Agent will be automatically brought in on the next task."),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));

        content.addView(AgentKit.section(activity, "Dialogue Agent"),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        final List<String> agentNames = new ArrayList<>();
        agentNames.add("Default");
        agentNames.addAll(caps.listAgentNames());
        final String[] agentValues = new String[agentNames.size()];
        agentValues[0] = "";
        for (int i = 1; i < agentNames.size(); i++) {
            agentValues[i] = agentNames.get(i);
        }
        final String[] agentChoice = {engine.currentConversation() == null ? ""
                : engine.currentConversation().agentProfile};
        final AgentKit.ValueRow agentRow = AgentKit.selectRow(activity, "Current Agent",
                agentChoice[0].isEmpty() ? "Default" : agentChoice[0], null);
        final Button editAgent = AgentKit.secondary(activity, "Edit Agent JSON");
        agentRow.view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.showChoiceSheet("Selection Agent", agentNames.toArray(new String[0]),
                        agentValues, agentChoice[0], agentRow, agentChoice,
                        new Runnable() {
                            @Override
                            public void run() {
                                engine.setAgentProfile(agentChoice[0]);
                                editAgent.setVisibility(agentChoice[0].isEmpty()
                                        ? View.GONE : View.VISIBLE);
                            }
                        });
            }
        });
        content.addView(agentRow.view, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        LinearLayout agentActions = AgentKit.row(activity);
        agentActions.setPadding(0, host.dp(8), 0, 0);
        Button newAgent = AgentKit.secondary(activity, "New Agent");
        newAgent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNewAgentDialog(caps, holder[0]);
            }
        });
        agentActions.addView(newAgent, new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP));
        editAgent.setVisibility(agentChoice[0].isEmpty() ? View.GONE : View.VISIBLE);
        editAgent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String path = caps.agentFilePath(agentChoice[0]);
                if (!path.isEmpty()) {
                    host.openBaseEditor(path);
                }
            }
        });
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP);
        editParams.setMargins(host.dp(8), 0, 0, 0);
        agentActions.addView(editAgent, editParams);
        content.addView(agentActions, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        content.addView(AgentKit.hint(activity,
                        "Agent JSON Tools available for limiting;default Agent Use all tools."),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));

        content.addView(AgentKit.section(activity, "Rules and memories"),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        LinearLayout rulesRow = AgentKit.row(activity);
        Button rules = AgentKit.secondary(activity, "Edit Rules");
        rules.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.openBaseEditor(Capabilities.RULES_PATH);
            }
        });
        rulesRow.addView(rules, new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP));
        Button memory = AgentKit.secondary(activity, "Long-term memory");
        LinearLayout.LayoutParams memoryParams = new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP);
        memoryParams.setMargins(host.dp(8), 0, 0, 0);
        rulesRow.addView(memory, memoryParams);
        memory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.showMemory();
            }
        });
        content.addView(rulesRow, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        content.addView(AgentKit.hint(activity,
                        "Rules Support .agent/rules.md, .agent/rules/*.md and root directory AGENTS.md. "),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));

        content.addView(AgentKit.section(activity, "Capacity"),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        final AgentKit.ValueRow skillsRow = AgentKit.selectRow(activity, "Skills",
                caps.listSkills().size() + " Skills", null);
        skillsRow.view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSkillsDialog(caps);
            }
        });
        content.addView(skillsRow.view, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        final AgentKit.ValueRow knowledgeRow = AgentKit.selectRow(activity, "Local Knowledge Bank",
                caps.listKnowledgeFiles().size() + " File", null);
        knowledgeRow.view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showKnowledgeDialog(caps);
            }
        });
        LinearLayout.LayoutParams knowledgeParams =
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP);
        knowledgeParams.setMargins(0, host.dp(6), 0, 0);
        content.addView(knowledgeRow.view, knowledgeParams);
        final McpManager mcp = new McpManager(activity);
        final AgentKit.ValueRow mcpRow = AgentKit.selectRow(activity, "MCP Services",
                mcp.loadServers().size() + " Services", null);
        mcpRow.view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMcpDialog();
            }
        });
        LinearLayout.LayoutParams mcpParams = new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP);
        mcpParams.setMargins(0, host.dp(6), 0, 0);
        content.addView(mcpRow.view, mcpParams);

        content.addView(AgentKit.section(activity, "Security and context"),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        final AgentKit.ValueRow allowedRow = AgentKit.selectRow(activity, "Tool Authorization",
                engine.alwaysAllowedTools().size() + " Always allowed.", null);
        allowedRow.view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.showAllowedTools();
            }
        });
        content.addView(allowedRow.view, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        final AgentEngine.ContextInfo context = engine.contextInfo();
        final AgentKit.ValueRow contextRow = AgentKit.selectRow(activity, "Context",
                context.percent + "% · Click Compression", null);
        contextRow.view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String result = engine.compactNow();
                host.toast(result);
                contextRow.setValue(engine.contextInfo().percent + "% · Click Compression");
            }
        });
        LinearLayout.LayoutParams contextParams = new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP);
        contextParams.setMargins(0, host.dp(6), 0, 0);
        content.addView(contextRow.view, contextParams);
        content.addView(AgentKit.hint(activity,
                        "MCP Tools will be used mcp_ name appears; sub-agents can only read, and cannot be modified."),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        holder[0] = AgentKit.page(activity, "Capabilities and memory", scroll);
    }

    private void showNewAgentDialog(final Capabilities caps, final Dialog parent) {
        LinearLayout content = AgentKit.column(activity);
        content.setPadding(host.dp(18), 0, host.dp(18), 0);
        final EditText nameInput = AgentKit.field(activity, "", "Agent Name, for example reviewer", false);
        final EditText instructionsInput = AgentKit.field(activity, "",
                "This. Agent Roles, ways and constraints of doing business...", true);
        content.addView(nameInput, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        content.addView(AgentKit.label(activity, "Instructions"),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        content.addView(instructionsInput, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        content.addView(AgentKit.hint(activity,
                        "The name will appear in the Current Agent'lists;to continue editing after creating JSON Limit tool."),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        LinearLayout actions = AgentKit.row(activity);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, host.dp(16), 0, 0);
        final Dialog[] holder = new Dialog[1];
        Button cancel = AgentKit.secondary(activity, "Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP));
        Button create = AgentKit.primary(activity, "Create");
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP);
        createParams.setMargins(host.dp(8), 0, 0, 0);
        actions.addView(create, createParams);
        create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = nameInput.getText().toString().trim();
                String instructions = instructionsInput.getText().toString().trim();
                if (name.isEmpty()) {
                    host.toast("Please fill in your name.");
                    return;
                }
                String path = caps.createAgent(name, instructions);
                if (path == null) {
                    host.toast("Create failed or name already exists");
                    return;
                }
                engine.setAgentProfile(name);
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                if (parent != null) {
                    parent.dismiss();
                }
                show();
                host.toast(
                        "Created and selected Agent: " + name);
            }
        });
        content.addView(actions, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        holder[0] = AgentKit.sheet(activity, "New Agent", content);
        Window window = holder[0].getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        nameInput.requestFocus();
    }

    private void showSkillsDialog(final Capabilities caps) {
        LinearLayout content = AgentKit.column(activity);
        content.setPadding(host.dp(16), host.dp(6), host.dp(16), host.dp(22));
        content.addView(AgentKit.hint(activity,
                        "Skills are on demand. SKILL.md. Agent Read the full text when necessary, save the context."),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        final Dialog[] holder = new Dialog[1];
        Button create = AgentKit.primary(activity, "New Skills");
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP);
        createParams.setMargins(0, host.dp(10), 0, host.dp(4));
        content.addView(create, createParams);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = AgentKit.column(activity);
        scroll.addView(list, new ScrollView.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        content.addView(scroll, new LinearLayout.LayoutParams(MainActivity.MATCH, 0, 1f));

        List<Capabilities.SkillInfo> skills = caps.listSkills();
        if (skills.isEmpty()) {
            list.addView(AgentKit.hint(activity,
                            "No skills. Dots 'New skills' will be created. SKILL.md template."),
                    new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        } else {
            for (final Capabilities.SkillInfo skill : skills) {
                LinearLayout row = AgentKit.row(activity);
                row.setBackground(host.rounded(MainActivity.CARD, 14));
                row.setPadding(host.dp(12), host.dp(11), host.dp(10), host.dp(11));
                row.setClickable(true);
                row.setFocusable(true);
                row.addView(host.icon("ic_sparkle", MainActivity.MUTED), host.iconSize(host.dp(18), host.dp(10)));
                LinearLayout info = AgentKit.column(activity);
                info.addView(AgentKit.title(activity, skill.name, 12.5f),
                        new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
                TextView desc = AgentKit.hint(activity, skill.description.isEmpty()
                        ? "(No description)" : skill.description);
                info.addView(desc, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
                row.addView(info, new LinearLayout.LayoutParams(0, MainActivity.WRAP, 1f));
                row.addView(host.icon("ic_chevron_right", MainActivity.DIM),
                        host.iconSize(host.dp(16), 0));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        host.openBaseEditor(skill.filePath);
                    }
                });
                LinearLayout.LayoutParams rowParams =
                        new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP);
                rowParams.setMargins(0, host.dp(6), 0, 0);
                list.addView(row, rowParams);
            }
        }

        LinearLayout footer = AgentKit.row(activity);
        footer.setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(16));
        Button close = AgentKit.secondary(activity, "Close");
        footer.addView(close, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        holder[0] = AgentKit.page(activity, "Skills", content, footer);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNewSkillDialog(caps, holder[0]);
            }
        });
    }

    private void showNewSkillDialog(final Capabilities caps, final Dialog parent) {
        LinearLayout content = AgentKit.column(activity);
        content.setPadding(host.dp(18), 0, host.dp(18), 0);
        final EditText nameInput = AgentKit.field(activity, "",
                "Name of skill, e.g. pdf-summary", false);
        final EditText descInput = AgentKit.field(activity, "",
                "What do you want to describe this skill for?...", true);
        content.addView(nameInput, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        content.addView(AgentKit.label(activity, "Description"),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        content.addView(descInput, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        content.addView(AgentKit.hint(activity,
                        "Open in editor after creation SKILL.md, Effective immediately after storage."),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        LinearLayout actions = AgentKit.row(activity);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, host.dp(16), 0, 0);
        final Dialog[] holder = new Dialog[1];
        Button cancel = AgentKit.secondary(activity, "Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP));
        Button create = AgentKit.primary(activity, "Create");
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP);
        createParams.setMargins(host.dp(8), 0, 0, 0);
        actions.addView(create, createParams);
        create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = nameInput.getText().toString().trim();
                String description = descInput.getText().toString().trim();
                if (name.isEmpty()) {
                    host.toast("Please fill out your skills.");
                    return;
                }
                String path = caps.createSkill(name, description);
                if (path == null) {
                    host.toast("Create failed, name may already exist");
                    return;
                }
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                if (parent != null) {
                    parent.dismiss();
                }
                host.openBaseEditor(path);
            }
        });
        content.addView(actions, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        holder[0] = AgentKit.sheet(activity, "New Skills", content);
        Window window = holder[0].getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        nameInput.requestFocus();
    }

    private void showKnowledgeDialog(final Capabilities caps) {
        LinearLayout content = AgentKit.column(activity);
        content.setPadding(host.dp(16), host.dp(6), host.dp(16), host.dp(22));
        content.addView(AgentKit.hint(activity,
                        "Agent Yes, it will. search_knowledge Search .agent/knowledge Medium .md/.txt Documentation."),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        final Dialog[] holder = new Dialog[1];
        Button create = AgentKit.primary(activity, "New Knowledge File");
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP);
        createParams.setMargins(0, host.dp(10), 0, host.dp(4));
        content.addView(create, createParams);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = AgentKit.column(activity);
        scroll.addView(list, new ScrollView.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        content.addView(scroll, new LinearLayout.LayoutParams(MainActivity.MATCH, 0, 1f));

        List<String> files = caps.listKnowledgeFiles();
        if (files.isEmpty()) {
            list.addView(AgentKit.hint(activity,
                            "The knowledge base is empty."),
                    new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        } else {
            for (final String path : files) {
                LinearLayout row = AgentKit.row(activity);
                row.setBackground(host.rounded(MainActivity.CARD, 14));
                row.setPadding(host.dp(12), host.dp(12), host.dp(10), host.dp(12));
                row.setClickable(true);
                row.setFocusable(true);
                row.addView(host.icon("ic_file", MainActivity.MUTED), host.iconSize(host.dp(18), host.dp(10)));
                TextView name = new TextView(activity);
                name.setText(path);
                name.setTextColor(MainActivity.TEXT);
                name.setTextSize(12.5f);
                row.addView(name, new LinearLayout.LayoutParams(0, MainActivity.WRAP, 1f));
                row.addView(host.icon("ic_chevron_right", MainActivity.DIM), host.iconSize(host.dp(16), 0));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        host.openBaseEditor(path);
                    }
                });
                LinearLayout.LayoutParams rowParams =
                        new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP);
                rowParams.setMargins(0, host.dp(6), 0, 0);
                list.addView(row, rowParams);
            }
        }

        LinearLayout footer = AgentKit.row(activity);
        footer.setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(16));
        Button close = AgentKit.secondary(activity, "Close");
        footer.addView(close, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        holder[0] = AgentKit.page(activity, "Local Knowledge Bank", content, footer);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNewKnowledgeDialog(caps, holder[0]);
            }
        });
    }

    private void showNewKnowledgeDialog(final Capabilities caps, final Dialog parent) {
        LinearLayout content = AgentKit.column(activity);
        content.setPadding(host.dp(18), 0, host.dp(18), 0);
        final EditText nameInput = AgentKit.field(activity, "",
                "File name, e.g. notes.md", false);
        content.addView(nameInput, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        content.addView(AgentKit.hint(activity,
                        "Creates the file, which is opened in the editor, and is then retrieved directly after saving."),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        LinearLayout actions = AgentKit.row(activity);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, host.dp(16), 0, 0);
        final Dialog[] holder = new Dialog[1];
        Button cancel = AgentKit.secondary(activity, "Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP));
        Button create = AgentKit.primary(activity, "Create");
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP);
        createParams.setMargins(host.dp(8), 0, 0, 0);
        actions.addView(create, createParams);
        create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = nameInput.getText().toString().trim();
                if (name.isEmpty()) {
                    host.toast("Please fill in the file name.");
                    return;
                }
                String path = caps.createKnowledge(name, "");
                if (path == null) {
                    host.toast("Failed to create");
                    return;
                }
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                if (parent != null) {
                    parent.dismiss();
                }
                host.openBaseEditor(path);
            }
        });
        content.addView(actions, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        holder[0] = AgentKit.sheet(activity, "New Knowledge File", content);
        Window window = holder[0].getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        nameInput.requestFocus();
    }

    private void showMcpDialog() {
        final McpManager manager = new McpManager(activity);
        LinearLayout content = AgentKit.column(activity);
        content.setPadding(host.dp(16), host.dp(6), host.dp(16), host.dp(22));
        content.addView(AgentKit.hint(activity,
                        "Support Streamable HTTP It's... MCP Service. The Internet is available. https; Available on-site "
                                + "http://127.0.0.1(Complement adb reverse or Termux). Token Only in the application of private directories."),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        final Dialog[] holder = new Dialog[1];
        Button add = AgentKit.primary(activity, "Add MCP Services");
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP);
        addParams.setMargins(0, host.dp(10), 0, host.dp(4));
        content.addView(add, addParams);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = AgentKit.column(activity);
        scroll.addView(list, new ScrollView.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        content.addView(scroll, new LinearLayout.LayoutParams(MainActivity.MATCH, 0, 1f));

        List<McpManager.Server> servers = manager.loadServers();
        if (servers.isEmpty()) {
            list.addView(AgentKit.hint(activity,
                            "Not yet. MCP service. Add the tool to the next task."),
                    new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        } else {
            for (final McpManager.Server server : servers) {
                LinearLayout row = AgentKit.row(activity);
                row.setBackground(host.rounded(MainActivity.CARD, 14));
                row.setPadding(host.dp(12), host.dp(11), host.dp(10), host.dp(11));
                row.setClickable(true);
                row.setFocusable(true);
                row.addView(host.icon("ic_terminal", MainActivity.MUTED), host.iconSize(host.dp(18), host.dp(10)));
                LinearLayout info = AgentKit.column(activity);
                LinearLayout titleRow = AgentKit.row(activity);
                titleRow.addView(AgentKit.title(activity, server.name, 12.5f),
                        new LinearLayout.LayoutParams(0, MainActivity.WRAP, 1f));
                TextView state = AgentKit.hint(activity, server.enabled ? "Enabled" : "Disabled");
                state.setTextColor(server.enabled ? MainActivity.SUCCESS : MainActivity.DIM);
                state.setTextSize(10f);
                titleRow.addView(state, new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP));
                info.addView(titleRow, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
                info.addView(AgentKit.hint(activity, server.url),
                        new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
                row.addView(info, new LinearLayout.LayoutParams(0, MainActivity.WRAP, 1f));
                row.addView(host.icon("ic_chevron_right", MainActivity.DIM), host.iconSize(host.dp(16), 0));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showMcpEditDialog(manager, server, holder[0]);
                    }
                });
                LinearLayout.LayoutParams rowParams =
                        new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP);
                rowParams.setMargins(0, host.dp(6), 0, 0);
                list.addView(row, rowParams);
            }
        }

        LinearLayout footer = AgentKit.row(activity);
        footer.setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(16));
        Button close = AgentKit.secondary(activity, "Close");
        footer.addView(close, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        holder[0] = AgentKit.page(activity, "MCP Services", content, footer);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMcpEditDialog(manager, null, holder[0]);
            }
        });
    }

    private void showMcpEditDialog(final McpManager manager,
                                   final McpManager.Server original,
                                   final Dialog listPage) {
        LinearLayout form = AgentKit.column(activity);
        form.setPadding(host.dp(18), 0, host.dp(18), 0);
        final EditText nameInput = AgentKit.field(activity,
                original == null ? "" : original.name, "Name of service, e.g. Mock", false);
        final EditText urlInput = AgentKit.field(activity,
                original == null ? "" : original.url, "https://example.com/mcp", false);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText tokenInput = AgentKit.field(activity,
                original == null ? "" : original.token, "Optional Bearer Token", false);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final boolean[] enabledState = {original == null || original.enabled};

        form.addView(AgentKit.label(activity, "Name"),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        form.addView(nameInput, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        form.addView(AgentKit.label(activity, "URL"),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        form.addView(urlInput, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        form.addView(AgentKit.label(activity, "Token"),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        form.addView(tokenInput, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        form.addView(AgentKit.toggleRow(activity, "Enable this service", "Load tool at start of task",
                        enabledState[0], new AgentKit.ToggleHandler() {
                            @Override
                            public void onToggle(boolean checked) {
                                enabledState[0] = checked;
                            }
                        }),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));

        final Button probe = AgentKit.secondary(activity, "Test Connection");
        LinearLayout.LayoutParams probeParams = new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP);
        probeParams.setMargins(0, host.dp(10), 0, 0);
        form.addView(probe, probeParams);
        form.addView(AgentKit.hint(activity,
                        "The test will be requested immediately. tools/list; This will be saved and reloaded at the beginning of each mission."),
                new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));

        probe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String name = nameInput.getText().toString().trim();
                final String url = urlInput.getText().toString().trim();
                final String token = tokenInput.getText().toString().trim();
                if (url.isEmpty()) {
                    host.toast("Please fill in. URL");
                    return;
                }
                probe.setEnabled(false);
                probe.setText("Connecting...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String result = manager.probe(new McpManager.Server(
                                name.isEmpty() ? "MCP" : name, url, token, true));
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                probe.setEnabled(true);
                                probe.setText("Test Connection");
                                host.toast(result);
                            }
                        });
                    }
                }).start();
            }
        });

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(form, new ScrollView.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        LinearLayout content = AgentKit.column(activity);
        content.addView(scroll, new LinearLayout.LayoutParams(MainActivity.MATCH, host.dp(420)));

        LinearLayout actions = AgentKit.row(activity);
        actions.setPadding(host.dp(18), host.dp(12), host.dp(18), 0);
        final Dialog[] holder = new Dialog[1];
        Button cancel = AgentKit.secondary(activity, "Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP));
        if (original != null) {
            Button delete = AgentKit.danger(activity, "Delete");
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP);
            deleteParams.setMargins(host.dp(8), 0, 0, 0);
            actions.addView(delete, deleteParams);
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    host.confirm("Delete MCP Service?",
                            original.name, "Delete", true, new Runnable() {
                                @Override
                                public void run() {
                                    manager.delete(original.name);
                                    if (holder[0] != null) {
                                        holder[0].dismiss();
                                    }
                                    if (listPage != null) {
                                        listPage.dismiss();
                                    }
                                    showMcpDialog();
                                }
                            });
                }
            });
        }
        Button save = AgentKit.primary(activity, "Save");
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(MainActivity.WRAP, MainActivity.WRAP);
        saveParams.setMargins(host.dp(8), 0, 0, 0);
        actions.addView(save, saveParams);
        content.addView(actions, new LinearLayout.LayoutParams(MainActivity.MATCH, MainActivity.WRAP));
        holder[0] = AgentKit.sheet(activity,
                original == null ? "Add MCP Services" : "Edit MCP Services", content);
        Window window = holder[0].getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        nameInput.requestFocus();
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = nameInput.getText().toString().trim();
                String url = urlInput.getText().toString().trim();
                String token = tokenInput.getText().toString().trim();
                if (name.isEmpty()) {
                    host.toast("Please fill in your name.");
                    return;
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    host.toast("URL I must. http:// or https:// Start");
                    return;
                }
                if (original == null) {
                    for (McpManager.Server existing : manager.loadServers()) {
                        if (existing.name.equalsIgnoreCase(name)) {
                            host.toast("Name Exists");
                            return;
                        }
                    }
                }
                manager.upsert(original == null ? "" : original.name,
                        new McpManager.Server(name, url, token, enabledState[0]));
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                if (listPage != null) {
                    listPage.dismiss();
                }
                showMcpDialog();
            }
        });
    }


}
