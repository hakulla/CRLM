package org.mobileagent.agent;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class PackageInstallHelper {
    static final String ACTION_INSTALL_RESULT = "org.mobileagent.agent.INSTALL_RESULT";
    private static final Object LOCK = new Object();
    private static CountDownLatch latch;
    private static int resultStatus = -1;
    private static String resultMessage = "";

    static final class Result {
        final boolean success;
        final String message;

        Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private PackageInstallHelper() {
    }

    static Result install(Context context, File apk) {
        if (apk == null || !apk.isFile()) {
            return new Result(false, "APK File does not exist");
        }
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            FileInputStream input = new FileInputStream(apk);
            OutputStream output = session.openWrite("base.apk", 0, apk.length());
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                session.fsync(output);
            } finally {
                output.close();
                input.close();
            }

            CountDownLatch localLatch = new CountDownLatch(1);
            synchronized (LOCK) {
                latch = localLatch;
                resultStatus = -1;
                resultMessage = "";
            }
            Intent resultIntent = new Intent(context, InstallResultReceiver.class);
            resultIntent.setAction(ACTION_INSTALL_RESULT);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, sessionId, resultIntent, flags);
            session.commit(pendingIntent.getIntentSender());

            if (!localLatch.await(120, TimeUnit.SECONDS)) {
                return new Result(false, "Waiting for system installation confirmation timeout");
            }
            synchronized (LOCK) {
                boolean success = resultStatus == PackageInstaller.STATUS_SUCCESS;
                return new Result(success, success ? "Installation succeeded" : resultMessage);
            }
        } catch (SecurityException e) {
            return new Result(false, "No application privileges installed. Please allowCRLM Installs an unknown application.");
        } catch (Exception e) {
            return new Result(false, "Installation failed:" + e.getMessage());
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    static void onInstallResult(int status, String message) {
        synchronized (LOCK) {
            resultStatus = status;
            resultMessage = message == null ? "" : message;
            if (latch != null) {
                latch.countDown();
            }
        }
    }
}
