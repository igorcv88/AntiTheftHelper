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
  <img alt="Root not required" src="https://img.shields.io/badge/root-not%20required-64748B">
</p>

Android helper focused on the **Before First Unlock (BFU)** state: the period after a reboot while the phone is still waiting for the first PIN/password unlock.

The app is independent of Tasker and root for the BFU path. It registers a `directBootAware` receiver for `LOCKED_BOOT_COMPLETED`, stores its Telegram configuration in **Device Protected Storage**, and arms a `directBootAware` `JobService` that requires both **charging** and an **available network**. When those constraints are satisfied while the device is still before the first unlock, it attempts to obtain the best available location and sends an alert directly through the Telegram Bot API.

## App icon

The launcher icon is implemented as a native Android **adaptive icon**, with separate layers for:

- opaque background;
- transparent foreground artwork;
- round launcher icon;
- Android 13+ `monochrome` themed-icon layer.

That keeps the icon compatible with launcher masks, Samsung Theme Park, themed icons and tools such as Icon Pack Studio instead of baking everything into a single square bitmap.

## Current behavior

At `LOCKED_BOOT_COMPLETED`:

- If **Alert on LOCKED_BOOT_COMPLETED when charging** is enabled and the phone is already charging, the BFU alert job is armed.
- If **Alert when power is connected** is enabled, the BFU alert job is armed even if the phone is not charging yet; Android's `JobScheduler` waits for the charging constraint to become true.
- The job also waits for any usable network before it runs.
- If the first Telegram attempt fails, `JobScheduler` requests a retry with exponential backoff.
- At the first normal `BOOT_COMPLETED` after the user unlocks, the BFU job is cancelled so it does not become a normal post-unlock trigger.

Using a charging-constrained `JobService` is deliberate: modern Android does not reliably deliver `ACTION_POWER_CONNECTED` to manifest-declared receivers in ordinary apps, while a Direct-Boot-aware scheduled job can wait for charging without keeping a process alive.

The Telegram alert includes:

- `Before first unlock: YES`.
- Trigger source.
- Battery percentage.
- Network transport when detectable.
- Wi-Fi SSID when Android exposes it.
- Best available latitude/longitude, accuracy, provider and a Google Maps link.

No Telegram app session is required on the protected phone. The helper talks directly to `api.telegram.org`.

## Important limitation: camera before the first PIN

This version does **not** attempt a silent front-camera capture in BFU. Modern Android restricts background camera access, and Android 15+ additionally restricts camera foreground services started from boot receivers. Camera support needs a separate, device-specific solution rather than pretending a normal background camera call will be reliable.

Tasker can still handle the normal post-unlock path (photo + Telegram + other channels).

The helper also cannot reliably disable Airplane mode or enable mobile data as an ordinary app. A network connection must become available after boot for Telegram delivery.

## Telegram setup

1. Open `@BotFather` in Telegram.
2. Create a bot with `/newbot`.
3. Send `/start` to the new bot from the Telegram account that should receive alerts.
4. Open:

   ```text
   https://api.telegram.org/botYOUR_TOKEN/getUpdates
   ```

5. Find `message.chat.id` in the JSON response. That number is the chat ID.
6. Install and open AntiTheftHelper.
7. Enter the bot token and chat ID.
8. Keep **Alert on LOCKED_BOOT_COMPLETED when charging** enabled.
9. Keep **Alert when power is connected** enabled if you want the app to wait for a charger that is connected after boot.
10. Press **Save configuration**.
11. Press **Send Telegram test now** and verify delivery.

Do not commit the Telegram token to this repository. It is stored at runtime in the app's Device Protected Storage.

## Location permissions

For location to have the best chance of working while the app is in the background / BFU:

1. Press **Grant foreground location** in the app and allow precise location.
2. Press **Open app settings**.
3. Open **Permissions > Location** and set it to **Allow all the time**, if your firmware exposes that option.
4. Set the app battery mode to **Unrestricted** on Samsung if available. Android can defer `LOCKED_BOOT_COMPLETED` for apps placed in the restricted battery state.

The Telegram alert is still sent if location is unavailable.

## Real BFU test

A normal in-app test does not prove Direct Boot operation. Test the actual target state:

1. Configure the app while the phone is unlocked.
2. Reboot the phone.
3. **Do not enter the PIN.**
4. Either boot with the charger connected or connect it after the lock screen appears.
5. Wait for network connectivity.
6. Watch the Telegram bot from another device.

A successful alert should contain:

```text
Before first unlock: YES
Trigger: BFU_CHARGING_JOB
```

You can inspect the Direct Boot components with ADB:

```bash
adb shell dumpsys package com.igorcv.antithefthelper | grep -i -E "LOCKED_BOOT_COMPLETED|directBootAware|AlertJobService|RECEIVE_BOOT_COMPLETED"
```

You can also inspect the scheduled job after reboot, before entering the PIN:

```bash
adb shell dumpsys jobscheduler | grep -i -A 20 -B 5 "com.igorcv.antithefthelper"
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

Unsigned release build if no signing environment is supplied:

```bash
gradle :app:assembleRelease
```

Gradle build caching and parallel task execution are enabled in `gradle.properties`, and the GitHub Actions workflow persists the Gradle cache between manual release runs.

## Signed GitHub Releases

The repository contains a **manual-only** workflow:

```text
.github/workflows/build-release.yml
```

It has only `workflow_dispatch`; pushing commits does not start a build. Manual runs still consume Actions minutes when the repository/account billing model charges for hosted runners, but no minutes are consumed simply by pushing code.

Configure these repository Actions secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

`KEYSTORE_BASE64` must contain the complete signing keystore encoded as Base64.

To publish an APK:

1. Open **Actions** on GitHub.
2. Select **Build signed APK and publish release**.
3. Choose **Run workflow**.
4. Enter a `version_name`, integer `version_code`, release `tag_name`, and release title.
5. Run it manually.

The workflow:

- installs the Android SDK and Gradle;
- restores/persists the Gradle cache;
- reconstructs the signing keystore only inside the GitHub runner;
- builds a signed release APK;
- creates a SHA-256 checksum;
- publishes the APK and checksum directly as **GitHub Release assets**.

It deliberately does **not** use `actions/upload-artifact`, so release builds do not consume GitHub Actions artifact-storage quota. The APK exists only as a Release asset after the runner finishes.

Generated filenames are similar to:

```text
AntiTheftHelper-v0.1.0.apk
AntiTheftHelper-v0.1.0.apk.sha256
```

## Signing key continuity

Android updates must be signed with the same key as the installed APK. Keep the four signing secrets stable across releases. If the signing key changes, Android will reject the APK as an update and require uninstalling the existing app first.

## Security notes

- Treat the Telegram bot token as a password. Anyone who obtains it can call that bot's API.
- Device Protected Storage is necessary for BFU access. This app stores only the configuration required for the Direct Boot alert there.
- Root is not required.
- Device Owner is not required by this version.
- This is a sideloaded personal anti-theft helper, not a replacement for Samsung Find / Google's device-finding features.
