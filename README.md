# AntiTheftHelper

Android helper focused on the **Before First Unlock (BFU)** state: the period after a reboot while the phone is still waiting for the first PIN/password unlock.

The app is intentionally independent of Tasker and root for the BFU path. It registers a `directBootAware` receiver for `LOCKED_BOOT_COMPLETED`, stores its Telegram configuration in **Device Protected Storage**, checks whether the phone is charging, attempts to obtain the best available location, and sends an alert directly through the Telegram Bot API.

## Current behavior

When configured, the app can trigger on:

- `LOCKED_BOOT_COMPLETED`, but only if the phone is charging.
- `ACTION_POWER_CONNECTED`, including while the device is still in Direct Boot.
- `BOOT_COMPLETED` as a post-unlock fallback.
- Internal retry alarms if Telegram cannot be reached immediately after boot.

The Telegram alert includes:

- Whether the device is still before the first unlock.
- Trigger source.
- Battery percentage.
- Network transport when detectable.
- Wi-Fi SSID when Android exposes it.
- Best available latitude/longitude, accuracy, provider and a Google Maps link.

No Telegram app session is required on the protected phone. The helper talks directly to `api.telegram.org`.

## Important limitation: camera before the first PIN

This version does **not** attempt a silent front-camera capture in BFU. Modern Android restricts background camera access, and Android 15+ additionally restricts camera foreground services started from boot receivers. Faking camera support here would make the anti-theft path unreliable.

Tasker can still handle the normal post-unlock path (photo + Telegram + other channels). Camera support for BFU should only be added after a separate, device-specific design is proven to work on the target One UI / Android build.

The helper also cannot reliably disable Airplane mode or enable mobile data as an ordinary app. A network connection must become available after boot for Telegram delivery.

## Telegram setup

1. Open `@BotFather` in Telegram.
2. Create a bot with `/newbot`.
3. Send `/start` to the new bot from the Telegram account that should receive the alerts.
4. Open:

   ```text
   https://api.telegram.org/botYOUR_TOKEN/getUpdates
   ```

5. Find `message.chat.id` in the JSON response. That number is the chat ID.
6. Install and open AntiTheftHelper.
7. Enter the bot token and chat ID.
8. Keep **Alert on LOCKED_BOOT_COMPLETED when charging** enabled.
9. Keep **Alert when power is connected** enabled.
10. Press **Save configuration**.
11. Press **Send Telegram test now** and verify delivery.

Do not commit the Telegram token to this repository. It is stored at runtime in the app's Device Protected Storage.

## Location permissions

For location to have the best chance of working while the app is in the background / BFU:

1. Press **Grant foreground location** in the app and allow precise location.
2. Press **Open app settings**.
3. Open **Permissions > Location** and set it to **Allow all the time**, if your firmware exposes that option.
4. Set the app battery mode to **Unrestricted** on Samsung if available.

The alert is still sent if location is unavailable.

## Real BFU test

A normal in-app test does not prove Direct Boot operation. Test the actual target state:

1. Configure the app while the phone is unlocked.
2. Reboot the phone.
3. **Do not enter the PIN.**
4. Leave the phone connected to power, or connect the charger after the lock screen appears.
5. Watch the Telegram bot from another device.

A successful alert should contain:

```text
Before first unlock: YES
```

You can inspect registration with ADB:

```bash
adb shell dumpsys package com.igorcv.antithefthelper | grep -i -E "LOCKED_BOOT_COMPLETED|directBootAware|RECEIVE_BOOT_COMPLETED"
```

## Retry behavior

If the first Telegram request fails, the helper schedules retries while the phone remains charging. Current retry delays are approximately:

```text
30 s, 1 min, 2 min, 5 min, 10 min, 15 min
```

A successful alert starts a short cooldown to avoid duplicate messages from overlapping boot/power broadcasts.

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

## Signed GitHub Releases

The repository contains a **manual-only** workflow:

```text
.github/workflows/build-release.yml
```

It has only `workflow_dispatch`; pushing commits does not start a build.

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
- reconstructs the signing keystore only inside the GitHub runner;
- builds a signed release APK;
- creates a SHA-256 checksum;
- uploads both as a workflow artifact;
- creates or updates the requested GitHub Release and attaches the APK and checksum.

The generated filenames are similar to:

```text
AntiTheftHelper-v0.1.0.apk
AntiTheftHelper-v0.1.0.apk.sha256
```

## Signing key continuity

Android updates must be signed with the same key as the installed APK. Keep the four signing secrets stable across releases. If the signing key changes, Android will reject the APK as an update and require uninstalling the existing app first.

## Security notes

- Treat the Telegram bot token as a password. Anyone who obtains it can call that bot's API.
- Device Protected Storage is necessary for BFU access, but it should contain only the minimum data required for Direct Boot operation.
- Root is not required by this project.
- Device Owner is not required by this version.
- This is a sideloaded personal anti-theft helper, not a replacement for Samsung Find / Google's device-finding features.
