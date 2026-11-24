# Circle-to-Search Trigger (OpdCTS)

[한국어](./README.md) · **English**

> [!NOTE]
> OpdCTS currently(v0.4.1) supports Korean only. An English README is provided for international users, but the app itself is not yet available in English.

**Use Circle to Search and Gemini Assistant naturally on your OPPO Pad Mini.**

OpdCTS is an Android app that makes it easy to launch Google Circle to Search and Gemini Assistant on ColorOS. It is designed for responsiveness and a natural user experience, and lets you choose which app to launch.

- 🪶 Lightweight app under 1 MB
- No root access or bootloader unlocking required
- Launches Circle to Search directly without MiCTS
- Works without persistent notifications or background polling
- Uses approximately 50 MB of memory when the settings screen is closed

**[Download the latest release](https://github.com/JngVedere/OPD2515_CST/releases/latest)** · [Changelog](./RELEASES.md) · [Report an issue](https://github.com/JngVedere/OPD2515_CST/issues)

> Tested on the OPPO Pad Mini (OPD2515). Proper operation on other devices or firmware versions is not guaranteed.

<br>

## 1. Main Features

### Trigger Modes

| Feature | Description | ADB required |
| --- | --- | :---: |
| **Bottom touch area** | Press and hold the touch-sensitive area at the bottom center of the screen. | No |
| **Native ColorOS navigation bar** | Launch while retaining native ColorOS gestures and animations. | Yes |
| **Gemini Assistant** | Press and hold the power button to launch Gemini. | Yes |
| **Custom app** | Launch an app of your choice instead of Circle to Search. | Depends on the mode |

With three-button navigation, press and hold the Home button to launch the selected app.

Choose either the bottom touch area or the native ColorOS navigation bar. Gemini Assistant can be enabled independently.

### Additional Features

| Feature | Description |
| --- | --- |
| Touch area settings | Adjust the position, size, and long-press duration of the touch area separately for portrait and landscape orientations. |
| Quick Settings tiles | Toggle OpdCTS, launch Search or Assistant, and enable Deep Dark Mode. |
| App icon settings | Configure the OpdCTS icon to open Settings, Search, or Assistant. |
| Automatic recovery after reboot | Helps restore the accessibility service after rebooting. |
| Show in Recents | Show or hide the settings screen in the recent apps list. |
| Updates | Check for and install new versions from within the app. |
| Status notifications | Device log connection and update notifications, with support for camera island (live notifications). |
| System utilities | Animation scales, maximum refresh rate, screen brightness, rotation suggestions, clock seconds, device name, and more. |
| System shortcuts | Quickly access frequently used system settings screens. |
| Double-press power button | Launch an app of your choice (experimental). |

<br>

## 2. Compatibility & Requirements

### Supported Environment

| Item | Environment |
| --- | --- |
| Tested device | OPPO Pad Mini (OPD2515) |
| Operating system | ColorOS 17 CN (Chinese firmware) |
| Root access | Not required |
| ADB | Required for certain trigger modes |

### Required Apps

- **[Google](https://play.google.com/store/apps/details?id=com.google.android.googlequicksearchbox)** — Used to launch Circle to Search and Gemini.
- **[Google Speech Recognition & Synthesis](https://play.google.com/store/search?q=google+speech+services&c=apps)** — Recommended for compatibility.

If your device does not have Google services, set up Google services and the Play Store first.

Starting with version 0.3.6, MiCTS is no longer required. If it is already installed, it is used as a fallback only when direct launching fails.

<br>

## 3. Permissions & System Changes

OpdCTS uses the following permissions depending on the selected trigger mode and enabled features.

### Permission Usage

| Permission | Purpose |
| --- | --- |
| Accessibility service | Maintain the bottom touch area and launch the selected app. |
| `READ_LOGS` | Detect native ColorOS navigation bar gestures and power button long presses. |
| `WRITE_SECURE_SETTINGS` | Support automatic accessibility recovery, gesture switching, and system utilities. |
| `INTERNET` | Check for new versions and download APKs. |
| `REQUEST_INSTALL_PACKAGES` | Request installation of in-app updates. |
| `POST_NOTIFICATIONS` | Show device log connection and update notifications. |
| `POST_PROMOTED_NOTIFICATIONS` | Support live notifications, such as those displayed in the camera island. |
| Battery optimization exemption | Minimize service interruptions during background operation. |
| Background execution | Keep the service running during screen transitions and extended use. |

Android may require you to allow restricted settings before enabling the accessibility service of a sideloaded APK.

### Changes Made by One-click ADB Setup

The ADB setup script changes or requests the activation of the following system settings:

- Disable Breeno (Xiaobu Assistant).
- Set Google as the default digital assistant.
- Allow the digital assistant to use screen and app data.
- Enable the accessibility service and grant the required permissions.
- Exempt the app from battery optimization and remove background execution restrictions.
- Allow standard and live notifications.
- Select the native ColorOS navigation bar trigger mode.
- Enable Circle to Search and power button activation of Gemini.
- Enable automatic recovery after reboot.

Disabling system optimization, approving device log access, allowing auto-launch, and locking the app in Recents must be done manually.

> **Caution:** Accessibility, device log access, and secure settings permissions can provide access to sensitive information or system settings. Disabling system optimization may also affect other apps and system behavior. Review the purpose of these permissions and the setup script before proceeding.

<br>

## 4. Privacy & Data Handling

### Screen Data

OpdCTS itself does not read or capture screen content. Screen recognition and search for Circle to Search are handled by the Google app.

### Device Logs

Device logs are used to detect ColorOS gestures and power button actions. OpdCTS does not store these logs or transmit them externally.

Android security policies require you to approve device log access again after rebooting.

### Network Access & Updates

OpdCTS uses its internet connection to check for and download app updates. It retrieves version information and APKs from GitHub without separately transmitting device information.

Data handled by third-party apps, including Google and MiCTS, is subject to their respective privacy policies.

<br>

## 5. Installation

1. Download the APK from the [latest release](https://github.com/JngVedere/OPD2515_CST/releases/latest).
2. Open the APK on your tablet and install it.
3. Launch **OpdCTS** from the app drawer.
4. Choose either the one-click ADB setup or manual setup described below.

If APK installation is restricted, check whether the app used to open the file is allowed to install apps from that source.

<br>

## 6. One-click ADB Setup

Recommended if you want to use both the native ColorOS navigation bar and power button activation of Gemini.

### Requirements

- A tablet with OpdCTS installed
- A Windows PC
- [Android Platform Tools (ADB)](https://developer.android.com/tools/releases/platform-tools)
- USB debugging enabled on your device

ADB must be added to your PATH or otherwise be available from PowerShell.

### Setup Instructions

1. Enable **Developer options → USB debugging** on your tablet.
2. Connect the tablet to your PC and **allow USB debugging** when prompted.
3. Run the following command in **Windows PowerShell**:

   ```powershell
   irm https://raw.githubusercontent.com/JngVedere/OPD2515_CST/main/scripts/adb_setup.ps1 | iex
   ```

4. Enable **Disable system optimization** in Developer options, then press Enter in PowerShell.
5. When prompted to allow device log access, select **Allow one-time access**. If the prompt does not appear, tap the log connection prompt within OpdCTS.
6. Press Enter in PowerShell. Once the automated setup finishes, the script attempts to launch Google Assistant for the first time.
7. Go to **Settings → Apps → Auto launch** and allow Circle-to-Search Trigger.
8. In OpdCTS settings, open **the Recents cleanup exclusion settings** and lock the app card in Recents.

Setup is complete once you have finished all the steps above.

- After locking the app in Recents, you can turn off **Show in Recents** in OpdCTS to hide its settings screen from the recent apps list.
- If needed, go to Settings → Apps → Associated launch and allow Circle-to-Search Trigger (optional).

[View the ADB setup script](./scripts/adb_setup.ps1)

> This command retrieves the latest script from the GitHub main branch and executes it immediately. Review the original script and the system changes before running it.

<br>

## 7. Manual Setup

If you prefer not to use one-click ADB setup or want to enable only specific features, refer to the manual setup guide.

**[Manual setup guide](./HOW%20TO%20SETUP%28MANUAL%29.md)**

The default bottom touch area mode can be configured entirely on the tablet. ADB is required for the native ColorOS navigation bar and power button activation of Gemini.

### Reopening Settings

You can reopen OpdCTS settings in any of the following ways:

- Tap the OpdCTS icon in the app drawer.
- Press and hold the OpdCTS icon → Settings.
- Settings → Apps → App management → Circle-to-Search Trigger → Additional settings in the app.
- Settings → Accessibility → Downloaded apps → Circle-to-Search Trigger → Settings.
- Press and hold the Circle-to-Search Trigger tile in Quick Settings.

<br>

## 8. Uninstall & Restore System Settings

Before uninstalling the app, we recommend taking the following steps:

1. Select **Turn off** in OpdCTS settings.
2. Disable the double-press power button feature if you have enabled it.
3. Disable the OpdCTS accessibility service.
4. Uninstall the app.

If you used the native ColorOS navigation bar or Gemini features, you can restore the relevant system settings using these ADB commands:

```powershell
adb shell pm enable --user 0 com.heytap.speechassist
adb shell settings --user 0 put secure oplus_gesture_handle_cui_enable 1
adb shell settings delete global power_button_short_press
adb shell settings delete global power_button_double_press
```

If necessary, manually restore the following settings changed by one-click setup:

- Default digital assistant
- Screen and app data access for the digital assistant
- Disable system optimization in Developer options

These commands restore certain ColorOS settings. They do not automatically restore every setting to its pre-installation value.

<br>

## 9. Known Limitations & Troubleshooting

### Known Limitations

- When using the native ColorOS navigation bar or power button activation of Gemini, device log access must be approved again after rebooting.
- Accessibility recovery or device log connection may not work correctly during the first unlock after rebooting.
- The current VIS-based launch method does not support Circle to Search's Scroll Translate (Continuous Translate) feature.
- Updates to the Google app or ColorOS may change how certain features behave.

### Frequently Asked Questions

**Q. OpdCTS stops responding after I tap Close all in Recents.**

Check whether the accessibility service, auto-launch, and background activity are enabled, and whether the app is locked in Recents.

**Q. OpdCTS does not work after rebooting.**

Unlock your device once and approve device log access. If the problem persists, check the accessibility service and automatic recovery settings, and repeat the ADB permission setup if necessary.

**Q. Circle to Search does not launch.**

Make sure Google is selected as the default digital assistant and screen and app data access is enabled.

**Q. Switching to Ask about screen is slow.**

The transition time may vary depending on the Google app's processing and the screen content. Changing the OpdCTS trigger mode may not resolve this delay.

**Q. App installation is blocked for device protection.**

This may be a Google Play Protect installation restriction. After verifying that you trust the APK, you can try installing it using ADB:

```powershell
adb install Circle-to-Search-Trigger-0.4.0.apk
```

Replace the filename with the name of the APK you downloaded.

**Q. The accessibility service is grayed out or access is denied.**

1. Open Settings → Apps → App management → Circle-to-Search Trigger.
2. Select **Allow restricted settings** from the menu in the upper-right corner.
3. Authenticate using your screen lock.
4. Enable Circle-to-Search Trigger in Accessibility settings.

If the menu is missing, try enabling the accessibility service once, then reopen the app info screen.

For other issues, please report them through [GitHub Issues](https://github.com/JngVedere/OPD2515_CST/issues).

<br>

## 10. Related Projects

- [MiCTS](https://github.com/parallelcc/MiCTS) — Reference for launching Circle to Search.
- [ColorGoogle](https://github.com/joeyandu/ColorGoogle) — Reference for the native ColorOS navigation bar trigger.
- [MindTrigger Assist](https://github.com/evokermc098-coder/MindTriggerAssist) — Reference for device log connection.

<br>

## 11. License & Changelog

Starting with version 0.3.6, OpdCTS is distributed under the **[GNU GPL-3.0](./LICENSE)** license.

- [LICENSE](./LICENSE) — Full license text
- [NOTICE](./NOTICE) — Acknowledgments for referenced projects and included libraries
- [RELEASES.md](./RELEASES.md) — Version history and release notes

<br>

## 12. Use of AI Tools

The project's features and development direction are determined by the developer. The following AI tools were used during implementation and review:

| Tool | Model | Usage |
| --- | --- | --- |
| ChatGPT | GPT Sol 6.1 High | Requirements, task coordination, feature implementation and fixes, and on-device ADB testing. |
| Claude | Claude Opus 5.5 High | Feature implementation, independent code review, and bug analysis. |
| Gemini | Gemini 4 Neon High | Documentation review. |
