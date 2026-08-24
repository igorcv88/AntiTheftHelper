package com.igorcv.antithefthelper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DirectBootReceiver extends BroadcastReceiver {
    private static final String ACTION_RETRY = "com.igorcv.antithefthelper.RETRY";
    private static final String EXTRA_RETRY = "retry_count";
    private static final int MAX_RETRIES = 6;
    private static final long SUCCESS_COOLDOWN_MS = 60_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        ConfigStore config = new ConfigStore(context);
        if (!config.isConfigured()) return;

        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) && !config.alertOnLockedBoot()) return;
        if (Intent.ACTION_POWER_CONNECTED.equals(action) && !config.alertOnPowerConnected()) return;

        boolean triggerAction = Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_POWER_CONNECTED.equals(action)
                || ACTION_RETRY.equals(action);
        if (!triggerAction || !isCharging(context)) return;

        int retry = intent == null ? 0 : intent.getIntExtra(EXTRA_RETRY, 0);
        if (System.currentTimeMillis() - config.lastSuccessMs() < SUCCESS_COOLDOWN_MS) return;

        PendingResult pendingResult = goAsync();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            PowerManager.WakeLock wakeLock = null;
            try {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AntiTheftHelper:DirectBoot");
                    wakeLock.acquire(15_000L);
                }

                LocationHelper.Snapshot location = config.includeLocation() ? LocationHelper.getBest(context) : null;
                String message = buildMessage(context, action, retry, location);
                TelegramClient.Result result = TelegramClient.sendMessage(config.token(), config.chatId(), message);

                if (result.ok) {
                    config.markSuccess();
                } else if (retry < MAX_RETRIES) {
                    scheduleRetry(context, retry + 1);
                }
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
                executor.shutdown();
                pendingResult.finish();
            }
        });
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

    private static String buildMessage(Context context, String action, int retry, LocationHelper.Snapshot location) {
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        boolean unlocked = userManager != null && userManager.isUserUnlocked();

        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = -1;
        int scale = 100;
        if (battery != null) {
            level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        }
        int percent = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;

        StringBuilder out = new StringBuilder();
        out.append("🚨 AntiTheftHelper\n");
        out.append("Trigger: ").append(shortAction(action)).append('\n');
        out.append("Before first unlock: ").append(!unlocked ? "YES" : "NO").append('\n');
        out.append("Charging: YES\n");
        if (percent >= 0) out.append("Battery: ").append(percent).append("%\n");
        out.append("Time: ").append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date())).append('\n');
        out.append("Network: ").append(networkSummary(context)).append('\n');
        String ssid = wifiSsid(context);
        if (ssid != null) out.append("Wi-Fi: ").append(ssid).append('\n');
        if (retry > 0) out.append("Retry: ").append(retry).append('/').append(MAX_RETRIES).append('\n');

        if (location != null) {
            out.append(String.format(Locale.US, "Location: %.6f, %.6f\n", location.latitude, location.longitude));
            if (location.accuracy >= 0) out.append(String.format(Locale.US, "Accuracy: %.0f m\n", location.accuracy));
            out.append("Provider: ").append(location.provider).append('\n');
            out.append(String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", location.latitude, location.longitude));
        } else {
            out.append("Location: unavailable");
        }
        return out.toString();
    }

    private static String shortAction(String action) {
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) return "LOCKED_BOOT_COMPLETED";
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) return "BOOT_COMPLETED";
        if (Intent.ACTION_POWER_CONNECTED.equals(action)) return "POWER_CONNECTED";
        if (ACTION_RETRY.equals(action)) return "RETRY";
        return action == null ? "unknown" : action;
    }

    private static String networkSummary(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "unknown";
            Network network = cm.getActiveNetwork();
            if (network == null) return "offline";
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return "connected";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Cellular";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
            return "connected";
        } catch (Exception e) {
            return "unknown";
        }
    }

    @SuppressWarnings("deprecation")
    private static String wifiSsid(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            String ssid = info.getSSID();
            if (ssid == null || "<unknown ssid>".equalsIgnoreCase(ssid)) return null;
            if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            return ssid;
        } catch (Exception e) {
            return null;
        }
    }

    private static void scheduleRetry(Context context, int retry) {
        long[] delays = {30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 900_000L};
        long delay = delays[Math.min(retry - 1, delays.length - 1)];

        Intent retryIntent = new Intent(context, DirectBootReceiver.class)
                .setAction(ACTION_RETRY)
                .putExtra(EXTRA_RETRY, retry);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                4100 + retry,
                retryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delay,
                    pendingIntent);
        }
    }
}
