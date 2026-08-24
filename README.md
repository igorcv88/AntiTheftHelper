<p align="center">
  <img src="docs/icon.svg" width="128" height="128" alt="AntiTheftHelper icon">
</p>

<h1 align="center">AntiTheftHelper</h1>

<p align="center">
  <a href="https://github.com/igorcv88/AntiTheftHelper/actions/workflows/build-release.yml"><img alt="Release build" src="https://img.shields.io/github/actions/workflow/status/igorcv88/AntiTheftHelper/build-release.yml?branch=main&label=release%20build"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Target SDK 35" src="https://img.shields.io/badge/targetSdk-35-3DDC84">
  <img alt="Java 17" src="https://img.shields.io/badge/Java-17-E76F00?logo=openjdk&logoColor=white">
  <img alt="Direct Boot" src="https://img.shields.io/badge/Direct%20Boot-BFU-2563EB">
  <img alt="Tasker independent" src="https://img.shields.io/badge/Tasker-not%20required-64748B">
  <img alt="Root not required" src="https://img.shields.io/badge/root-not%20required-64748B">
</p>

Android anti-theft helper focused on **Before First Unlock (BFU)**: the period after a reboot while the phone is still waiting for the first PIN/password unlock.

The BFU path is deliberately independent of Tasker and root. The app uses `directBootAware` components, stores the minimum runtime configuration in **Device Protected Storage**, evaluates trusted Wi-Fi/Bluetooth rules, attempts location and an experimental front-camera capture, and sends directly through the Telegram Bot API.

## Why this is independent of Tasker

Tasker remains useful after the first unlock, but on the target Galaxy/One UI build its normal boot task was experimentally confirmed to start only after the first PIN unlock. Making this helper a Tasker plugin would therefore put the BFU path back behind the same limitation.

The intended architecture is:

```text
Before first unlock -> AntiTheftHelper runs independently
After first unlock  -> Tasker may still provide additional automations
```

A Tasker-facing explicit trigger can be added later as an optional integration, but Tasker is not a dependency for theft detection.

## App icon

The launcher icon is a native Android **adaptive icon**, with separate background, transparent foreground and Android 13+ monochrome layers. This keeps it compatible with launcher masks, Samsung Theme Park, themed icons and Icon Pack Studio.

## Triggers

Charging is now a **trigger**, not a prerequisite.

### 1. BFU boot trigger

At `LOCKED_BOOT_COMPLETED`, if enabled, the app schedules an alert job that requires network connectivity but **does not require charging**.

Expected Telegram field:

```text
Trigger: BFU_BOOT
Charging: YES or NO
Before first unlock: YES
```

### 2. Charging trigger

At the same boot, the app also arms a separate one-shot `JobScheduler` job with `requiresCharging=true`. This lets the first charging state after reboot become another alert trigger even if the phone booted on battery.

Expected field:

```text
Trigger: BFU_POWER_CONNECTED
```

The app additionally listens for power connect/disconnect broadcasts as a best-effort re-arm path. Android background broadcast behavior varies by release, so the charging-constrained job is the primary BFU fallback.

A short duplicate cooldown prevents the boot and charging jobs from sending two nearly simultaneous alerts when the device boots already connected to power.

## Trusted environment suppression

Before taking a photo or sending anything, the app evaluates the configured trusted environment.

Default trusted Wi-Fi:

```text
IGOR VIEIRA 5G
```

Default trusted Bluetooth devices:

```text
Venu 3S
Creta
Bose QC Ultra Earbuds
```

These lists are editable in the app. Use one SSID/device name/MAC address per line.

If any configured trusted Wi-Fi or Bluetooth matches, the alert is suppressed. If Android refuses access to Wi-Fi/Bluetooth state or the required permission is missing, the anti-theft logic **fails open**: inability to prove that the environment is trusted does not suppress an alert.

Bluetooth connection state is tracked in Device Protected Storage from Direct-Boot-aware ACL/A2DP/headset broadcasts, with an additional GATT connected-device query when available. Grant **Nearby devices / Bluetooth** permission before performing the BFU test.

## Location

The app attempts to obtain the best available last-known/current location and includes latitude, longitude, accuracy, provider and a Google Maps link.

Recommended setup:

1. Grant precise foreground location.
2. Open Android app settings.
3. Set **Location -> Allow all the time** if available.
4. Set battery use to **Unrestricted** on Samsung.

The Telegram alert is still sent if location is unavailable.

## Experimental front camera

The app now includes an experimental direct Camera2 front-camera capture. The JPEG is written to Device Protected cache, uploaded with Telegram `sendPhoto`, then deleted locally.

Grant **Camera** permission while the phone is unlocked. Use **Test Telegram + location + front camera now** first to prove that the camera and multipart upload work in the normal foreground state.

