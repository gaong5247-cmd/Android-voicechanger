# VoiceShift

[![Android APK Build](https://github.com/gaong5247-cmd/Android-voicechanger/actions/workflows/android-build.yml/badge.svg)](https://github.com/gaong5247-cmd/Android-voicechanger/actions/workflows/android-build.yml)

Native Android zero-shot voice changer project based on MeanVC2. The first milestone is a reproducible, debug-signed arm64 APK built by GitHub Actions. Model inference, streaming conversion and system virtual microphone routing are still under development. START remains disabled until the actual pipeline is verified.

## Download APK

1. Open [Actions → Android APK Build](https://github.com/gaong5247-cmd/Android-voicechanger/actions/workflows/android-build.yml).
2. Select the latest successful run on `main`.
3. Under **Artifacts**, download **VoiceShift-APK**.
4. Extract `VoiceShift-debug.apk` and install it on an arm64 Android 8.0+ device. Allow installation from the app you use to open the file.

Artifacts are retained for 30 days. Sign in to GitHub to download them. You can create a fresh build with **Run workflow**; no local Android Studio build is required.

[First verified APK build](https://github.com/gaong5247-cmd/Android-voicechanger/actions/runs/37586165030) · [VoiceShift-APK artifact](https://github.com/gaong5247-cmd/Android-voicechanger/actions/runs/37586165030/artifacts/11467315206)

## Build

Open the repository root in Android Studio, or use JDK 17 and the checked-in Gradle Wrapper:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

Windows: `gradlew.bat assembleDebug`.

Pinned tools: Gradle 8.7, Android Gradle Plugin 8.5.2, Kotlin 2.0.0, SDK/Build Tools 35, NDK 27.0.12077973, CMake 3.22.1. SDK location is discovered from `ANDROID_HOME` or local Android Studio setup; `local.properties` is never committed. Maven dependencies use Google and Maven Central. Model checkpoints are not required to build or launch this app.

## Tests and CI

The workflow has separate steps for host C++ tests, Android unit tests, lint, APK assembly, APK signature/manifest/ABI verification and artifact upload. A missing APK fails the job. Native tests cover 100,000 ordered PCM samples, 100 START/STOP cycles, concurrent control, inference auto-bypass and transport/capture failure rollback.

```bash
./native/run-tests.sh
```

The APK is debug-signed for installation testing. Runtime device tests and the current actual CI result are documented in [Build Status](docs/BUILD_STATUS.md). The workflow also builds an emulator-only x86_64 APK for Android 15 install/launch/rotation/permission smoke tests and uploads screenshot/UI-tree/logcat evidence separately as `VoiceShift-Emulator-QA`. The user APK remains arm64. Emulator verification does not certify every phone.

## App state

Home uses Compose Material 3 with Android 12+ dynamic colors and light/dark themes. It reports MeanVC2 model not installed, CPU engine pending, Monitor not ready, Virtual Mic not ready, and Shizuku package status. Shizuku service connectivity is not inferred from package installation. The model-import button explains that installation is a later milestone. Diagnostics can inspect backend requirements or attempt a coded-PCM test against a separately installed compatible development HAL. It never plays the test into a speaker.

## Development stages

CPU offline MeanVC2 inference → streaming native microphone/worker pipeline → headphone monitor validation → latency/thermal optimization → optional accelerators → privileged system input routing research and third-party Recorder acceptance.

Shizuku is not a virtual microphone. Monitor output is not another app's microphone. System Virtual Mic may only become Ready after another app's AudioRecord receives converted PCM and isolated physical capture plus route recovery are tested. See [Architecture](docs/ARCHITECTURE.md).

## Model conversion

Build-workstation experiments live in `tools/model_conversion/`. They export isolated GTM/ASR components and check CPU parity; they do not yet create a complete Android-ready runtime. Use the official [MeanVC2 upstream](https://github.com/ASLP-lab/MeanVC2) and its checkpoints. Python is only a workstation conversion tool; no Python server, cloud inference, Gradio or WebView runs on Android.

## Licensing

Original code is Apache-2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE). Reference voices must be used with the speaker's permission. This repository intentionally does not bundle checkpoint files or an unverified Magisk module.
