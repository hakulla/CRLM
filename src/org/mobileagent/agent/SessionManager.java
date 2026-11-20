package org.mobileagent.agent;

import android.content.Context;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One UI engine per conversation. Background engines keep running when the user
 * switches to another conversation, which is what enables concurrent tasks.
 */
final class SessionManager {
    private static SessionManager instance;

    private final Context appContext;
    private final Map<String, AgentEngine> engines = new LinkedHashMap<>();

    private SessionManager(Context context) {
        appContext = context.getApplicationContext();
    }

    static synchronized SessionManager get(Context context) {
        if (instance == null) {
            instance = new SessionManager(context);
        }
        return instance;
    }

    synchronized void register(AgentEngine engine) {
        if (engine == null) {
            return;
        }
        String id = engine.currentConversationId();
        if (id != null && !id.isEmpty()) {
            engines.put(id, engine);
        }
    }

    synchronized AgentEngine get(String id) {
        return id == null ? null : engines.get(id);
    }

    synchronized AgentEngine open(Conversation conversation) {
        if (conversation == null || conversation.id == null) {
            return null;
        }
        AgentEngine existing = engines.get(conversation.id);
        if (existing != null) {
            return existing;
        }
        AgentEngine created = AgentEngine.forSession(appContext, conversation);
        engines.put(conversation.id, created);
        return created;
    }

    synchronized AgentEngine create(AppPrefs prefs, Conversation template) {
        Conversation created = ConversationStore.shared(appContext).create(prefs, template);
        AgentEngine engine = AgentEngine.forSession(appContext, created);
        engines.put(created.id, engine);
        return engine;
    }

    synchronized boolean otherRunning(String exceptId) {
        for (Map.Entry<String, AgentEngine> entry : engines.entrySet()) {
            if (exceptId != null && exceptId.equals(entry.getKey())) {
                continue;
            }
            if (entry.getValue().isRunning()) {
                return true;
            }
        }
        return false;
    }

    synchronized int runningCount() {
        int count = 0;
        for (AgentEngine engine : engines.values()) {
            if (engine.isRunning()) {
                count++;
            }
        }
        return count;
    }

    synchronized void stop(String id) {
        AgentEngine engine = engines.remove(id);
        if (engine != null) {
            engine.setListener(null);
            engine.requestStop();
        }
    }

    synchronized void remove(String id) {
        engines.remove(id);
    }

    synchronized void stopAll() {
        for (AgentEngine engine : engines.values()) {
            engine.setListener(null);
            engine.requestStop();
        }
        engines.clear();
    }
}
