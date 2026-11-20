package org.mobileagent.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Other APP Pass. Agent The radio service backstage is awakening. No user is required to open it. Agent.  */
public class BridgeRequestReceiver extends BroadcastReceiver {
    static final String ACTION_REQUEST = "org.mobileagent.agent.BRIDGE_REQUEST";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_REQUEST.equals(intent.getAction())) {
            return;
        }
        AppPrefs prefs = new AppPrefs(context);
        if (prefs.bridgeAutoEnabled() || prefs.debugBridgeEnabled()) {
            BridgeAutoService.start(context);
        }
    }
}
