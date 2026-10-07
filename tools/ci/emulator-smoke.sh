#!/usr/bin/env bash
set -euo pipefail
mkdir -p qa
export ANDROID_SERIAL=emulator-5554
export ANDROID_USER_HOME="${RUNNER_TEMP:-/tmp}/voiceshift-android"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
mkdir -p "$ANDROID_AVD_HOME"
cleanup() {
  timeout 15s adb -s "$ANDROID_SERIAL" logcat -d > qa/logcat.txt 2>/dev/null || true
  timeout 15s adb -s "$ANDROID_SERIAL" logcat -b crash -d > qa/crash.txt 2>/dev/null || true
  timeout 10s adb -s "$ANDROID_SERIAL" emu kill >/dev/null 2>&1 || true
}
trap cleanup EXIT
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --force \
  --name voiceshift-ci --path "$ANDROID_AVD_HOME/voiceshift-ci.avd" --package 'system-images;android-35;google_apis;x86_64' --device pixel_6
test -f "$ANDROID_AVD_HOME/voiceshift-ci.ini"
"$ANDROID_HOME/emulator/emulator" -list-avds
"$ANDROID_HOME/emulator/emulator" -avd voiceshift-ci -port 5554 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 > qa/emulator.log 2>&1 &
booted=false
for i in $(seq 1 150); do
  if [ "$(timeout 5s adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = '1' ]; then booted=true; break; fi
  sleep 2
done
if [ "$booted" != true ]; then echo 'Emulator boot timed out'; exit 1; fi
timeout 20s adb -s "$ANDROID_SERIAL" shell input keyevent 82
timeout 20s adb -s "$ANDROID_SERIAL" shell wm density 320
timeout 20s adb -s "$ANDROID_SERIAL" shell settings put global window_animation_scale 0
timeout 20s adb -s "$ANDROID_SERIAL" shell settings put global transition_animation_scale 0
timeout 20s adb -s "$ANDROID_SERIAL" shell settings put global animator_duration_scale 0
timeout 15s adb -s "$ANDROID_SERIAL" logcat -c
timeout 60s adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
timeout 240s python3 tools/ci/smoke.py
