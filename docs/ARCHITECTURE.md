# Architecture

## First milestone

Compose Activity → honest capability state and diagnostics. C++ JNI transport is built for arm64-v8a; portable native session/ring code is tested on the runner host. No model file is accessed at app startup. Model missing is a runtime status, not a build failure.

Three independent capabilities exist: Monitor Ready requires verified local conversion; Shizuku Ready requires actual service communication/permission; Virtual Mic Ready requires converted PCM capture by another app. The Kotlin readiness tests prevent monitor or Shizuku status from enabling system conversion.

## Planned MeanVC2 CPU pipeline

Physical microphone → 20–40 ms native capture → bounded input ring → inference worker → streaming content encoder → cached target-conditioning MeanVC2 decoder → Vocos → converted PCM. Inference never runs in an audio callback or on the UI thread. Reference speaker encoding runs once per imported profile, not once per audio chunk.

The inspected official MeanVC2 runtime uses ASR attention/CNN caches, BN/frame/noise/DiT KV caches and Vocos overlap state. Its real-time microphone loop currently captures 2560 samples at 16 kHz (160 ms), even with its 40 ms decoder preset. A phone runtime requires cache-preserving export, feature-extractor equivalence and corrected cadence, not a direct Python embedding.

CPU offline inference is the next milestone. ONNX Runtime Mobile/XNNPACK is the initial candidate. Unsupported cached sequence or Vocos operations may require graph rewrites/custom native operations. ExecuTorch is an alternative to evaluate after baseline correctness. GPU/NPU choices must be benchmarked on target hardware; none are implemented yet.

## Native foundations

`native/include/voiceshift/ring.hpp` is a bounded SPSC ring. `session.hpp` serializes START/STOP, runs inference on a worker, auto-bypasses converter failure and disables the route on transport failure. Its injected Capture/Converter/Route interfaces are not yet Android implementations.

## Diagnostics transport

JNI contains a PCM16 stereo/48-kHz shared-memory producer compatible with the studied AOSP virtualmic PoC's socket protocol. It supplies coded PCM directly to the input service, then AudioRecord checks MIC and VOICE_COMMUNICATION. It requires a compatible separately installed development HAL. A socket connection or this in-app transport test does not certify third-party converted-voice acceptance.

## System microphone research

Ordinary Android app APIs do not register an arbitrary PCM-backed global microphone. Concurrent capture policy may silence ordinary capture when another app requests microphone input. Shizuku shell access does not add an Audio HAL or AudioPolicy device. A real backend may need device-specific root/Magisk, Audio HAL/AudioPolicy and privileged service integration. The candidate AOSP HAL needs authenticated clients, SELinux restrictions, isolated physical input and verified rollback before deployment. No HAL, root overlay or Magisk module is installed by this APK.

Sources: [Android input sharing](https://developer.android.com/media/platform/sharing-audio-input), [AIDL HAL](https://source.android.com/docs/core/audio/aidl-implement), [Shizuku](https://shizuku.rikka.app/introduction/), [MeanVC2](https://github.com/ASLP-lab/MeanVC2), [AOSP virtual mic research](https://github.com/jsuppe/aosp-virtual-mic).
