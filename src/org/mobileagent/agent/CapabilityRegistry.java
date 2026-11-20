package org.mobileagent.agent;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/** Runtime capability fingerprint; old failure conclusions are invalid when this changes. */
final class CapabilityRegistry {
    static final class Snapshot {
        final String version;
        final String fingerprint;
        final String render;
        final boolean webTools;
        final boolean browserRead;
        final boolean webview;
        final boolean accessibility;
        final boolean deviceControl;
        final boolean termux;
        final boolean chrome;

        Snapshot(String version, String fingerprint, String render, boolean webTools,
                 boolean browserRead, boolean webview, boolean accessibility,
                 boolean deviceControl, boolean termux, boolean chrome) {
            this.version = version;
            this.fingerprint = fingerprint;
            this.render = render;
            this.webTools = webTools;
            this.browserRead = browserRead;
            this.webview = webview;
            this.accessibility = accessibility;
            this.deviceControl = deviceControl;
            this.termux = termux;
            this.chrome = chrome;
        }

        boolean changedSince(String oldFingerprint) {
            return oldFingerprint != null && !oldFingerprint.isEmpty()
                    && !oldFingerprint.equals(fingerprint);
        }
    }

    private CapabilityRegistry() {
    }

    static Snapshot snapshot(Context context, AppPrefs prefs) {
        String version = "unknown";
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            version = info.versionName + " (" + info.versionCode + ")";
        } catch (Exception ignored) {
        }
        boolean web = prefs.allowWebTools();
        boolean accessibility = AgentAccessibilityService.getInstance() != null;
        boolean device = prefs.allowDeviceControl();
        boolean chrome = installed(context, "com.android.chrome");
        boolean termux = TermuxBridge.isInstalled(context);
        // Device control can be granted per call; browser capability itself
        // depends on Chrome plus a bound accessibility service.
        boolean sysBrowser = chrome && accessibility;
        boolean webview = hasWebView(context);
        String raw = version + "|" + web + "|" + sysBrowser + "|" + webview + "|"
                + accessibility + "|" + device + "|" + termux + "|" + chrome;
        String fingerprint = ToolRegistry.sha256(raw);
        String render = "App " + version
                + " · web=" + (web ? "on" : "off")
                + " · webview=" + (webview ? "on" : "off")
                + " · sysbrowser=" + (sysBrowser ? "on" : "off")
                + " · a11y=" + (accessibility ? "on" : "off")
                + " · device=" + (device ? "on" : "off")
                + " · termux=" + (termux ? "on" : "off");
        return new Snapshot(version, fingerprint, render, web, sysBrowser, webview,
                accessibility, device, termux, chrome);
    }

    private static boolean hasWebView(Context context) {
        try {
            return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WEBVIEW);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean installed(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
