package com.igorcv.antithefthelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class DirectBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        ConfigStore config = new ConfigStore(context);

        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            if (config.isConfigured() && (config.alertOnLockedBoot() || config.alertOnPowerConnected())) {
                AlertJobService.schedule(context);
            }
            return;
        }

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            // BOOT_COMPLETED is delivered after the first unlock. The BFU job is no longer needed.
            AlertJobService.cancel(context);
        }
    }
}
