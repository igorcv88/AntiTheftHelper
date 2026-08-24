package com.igorcv.antithefthelper;

import android.Manifest;
import android.app.Activity;
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 1001;

    private EditText tokenField;
    private EditText chatIdField;
    private CheckBox bootCheck;
    private CheckBox powerCheck;
    private CheckBox locationCheck;
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
        description.setText("Direct Boot alert helper. Configuration is stored in Device Protected Storage so the boot receiver can read it before the first PIN unlock.");
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
        chatIdField.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(chatIdField);

        bootCheck = new CheckBox(this);
        bootCheck.setText("Alert on LOCKED_BOOT_COMPLETED when charging");
        root.addView(bootCheck);

        powerCheck = new CheckBox(this);
        powerCheck.setText("Alert when power is connected");
        root.addView(powerCheck);

        locationCheck = new CheckBox(this);
        locationCheck.setText("Include best available location");
        root.addView(locationCheck);

        Button save = new Button(this);
        save.setText("Save configuration");
        save.setOnClickListener(v -> saveConfig());
        root.addView(save);

        Button requestLocation = new Button(this);
        requestLocation.setText("Grant foreground location");
        requestLocation.setOnClickListener(v -> requestForegroundLocation());
        root.addView(requestLocation);

        Button appSettings = new Button(this);
        appSettings.setText("Open app settings (set Location to Allow all the time)");
        appSettings.setOnClickListener(v -> openAppSettings());
        root.addView(appSettings);

        Button test = new Button(this);
        test.setText("Send Telegram test now");
        test.setOnClickListener(v -> sendTest());
        root.addView(test);

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
    }

    private void saveConfig() {
        config.save(
                tokenField.getText().toString(),
                chatIdField.getText().toString(),
                bootCheck.isChecked(),
                powerCheck.isChecked(),
                locationCheck.isChecked()
        );
        Toast.makeText(this, "Saved to Device Protected Storage", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void requestForegroundLocation() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_LOCATION);
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
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
            LocationHelper.Snapshot location = config.includeLocation() ? LocationHelper.getBest(this) : null;
            StringBuilder text = new StringBuilder("✅ AntiTheftHelper test\n");
            text.append("Device Protected Storage configuration is readable.\n");
            if (location != null) {
                text.append(String.format(java.util.Locale.US,
                        "Location: %.6f, %.6f (%.0f m)\nhttps://maps.google.com/?q=%.6f,%.6f",
                        location.latitude, location.longitude, location.accuracy,
                        location.latitude, location.longitude));
            } else {
                text.append("Location: unavailable");
            }
            TelegramClient.Result result = TelegramClient.sendMessage(config.token(), config.chatId(), text.toString());
            runOnUiThread(() -> {
                status.setText("Telegram test: " + (result.ok ? "SUCCESS" : "FAILED")
                        + "\nHTTP: " + result.code
                        + "\n" + result.body);
                Toast.makeText(this, result.ok ? "Telegram test sent" : "Telegram test failed", Toast.LENGTH_LONG).show();
            });
            executor.shutdown();
        });
    }

    private void refreshStatus() {
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean background = Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;

        status.setText("Configured: " + (config.isConfigured() ? "YES" : "NO")
                + "\nFine location: " + (fine ? "GRANTED" : "NOT GRANTED")
                + "\nBackground location: " + (background ? "GRANTED" : "NOT GRANTED")
                + "\nPackage: " + getPackageName()
                + "\n\nFor the real test: reboot, do NOT enter the PIN, keep/connect power, and watch the bot from another device.");
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
