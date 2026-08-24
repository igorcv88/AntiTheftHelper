package com.igorcv.antithefthelper;

import android.app.admin.DeviceAdminService;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.UserManager;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional Device Owner path.
 *
 * Android keeps a DeviceAdminService bound for a device/profile owner while the user is running,
 * which gives the owner process foreground importance. This is useful for camera access in BFU
 * without trying to launch a camera foreground service directly from a boot receiver.
 */
public class AntiTheftAdminService extends DeviceAdminService {
    private static final long DUPLICATE_COOLDOWN_MS = 120_000L;
    private static final long RETRY_DELAY_SECONDS = 30L;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService executor;
    private BroadcastReceiver powerReceiver;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean bootPending;
    private volatile boolean powerPending;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadScheduledExecutor();

        if (!isDeviceOwner()) return;

        registerPowerReceiver();
        registerNetworkCallback();

        if (isBeforeFirstUnlock()) {
            ConfigStore config = new ConfigStore(this);
            bootPending = config.isConfigured() && config.alertOnLockedBoot();
            powerPending = config.isConfigured() && config.alertOnPowerConnected() && isCharging();
            scheduleAttempt(2);
        }
    }

    private void registerPowerReceiver() {
        powerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) return;
                if (!isBeforeFirstUnlock()) return;
                ConfigStore config = new ConfigStore(AntiTheftAdminService.this);
                if (config.isConfigured() && config.alertOnPowerConnected()) {
                    powerPending = true;
                    scheduleAttempt(0);
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            //noinspection UnspecifiedRegisterReceiverFlag
            registerReceiver(powerReceiver, filter);
        }
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (bootPending || powerPending) scheduleAttempt(0);
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ignored) {
        }
    }

    private void scheduleAttempt(long delaySeconds) {
        ScheduledExecutorService current = executor;
        if (current == null || current.isShutdown()) return;
        current.schedule(this::runPendingAlert, delaySeconds, TimeUnit.SECONDS);
    }

    private void runPendingAlert() {
        if (!isDeviceOwner() || !isBeforeFirstUnlock()) {
            bootPending = false;
            powerPending = false;
            return;
        }
        if (!running.compareAndSet(false, true)) return;

        File photo = null;
        try {
            ConfigStore config = new ConfigStore(this);
            if (!config.isConfigured()) return;

            String trigger;
            if (bootPending && config.alertOnLockedBoot()) {
                trigger = "DEVICE_OWNER_BFU_BOOT";
            } else if (powerPending && config.alertOnPowerConnected()) {
                trigger = "DEVICE_OWNER_BFU_POWER_CONNECTED";
            } else {
                return;
            }

            if (!hasNetwork()) {
                scheduleAttempt(RETRY_DELAY_SECONDS);
                return;
            }

            long sinceLast = System.currentTimeMillis() - config.lastSuccessMs();
            if (sinceLast >= 0 && sinceLast < DUPLICATE_COOLDOWN_MS) {
                if (trigger.equals("DEVICE_OWNER_BFU_BOOT")) bootPending = false;
                else powerPending = false;
                return;
            }

            TrustedEnvironment.Result environment = TrustedEnvironment.evaluate(this, config);
            if (environment.trusted) {
                if (trigger.equals("DEVICE_OWNER_BFU_BOOT")) bootPending = false;
                else powerPending = false;
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
                    send = TelegramClient.sendMessage(
                            config.token(),
                            config.chatId(),
                            message + "\nPhoto upload: FAILED (HTTP " + send.code + ")"
                    );
                }
            } else {
                send = TelegramClient.sendMessage(config.token(), config.chatId(), message);
            }

            if (send.ok) {
                config.markSuccess();
                if (trigger.equals("DEVICE_OWNER_BFU_BOOT")) {
                    bootPending = false;
                    // If the device was already charging at boot, don't immediately duplicate the same alert.
                    if (isCharging()) powerPending = false;
                } else {
                    powerPending = false;
                }
            } else {
                scheduleAttempt(RETRY_DELAY_SECONDS);
            }
        } catch (Exception e) {
            scheduleAttempt(RETRY_DELAY_SECONDS);
        } finally {
            if (photo != null) {
                //noinspection ResultOfMethodCallIgnored
                photo.delete();
            }
            running.set(false);
        }
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
        out.append("Device Owner: YES\n");
        out.append("Before first unlock: YES\n");
        out.append("Charging: ").append(isCharging() ? "YES" : "NO").append('\n');
        if (percent >= 0) out.append("Battery: ").append(percent).append("%\n");
        out.append("Time: ").append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date())).append('\n');
        out.append("Trusted check: ").append(environment.reason).append('\n');
        out.append("Wi-Fi: ").append(environment.wifiSsid == null ? "unknown/not connected" : environment.wifiSsid).append('\n');
        out.append("Bluetooth: ").append(environment.bluetoothDevices.isEmpty()
                ? "none detected"
                : String.join(", ", environment.bluetoothDevices)).append('\n');

        if (camera.ok()) {
            out.append("Front camera: CAPTURED - ").append(camera.captureSummary()).append('\n');
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

    private boolean isDeviceOwner() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }

    private boolean isBeforeFirstUnlock() {
        UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
        return um != null && !um.isUserUnlocked();
    }

    private boolean hasNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return false;
        }
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

    @Override
    public void onDestroy() {
        try {
            if (powerReceiver != null) unregisterReceiver(powerReceiver);
        } catch (Exception ignored) {
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && networkCallback != null) cm.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {
        }
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
}
