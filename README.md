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
  <img alt="Device Owner optional" src="https://img.shields.io/badge/Device%20Owner-optional-7C3AED">
  <img alt="Tasker independent" src="https://img.shields.io/badge/Tasker-not%20required-64748B">
  <img alt="Root not required" src="https://img.shields.io/badge/root-not%20required-64748B">
</p>

Android anti-theft helper focused on **Before First Unlock (BFU)**: the period after a reboot while the phone is still waiting for the first PIN/password unlock.

The BFU path is independent of Tasker and root. The app uses `directBootAware` components, stores runtime configuration in **Device Protected Storage**, evaluates trusted Wi-Fi/Bluetooth rules, obtains location, captures the front camera when Android permits it, and sends directly through the Telegram Bot API.

## Why this is independent of Tasker

Tasker remains useful after the first unlock, but on the target Galaxy/One UI build its normal boot task was experimentally confirmed to start only after the first PIN unlock. Making the helper dependent on Tasker would put the BFU path behind that same limitation.

```text
Before first unlock -> AntiTheftHelper runs independently
After first unlock  -> Tasker may still provide additional automations
```

## App icon

The launcher icon is a native Android **adaptive icon**, with separate background, transparent foreground and Android 13+ monochrome layers. This keeps it compatible with launcher masks, Samsung Theme Park, themed icons and Icon Pack Studio.

## Triggers

Charging is a **trigger**, not a prerequisite.

### 1. BFU boot trigger

At `LOCKED_BOOT_COMPLETED`, if enabled, the app alerts when network becomes available. Charging is not required.

```text
Trigger: BFU_BOOT
Charging: YES or NO
Before first unlock: YES
```

### 2. Charging trigger

A separate BFU path treats the first charging state/event after reboot as another trigger.

```text
Trigger: BFU_POWER_CONNECTED
Before first unlock: YES
Charging: YES
```

A duplicate cooldown prevents boot and charging events from producing two nearly simultaneous messages.

## Trusted environment suppression

Before taking a photo or sending anything, the app evaluates trusted environment rules.

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

The lists are editable. Use one SSID/device name/MAC address per line.

If any trusted Wi-Fi or Bluetooth matches, the alert is suppressed. If Android prevents the app from proving that the environment is trusted, the anti-theft logic fails open: an unknown environment does not suppress an alert.

Bluetooth state is tracked in Device Protected Storage using Direct-Boot-aware connection broadcasts plus connected-device queries when available. Grant **Nearby devices / Bluetooth** permission before BFU testing.

## Location

The app attempts to obtain the best available location and includes latitude, longitude, accuracy, provider and a Google Maps link.

Recommended setup:

1. Grant precise foreground location.
2. Open Android app settings.
3. Set **Location -> Allow all the time** if available.
4. Set battery use to **Unrestricted** on Samsung.

The Telegram alert is still sent if location is unavailable.

## Camera quality and field of view

Camera capture now prioritizes image quality and field of view instead of the previous ~1280x960 target.

The Camera2 path now:

- selects the front-facing camera with the widest reported field of view;
- requests the **largest available JPEG resolution**;
- requests JPEG quality 100;
- enables automatic exposure/white balance and picture autofocus when supported;
- reads `CONTROL_ZOOM_RATIO_RANGE` and automatically uses the **minimum supported zoom ratio**.

If a front logical camera reports a minimum zoom below `1.0x`, the helper uses it automatically. For example, if the device exposes `0.5x` or `0.8x` on the front camera through Camera2, that value is used. If the front camera reports a minimum of `1.0x`, Android does not expose a wider front-camera view to this app and the helper cannot manufacture a true `0.5x` view.

The Telegram caption reports the captured resolution and requested zoom ratio so device behavior can be verified.

## Optional Device Owner mode for BFU camera access

Android restricts camera access for ordinary background apps. AntiTheftHelper now implements a minimal Device Policy Controller (DPC) path specifically to improve BFU camera access without root.

The app includes:

```text
AntiTheftAdminReceiver
AntiTheftAdminService
```

