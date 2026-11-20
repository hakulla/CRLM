package org.mobileagent.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ScreenCaptureService extends Service {
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_DATA = "data";
    private static final String CHANNEL_ID = "screen_capture";
    private static final int NOTIFICATION_ID = 4022;
    private static final String ACTION_STOP = "org.mobileagent.agent.STOP_SCREEN_CAPTURE";
    private static final int MAX_WIDTH = 960;
    private static volatile ScreenCaptureService instance;

    interface CaptureCallback {
        void onCapture(CaptureResult result);
    }

    static final class CaptureResult {
        final byte[] jpeg;
        final String path;
        final String error;

        private CaptureResult(byte[] jpeg, String path, String error) {
            this.jpeg = jpeg;
            this.path = path;
            this.error = error;
        }

        static CaptureResult success(byte[] jpeg, String path) {
            return new CaptureResult(jpeg, path, null);
        }

        static CaptureResult error(String message) {
            return new CaptureResult(null, null, message);
        }
    }

    private HandlerThread thread;
    private Handler handler;
    private volatile MediaProjection projection;
    private int screenWidth;
    private int screenHeight;
    private int densityDpi;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private CaptureCallback pendingCallback;
    private boolean captureInProgress;
    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            if (!captureInProgress) {
                return;
            }
            CaptureCallback callback = pendingCallback;
            pendingCallback = null;
            captureInProgress = false;
            releaseCaptureResources();
            if (callback != null) {
                callback.onCapture(CaptureResult.error("Check that screen reading is on."));
            }
        }
    };

    static boolean isActive() {
        ScreenCaptureService service = instance;
        return service != null && service.projection != null;
    }

    static void stop(Context context) {
        Intent intent = new Intent(context, ScreenCaptureService.class).setAction(ACTION_STOP);
        context.startService(intent);
    }

    static CaptureResult captureBlocking() {
        ScreenCaptureService service = instance;
        if (service == null || service.projection == null) {
            return CaptureResult.error("Screen Read Unopened. Click App Top right corner, 'Devices', open screen reading.");
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final CaptureResult[] result = new CaptureResult[1];
        service.requestCapture(new CaptureCallback() {
            @Override
            public void onCapture(CaptureResult captureResult) {
                result[0] = captureResult;
                latch.countDown();
            }
        });
        try {
            if (!latch.await(12, TimeUnit.SECONDS)) {
                return CaptureResult.error("Timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CaptureResult.error("The screenshot was cut.");
        }
        return result[0] == null ? CaptureResult.error("Screenshot failed") : result[0];
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("screen-capture");
        thread.start();
        handler = new Handler(thread.getLooper());
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Screen Read", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Agent Read the current screen while closing");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopCapture();
            return START_NOT_STICKY;
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        if (projection != null) {
            instance = this;
            return START_NOT_STICKY;
        }
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            MediaProjectionManager manager = (MediaProjectionManager)
                    getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
            projection = manager.getMediaProjection(resultCode, data);
            if (projection == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopCapture();
                }
            }, handler);

            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            densityDpi = metrics.densityDpi;
            instance = this;
        } catch (Exception e) {
            Log.e("AgentScreen", "screen capture start failed", e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void requestCapture(final CaptureCallback callback) {
        if (handler == null) {
            callback.onCapture(CaptureResult.error("Screen Read Service Not Ready"));
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (projection == null) {
                    callback.onCapture(CaptureResult.error("Screen Read Unopened"));
                    return;
                }
                if (captureInProgress) {
                    callback.onCapture(CaptureResult.error("We have a screenshot in progress."));
                    return;
                }
                captureInProgress = true;
                pendingCallback = callback;
                try {
                    imageReader = ImageReader.newInstance(
                            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
                    imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                        @Override
                        public void onImageAvailable(ImageReader reader) {
                            handleImage(reader);
                        }
                    }, handler);
                    virtualDisplay = projection.createVirtualDisplay(
                            "agent-screen-capture",
                            screenWidth, screenHeight, densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            imageReader.getSurface(), null, handler);
                    handler.postDelayed(timeout, 8000);
                } catch (Exception e) {
                    captureInProgress = false;
                    pendingCallback = null;
                    releaseCaptureResources();
                    callback.onCapture(CaptureResult.error("Could not close temporary folder: %s" + e.getMessage()));
                }
            }
        });
    }

    private void handleImage(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            if (!captureInProgress || pendingCallback == null) {
                return;
            }
            handler.removeCallbacks(timeout);
            CaptureCallback callback = pendingCallback;
            pendingCallback = null;
            CaptureResult result;
            try {
                result = processImage(image);
            } catch (Exception e) {
                result = CaptureResult.error("Could not close temporary folder: %s" + e.getMessage());
            }
            image.close();
            image = null;
            captureInProgress = false;
            releaseCaptureResources();
            callback.onCapture(result);
        } catch (Exception e) {
            if (pendingCallback != null) {
                CaptureCallback callback = pendingCallback;
                pendingCallback = null;
                captureInProgress = false;
                releaseCaptureResources();
                callback.onCapture(CaptureResult.error("Screenshot failed"));
            }
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private CaptureResult processImage(Image image) throws Exception {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int paddedWidth = rowStride / pixelStride;
        Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        buffer.rewind();
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        padded.recycle();

        if (width > MAX_WIDTH) {
            int newHeight = Math.round(height * (MAX_WIDTH / (float) width));
            Bitmap scaled = Bitmap.createScaledBitmap(cropped, MAX_WIDTH, newHeight, true);
            cropped.recycle();
            cropped = scaled;
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        cropped.compress(Bitmap.CompressFormat.JPEG, 60, output);
        byte[] jpeg = output.toByteArray();
        cropped.recycle();

        Workspace workspace = new Workspace(this);
        File directory = workspace.resolve("screenshots");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not close temporary folder: %s");
        }
        File file = new File(directory, "screen-" + System.currentTimeMillis() + ".jpg");
        FileOutputStream fileOutput = new FileOutputStream(file, false);
        try {
            fileOutput.write(jpeg);
        } finally {
            fileOutput.close();
        }
        return CaptureResult.success(jpeg, workspace.relativePath(file));
    }

    private void releaseCaptureResources() {
        if (handler != null) {
            handler.removeCallbacks(timeout);
        }
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (RuntimeException ignored) {
            }
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (RuntimeException ignored) {
            }
            imageReader = null;
        }
    }

    private void stopCapture() {
        if (pendingCallback != null) {
            CaptureCallback callback = pendingCallback;
            pendingCallback = null;
            callback.onCapture(CaptureResult.error("Screen Read Stopped"));
        }
        captureInProgress = false;
        releaseCaptureResources();
        if (projection != null) {
            try {
                projection.stop();
            } catch (RuntimeException ignored) {
            }
            projection = null;
        }
        instance = null;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (pendingCallback != null) {
            CaptureCallback callback = pendingCallback;
            pendingCallback = null;
            callback.onCapture(CaptureResult.error("Screen Read Stopped"));
        }
        captureInProgress = false;
        releaseCaptureResources();
        if (projection != null) {
            try {
                projection.stop();
            } catch (RuntimeException ignored) {
            }
            projection = null;
        }
        instance = null;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open, flags);
        Intent stop = new Intent(this, ScreenCaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, flags);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("CRLM Screen Read")
                .setContentText("Agent Capable of intercepting in task;points stopped closing")
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent).build());
        return builder.build();
    }
}
