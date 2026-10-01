# Solipsistic

An Xposed / LSPosed privacy module for Android that sanitizes shared media metadata and globally shields device inspection (USB debugging, developer options, and accessibility detection).

Tested on: **Android 14–16 (HyperOS 2.0 / 3.0 & AOSP)**

## Features

### 1. Media Privacy (EXIF & Metadata Sanitization)
- **Automatic EXIF Sanitization:** Strips identifying EXIF tags (device model, serial numbers, camera owner, lens info, and comments) on photo access and picker selection.
- **Video & Audio Metadata:** Cleans MP4/MOV atoms (`moov.udta.meta`) and audio metadata to remove device and author tags.
- **Generic Filenames:** Replaces descriptive camera filenames (e.g. `IMG_20261001_...`, `VID_...`) with clean generic names (`image_123.jpg`, `video_123.mp4`) when shared or selected via Photo Picker.

### 2. Device Privacy Shield (Anti-Detection)
- **USB & Wireless Debugging Cloaking:** Hides `adb_enabled`, `adb_wifi_enabled`, and `init.svc.adbd` from third-party apps.
- **Developer Options Cloaking:** Reports Developer Mode (`development_settings_enabled`) as disabled.
- **Accessibility Service Protection:** Cloaks active accessibility services (e.g. password managers like Bitwarden, automation tools) from banking, financial, and anti-cheat apps that block rooted or accessibility-enabled devices.
- **System-Wide Auto Cloaking:** Injects into `system_server` and `SettingsProvider` to automatically cloak all non-system applications without needing to manually toggle individual app scopes in LSPosed.

## Installation

### Prerequisites
- Root (KernelSU, Magisk, or APatch)
- LSPosed (or Vector Framework) active

### Steps
1. Download the latest `Solipsistic.apk` from [Releases](https://github.com/bgwastu/solipsistic/releases).
2. Install the APK.
3. Open LSPosed / Vector, enable **Solipsistic**, and check:
   - **System Framework** (`android`)
   - **Settings Storage** (`com.android.providers.settings`)
   - **Media Storage** (`com.android.providers.media.module` / `com.android.providers.media`)
   - *(Optional)* Target applications or enable **Include new apps (auto-include)**.
4. Soft reboot or restart system server.
