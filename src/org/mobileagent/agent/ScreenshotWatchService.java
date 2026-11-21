package org.mobileagent.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.File;

/** Watches MediaStore for screenshots taken while "screenshot mode" is on. */
public class ScreenshotWatchService extends Service {
    static final String ACTION_START = "org.mobileagent.agent.START_SCREENSHOT_WATCH";
    static final String ACTION_STOP = "org.mobileagent.agent.STOP_SCREENSHOT_WATCH";
    static final String ACTION_SCREENSHOT_ADDED =
            "org.mobileagent.agent.SCREENSHOT_ADDED";
    static final String ACTION_STOPPED =
            "org.mobileagent.agent.SCREENSHOT_WATCH_STOPPED";
    private static final String CHANNEL_ID = "screenshot_watch";
    private static final int NOTIFICATION_ID = 4023;
    private static volatile ScreenshotWatchService instance;

    private HandlerThread thread;
    private Handler handler;
    private ContentObserver observer;
    private long startedAt;
    private long lastSeenId = -1L;
    private int addedCount;

    static boolean isRunning() {
        return instance != null;
    }

    static void start(Context context) {
        Intent intent = new Intent(context, ScreenshotWatchService.class)
                .setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        Intent intent = new Intent(context, ScreenshotWatchService.class)
                .setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("screenshot-watch");
        thread.start();
        handler = new Handler(thread.getLooper());
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Screenshot Mode", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Intercept the system and automatically join the dialogue");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopWatching();
            return START_NOT_STICKY;
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        if (instance != null) {
            return START_NOT_STICKY;
        }
        instance = this;
        startedAt = System.currentTimeMillis();
        handler.post(new Runnable() {
            @Override
            public void run() {
                lastSeenId = maxImageId();
                registerObserver();
            }
        });
        return START_NOT_STICKY;
    }

    private void registerObserver() {
        if (observer != null) {
            return;
        }
        observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                scanNewImages();
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                scanNewImages();
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
        } catch (Exception e) {
            Log.e("AgentScreenshot", "register observer failed", e);
        }
    }

    private long maxImageId() {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID},
                    null, null, MediaStore.Images.Media._ID + " DESC");
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Exception e) {
            Log.e("AgentScreenshot", "query max id failed", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return 0L;
    }

    private void scanNewImages() {
        if (instance != this) {
            return;
        }
        ContentResolver resolver = getContentResolver();
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.MIME_TYPE
        };
        Cursor cursor = null;
        try {
            cursor = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, MediaStore.Images.Media._ID + " > ?",
                    new String[]{String.valueOf(lastSeenId)},
                    MediaStore.Images.Media._ID + " ASC");
        } catch (Exception first) {
            String[] fallback = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.MIME_TYPE
            };
            try {
                cursor = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        fallback, MediaStore.Images.Media._ID + " > ?",
                        new String[]{String.valueOf(lastSeenId)},
                        MediaStore.Images.Media._ID + " ASC");
            } catch (Exception second) {
                Log.e("AgentScreenshot", "query images failed", second);
                notifyError("Could not read album, please give storage/Access rights for all files");
                return;
            }
        }
        if (cursor == null) {
            return;
        }
        int processed = 0;
        try {
            while (cursor.moveToNext() && processed < 20) {
                processed++;
                long id = cursor.getLong(0);
                String name = cursor.getString(1);
                long added = cursor.getLong(2);
                String path = cursor.getColumnCount() >= 5 ? cursor.getString(3) : "";
                if (id <= lastSeenId) {
                    continue;
                }
                lastSeenId = id;
                if (!looksLikeScreenshot(name, path, added)) {
                    continue;
                }
                Uri uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                try {
                    AgentEngine.ImageAttachment attachment = ImageUtils.buildAttachment(
                            this, uri, (int) (id % 100000),
                            new File(getFilesDir(), "attachments"));
                    if (attachment == null) {
                        continue;
                    }
                    AppPrefs prefs = new AppPrefs(this);
                    prefs.addPendingScreenshot(attachment.path);
                    addedCount++;
                    updateNotification();
                    try {
                        Intent broadcast = new Intent(ACTION_SCREENSHOT_ADDED)
                                .setPackage(getPackageName());
                        sendBroadcast(broadcast);
                    } catch (Exception ignored) {
                    }
                    LanguageManager.toast(this, "The screenshots are on the line.", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.w("AgentScreenshot", "copy screenshot failed", e);
                }
            }
        } finally {
            cursor.close();
        }
    }

    private boolean looksLikeScreenshot(String name, String path, long addedSeconds) {
        long addedMillis = addedSeconds * 1000L;
        if (addedMillis > 0 && addedMillis < startedAt - 5000L) {
            return false;
        }
        String lowerName = name == null ? "" : name.toLowerCase();
        String lowerPath = path == null ? "" : path.toLowerCase();
        return lowerName.contains("screenshot")
                || lowerName.contains("screen_shot")
                || lowerName.contains("Screenshot")
                || lowerPath.contains("/screenshots/")
                || lowerPath.contains("/screenshot/")
                || lowerPath.contains("/Screenshot/");
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 11, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ScreenshotWatchService.class)
                .setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 12, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = addedCount == 0
                ? "Use a system screen, screenshots automatically add to the current dialogue"
                : "Yes. " + addedCount + " An amplitude · Continue to screen with the system.";
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Running screenshot mode")
                .setContentText(text)
                .setContentIntent(openPending)
                .setOngoing(true)
                .addAction(0, "Quit", stopPending)
                .build();
    }

    private void updateNotification() {
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification());
            }
        } catch (Exception ignored) {
        }
    }

    private void notifyError(String message) {
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                        ? new Notification.Builder(this, CHANNEL_ID)
                        : new Notification.Builder(this);
                manager.notify(NOTIFICATION_ID, builder
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("Screenshot mode: cannot read screenshot")
                        .setContentText(message)
                        .setOngoing(true)
                        .build());
            }
        } catch (Exception ignored) {
        }
    }

    private void notifyStopped() {
        try {
            Intent stopped = new Intent(ACTION_STOPPED).setPackage(getPackageName());
            sendBroadcast(stopped);
        } catch (Exception ignored) {
        }
    }

    private void stopWatching() {
        if (observer != null) {
            try {
                getContentResolver().unregisterContentObserver(observer);
            } catch (Exception ignored) {
            }
            observer = null;
        }
        instance = null;
        notifyStopped();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (observer != null) {
            try {
                getContentResolver().unregisterContentObserver(observer);
            } catch (Exception ignored) {
            }
            observer = null;
        }
        if (instance == this) {
            instance = null;
            notifyStopped();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
