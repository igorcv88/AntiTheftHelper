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
  <img alt="Lock-screen fallback" src="https://img.shields.io/badge/camera-lock--screen%20fallback-2563EB">
  <img alt="Tasker independent" src="https://img.shields.io/badge/Tasker-not%20required-64748B">
  <img alt="Root not required" src="https://img.shields.io/badge/root-not%20required-64748B">
</p>

Android anti-theft helper focused on **Before First Unlock (BFU)**: the period after a reboot while the phone is still waiting for the first PIN/password unlock.

The BFU path is independent of Tasker and root. The app uses `directBootAware` components, stores runtime configuration in **Device Protected Storage**, evaluates trusted Wi-Fi/Bluetooth rules, obtains location, tries Camera2 directly, and can fall back to a visible full-screen lock-screen activity when Android rejects background camera access. Alerts are sent directly through the Telegram Bot API.

## Why this is independent of Tasker

Tasker remains useful after the first unlock, but on the target Galaxy/One UI build its normal boot task was experimentally confirmed to start only after the first PIN unlock.

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

A duplicate cooldown prevents boot and charging events from producing two nearly simultaneous base alerts.

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

The Camera2 path:

- selects the front-facing camera with the widest reported field of view;
- requests the **largest available JPEG resolution**;
- requests JPEG quality 100;
- enables automatic exposure/white balance and picture autofocus when supported;
- reads `CONTROL_ZOOM_RATIO_RANGE` and automatically uses the **minimum supported zoom ratio**.

If a front logical camera reports a minimum zoom below `1.0x`, the helper uses it automatically. If the front camera reports a minimum of `1.0x`, Android does not expose a wider front-camera view to this app and the helper cannot manufacture a true `0.5x` view.

The Telegram caption reports the captured resolution and requested zoom ratio so device behavior can be verified.

## Visible lock-screen camera fallback

Modern Android treats `CAMERA` as a while-in-use permission, so a Direct-Boot `JobService` can obtain location/network while Camera2 is still rejected because the UID is background.

AntiTheftHelper now uses this sequence:

```text
BFU trigger
  -> trusted environment check
  -> direct Camera2 attempt
      -> success: send photo normally
      -> failure: send the location/text alert immediately
                  + post a high-priority full-screen notification
                  + open CaptureActivity over the lock screen
                  + retry Camera2 while the activity is visible/foreground
                  + send the captured photo as a second Telegram message
```

`CaptureActivity` is `directBootAware`, `showWhenLocked=true`, `turnScreenOn=true`, excluded from Recents and marked `noHistory`. It displays a visible security-check screen while Camera2 is active and closes automatically after the attempt.

The camera privacy indicator remains controlled by Android. AntiTheftHelper does not attempt to suppress system privacy indicators.

### Required setup for the fallback

In addition to Camera permission:

1. Grant **Notifications** permission on Android 13+.
2. On Android 14+, tap **Grant full-screen camera fallback access** and enable the special full-screen-intent access for AntiTheftHelper.
3. Confirm the app status shows:

```text
Notifications: GRANTED
Full-screen camera fallback: GRANTED
```

If either permission/access is missing, the normal BFU location/text alert still works and reports:

```text
Full-screen camera fallback: UNAVAILABLE / NOT GRANTED
```

If the fallback notification is accepted by Android, the base alert reports:

```text
Full-screen camera fallback: REQUESTED
```

and a successful second message contains:

```text
📷 AntiTheftHelper camera fallback
Lock-screen activity: VISIBLE
Camera: CAPTURED - <resolution> @ <zoom>x
```

## Optional Device Owner mode

The repository still contains `AntiTheftAdminReceiver` and `AntiTheftAdminService` for Device Owner experiments, but Device Owner is no longer required for the primary camera fallback.

Provisioning command:

```bash
adb shell dpm set-device-owner --user 0 com.igorcv.antithefthelper/.AntiTheftAdminReceiver
```

On already configured personal devices, Android can reject Device Owner provisioning because setup is complete, accounts exist, or additional managed profiles/users exist. Do not factory-reset a primary device just to enable this optional path without reviewing the exact `dpm` failure first.

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
10. Grant Notifications permission.
11. On Android 14+, grant full-screen camera fallback access.
12. Save configuration.
13. Run the foreground Telegram/camera test.

The Telegram app does not need to be installed or logged in on the protected phone. AntiTheftHelper calls `api.telegram.org` directly.

Do not commit the bot token to this repository. Runtime configuration is stored in Device Protected Storage.

## Real BFU test

1. Configure the app while unlocked.
2. Confirm the foreground camera test works.
3. Confirm Notifications and Full-screen camera fallback both show `GRANTED`.
4. Move outside all configured trusted Wi-Fi/Bluetooth criteria.
5. Reboot.
6. **Do not enter the PIN.**
7. Watch Telegram from another device.

Expected sequence when direct background Camera2 is denied but the full-screen fallback succeeds:

```text
Message 1:
🚨 AntiTheftHelper
Trigger: BFU_BOOT
Before first unlock: YES
Front camera: FAILED - ...
Full-screen camera fallback: REQUESTED
Location: ...

Message 2:
📷 AntiTheftHelper camera fallback
Lock-screen activity: VISIBLE
Camera: CAPTURED - ...
Location: ...
```

Later connect power to test the independent charging trigger.

## ADB diagnostics

Inspect Direct Boot and fallback components:

```bash
adb shell dumpsys package com.igorcv.antithefthelper | grep -i -E "LOCKED_BOOT_COMPLETED|directBootAware|CaptureActivity|AlertJobService|BluetoothStateReceiver"
```

Inspect fallback jobs:

```bash
adb shell dumpsys jobscheduler | grep -i -A 30 -B 5 "com.igorcv.antithefthelper"
```

Inspect Camera AppOps when debugging OEM restrictions:

```bash
adb shell cmd appops get com.igorcv.antithefthelper CAMERA
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

Release versioning is automatic. Running the workflow with **Run workflow** calculates the next release from the latest `vMAJOR.MINOR.0` tag:

```text
v0.1.0 -> v0.2.0 -> ... -> v0.9.0 -> v1.0.0 -> v1.1.0 -> ...
```

Android `versionCode` uses the monotonically increasing GitHub Actions run number, so failed builds do not create version-code collisions.

The workflow reconstructs the signing keystore inside the runner, builds the signed APK, creates a SHA-256 checksum, and publishes both directly as **GitHub Release assets**. It deliberately does **not** use `actions/upload-artifact`, so release builds do not consume GitHub Actions artifact-storage quota.

## Signing key continuity

Android updates must be signed with the same key as the installed APK. Keep the four signing secrets stable across releases.

## Security notes

- Treat the Telegram bot token as a password.
- Device Protected Storage is required for BFU operation, so only the minimum configuration needed before unlock is stored there.
- Root is not required.
- Tasker is not required for the BFU path.
- Device Owner is optional and experimental.
- The full-screen camera fallback is deliberately visible and relies on Android's normal notification/full-screen-intent controls.
- Camera behavior before the first PIN remains firmware-dependent and is reported explicitly.
- This is a personal sideloaded anti-theft helper, not a replacement for Samsung Find or Google's device-finding services.
