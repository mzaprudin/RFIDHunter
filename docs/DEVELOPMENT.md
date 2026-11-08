# Build and verification

[Русская версия](DEVELOPMENT.RU.md) · [Overview](../README.md) · [User workflows](USAGE.md)

## Build prerequisites

Use JDK 17, Android SDK Platform 35 and the Gradle 8.9 wrapper. Set `ANDROID_HOME` to the SDK directory, or put `sdk.dir=/absolute/path/to/android-sdk` in the ignored `local.properties`. Install missing SDK components and accept their licenses through Android Studio or the SDK manager. The minimum device version is Android 9 (API 28).

The repository's shell wrapper has no executable bit; use `bash ./gradlew` on Linux/macOS. In PowerShell use `.\gradlew.bat` for the same tasks. No release key, Honeywell SDK or speech model is required for the mock debug build.

```bash
bash ./gradlew :app:assembleMockDebug :app:testMockDebugUnitTest
```

APK: `app/build/outputs/apk/mock/debug/app-mock-debug.apk`. Its application ID is `ru.rfidhunter.app.mock`, separate from the hardware app. To install on a disposable emulator or development phone with USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/mock/debug/app-mock-debug.apk
```

## Mock walkthrough

The app UI and commands are Russian. From the idle screen press Volume Down five times, holding the fifth for at least 800 ms; complete the gesture within three seconds. The service screen exposes `Подключить` (connect), `trigger`, tag scenarios and `Тест голосовой команды` (text command input).

1. Select `UNKNOWN`, then `trigger`: the simulated nearby tag starts registration. The fixed EPC is `E2000017221101441890DEMO`.
2. To explore registration without a speech model, return to idle, open the service screen and send `добавь очки` in the text field. Confirm the name, take and accept a photo. This route begins with the name. After photo acceptance the app waits for a tag: keep `UNKNOWN` selected and press `ДЕМО: СЧИТАТЬ МЕТКУ` to bind the tag and save the item.
3. Open the catalog to select a saved item, or send `найди очки` from the service screen to exercise command matching. An empty catalog has no preloaded items.
4. From idle, reopen the service screen and press `Подключить` first: diagnostic inventory requires a ready reader. Use `Проверить метки (5 секунд)` to inspect emitted EPC/RSSI values. The fake reader emits short, repeatable batches; it is useful for workflow inspection and does not simulate a moving antenna.

| Scenario | Emitted tags |
| --- | --- |
| `NONE` | No tags; the context check requests a voice search |
| `UNKNOWN` | One nearby tag ending in `DEMO` |
| `KNOWN` | One tag ending in `AB01`; it is treated as known only if that EPC is already in the catalog |
| `MULTIPLE` | Two tags with close RSSI values; no clear leader |
| `SEARCH_FOUND` | Custom tag if configured by code, otherwise the same `AB01` tag at stronger RSSI |

Scenario names describe fixed input batches. `UNKNOWN` becomes known after saving its EPC, and `SEARCH_FOUND` matches a selected item only when its EPC matches the emitted tag.

## Hardware build and models

Place the separately supplied Honeywell SDK at `Honeywell_RFID_SDK_v0.5.0/libs/honeywell_rfid_sdk.aar`, relative to the repository root:

```bash
bash ./gradlew :app:assembleHardwareDebug
```

Output: `app/build/outputs/apk/hardware/debug/app-hardware-debug.apk`.

Download and unpack a compatible Russian Vosk model separately. Set a path in `local.properties`, or pass `-PvoskModelDir=models/vosk-model-small-ru-0.22` to Gradle. Relative paths resolve from the repository root:

```properties
voskModelDir=models/vosk-model-small-ru-0.22
```

The build copies the directory into generated assets; first launch installs it into private storage. With no model, use the service screen's text commands. Recorded prompts belong in `app/src/main/assets/voice`; missing prompts leave text feedback. The optional `scripts/generate-elevenlabs-audio.ps1` generates them on the developer machine using `ELEVENLABS_API_KEY` and `ELEVENLABS_VOICE_ID`.

Whisper.cpp is a manually selected diagnostic backend. To build it, provide NDK `26.3.11579264`, CMake `3.22.1` and a local checkout of Whisper.cpp tag `v1.9.2` at `third_party/whisper.cpp`. The native build targets `arm64-v8a`. Gradle enables it when `ANDROID_NDK_HOME` points to an NDK containing `source.properties` (on Windows it also checks the default SDK NDK path). A detected NDK without the Whisper sources causes CMake to fail; ordinary Vosk builds can run without the optional native layer.

Set `whisperModelDir=models/whisper` to package `ggml-base-q5_1.bin`. `scripts/download-whisper-models.ps1` downloads diagnostic model files and checks their hashes; the app packages only q5_1. Models, SDK, third-party sources and signing keys are ignored by Git.

## Signed release and on-device checks

Release builds require an existing keystore and all four properties in ignored `local.properties` or local Gradle properties:

```properties
rfidhunter.storeFile=keystore/rfidhunter-release.jks
rfidhunter.storePassword=<keystore-password>
rfidhunter.keyAlias=rfidhunter
rfidhunter.keyPassword=<key-password>
```

```bash
bash ./gradlew :app:assembleHardwareRelease
```

Output: `app/build/outputs/apk/hardware/release/app-hardware-release.apk`. Keep the signing key for updates. An installed app can be updated only with the same signing key; uninstalling it removes its local catalog and photos.

`TargetApkSmokeRunner` checks JNA/Vosk loading from the optimized app APK and the BLE `neverForLocation` flag on Android 12+. It unpacks a temporary model and recognizes silence without using the microphone. The flag check is skipped on Android 9–11. It requires the hardware SDK and a packaged Vosk model.

```bash
bash ./gradlew :app:assembleHardwareRelease :app:assembleHardwareReleaseAndroidTest -PandroidTestBuildType=release
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/hardware/release/app-hardware-release.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/hardware/release/app-hardware-release-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -r ru.rfidhunter.app.test/ru.rfidhunter.app.TargetApkSmokeRunner
```

Expected: `status=PASS`, `vosk=PASS`, `bluetooth=PASS` (or skip on Android 9–11), `INSTRUMENTATION_CODE: -1`. Use manual update installs on a phone with existing data: `connectedAndroidTest` can uninstall the target app afterward.

Manual camera regression: grant camera access from the capture screen and take a photo without reopening it; try both volume buttons and rapid repeats; leave during capture and confirm that an old result cannot open a new screen. With IH25 connected, a permission dialog should preserve the connection in normal mode; Home should close it, with reconnection on return. Log tags: `RfidCamera`, `RfidLifecycle`, `RfidVoice`, `HoneywellRfidGateway`.

## Audio diagnostics

Enable diagnostic recording in the service screen to retain raw mono PCM16 WAV captures in the app's private `files/voice-diagnostics` directory. On a debug installation, export a known recording name using `adb exec-out run-as ru.rfidhunter.app.mock cat files/voice-diagnostics/capture_TIMESTAMP.wav > capture.wav` from Bash. Use a disposable test installation and recordings cleared for sharing.

From the repository root:

```bash
python3 scripts/vad_reference.py capture.wav --output-dir artifacts/vad --summary-csv artifacts/vad/summary.csv
python3 scripts/analyze_voice_wav.py capture.wav --csv-dir artifacts/levels
```

The reference detector writes a JSON report, per-frame noise floor/threshold/state CSV and a trimmed WAV when speech is detected. The level analyzer uses a fixed threshold; its optional `--plot artifacts/levels.png` requires `matplotlib` and plots the first input. These serve different diagnostic purposes. Python and Kotlin use different frame steps, so their current implementations are described as related detectors rather than sample-identical outputs. [Audio algorithm](../ARCHITECTURE.md#audio-pipeline)

<a id="verification"></a>
## Verification

```bash
bash ./gradlew :app:testMockDebugUnitTest :app:assembleMockDebug
python3 scripts/test_vad_reference.py
cd server
uv sync --dev
uv run pytest
```

Results for this documentation pass (2026-11-08): Python VAD **5 passed**; backup server **14 passed** (CPython 3.14.3). Server tests emitted one Starlette deprecation warning about its httpx integration. Android JVM **38 passed**, with no failures or skips; `assembleMockDebug` succeeded using OpenJDK 21.0.12.1, Gradle 8.9, Platform 35 and Build Tools 34.0.0. No speech models or Honeywell SDK were used.

Hardware, on-device smoke tests and microphone recognition require the corresponding device, SDK and models. They are separate from the mock/JVM checks above.
