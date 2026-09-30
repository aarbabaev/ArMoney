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
xcodebuild -project ios/ArMoney.xcodeproj -scheme ArMoney \
  -destination "platform=iOS Simulator,id=$device" \
  -derivedDataPath "${RUNNER_TEMP:-/tmp}/armoney-ios-derived" \
  CODE_SIGNING_ALLOWED=NO test