`AntiTheftAdminService` is a `DeviceAdminService`. Android keeps this service bound for a Device Owner while the user is running, which gives the DPC process foreground importance. The helper attempts Camera2 directly from this owner process instead of starting a camera foreground service from the boot receiver.

This design is intentional because Android 15+ prohibits apps targeting API 35 from launching a `camera` foreground service directly from a `BOOT_COMPLETED` receiver.

### Provisioning Device Owner

The app cannot make itself Device Owner. Install the signed APK first, then provision it through ADB:

```bash
adb shell dpm set-device-owner --user 0 com.igorcv.antithefthelper/.AntiTheftAdminReceiver
```

The app has a **Copy Device Owner ADB command** button and displays the current status as `ACTIVE` or `NOT ACTIVE`.

Verify with:

```bash
adb shell dpm list-owners
```

and:

```bash
adb shell dumpsys device_policy | grep -i -E "device.owner|antitheft"
```

Important: Android's normal `set-device-owner` development flow has provisioning constraints. On an already configured personal phone it can fail because accounts, setup state, users or profiles already exist. Do not factory-reset a primary device merely to satisfy this experiment without first reviewing the exact `dpm` error.

The Device Owner role persists across normal reboots and does not depend on KernelSU/root remaining active.

### Device Owner BFU behavior

When Device Owner is active, the owner service gets the first chance to send:

```text
Trigger: DEVICE_OWNER_BFU_BOOT
Device Owner: YES
Before first unlock: YES
Front camera: CAPTURED - <resolution> @ <zoom>x
```

or, for a power trigger:

```text
Trigger: DEVICE_OWNER_BFU_POWER_CONNECTED
```

The existing `JobScheduler` path remains as a delayed fallback. When Device Owner is active, fallback jobs are delayed so the owner service gets the first camera attempt; the shared cooldown prevents duplicate successful alerts.

Camera capture before the first PIN is still firmware-dependent. The DPC/Device Owner path makes it substantially more plausible on modern Android, but the Galaxy/One UI result must be tested empirically.

The Android camera privacy indicator may still appear. AntiTheftHelper does not attempt to bypass system privacy indicators.

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

### Without Device Owner

1. Configure the app while unlocked.
2. Reboot.
3. **Do not enter the PIN.**
4. Leave the phone off the charger initially.
5. Watch Telegram from another device.
6. Later connect power to test the charging trigger.

The location/text BFU path should remain available even if camera access is denied.

### With Device Owner

1. Confirm the app shows `Device Owner: ACTIVE`.
2. Confirm foreground camera test works and reports the new full resolution/zoom.
3. Reboot.
4. **Do not enter the PIN.**
5. Move the device outside trusted Wi-Fi/Bluetooth criteria for the test.
6. Watch Telegram for `DEVICE_OWNER_BFU_BOOT`.
7. If testing power separately, connect the charger after the cooldown.

## ADB diagnostics

Inspect Direct Boot and Device Owner components:

```bash
adb shell dumpsys package com.igorcv.antithefthelper | grep -i -E "LOCKED_BOOT_COMPLETED|directBootAware|AntiTheftAdmin|AlertJobService|BluetoothStateReceiver"
```

Inspect ownership:

```bash
adb shell dpm list-owners
```

Inspect fallback jobs:

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

The repository contains a **manual-only** workflow at `.github/workflows/build-release.yml`. Pushes do not automatically start a release build.

Configure these Actions secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

The workflow reconstructs the signing keystore inside the runner, builds the signed APK, creates a SHA-256 checksum, and publishes both directly as **GitHub Release assets**.

It deliberately does **not** use `actions/upload-artifact`, so release builds do not consume GitHub Actions artifact-storage quota.

## Signing key continuity

Android updates must be signed with the same key as the installed APK. Keep the four signing secrets stable across releases.

## Security notes

- Treat the Telegram bot token as a password.
- Device Protected Storage is required for BFU operation, so only the minimum configuration needed before unlock is stored there.
- Root is not required.
- Device Owner is optional for location/text and recommended specifically for the experimental BFU camera path.
- Camera BFU behavior is firmware-dependent and is reported explicitly rather than silently assumed to work.
- This is a personal sideloaded anti-theft helper, not a replacement for Samsung Find or Google's device-finding services.
