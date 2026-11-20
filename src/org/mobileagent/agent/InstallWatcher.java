package org.mobileagent.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/** Installation results validation: The version of the interview has been installed; the self-renewal kills the process, so the dialogue is left behind. */
final class InstallWatcher {
    static final String PREFS = "install_watch";
    private static final String KEY_CONV = "pending_conversation";
    private static final String KEY_CONV_PKG = "pending_package";
    private static final String KEY_CONV_AT = "pending_at";
    private static final String KEY_ACTIVE = "active_conversation";

    private InstallWatcher() {
    }

    static final class Target {
        final String packageName;
        final int versionCode;

        Target(String packageName, int versionCode) {
            this.packageName = packageName;
            this.versionCode = versionCode;
        }
    }

    static Target archiveTarget(Context context, String apkPath) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageArchiveInfo(apkPath, 0);
            if (info == null || info.packageName == null) {
                return null;
            }
            return new Target(info.packageName, versionOf(info));
        } catch (Throwable ignored) {
            return null;
        }
    }

    static int installedVersion(Context context, String packageName) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(packageName, 0);
            return info == null ? -1 : versionOf(info);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int versionOf(android.content.pm.PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) {
            return (int) info.getLongVersionCode();
        }
        return info.versionCode;
    }

    static boolean isSelfPackage(Context context, String packageName) {
        return packageName != null && packageName.equals(context.getPackageName());
    }

    /** Drop the disc before updating: the process will be killed by the installor and re-open App The dialogue was resumed accordingly. */
    static void rememberPendingConversation(Context context, String packageName, String conversationId) {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            return;
        }
        prefs(context).edit()
                .putString(KEY_CONV, conversationId.trim())
                .putString(KEY_CONV_PKG, packageName == null ? "" : packageName)
                .putLong(KEY_CONV_AT, System.currentTimeMillis())
                .commit();
    }

    /** Remove and immediately remove the state of suspense and ensure that it is restored only once. */
    static String takePendingConversation(Context context) {
        SharedPreferences p = prefs(context);
        String id = p.getString(KEY_CONV, "");
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        p.edit().remove(KEY_CONV).remove(KEY_CONV_PKG).remove(KEY_CONV_AT).commit();
        return id.trim();
    }

    static boolean hasPendingConversation(Context context) {
        String id = prefs(context).getString(KEY_CONV, "");
        return id != null && !id.trim().isEmpty();
    }

    /** Memory'The dialogue that is currently under way', To set up a hold until installation is updated. */
    static void setActiveConversation(Context context, String conversationId) {
        if (conversationId == null) {
            conversationId = "";
        }
        prefs(context).edit().putString(KEY_ACTIVE, conversationId).commit();
    }

    static String activeConversation(Context context) {
        String id = prefs(context).getString(KEY_ACTIVE, "");
        if (id != null && !id.trim().isEmpty()) {
            return id.trim();
        }
        // Bottom of the pocket:active Use when recording missing'Recent Open Dialogue', The government is also making a commitment to ensure that the dialogue will be able to return to its original state of affairs since its renewal.
        try {
            String last = new AppPrefs(context).lastConversationId();
            return last == null || last.trim().isEmpty() ? null : last.trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Dismantling the stagger status when installation is confirmed to be successful and avoiding the next activation error. */
    static void clearPendingConversation(Context context) {
        prefs(context).edit().remove(KEY_CONV).remove(KEY_CONV_PKG).remove(KEY_CONV_AT).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Results of the round of inquiry:confirmed=Version changed/Achieved;timeout=The point remains unrecognized. */
    static final class Poll {
        final boolean confirmed;
        final int version;
        final int attempts;
        final long elapsedMs;

        Poll(boolean confirmed, int version, int attempts, long elapsedMs) {
            this.confirmed = confirmed;
            this.version = version;
            this.attempts = attempts;
            this.elapsedMs = elapsedMs;
        }
    }

    /**
     * We'll wait for the installation to land, then every one. intervalMs Check once installed version, maximum maxAttempts - I'm not gonna do it again.
     * before The project is a new one for the first time in the world.target Yes APK version in the'Is it true that the version was changed?'. 
     */
    static Poll pollInstalled(Context context, String packageName, int targetVersion, int before,
                             long firstDelayMs, long intervalMs, int maxAttempts, long deadlineMs) {
        long start = System.currentTimeMillis();
        int attempts = 0;
        int last = -1;
        for (int i = 0; i <= maxAttempts; i++) {
            long delay = i == 0 ? firstDelayMs : intervalMs;
            if (System.currentTimeMillis() - start + delay > deadlineMs) {
                break;
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            attempts++;
            last = installedVersion(context, packageName);
            boolean ok = last >= 0 && (targetVersion <= before ? true : (last >= targetVersion || last != before));
            if (ok) {
                return new Poll(true, last, attempts, System.currentTimeMillis() - start);
            }
        }
        return new Poll(false, last, attempts, System.currentTimeMillis() - start);
    }
}
