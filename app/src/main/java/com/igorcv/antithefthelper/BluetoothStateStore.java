package com.igorcv.antithefthelper;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

final class BluetoothStateStore {
    private static final String PREFS = "antitheft_bt_state";
    private static final String KEY_CONNECTED = "connected_devices";

    private final SharedPreferences prefs;

    BluetoothStateStore(Context context) {
        Context dp = context.createDeviceProtectedStorageContext();
        prefs = dp.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized Set<String> connected() {
        Set<String> stored = prefs.getStringSet(KEY_CONNECTED, null);
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    synchronized void add(String value) {
        if (value == null) return;
        String normalized = value.trim();
        if (normalized.isEmpty()) return;
        Set<String> values = connected();
        values.add(normalized);
        prefs.edit().putStringSet(KEY_CONNECTED, values).apply();
    }

    synchronized void remove(String value) {
        if (value == null) return;
        String normalized = value.trim();
        if (normalized.isEmpty()) return;
        Set<String> values = connected();
        values.removeIf(existing -> existing.equalsIgnoreCase(normalized));
        prefs.edit().putStringSet(KEY_CONNECTED, values).apply();
    }

    synchronized void clear() {
        prefs.edit().remove(KEY_CONNECTED).apply();
    }
}
