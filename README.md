# Samvaad

Android communication app for a Bluetooth LE gesture glove, with gesture recognition, speech, Indian-language translation, and QR-based browser guest conversations.

## Project files

- `app/`: Kotlin and Jetpack Compose Android app, assets, and tests.
- `firmware/`: glove firmware.
- `guest-chat/`: React and TypeScript guest chat, automatic message audio, and listening controls.
- `firebase/`: Firestore rules, optional translation functions, and tests.
- `local-translation/`: IndicTrans2 download, export, verification, and optional computer translator.
- `scripts/`: Firebase setup and guest asset preparation.

## Android setup

Use Android Studio with Android SDK 36 and JDK 17. Configure your local Android SDK path in `local.properties` if needed. The application ID is `com.samvaad.mobile`.

The Android build requires the offline translation assets described in [INDICTRANS_SETUP.md](INDICTRANS_SETUP.md). Model weights and generated builds are not stored in this repository.

Copy `app/src/main/assets/firebase-config.example.json` to `app/src/main/assets/firebase-config.json` and configure your Firebase project to enable guest conversations. Leave `enabled` false if guest conversations are not configured. Never commit private tokens, service-account credentials, or signing keys.

On Windows:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

## Guest chat

Install Node.js 22 or later. From the repository root:

```powershell
npm ci
npm --prefix guest-chat ci
npm run web:build
npm run web:dev
```

The web build prepares public chat assets from the Android assets. For your own Firebase project, enable anonymous authentication and Firestore, configure the app using `scripts/configure-firebase.mjs`, and deploy the provided rules and hosting configuration. See the script's arguments before running it. Live Firebase configuration and local deployment selections are excluded from Git.

Run guest tests on Windows:

```powershell
$testFiles = @(Get-ChildItem guest-chat/tests/*.test.mjs | ForEach-Object { $_.FullName })
node --test @testFiles
```

Guests join through the app's QR link and require host approval. New host messages play automatically after the guest joins; Audio on/off controls mute playback. Browser audio requires a user gesture and available voices. Connection speed depends on network conditions and host approval.

## Translation and models

Offline translation uses AI4Bharat IndicTrans2 English-to-Indic distilled 200M, exported to quantized ONNX. Gesture recognition supports the project's bundled and user-trained models. Speech depends on installed device/browser voices. Review generated translations before relying on their meaning.

Upstream preprocessing licenses and attribution are retained under `local-translation/vendor/`.
