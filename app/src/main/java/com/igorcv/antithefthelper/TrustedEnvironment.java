package com.igorcv.antithefthelper;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class TrustedEnvironment {
    static final class Result {
        final boolean trusted;
        final String reason;
        final String wifiSsid;
        final List<String> bluetoothDevices;
        final String bluetoothDiagnostic;

        Result(boolean trusted,
               String reason,
               String wifiSsid,
               List<String> bluetoothDevices,
               String bluetoothDiagnostic) {
            this.trusted = trusted;
            this.reason = reason;
            this.wifiSsid = wifiSsid;
            this.bluetoothDevices = bluetoothDevices;
            this.bluetoothDiagnostic = bluetoothDiagnostic;
        }
    }

    static Result evaluate(Context context, ConfigStore config) {
        String ssid = wifiSsid(context);
        Set<String> trustedWifi = parse(config.trustedWifi());
        if (ssid != null && containsIgnoreCase(trustedWifi, ssid)) {
            return new Result(true, "trusted Wi-Fi: " + ssid, ssid, new ArrayList<>(), "not needed");
        }

        BluetoothRead bt = readBluetooth(context);
        Set<String> trustedBt = parse(config.trustedBluetooth());
        for (String connected : bt.devices) {
            if (containsIgnoreCase(trustedBt, connected)) {
                return new Result(true, "trusted Bluetooth: " + connected, ssid, bt.devices, bt.diagnostic);
            }
        }

        return new Result(false, "no trusted environment matched", ssid, bt.devices, bt.diagnostic);
    }

    private static final class BluetoothRead {
        final List<String> devices;
        final String diagnostic;

        BluetoothRead(List<String> devices, String diagnostic) {
            this.devices = devices;
            this.diagnostic = diagnostic;
        }
    }

    private static BluetoothRead readBluetooth(Context context) {
        LinkedHashSet<String> values = new LinkedHashSet<>(new BluetoothStateStore(context).connected());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return new BluetoothRead(new ArrayList<>(values), "BLUETOOTH_CONNECT not granted; fail-open");
        }

        try {
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (manager != null) {
                List<BluetoothDevice> gatt = manager.getConnectedDevices(BluetoothProfile.GATT);
                for (BluetoothDevice device : gatt) {
                    try {
                        String name = device.getName();
                        String address = device.getAddress();
                        if (name != null && !name.trim().isEmpty()) values.add(name.trim());
                        if (address != null && !address.trim().isEmpty()) values.add(address.trim());
                    } catch (SecurityException ignored) {
                    }
                }
            }
            return new BluetoothRead(new ArrayList<>(values), "OK");
        } catch (Exception e) {
            return new BluetoothRead(new ArrayList<>(values), e.getClass().getSimpleName() + ": " + e.getMessage());
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

    private static Set<String> parse(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        String normalized = raw.replace(';', '\n').replace(',', '\n');
        for (String item : normalized.split("\\n")) {
            String value = item.trim();
            if (!value.isEmpty()) out.add(value);
        }
        return out;
    }

    private static boolean containsIgnoreCase(Set<String> set, String value) {
        if (value == null) return false;
        String needle = value.trim().toLowerCase(Locale.ROOT);
        for (String item : set) {
            if (item.trim().toLowerCase(Locale.ROOT).equals(needle)) return true;
        }
        return false;
    }
}
