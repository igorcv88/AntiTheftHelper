package com.igorcv.antithefthelper;

import android.Manifest;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public class BluetoothStateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        BluetoothDevice device;
        if (Build.VERSION.SDK_INT >= 33) {
            device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        } else {
            //noinspection deprecation
            device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        }
        if (device == null) return;

        String name;
        String address;
        try {
            name = device.getName();
            address = device.getAddress();
        } catch (SecurityException e) {
            return;
        }

        int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, Integer.MIN_VALUE);
        String action = intent.getAction();
        boolean connected = BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                || ((BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                || BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action))
                && state == BluetoothProfile.STATE_CONNECTED);
        boolean disconnected = BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)
                || ((BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                || BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action))
                && state == BluetoothProfile.STATE_DISCONNECTED);

        BluetoothStateStore store = new BluetoothStateStore(context);
        if (connected) {
            store.add(name);
            store.add(address);
        } else if (disconnected) {
            store.remove(name);
            store.remove(address);
        }
    }
}
