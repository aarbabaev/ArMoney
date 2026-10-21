#!/usr/bin/env bash
# Runs inside the disposable Node container. Added diagnostics use finite labels.
set -euo pipefail
stage=''
started=0
finish() {
  local code=$?
  if [[ -n "$stage" ]]; then
    printf 'ARMONEY_WEB_CI stage=%s event=end duration_seconds=%d exit=%d\n' \
      "$stage" "$((SECONDS - started))" "$code"
  fi
  exit "$code"
}
trap finish EXIT
run_stage() {
  stage=$1
  shift
  started=$SECONDS
  printf 'ARMONEY_WEB_CI stage=%s event=start\n' "$stage"
  # Preserve normal tool output, including audit findings and test evidence.
  "$@"
  printf 'ARMONEY_WEB_CI stage=%s event=end duration_seconds=%d exit=0\n' \
    "$stage" "$((SECONDS - started))"
  stage=''
}
export npm_config_fetch_timeout=60000
export npm_config_fetch_retries=2
run_stage INSTALL timeout --signal=TERM --kill-after=10s 600s npm ci --no-audit --loglevel=http
run_stage AUDIT npm audit --audit-level=high
run_stage BUILD npm run build
run_stage UNIT npm test
run_stage BROWSER_INSTALL npx playwright install --with-deps chromium
run_stage E2E npm run e2e
