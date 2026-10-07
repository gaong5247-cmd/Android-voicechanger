#!/usr/bin/env bash
set -euo pipefail
mkdir -p qa
export ANDROID_SERIAL=emulator-5554
cleanup() {
  adb -s "$ANDROID_SERIAL" logcat -d > qa/logcat.txt 2>/dev/null || true
  adb -s "$ANDROID_SERIAL" logcat -b crash -d > qa/crash.txt 2>/dev/null || true
  adb -s "$ANDROID_SERIAL" emu kill >/dev/null 2>&1 || true
}
trap cleanup EXIT
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --force \
  --name voiceshift-ci --package 'system-images;android-35;google_apis;x86_64' --device pixel_6
"$ANDROID_HOME/emulator/emulator" -avd voiceshift-ci -port 5554 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 > qa/emulator.log 2>&1 &
booted=false
for i in $(seq 1 150); do
  if [ "$(adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = '1' ]; then booted=true; break; fi
  sleep 2
done
if [ "$booted" != true ]; then echo 'Emulator boot timed out'; exit 1; fi
adb -s "$ANDROID_SERIAL" shell input keyevent 82
adb -s "$ANDROID_SERIAL" shell wm density 320
adb -s "$ANDROID_SERIAL" shell settings put global window_animation_scale 0
adb -s "$ANDROID_SERIAL" shell settings put global transition_animation_scale 0
adb -s "$ANDROID_SERIAL" shell settings put global animator_duration_scale 0
adb -s "$ANDROID_SERIAL" logcat -c
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
python3 tools/ci/smoke.py
