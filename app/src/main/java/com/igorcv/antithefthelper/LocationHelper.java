package com.igorcv.antithefthelper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class LocationHelper {
    static final class Snapshot {
        final double latitude;
        final double longitude;
        final float accuracy;
        final long time;
        final String provider;

        Snapshot(Location location) {
            latitude = location.getLatitude();
            longitude = location.getLongitude();
            accuracy = location.hasAccuracy() ? location.getAccuracy() : -1f;
            time = location.getTime();
            provider = location.getProvider() == null ? "unknown" : location.getProvider();
        }
    }

    static Snapshot getBest(Context context) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }

        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;

        Location best = bestLastKnown(lm);

        boolean backgroundAllowed = Build.VERSION.SDK_INT < 29
                || context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (Build.VERSION.SDK_INT >= 30 && backgroundAllowed) {
            Location current = currentLocation(lm);
            if (current != null && isBetter(current, best)) {
                best = current;
            }
        }

        return best == null ? null : new Snapshot(best);
    }

    private static Location bestLastKnown(LocationManager lm) {
        Location best = null;
        List<String> providers = new ArrayList<>();
        try {
            providers.addAll(lm.getProviders(true));
        } catch (Exception ignored) {
        }

        for (String provider : providers) {
            try {
                Location candidate = lm.getLastKnownLocation(provider);
                if (candidate != null && isBetter(candidate, best)) {
                    best = candidate;
                }
            } catch (SecurityException ignored) {
            }
        }
        return best;
    }

    private static Location currentLocation(LocationManager lm) {
        String provider = null;
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                provider = LocationManager.GPS_PROVIDER;
            } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                provider = LocationManager.NETWORK_PROVIDER;
            }
        } catch (Exception ignored) {
        }
        if (provider == null) return null;

        AtomicReference<Location> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        CancellationSignal cancellation = new CancellationSignal();
        try {
            lm.getCurrentLocation(provider, cancellation, Executors.newSingleThreadExecutor(), location -> {
                result.set(location);
                latch.countDown();
            });
            latch.await(3500, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            cancellation.cancel();
        }
        return result.get();
    }

    private static boolean isBetter(Location candidate, Location current) {
        if (candidate == null) return false;
        if (current == null) return true;

        long timeDelta = candidate.getTime() - current.getTime();
        if (timeDelta > 120_000) return true;
        if (timeDelta < -120_000) return false;

        if (candidate.hasAccuracy() && current.hasAccuracy()) {
            return candidate.getAccuracy() < current.getAccuracy();
        }
        return candidate.getTime() > current.getTime();
    }
}
