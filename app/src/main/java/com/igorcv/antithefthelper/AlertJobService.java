package com.igorcv.antithefthelper;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.UserManager;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AlertJobService extends JobService {
    static final int JOB_ID = 884201;
    private ExecutorService executor;

    static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;

        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, AlertJobService.class))
                .setRequiresCharging(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        scheduler.schedule(job);
    }

    static void cancel(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) scheduler.cancel(JOB_ID);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        ConfigStore config = new ConfigStore(this);
        UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
        boolean unlocked = userManager != null && userManager.isUserUnlocked();

        if (!config.isConfigured() || unlocked || (!config.alertOnLockedBoot() && !config.alertOnPowerConnected())) {
            jobFinished(params, false);
            return false;
        }

        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                LocationHelper.Snapshot location = config.includeLocation() ? LocationHelper.getBest(this) : null;
                String message = buildMessage(location);
                TelegramClient.Result result = TelegramClient.sendMessage(config.token(), config.chatId(), message);
                if (result.ok) {
                    config.markSuccess();
                    jobFinished(params, false);
                } else {
                    jobFinished(params, true);
                }
            } finally {
                if (executor != null) {
                    executor.shutdown();
                    executor = null;
                }
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        return true;
    }

    private String buildMessage(LocationHelper.Snapshot location) {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = -1;
        int scale = 100;
        if (battery != null) {
            level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        }
        int percent = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;

        StringBuilder out = new StringBuilder();
        out.append("🚨 AntiTheftHelper\n");
        out.append("Trigger: BFU_CHARGING_JOB\n");
        out.append("Before first unlock: YES\n");
        out.append("Charging: YES\n");
        if (percent >= 0) out.append("Battery: ").append(percent).append("%\n");
        out.append("Time: ").append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date())).append('\n');
        out.append("Network: ").append(networkSummary()).append('\n');
        String ssid = wifiSsid();
        if (ssid != null) out.append("Wi-Fi: ").append(ssid).append('\n');

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

    private String networkSummary() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
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
    private String wifiSsid() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
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
}
