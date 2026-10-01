# Solipsist

Solipsist is an Xposed / LSPosed privacy module for Android. It reduces identifying metadata when third-party apps read shared media, and hides selected debugging and accessibility signals from non-exempt apps. A single switch controls protection globally; there is no target-app list in Solipsist.

The existing hooks have been used on Android 14–16 (HyperOS 2.0 / 3.0 and AOSP). Check the live switch and runtime status on your ROM before relying on them.

## Protection

- **Shared images:** extends MediaProvider redaction to selected identifying EXIF fields, including device model, camera owner, serial, lens, and comments.
- **MP4-family media:** redacts selected ISO Base Media File Format boxes in MP4, MOV, 3GP, and M4A files. MP3, FLAC, Ogg/Opus, WAV, AAC streams, WebM, and other non-MP4 containers are not covered by this extra scanner. Android's own media redaction may still apply separately.
- **Shared filenames:** presents generic names in MediaProvider and Photo Picker query results and in image, video, and audio content-URI queries made by scoped apps, including document-provider queries. For protected callers, the legacy `_data` path is returned as `null`; apps should open the content URI instead. The original file is not renamed.
- **Camera attachments:** after a scoped app receives a successful camera result, Solipsist removes metadata segments from a JPEG captured into that app's private file, preserving only the orientation tag. It masks the captured URI's display name, last path segment, and backing file name within that app process. Other image formats, shared gallery originals, files uploaded without an external camera result, and filenames constructed independently by an app are outside this camera path.
- **Device inspection:** masks selected ADB, developer-option, and accessibility results for non-exempt apps. Camera and system apps remain exempt under the module's app filter.

The module also records counts of selected clipboard, account, WebView user-agent, location, and VPN API calls where it is injected. These observation hooks do not alter results.

## Install and use

1. Install the APK from [Releases](https://github.com/bgwastu/solipsist/releases). Root and an active LSPosed-compatible framework are required.
2. Enable Solipsist in the framework and scope it to **System Framework** (`android`), **Settings Storage** (`com.android.providers.settings`), and the installed **Media Storage** package (`com.android.providers.media.module`, `com.google.android.providers.media.module`, or `com.android.providers.media`). Restart the affected system processes.
3. Open the Solipsist app to use the global switch and runtime status view. Changes to the switch are sent to running processes; the hooks check the updated state without a restart. Install Solipsist in each Android user or work profile where scoped apps run.
4. For app-process privacy hooks and advanced API counts, include third-party apps in the framework's scope or use its auto-include option. System-service hooks can cover their supported calls without each app being individually scoped.

The status view shows the configured switch separately from reported hook installations and failures. It shows overall and per-app API category counts from the last 24 hours. It does not store queried values, clipboard contents, account names, locations, URLs, or stack traces. Counts are diagnostic: an app outside LSPosed scope cannot report app-process API calls, and unavailable hook points will be listed as failures rather than silently counted.

## Build

Use a compatible Android SDK and JDK 21:

```sh
./gradlew :app:assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

The release workflow uses the repository's Android signing secrets and fails if they are missing. It publishes a signed `Solipsist.apk` to GitHub Releases. For a local release build, set `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, and `ANDROID_KEYSTORE_ALIAS` to the matching production key before running `./gradlew :app:assembleRelease`.
