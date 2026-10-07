# MeowRVC 한국어판
Android용 실시간 AI RVC 음성변환 앱 MeowRVC의 한국어 UI 빌드입니다.

- 원본: https://github.com/xiaoxiaoyu-miao/MeowRVC
- 라이선스: MIT
- 기능 로직은 유지하고 사용자 UI를 한국어화했습니다.
- Android 8.0+ / arm64-v8a
- RVC ONNX 모델: `/sdcard/models/`

## GitHub Actions
Actions → **Android APK Build** → Artifacts → **VoiceShift-APK**

## 빌드
```bash
./gradlew :app:assembleDebug
```
JDK 17 / Android SDK 35 / NDK 27.1.12297006

원본 저작권 및 라이선스 고지는 LICENSE와 THIRD_PARTY_NOTICES.md를 따릅니다.
