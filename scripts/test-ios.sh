#!/usr/bin/env bash
set -euo pipefail
# Prefer the requested device. Hosted runners may ship a newer simulator catalogue.
device=$(xcrun simctl list devices available --json | python3 -c '
import json,sys
items=[]
for runtime, devices in json.load(sys.stdin)["devices"].items():
    if "iOS" not in runtime: continue
    for device in devices:
        if device.get("isAvailable") and "iPhone" in device["name"]:
            items.append(device)
if not items: raise SystemExit("No available iPhone simulator on this runner")
choice=next((d for d in items if d["name"]=="iPhone 16 Pro Max"),items[0])
print(choice["name"], file=sys.stderr)
print(choice["udid"])
')
# The simulator needs an ad-hoc signature carrying the app identity to exercise
# real Keychain access. This uses no distribution certificate or developer team.
derived="${RUNNER_TEMP:-/tmp}/armoney-ios-derived"
report_entitlements() {
  status=$?
  app="$derived/Build/Products/Debug-iphonesimulator/ArMoney.app"
  if [[ -d "$app" ]]; then
    codesign --display --entitlements :- "$app" || true
  fi
  exit "$status"
}
trap report_entitlements EXIT
xcodebuild -project ios/ArMoney.xcodeproj -scheme ArMoney \
  -destination "platform=iOS Simulator,id=$device" \
  -derivedDataPath "$derived" \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- test
