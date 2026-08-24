package com.igorcv.antithefthelper;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 1001;
    private static final int REQ_CAMERA = 1002;
    private static final int REQ_BLUETOOTH = 1003;
    private static final int REQ_NOTIFICATIONS = 1004;
    private static final String DEVICE_OWNER_COMMAND =
            "adb shell dpm set-device-owner --user 0 com.igorcv.antithefthelper/.AntiTheftAdminReceiver";

    private EditText tokenField;
    private EditText chatIdField;
    private EditText trustedWifiField;
    private EditText trustedBluetoothField;
    private CheckBox bootCheck;
    private CheckBox powerCheck;
    private CheckBox locationCheck;
    private CheckBox cameraCheck;
    private TextView status;
    private ConfigStore config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = new ConfigStore(this);
        setContentView(buildUi());
        loadConfig();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) refreshStatus();
    }

    private View buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("AntiTheftHelper");
        title.setTextSize(26f);
        root.addView(title);

        TextView description = new TextView(this);
        description.setText("Independent Direct Boot anti-theft helper. Telegram settings and trusted-environment rules are stored in Device Protected Storage so they can be evaluated before the first PIN unlock.");
        description.setPadding(0, dp(8), 0, dp(16));
        root.addView(description);

        tokenField = new EditText(this);
        tokenField.setHint("Telegram bot token");
        tokenField.setSingleLine(true);
        tokenField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(tokenField);

        chatIdField = new EditText(this);
        chatIdField.setHint("Telegram chat ID");
        chatIdField.setSingleLine(true);
        root.addView(chatIdField);

        bootCheck = new CheckBox(this);
        bootCheck.setText("Alert on LOCKED_BOOT_COMPLETED (charging NOT required)");
        root.addView(bootCheck);

        powerCheck = new CheckBox(this);
        powerCheck.setText("Alert on first charging event after reboot");
        root.addView(powerCheck);

        locationCheck = new CheckBox(this);
        locationCheck.setText("Include best available location");
        root.addView(locationCheck);

        cameraCheck = new CheckBox(this);
        cameraCheck.setText("Attempt front camera capture (visible lock-screen fallback if background capture is denied)");
        root.addView(cameraCheck);

        TextView wifiLabel = new TextView(this);
        wifiLabel.setText("Trusted Wi-Fi SSIDs — one per line. Alerts are suppressed when one matches.");
        wifiLabel.setPadding(0, dp(12), 0, 0);
        root.addView(wifiLabel);

        trustedWifiField = new EditText(this);
        trustedWifiField.setHint("IGOR VIEIRA 5G");
        trustedWifiField.setMinLines(2);
        trustedWifiField.setGravity(android.view.Gravity.TOP);
        root.addView(trustedWifiField);

        TextView btLabel = new TextView(this);
        btLabel.setText("Trusted Bluetooth names or MAC addresses — one per line.");
        btLabel.setPadding(0, dp(12), 0, 0);
        root.addView(btLabel);

        trustedBluetoothField = new EditText(this);
        trustedBluetoothField.setHint("Venu 3S\nCreta\nBose QC Ultra Earbuds");
        trustedBluetoothField.setMinLines(4);
        trustedBluetoothField.setGravity(android.view.Gravity.TOP);
        root.addView(trustedBluetoothField);

        Button save = new Button(this);
        save.setText("Save configuration");
        save.setOnClickListener(v -> saveConfig());
        root.addView(save);

        Button requestLocation = new Button(this);
        requestLocation.setText("Grant foreground location");
        requestLocation.setOnClickListener(v -> requestForegroundLocation());
        root.addView(requestLocation);

        Button requestCamera = new Button(this);
        requestCamera.setText("Grant camera permission");
        requestCamera.setOnClickListener(v -> requestCameraPermission());
        root.addView(requestCamera);

        Button requestBluetooth = new Button(this);
        requestBluetooth.setText("Grant Nearby devices / Bluetooth permission");
        requestBluetooth.setOnClickListener(v -> requestBluetoothPermission());
        root.addView(requestBluetooth);

        Button requestNotifications = new Button(this);
        requestNotifications.setText("Grant notifications permission");
        requestNotifications.setOnClickListener(v -> requestNotificationsPermission());
        root.addView(requestNotifications);

        Button fullScreenAccess = new Button(this);
        fullScreenAccess.setText("Grant full-screen camera fallback access");
        fullScreenAccess.setOnClickListener(v -> openFullScreenIntentSettings());
        root.addView(fullScreenAccess);

        Button appSettings = new Button(this);
        appSettings.setText("Open app settings (Location → Allow all the time; Battery → Unrestricted)");
        appSettings.setOnClickListener(v -> openAppSettings());
        root.addView(appSettings);

        TextView fallbackInfo = new TextView(this);
        fallbackInfo.setText("BFU camera path: first try Camera2 directly. If Android rejects background camera access, the app posts a high-priority full-screen notification that opens a visible lock-screen activity, then retries Camera2 while the activity is foreground.");
        fallbackInfo.setPadding(0, dp(14), 0, dp(6));
        root.addView(fallbackInfo);

        TextView ownerInfo = new TextView(this);
        ownerInfo.setText("Device Owner support remains optional/experimental. The full-screen lock-screen fallback does not require Device Owner.");
        ownerInfo.setPadding(0, dp(10), 0, dp(6));
        root.addView(ownerInfo);

        Button copyOwner = new Button(this);
        copyOwner.setText("Copy Device Owner ADB command");
        copyOwner.setOnClickListener(v -> copyDeviceOwnerCommand());
        root.addView(copyOwner);

        Button test = new Button(this);
        test.setText("Test Telegram + location + front camera now");
        test.setOnClickListener(v -> sendTest());
        root.addView(test);

        Button envTest = new Button(this);
        envTest.setText("Evaluate trusted environment now");
        envTest.setOnClickListener(v -> evaluateEnvironment());
        root.addView(envTest);

        status = new TextView(this);
        status.setPadding(0, dp(16), 0, dp(24));
        status.setTextIsSelectable(true);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void loadConfig() {
        tokenField.setText(config.token());
        chatIdField.setText(config.chatId());
        bootCheck.setChecked(config.alertOnLockedBoot());
        powerCheck.setChecked(config.alertOnPowerConnected());
        locationCheck.setChecked(config.includeLocation());
        cameraCheck.setChecked(config.includeCamera());
        trustedWifiField.setText(config.trustedWifi());
        trustedBluetoothField.setText(config.trustedBluetooth());
    }

    private void saveConfig() {
        config.save(
                tokenField.getText().toString(),
                chatIdField.getText().toString(),
                bootCheck.isChecked(),
                powerCheck.isChecked(),
                locationCheck.isChecked(),
                cameraCheck.isChecked(),
                trustedWifiField.getText().toString(),
                trustedBluetoothField.getText().toString()
        );
        Toast.makeText(this, "Saved to Device Protected Storage", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void requestForegroundLocation() {
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, REQ_LOCATION);
    }

    private void requestCameraPermission() {
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLUETOOTH);
        } else {
            Toast.makeText(this, "Bluetooth permission is install-time on this Android version", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        } else {
            Toast.makeText(this, "Notification runtime permission is not required on this Android version", Toast.LENGTH_SHORT).show();
        }
    }

    private void openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT < 34) {
            Toast.makeText(this, "Full-screen intent special access is not required on this Android version", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            openAppSettings();
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void copyDeviceOwnerCommand() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("AntiTheftHelper Device Owner", DEVICE_OWNER_COMMAND));
            Toast.makeText(this, "Device Owner command copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void evaluateEnvironment() {
        saveConfig();
        TrustedEnvironment.Result env = TrustedEnvironment.evaluate(this, config);
        status.setText("Trusted: " + (env.trusted ? "YES" : "NO")
                + "\nReason: " + env.reason
                + "\nWi-Fi: " + (env.wifiSsid == null ? "unknown/not connected" : env.wifiSsid)
                + "\nBluetooth: " + (env.bluetoothDevices.isEmpty() ? "none detected" : String.join(", ", env.bluetoothDevices))
                + "\nBluetooth diagnostic: " + env.bluetoothDiagnostic
                + "\nDevice Owner: " + (isDeviceOwner() ? "ACTIVE" : "NOT ACTIVE")
                + "\nFull-screen fallback: " + (FullScreenCaptureLauncher.hasFullScreenIntentAccess(this) ? "GRANTED" : "NOT GRANTED"));
    }

    private void sendTest() {
        saveConfig();
        if (!config.isConfigured()) {
            Toast.makeText(this, "Enter Telegram token and chat ID first", Toast.LENGTH_LONG).show();
            return;
        }

        status.setText("Testing…");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            TrustedEnvironment.Result env = TrustedEnvironment.evaluate(this, config);
            CameraHelper.Result camera = config.includeCamera()
                    ? CameraHelper.captureFront(this)
                    : new CameraHelper.Result(null, "disabled");
            LocationHelper.Snapshot location = config.includeLocation() ? LocationHelper.getBest(this) : null;

            StringBuilder text = new StringBuilder("✅ AntiTheftHelper foreground test\n");
            text.append("Device Owner: ").append(isDeviceOwner() ? "YES" : "NO").append('\n');
            text.append("Trusted environment: ").append(env.trusted ? "YES - " : "NO - ").append(env.reason).append('\n');
            text.append("Camera: ").append(camera.ok()
                    ? "CAPTURED - " + camera.captureSummary()
                    : "FAILED - " + camera.error).append('\n');
            if (location != null) {
                text.append(String.format(java.util.Locale.US,
                        "Location: %.6f, %.6f (%.0f m)\nhttps://maps.google.com/?q=%.6f,%.6f",
                        location.latitude, location.longitude, location.accuracy,
                        location.latitude, location.longitude));
            } else {
                text.append("Location: unavailable");
            }

            TelegramClient.Result result;
            File photo = camera.file;
            if (camera.ok()) {
                result = TelegramClient.sendPhoto(config.token(), config.chatId(), text.toString(), photo);
            } else {
                result = TelegramClient.sendMessage(config.token(), config.chatId(), text.toString());
            }
            if (photo != null) {
                //noinspection ResultOfMethodCallIgnored
                photo.delete();
            }

            TelegramClient.Result finalResult = result;
            runOnUiThread(() -> {
                status.setText("Telegram test: " + (finalResult.ok ? "SUCCESS" : "FAILED")
                        + "\nHTTP: " + finalResult.code
                        + "\n" + finalResult.body);
                Toast.makeText(this, finalResult.ok ? "Telegram test sent" : "Telegram test failed", Toast.LENGTH_LONG).show();
            });
            executor.shutdown();
        });
    }

    private boolean isDeviceOwner() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }

    private void refreshStatus() {
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean background = Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean bluetooth = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        boolean notifications = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean fullScreen = FullScreenCaptureLauncher.hasFullScreenIntentAccess(this);

        status.setText("Configured: " + (config.isConfigured() ? "YES" : "NO")
                + "\nDevice Owner: " + (isDeviceOwner() ? "ACTIVE" : "NOT ACTIVE")
                + "\nFine location: " + (fine ? "GRANTED" : "NOT GRANTED")
                + "\nBackground location: " + (background ? "GRANTED" : "NOT GRANTED")
                + "\nCamera: " + (camera ? "GRANTED" : "NOT GRANTED")
                + "\nNearby devices/Bluetooth: " + (bluetooth ? "GRANTED" : "NOT GRANTED")
                + "\nNotifications: " + (notifications ? "GRANTED" : "NOT GRANTED")
                + "\nFull-screen camera fallback: " + (fullScreen ? "GRANTED" : "NOT GRANTED")
                + "\nPackage: " + getPackageName()
                + "\n\nDevice Owner command:\n" + DEVICE_OWNER_COMMAND
                + "\n\nBFU test: grant notifications + full-screen access first, reboot, do NOT enter the PIN, and watch the bot from another device. If direct Camera2 is denied, the visible lock-screen activity should retry the camera.");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
