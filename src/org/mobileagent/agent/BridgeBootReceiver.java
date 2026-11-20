package org.mobileagent.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Turn it on./Autorecover after Upgrade Agent Bridges. Avoid having to open them manually. App The request is processed. */
public class BridgeBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action)) {
            AppPrefs prefs = new AppPrefs(context);
            if (prefs.bridgeAutoEnabled() || prefs.debugBridgeEnabled()) {
                BridgeAutoService.start(context);
            }
            AutomationService.startIfEnabled(context);
        }
    }
}
