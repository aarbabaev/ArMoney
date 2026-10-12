#!/usr/bin/env bash
set -euo pipefail
# Pass this flag only after the owner explicitly requests an emulator run.
if [[ "${1:-}" != "--owner-requested" ]]; then
  echo "Emulator launch requires an explicit owner request and --owner-requested." >&2
  exit 2
fi
avd=armoney_ci
cleanup() { adb -s emulator-5554 emu kill >/dev/null 2>&1 || true; }
trap cleanup EXIT
sdkmanager 'system-images;android-36;google_apis;x86_64' >/dev/null
printf 'no\n' | avdmanager create avd --force --name "$avd" --package 'system-images;android-36;google_apis;x86_64'
mkdir -p android/app/build/reports
emulator -avd "$avd" -port 5554 -accel off -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim -memory 3072 -cores 4 >android/app/build/reports/emulator.log 2>&1 &
emulator_pid=$!
booted=false
for ((i=0;i<240;i++)); do
  kill -0 "$emulator_pid" 2>/dev/null || { echo 'Android emulator exited; see emulator.log' >&2; exit 1; }
  if [[ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then booted=true; break; fi
  sleep 5
done
[[ "$booted" == true ]] || { echo 'Android emulator did not boot within 20 minutes' >&2; exit 1; }
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb -s emulator-5554 shell settings put global "$setting" 0
done
ANDROID_SERIAL=emulator-5554 ./gradlew -p android --no-daemon connectedDebugAndroidTest
