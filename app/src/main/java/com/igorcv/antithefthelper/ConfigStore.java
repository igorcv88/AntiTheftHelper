package com.igorcv.antithefthelper;

import android.content.Context;
import android.content.SharedPreferences;

final class ConfigStore {
    private static final String PREFS = "antitheft_direct_boot";
    private static final String KEY_TOKEN = "telegram_token";
    private static final String KEY_CHAT_ID = "telegram_chat_id";
    private static final String KEY_BOOT = "alert_on_locked_boot";
    private static final String KEY_POWER = "alert_on_power_connected";
    private static final String KEY_LOCATION = "include_location";
    private static final String KEY_LAST_SUCCESS = "last_success_ms";

    private final SharedPreferences prefs;

    ConfigStore(Context context) {
        Context dp = context.createDeviceProtectedStorageContext();
        prefs = dp.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String token() {
        return prefs.getString(KEY_TOKEN, "").trim();
    }

    String chatId() {
        return prefs.getString(KEY_CHAT_ID, "").trim();
    }

    boolean alertOnLockedBoot() {
        return prefs.getBoolean(KEY_BOOT, true);
    }

    boolean alertOnPowerConnected() {
        return prefs.getBoolean(KEY_POWER, true);
    }

    boolean includeLocation() {
        return prefs.getBoolean(KEY_LOCATION, true);
    }

    boolean isConfigured() {
        return !token().isEmpty() && !chatId().isEmpty();
    }

    long lastSuccessMs() {
        return prefs.getLong(KEY_LAST_SUCCESS, 0L);
    }

    void markSuccess() {
        prefs.edit().putLong(KEY_LAST_SUCCESS, System.currentTimeMillis()).apply();
    }

    void save(String token, String chatId, boolean boot, boolean power, boolean location) {
        prefs.edit()
                .putString(KEY_TOKEN, token == null ? "" : token.trim())
                .putString(KEY_CHAT_ID, chatId == null ? "" : chatId.trim())
                .putBoolean(KEY_BOOT, boot)
                .putBoolean(KEY_POWER, power)
                .putBoolean(KEY_LOCATION, location)
                .apply();
    }
}
