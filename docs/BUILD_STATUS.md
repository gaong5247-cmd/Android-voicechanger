# Build status

| Capability | Status |
|---|---|
| Android project / Gradle Wrapper | Implemented |
| Android APK CI Build | PENDING first workflow verification |
| APK Artifact | PENDING first workflow verification |
| Signature, manifest and arm64 ABI checks | Implemented in CI; result pending |
| App Launch / rotation / permission denial | UNVERIFIED on device |
| Native host core tests | PASS locally with ASan/UBSan (CI verification pending) |
| Android readiness unit tests / lint | Implemented; CI verification pending |
| MeanVC2 Android inference | TODO |
| Reference model import/cache | TODO |
| Streaming conversion / monitor | TODO |
| Shizuku integration | PARTIAL: package presence only; service connection unverified |
| System virtual microphone | TODO |
| Recorder / KakaoTalk / Discord acceptance | TODO |

CI result must be updated only after inspecting a real successful run and the VoiceShift-APK artifact. No weights or virtual-mic implementation are required for assembly. Local build network restrictions are independent of the GitHub-hosted build.
