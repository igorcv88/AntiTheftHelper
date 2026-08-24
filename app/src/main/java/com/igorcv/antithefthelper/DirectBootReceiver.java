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
            if (!config.isConfigured()) return;

            if (config.alertOnLockedBoot()) {
                // Boot itself is an alert trigger. Charging is NOT required.
                AlertJobService.scheduleBoot(context);
            }
            if (config.alertOnPowerConnected()) {
                // One-shot BFU job that waits for the first charging state after this reboot.
                AlertJobService.schedulePower(context);
            }
            return;
        }

        if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            if (config.isConfigured() && config.alertOnPowerConnected()) {
                AlertJobService.schedulePower(context);
            }
            return;
        }

        if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            if (config.isConfigured() && config.alertOnPowerConnected()) {
                // Re-arm so a later reconnection can become another trigger if Android delivers this broadcast.
                AlertJobService.schedulePower(context);
            }
            return;
        }

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            // Normal Tasker/post-unlock workflows can take over after the first unlock.
            AlertJobService.cancelAll(context);
        }
    }
}
