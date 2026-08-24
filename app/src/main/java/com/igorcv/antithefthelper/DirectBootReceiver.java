package com.igorcv.antithefthelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

public class DirectBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        ConfigStore config = new ConfigStore(context);

        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            if (!config.isConfigured()) return;

            boolean alreadyCharging = isCharging(context);
            boolean shouldArm = config.alertOnPowerConnected()
                    || (config.alertOnLockedBoot() && alreadyCharging);

            if (shouldArm) {
                AlertJobService.schedule(context);
            }
            return;
        }

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            // BOOT_COMPLETED is delivered after the first unlock. The BFU job is no longer needed.
            AlertJobService.cancel(context);
        }
    }

    private static boolean isCharging(Context context) {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return false;
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL
                || plugged != 0;
    }
}
