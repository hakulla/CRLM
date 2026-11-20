package org.mobileagent.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

public class InstallResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        PackageInstallHelper.onInstallResult(status, message);
        String text = status == PackageInstaller.STATUS_SUCCESS
                ? "Application installation successfully"
                : "Application installation not completed:" + (message == null ? "Unknown cause" : message);
        try {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            // installation results are already recorded in themselves; host window when failed Toast The radio receiver should not be destroyed.
        }
    }
}
