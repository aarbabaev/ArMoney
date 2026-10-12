#!/usr/bin/env bash
set -euo pipefail
kind=${1:?backend or android}
[[ "$kind" == backend || "$kind" == android ]] || exit 1
[[ "${GITHUB_RUN_ID:-}" =~ ^[0-9]+$ && "${GITHUB_RUN_ATTEMPT:-}" =~ ^[0-9]+$ ]] || exit 1
output=/artifacts/$GITHUB_RUN_ID/$GITHUB_RUN_ATTEMPT
mkdir -p "$output"
if [[ "$kind" == backend ]]; then
  find . -type d -path '*/build/reports/tests' -print0 | tar --null -T - -czf "$output/backend-tests.tgz"
else
  find android/app/build -type f \( -path '*/reports/*' -o -path '*/outputs/apk/debug/*.apk' -o -path '*/outputs/androidTest-results/*' \) -print0 | tar --null -T - -czf "$output/android-results.tgz"
fi
printf 'Artifacts stored locally: %s/%s\n' "$output" "$kind"