### BFU limitation

Modern Android restricts camera access from background apps, especially around boot. Android 14+ applies while-in-use permission restrictions to camera access, and Android 15+ prevents ordinary apps from starting a camera foreground service from `BOOT_COMPLETED` in the usual case.

This project intentionally attempts the direct Camera2 path anyway so the exact Galaxy/One UI behavior can be tested. A BFU camera failure does **not** cancel the alert: Telegram still receives the location/text message and includes the camera error, for example:

```text
Front camera: FAILED - CameraDevice error ...
```

If the target firmware permits the direct capture, Telegram receives the photo with the alert text as its caption.

## Telegram setup

1. Open `@BotFather` in Telegram.
2. Create a bot with `/newbot`.
3. Send `/start` to the bot from the receiving Telegram account.
4. Open:

   ```text
   https://api.telegram.org/botYOUR_TOKEN/getUpdates
   ```

5. Find `message.chat.id`.
6. Install and open AntiTheftHelper.
7. Enter the token and chat ID.
8. Configure trusted Wi-Fi/Bluetooth lists.
9. Grant Location, Camera and Nearby devices/Bluetooth permissions.
10. Save configuration.
11. Run the foreground Telegram/camera test.

The Telegram app does not need to be installed or logged in on the protected phone. AntiTheftHelper calls `api.telegram.org` directly.

Do not commit the bot token to this repository. Runtime configuration is stored in Device Protected Storage.

## Real BFU tests

### Boot without charger

1. Configure the app while unlocked.
2. Reboot.
3. **Do not enter the PIN.**
4. Leave the phone off the charger.
5. Wait for network connectivity.
6. Watch Telegram from another device.

Expected:

```text
Trigger: BFU_BOOT
Before first unlock: YES
Charging: NO
```

### Charging trigger

1. Reboot without entering the PIN.
2. If the boot alert already arrived, wait beyond the duplicate cooldown.
3. Connect power.
4. Watch Telegram.

Expected when the charging job fires:

```text
Trigger: BFU_POWER_CONNECTED
Before first unlock: YES
Charging: YES
```

### Trusted environment test

Before reboot, use **Evaluate trusted environment now**. Confirm that the app reports `Trusted: YES` while connected to one of the configured trusted Wi-Fi/Bluetooth devices and `Trusted: NO` outside those environments.

## ADB diagnostics

Inspect Direct Boot registration:

```bash
adb shell dumpsys package com.igorcv.antithefthelper | grep -i -E "LOCKED_BOOT_COMPLETED|directBootAware|AlertJobService|BluetoothStateReceiver|RECEIVE_BOOT_COMPLETED"
```

Inspect scheduled jobs during BFU:

```bash
adb shell dumpsys jobscheduler | grep -i -A 30 -B 5 "com.igorcv.antithefthelper"
```

## Building locally

Requirements:

- JDK 17
- Android SDK Platform 35
- Gradle 8.9

Debug build:

```bash
gradle :app:assembleDebug
```

Unsigned release build when no signing environment is supplied:

```bash
gradle :app:assembleRelease
```

Gradle build caching and parallel task execution are enabled, and GitHub Actions persists the Gradle cache between manual release runs.

## Signed GitHub Releases

The repository contains a **manual-only** workflow:

```text
.github/workflows/build-release.yml
```

It has only `workflow_dispatch`; pushes do not automatically consume Actions minutes.

Configure these Actions secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

To publish an APK:

1. Open **Actions**.
2. Select **Build signed APK and publish release**.
3. Choose **Run workflow**.
4. Enter `version_name`, integer `version_code`, `tag_name` and release title.
5. Run manually.

The workflow restores/persists Gradle cache, reconstructs the signing keystore in the runner, builds the signed APK, creates a SHA-256 checksum, and publishes both directly as **GitHub Release assets**.

It deliberately does **not** call `actions/upload-artifact`, so APK builds do not consume GitHub Actions artifact-storage quota.

Generated filenames are similar to:

```text
AntiTheftHelper-v0.2.0.apk
AntiTheftHelper-v0.2.0.apk.sha256
```

## Signing key continuity

Android updates must be signed with the same key as the installed APK. Keep the four signing secrets stable across releases. If the signing key changes, Android rejects the APK as an update.

## Security notes

- Treat the Telegram bot token as a password.
- Device Protected Storage is required for BFU operation, so only the minimum configuration needed before unlock is stored there.
- Root is not required.
- Device Owner is not required for the current implementation.
- Camera BFU behavior is firmware-dependent and is deliberately reported rather than silently assumed to work.
- This is a personal sideloaded anti-theft helper, not a replacement for Samsung Find or Google's device-finding services.
