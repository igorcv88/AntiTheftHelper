package com.igorcv.antithefthelper;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

final class FullScreenCaptureLauncher {
    static final String EXTRA_TRIGGER = "antitheft_trigger";
    static final String EXTRA_DIRECT_CAMERA_ERROR = "direct_camera_error";

    private static final String CHANNEL_ID = "antitheft_camera_fallback";
    private static final int NOTIFICATION_ID = 884299;

    private FullScreenCaptureLauncher() {}

    static boolean launch(Context context, String trigger, String directCameraError) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;

        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 34 && !manager.canUseFullScreenIntent()) {
            return false;
        }

        ensureChannel(manager);

        Intent activity = new Intent(context, CaptureActivity.class)
                .putExtra(EXTRA_TRIGGER, trigger == null ? "UNKNOWN" : trigger)
                .putExtra(EXTRA_DIRECT_CAMERA_ERROR, directCameraError == null ? "unknown" : directCameraError)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                activity,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);

        builder.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("AntiTheftHelper camera check")
                .setContentText("Camera fallback is starting")
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setFullScreenIntent(pendingIntent, true)
                .setContentIntent(pendingIntent);

        try {
            manager.notify(NOTIFICATION_ID, builder.build());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static void cancel(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    static boolean hasFullScreenIntentAccess(Context context) {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return manager != null && manager.canUseFullScreenIntent();
    }

    private static void ensureChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Camera fallback",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Starts the visible camera fallback on the lock screen when background camera access is unavailable.");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
    }
}
