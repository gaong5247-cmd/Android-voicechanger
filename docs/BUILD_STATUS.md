# Build status

| Capability | Status |
|---|---|
| Android project / Gradle Wrapper | Implemented |
| Android APK CI Build | PASS — [run 1](https://github.com/gaong5247-cmd/Android-voicechanger/actions/runs/37586165030) |
| APK Artifact | PASS — [run 1](https://github.com/gaong5247-cmd/Android-voicechanger/actions/runs/37586165030) |
| Signature, manifest and arm64 ABI checks | PASS in run 1 |
| App Launch / rotation / permission denial | PENDING emulator smoke test; physical device unverified |
| Native host core tests | PASS in run 1 with ASan/UBSan |
| Android readiness unit tests / lint | PASS in run 1 |
| MeanVC2 Android inference | TODO |
| Reference model import/cache | TODO |
| Streaming conversion / monitor | TODO |
| Shizuku integration | PARTIAL: package presence only; service connection unverified |
| System virtual microphone | TODO |
| Recorder / KakaoTalk / Discord acceptance | TODO |

Verified run 1 commit: `40f74406a63242983c9c48b19feb99801c4a71d1`. `VoiceShift-APK` artifact ID: `11467315206`, 7,711,919 bytes, unexpired. Assembly, APK signature/manifest/ABI checks, tests and upload all passed. An Android 15 x86_64 emulator smoke job is being added; its APK is only for QA, while the distributed artifact remains arm64. No weights or virtual-mic implementation are required for assembly. Local build network restrictions are independent of the GitHub-hosted build.
