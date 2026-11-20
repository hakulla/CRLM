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

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Agent Backstage bridge service: permanent listening agent-bridge/inbox. 
 * App Whether to send automatically by App (a) Control of its own;Agent The incoming request is automatically processed and returned without user access.
 */
public class BridgeAutoService extends Service {
    private static final String CHANNEL_ID = "agent_bridge";
    private static final int NOTIFICATION_ID = 4031;

    private volatile boolean running;
    private Thread loop;

    static void start(Context context) {
        Intent intent = new Intent(context, BridgeAutoService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable ignored) {
        }
    }

    static void stop(Context context) {
        try {
            context.stopService(new Intent(context, BridgeAutoService.class));
        } catch (Throwable ignored) {
        }
    }

    static File bridgeDir(Context context) {
        try {
            File base = new Workspace(context).baseRoot();
            return new File(base, "agent-bridge");
        } catch (Throwable e) {
            return new File("/storage/emulated/0/MobileAgent/agent-bridge");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Agent Bridge", NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("Automatically handle other APP Pass. Agent The request from the bridge.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("We're listening to the bridge."));
        if (new AppPrefs(this).debugBridgeEnabled()) {
            DebugBridge.prepare(this);
        }
        ensureLoop();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
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
        }, "agent-bridge-loop");
        loop.setDaemon(true);
        loop.start();
    }

    private void pollLoop() {
        long lastDebugState = 0L;
        while (running) {
            int handled = 0;
            boolean debugEnabled = false;
            try {
                AppPrefs prefs = new AppPrefs(this);
                boolean bridgeEnabled = prefs.bridgeAutoEnabled();
                debugEnabled = prefs.debugBridgeEnabled();
                if (!bridgeEnabled && !debugEnabled) {
                    break;
                }
                long now = System.currentTimeMillis();
                if (debugEnabled && now - lastDebugState > 1200L) {
                    DebugBridge.writeState(this);
                    lastDebugState = now;
                }
                File inbox = new File(bridgeDir(this), "inbox");
                File[] files = inbox.listFiles();
                if (files != null && files.length > 0) {
                    Arrays.sort(files, new Comparator<File>() {
                        @Override
                        public int compare(File a, File b) {
                            return a.getName().compareTo(b.getName());
                        }
                    });
                    for (File file : files) {
                        if (!running) {
                            break;
                        }
                        String name = file.getName();
                        if (!name.endsWith(".json") || !file.isFile()) {
                            continue;
                        }
                        boolean debugRequest = BridgeWorker.isDebugRequest(file);
                        if (!debugRequest && !bridgeEnabled) {
                            continue;
                        }
                        String app = debugRequest ? "debug" : BridgeWorker.appOf(file);
                        if (!debugRequest) {
                            prefs.noteBridgeApp(app);
                        }
                        String source = BridgeWorker.handle(this, file);
                        handled++;
                        if (!"debug".equals(source)) {
                            notifyText("The bridge request is automatically processed:" + name.replace(".json", "")
                                    + "(" + source + ")");
                        }
                        android.util.Log.i("AgentBridge", "handled app=" + app
                                + " file=" + name + " source=" + source);
                    }
                }
            } catch (Throwable ignored) {
            }
            if (!running) {
                break;
            }
            try {
                Thread.sleep(handled > 0 ? 300L : (debugEnabled ? 600L : 2000L));
            } catch (InterruptedException e) {
                break;
            }
        }
        stopForeground(true);
        stopSelf();
    }

    private void notifyText(String text) {
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification(text));
            }
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Agent Bridges running.")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openIntent);
        return builder.build();
    }
}
