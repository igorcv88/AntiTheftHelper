package com.igorcv.antithefthelper;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PersistableBundle;
import android.os.UserManager;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AlertJobService extends JobService {
    static final int JOB_BOOT = 884201;
    static final int JOB_POWER = 884202;
    static final String TRIGGER_BOOT = "BFU_BOOT";
    static final String TRIGGER_POWER = "BFU_POWER_CONNECTED";
    private static final String EXTRA_TRIGGER = "trigger";
    private static final long DUPLICATE_COOLDOWN_MS = 120_000L;

    private ExecutorService executor;

    static void scheduleBoot(Context context) {
        schedule(context, JOB_BOOT, TRIGGER_BOOT, false);
    }

    static void schedulePower(Context context) {
        schedule(context, JOB_POWER, TRIGGER_POWER, true);
    }

    private static void schedule(Context context, int jobId, String trigger, boolean requiresCharging) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;

        PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_TRIGGER, trigger);

        JobInfo.Builder builder = new JobInfo.Builder(jobId, new ComponentName(context, AlertJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setExtras(extras);
        if (requiresCharging) builder.setRequiresCharging(true);
        scheduler.schedule(builder.build());
    }

    static void cancelAll(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) {
            scheduler.cancel(JOB_BOOT);
            scheduler.cancel(JOB_POWER);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        ConfigStore config = new ConfigStore(this);
        UserManager userManager = (UserManager) getSystemService(Context.USER_SERVICE);
        boolean unlocked = userManager != null && userManager.isUserUnlocked();
        String trigger = params.getExtras().getString(EXTRA_TRIGGER, "UNKNOWN");

        boolean triggerEnabled = TRIGGER_BOOT.equals(trigger)
                ? config.alertOnLockedBoot()
                : TRIGGER_POWER.equals(trigger) && config.alertOnPowerConnected();

        if (!config.isConfigured() || unlocked || !triggerEnabled) {
            jobFinished(params, false);
            return false;
        }

        synchronized (this) {
            if (executor == null || executor.isShutdown()) {
                executor = Executors.newSingleThreadExecutor();
            }
        }

        executor.execute(() -> runAlert(params, config, trigger));
        return true;
    }

    private void runAlert(JobParameters params, ConfigStore config, String trigger) {
        File photo = null;
        try {
            long sinceLast = System.currentTimeMillis() - config.lastSuccessMs();
            if (sinceLast >= 0 && sinceLast < DUPLICATE_COOLDOWN_MS) {
                jobFinished(params, false);
                return;
            }

            TrustedEnvironment.Result environment = TrustedEnvironment.evaluate(this, config);
            if (environment.trusted) {
                jobFinished(params, false);
                return;
            }

            CameraHelper.Result camera = config.includeCamera()
                    ? CameraHelper.captureFront(this)
                    : new CameraHelper.Result(null, "disabled");
            if (camera.ok()) photo = camera.file;

            LocationHelper.Snapshot location = config.includeLocation() ? LocationHelper.getBest(this) : null;
            String message = buildMessage(trigger, location, environment, camera);

            TelegramClient.Result send;
            if (photo != null) {
                send = TelegramClient.sendPhoto(config.token(), config.chatId(), message, photo);
                if (!send.ok) {
                    String fallback = message + "\nPhoto upload: FAILED (HTTP " + send.code + ")";
                    send = TelegramClient.sendMessage(config.token(), config.chatId(), fallback);
                }
            } else {
                send = TelegramClient.sendMessage(config.token(), config.chatId(), message);
            }

            if (send.ok) {
                config.markSuccess();
                jobFinished(params, false);
            } else {
                jobFinished(params, true);
            }
        } catch (Exception e) {
            jobFinished(params, true);
        } finally {
            if (photo != null) {
                //noinspection ResultOfMethodCallIgnored
                photo.delete();
            }
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    @Override
    public void onDestroy() {
        synchronized (this) {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
        super.onDestroy();
    }

    private String buildMessage(String trigger,
                                LocationHelper.Snapshot location,
                                TrustedEnvironment.Result environment,
                                CameraHelper.Result camera) {
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
        out.append("Trigger: ").append(trigger).append('\n');
        out.append("Before first unlock: YES\n");
        out.append("Charging: ").append(isCharging() ? "YES" : "NO").append('\n');
        if (percent >= 0) out.append("Battery: ").append(percent).append("%\n");
        out.append("Time: ").append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date())).append('\n');
        out.append("Trusted check: ").append(environment.reason).append('\n');
        out.append("Wi-Fi: ").append(environment.wifiSsid == null ? "unknown/not connected" : environment.wifiSsid).append('\n');
        out.append("Bluetooth: ").append(environment.bluetoothDevices.isEmpty()
                ? "none detected"
                : String.join(", ", environment.bluetoothDevices)).append('\n');
        if (!"OK".equals(environment.bluetoothDiagnostic) && !"not needed".equals(environment.bluetoothDiagnostic)) {
            out.append("Bluetooth diagnostic: ").append(environment.bluetoothDiagnostic).append('\n');
        }

        if (camera.ok()) {
            out.append("Front camera: captured\n");
        } else if (!"disabled".equals(camera.error)) {
            out.append("Front camera: FAILED - ").append(camera.error == null ? "unknown" : camera.error).append('\n');
        } else {
            out.append("Front camera: disabled\n");
        }

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

    private boolean isCharging() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return false;
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL
                || plugged != 0;
    }
}
