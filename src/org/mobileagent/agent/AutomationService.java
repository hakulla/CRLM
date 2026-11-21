package org.mobileagent.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.util.List;

/** Foreground scheduler: runs due automations through a private headless AgentEngine. */
public class AutomationService extends Service {
    static final String ACTION_RUN_NOW = "org.mobileagent.agent.AUTOMATION_RUN_NOW";
    static final String ACTION_STOP = "org.mobileagent.agent.AUTOMATION_STOP";
    static final String EXTRA_TASK_ID = "task_id";
    private static final String CHANNEL_ID = "automation";
    private static final String DONE_CHANNEL_ID = "automation_done";
    private static final int FGS_ID = 4041;
    private static final int DONE_ID = 4042;
    private static final long MAX_RUN_MS = 20L * 60L * 1000L;

    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile String pendingRunId;
    private volatile AgentEngine activeEngine;
    private volatile String activeTaskId;
    private Thread loop;

    static void start(Context context) {
        Intent intent = new Intent(context, AutomationService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable ignored) {
        }
    }

    static void startIfEnabled(Context context) {
        if (AutomationStore.get(context).hasEnabled()) {
            start(context);
        }
    }

    static void runNow(Context context, String taskId) {
        Intent intent = new Intent(context, AutomationService.class);
        intent.setAction(ACTION_RUN_NOW);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable ignored) {
        }
    }

    static void stopCurrent(Context context) {
        Intent intent = new Intent(context, AutomationService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "Automation", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Run as planned Agent Automation missions");
                manager.createNotificationChannel(channel);
                NotificationChannel done = new NotificationChannel(
                        DONE_CHANNEL_ID, "Automation results", NotificationManager.IMPORTANCE_DEFAULT);
                done.setDescription("Automation Task Completion or Failed Reminder");
                manager.createNotificationChannel(done);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(FGS_ID, buildNotification("Automated services are in operation", null, true));
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_RUN_NOW.equals(action)) {
                pendingRunId = intent.getStringExtra(EXTRA_TASK_ID);
            } else if (ACTION_STOP.equals(action)) {
                stopRequested = true;
                AgentEngine engine = activeEngine;
                if (engine != null) {
                    engine.requestStop();
                }
            }
        }
        ensureLoop();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        stopRequested = true;
        AgentEngine engine = activeEngine;
        if (engine != null) {
            engine.requestStop();
        }
        Thread current = loop;
        loop = null;
        if (current != null) {
            current.interrupt();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureLoop() {
        if (loop != null) {
            return;
        }
        running = true;
        loop = new Thread(new Runnable() {
            @Override
            public void run() {
                pollLoop();
            }
        }, "mobileagent-automation");
        loop.setDaemon(true);
        loop.start();
    }

    private void pollLoop() {
        while (running) {
            AutomationStore store = AutomationStore.get(this);
            try {
                long now = System.currentTimeMillis();
                store.normalizeSchedules(now);
                if (!store.hasEnabled() && pendingRunId == null && activeEngine == null) {
                    break;
                }
                String requested = pendingRunId;
                pendingRunId = null;
                AutomationStore.Task task = requested == null ? null : store.find(requested);
                if (task == null) {
                    List<AutomationStore.Task> due = store.due(now);
                    if (!due.isEmpty()) {
                        task = due.get(0);
                    }
                }
                if (task != null) {
                    execute(store, task);
                }
            } catch (Throwable error) {
                android.util.Log.w("AgentAutomation", "automation tick failed", error);
            }
            if (!running) {
                break;
            }
            try {
                Thread.sleep(15000L);
            } catch (InterruptedException e) {
                break;
            }
        }
        stopForeground(true);
        stopSelf();
    }

    private void execute(AutomationStore store, AutomationStore.Task task) {
        activeTaskId = task.id;
        stopRequested = false;
        long started = System.currentTimeMillis();
        AppPrefs prefs = new AppPrefs(this);
        ConversationStore conversations = new ConversationStore(this);
        Conversation conversation = conversations.create(prefs, null);
        conversation.title = "[Automatic] " + task.name;
        conversation.workspace = task.workspace == null ? "" : task.workspace.trim();
        conversation.approvalMode = task.readOnly
                ? Conversation.APPROVAL_READONLY : Conversation.APPROVAL_AUTO;
        conversation.modelSource = prefs.hasKey()
                ? Conversation.SOURCE_CLOUD : Conversation.SOURCE_LOCAL;
        conversations.save(conversation);
        String runId = store.markRunStarted(task.id, conversation.id);
        notifyFgs("Running:" + task.name, conversation.id);

        AgentEngine engine = null;
        String outcome = "failed";
        String answer = "";
        String error = "";
        long tokens = 0L;
        try {
            engine = AgentEngine.forAutomation(this, conversation);
            activeEngine = engine;
            boolean startedOk = engine.startTask(task.prompt);
            if (!startedOk) {
                error = "Job not started: Check API Key, Local models or tool ranges.";
            } else {
                long deadline = System.currentTimeMillis() + MAX_RUN_MS;
                while (engine.isRunning() && System.currentTimeMillis() < deadline
                        && !stopRequested) {
                    Thread.sleep(500L);
                }
                if (engine.isRunning()) {
                    engine.requestStop();
                    long grace = System.currentTimeMillis() + 10000L;
                    while (engine.isRunning() && System.currentTimeMillis() < grace) {
                        Thread.sleep(300L);
                    }
                }
                outcome = engine.lastRunOutcome();
                if (outcome == null || outcome.isEmpty()) {
                    outcome = "unknown";
                }
                answer = lastAnswer(engine);
                error = engine.lastRunError();
                tokens = engine.lastRunTokens();
            }
        } catch (Throwable failure) {
            error = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            outcome = "failed";
        } finally {
            activeEngine = null;
            activeTaskId = null;
            if (engine != null) {
                engine.shutdown();
            }
        }
        store.finishRun(task.id, runId, outcome, answer, error, tokens);
        notifyDone(task, conversation.id, outcome, answer, error,
                System.currentTimeMillis() - started);
    }

    private String lastAnswer(AgentEngine engine) {
        List<AgentEngine.Entry> entries = engine.getEntries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            AgentEngine.Entry entry = entries.get(i);
            if (entry != null && entry.type == AgentEngine.Entry.ASSISTANT
                    && entry.text != null && !entry.text.trim().isEmpty()) {
                return entry.text.trim();
            }
        }
        return "";
    }

    private void notifyFgs(String text, String conversationId) {
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(FGS_ID, buildNotification(text, conversationId, true));
            }
        } catch (Throwable ignored) {
        }
    }

    private void notifyDone(AutomationStore.Task task, String conversationId,
                            String outcome, String answer, String error, long elapsedMs) {
        try {
            String title = "completed".equals(outcome) ? "Automation completed:" + task.name
                    : "Automation not completed:" + task.name;
            String text = "completed".equals(outcome)
                    ? (answer == null || answer.trim().isEmpty() ? "Task completed" : trim(answer, 160))
                    : trim(error == null || error.trim().isEmpty() ? outcome : error, 160);
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(DONE_ID, buildDoneNotification(title,
                        text + " · " + (elapsedMs / 1000L) + " sec", conversationId));
                manager.notify(FGS_ID, buildNotification(
                        LanguageManager.t(this, "Automation enabled · ") + enabledCount()
                                + LanguageManager.t(this, " Tasks"), null, true));
            }
        } catch (Throwable ignored) {
        }
    }

    private int enabledCount() {
        int count = 0;
        for (AutomationStore.Task task : AutomationStore.get(this).tasks()) {
            if (task.enabled) {
                count++;
            }
        }
        return count;
    }

    private Notification buildNotification(String text, String conversationId, boolean ongoing) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("CRLM Automation")
                .setContentText(text)
                .setOngoing(ongoing)
                .setContentIntent(openIntent(conversationId, 0));
        return builder.build();
    }

    private Notification buildDoneNotification(String title, String text, String conversationId) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, DONE_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openIntent(conversationId, 1));
        return builder.build();
    }

    private PendingIntent openIntent(String conversationId, int requestCode) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (conversationId != null && !conversationId.isEmpty()) {
            open.putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, requestCode, open, flags);
    }

    private static String trim(String text, int max) {
        if (text == null) return "";
        String clean = text.replace('\n', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }
}
