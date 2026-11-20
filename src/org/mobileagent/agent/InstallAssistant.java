package org.mobileagent.agent;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Best-effort assistant for the system installer. It only acts inside installer package
 * windows, fills a password field once, and never logs or exposes the secret.
 */
final class InstallAssistant {
    private static boolean running;

    private InstallAssistant() {
    }

    static synchronized boolean start(final Context context,
                                      final DeviceSecretStore store,
                                      final long timeoutMs) {
        if (running) {
            return true;
        }
        final AgentAccessibilityService service = AgentAccessibilityService.getInstance();
        if (service == null) {
            toast(context, "Accessibility services are not enabled, so installation passwords cannot be automatically filled",
                    Toast.LENGTH_LONG);
            return false;
        }
        final String secret = store == null ? null : store.get();
        if (secret == null || secret.isEmpty()) {
            toast(context, "No installation password saved; import first in settings",
                    Toast.LENGTH_LONG);
            return false;
        }
        running = true;
        final Handler handler = new Handler(Looper.getMainLooper());
        final long started = System.currentTimeMillis();
        final boolean[] filled = {false};
        final boolean[] blocked = {false};
        final int[] quietPolls = {0};
        final long[] filledAt = {0L};
        toast(context, "Installing aids enabled: only fill in the system installer password box",
                Toast.LENGTH_LONG);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (System.currentTimeMillis() - started > timeoutMs) {
                        finish(context, filled[0] ? "Installation of aids is over; if the installation is still waiting,"
                                + "Please confirm manually." : "Installing the help is timed out, and please do so manually in the installer.");
                        return;
                    }
                    if (service == null || AgentAccessibilityService.getInstance() == null) {
                        finish(context, "Accessibility services have been disconnected and installation aids have been stopped.");
                        return;
                    }
                    if (blocked[0]) {
                        return;
                    }
                    if (filled[0] && System.currentTimeMillis() - filledAt[0] > 6000L) {
                        finish(context, "The installation password has been submitted and the installation aids are completed; please check the installation results.");
                        return;
                    }
                    StringBuilder note = new StringBuilder();
                    String useSecret = filled[0] ? "" : secret;
                    boolean acted = service.assistInstaller(useSecret, note);
                    String detail = note.toString();
                    if (detail.contains("password_blocked")) {
                        blocked[0] = true;
                        finish(context, "The password box for the system installer is not allowed to be filled automatically, and please enter the password manually.");
                        return;
                    }
                    if (detail.contains("password_filled")) {
                        filled[0] = true;
                        filledAt[0] = System.currentTimeMillis();
                        toast(context, "Completed installation password; no attempt repeated",
                                Toast.LENGTH_SHORT);
                    }
                    if (acted) {
                        quietPolls[0] = 0;
                    } else {
                        quietPolls[0]++;
                    }
                    if (quietPolls[0] >= 12) {
                        finish(context, filled[0]
                                ? "Installation support is complete; please check the results of the installation."
                                : "No system installation was detected and the installation of the support was completed.");
                        return;
                    }
                    handler.postDelayed(this, 900L);
                } catch (Throwable t) {
                    finish(context, "Installation of assistive abnormally stopped:"
                            + (t.getMessage() == null
                            ? t.getClass().getSimpleName() : t.getMessage()));
                }
            }
        }, 500L);
        return true;
    }

    private static void toast(final Context context, final String message, final int duration) {
        if (context == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(context, message, duration).show();
                } catch (Throwable ignored) {
                    // Installer/Host App The window may have been replaced by the system.Toast Failure does not affect the installation process.
                }
            }
        });
    }

    private static synchronized void finish(Context context, String message) {
        running = false;
        if (context != null) {
            toast(context, message, Toast.LENGTH_LONG);
        }
    }
}
