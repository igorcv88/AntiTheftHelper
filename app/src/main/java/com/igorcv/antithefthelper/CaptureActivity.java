package com.igorcv.antithefthelper;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class CaptureActivity extends Activity {
    private final AtomicBoolean started = new AtomicBoolean(false);
    private ExecutorService executor;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("AntiTheftHelper security check");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setText("Camera check in progress…");
        status.setTextColor(0xffcccccc);
        status.setTextSize(16f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(14), 0, 0);
        root.addView(status);

        setContentView(root);
        executor = Executors.newSingleThreadExecutor();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!started.compareAndSet(false, true)) return;

        // Give the activity time to become RESUMED/visible before Camera2 checks the while-in-use permission state.
        new Handler(Looper.getMainLooper()).postDelayed(() -> executor.execute(this::runCapture), 700L);
    }

    private void runCapture() {
        ConfigStore config = new ConfigStore(this);
        if (!config.isConfigured() || !config.includeCamera()) {
            finishSoon("Camera fallback is not configured.");
            return;
        }

        TrustedEnvironment.Result environment = TrustedEnvironment.evaluate(this, config);
        if (environment.trusted) {
            finishSoon("Trusted environment detected. Capture cancelled.");
            return;
        }

        CameraHelper.Result camera = CameraHelper.captureFront(this);
        if (!camera.ok()) {
            finishSoon("Camera fallback failed: " + (camera.error == null ? "unknown" : camera.error));
            return;
        }

        File photo = camera.file;
        try {
            LocationHelper.Snapshot location = config.includeLocation() ? LocationHelper.getBest(this) : null;
            String trigger = getIntent().getStringExtra(FullScreenCaptureLauncher.EXTRA_TRIGGER);
            String directError = getIntent().getStringExtra(FullScreenCaptureLauncher.EXTRA_DIRECT_CAMERA_ERROR);

            StringBuilder caption = new StringBuilder();
            caption.append("📷 AntiTheftHelper camera fallback\n");
            caption.append("Trigger: ").append(trigger == null ? "UNKNOWN" : trigger).append('\n');
            caption.append("Lock-screen activity: VISIBLE\n");
            caption.append("Camera: CAPTURED - ").append(camera.captureSummary()).append('\n');
            if (directError != null && !directError.isEmpty()) {
                caption.append("Direct background camera: FAILED - ").append(directError).append('\n');
            }
            caption.append("Trusted check: ").append(environment.reason).append('\n');

            if (location != null) {
                caption.append(String.format(Locale.US,
                        "Location: %.6f, %.6f (%.0f m)\nhttps://maps.google.com/?q=%.6f,%.6f",
                        location.latitude,
                        location.longitude,
                        location.accuracy,
                        location.latitude,
                        location.longitude));
            } else {
                caption.append("Location: unavailable");
            }

            TelegramClient.Result send = TelegramClient.sendPhoto(
                    config.token(),
                    config.chatId(),
                    caption.toString(),
                    photo
            );

            if (send.ok) {
                config.markSuccess();
                finishSoon("Camera check completed.");
            } else {
                finishSoon("Photo upload failed (HTTP " + send.code + ").");
            }
        } finally {
            if (photo != null) {
                //noinspection ResultOfMethodCallIgnored
                photo.delete();
            }
        }
    }

    private void finishSoon(String message) {
        runOnUiThread(() -> {
            if (status != null) status.setText(message);
            FullScreenCaptureLauncher.cancel(this);
            new Handler(Looper.getMainLooper()).postDelayed(this::finish, 900L);
        });
    }

    @Override
    protected void onDestroy() {
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
