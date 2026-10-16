#!/usr/bin/env bash
# CI-only lifecycle owner for the disposable observer topology.
set -euo pipefail
[[ "${CI:-}" == true && "${GITHUB_ACTIONS:-}" == true ]] || { echo 'Disposable CI required' >&2; exit 1; }
[[ -z "${DOCKER_CONTEXT:-}" ]] || { echo 'Docker context override is forbidden' >&2; exit 1; }
bash scripts/check-local-ci.sh
python3 -B scripts/test-sso-provider-acceptance.py
project="armoney-observer-ci-$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
export COMPOSE_PROJECT_NAME="$project"
export OBSERVER_ACCEPTANCE_IMAGE="armoney-observer-acceptance:$project"
unset COMPOSE_FILE
for key in OBSERVER_DB_PASSWORD SSO_ADMIN_PASSWORD SSO_VERIFICATION_BRIDGE_KEY ARMONEY_PROVIDER_BINDING_KEY AUTH_TO_SSO_STATUS_KEY OBSERVER_FIXTURE_CONTROL_KEY; do
  printf -v "$key" '%s' "$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
  export "$key"
done
compose=(docker compose --project-name "$project" --file compose.sso-provider-acceptance.yaml)
if ! owned=$(docker ps -aq --filter "label=com.docker.compose.project=$project"); then
  echo 'Cannot verify empty project' >&2; exit 1
fi
[[ -z "$owned" ]] || { echo 'Project is not empty' >&2; exit 1; }
cleanup() {
  result=$?
  cleanup_ok=true
  trap - EXIT
  if [[ "$result" -ne 0 ]]; then
    # Private Docker text is consumed only by this bounded allowlist classifier.
    if ! timeout 10 "${compose[@]}" logs --no-color --tail 80 sso-b 2>&1 | python3 -c '
import sys
blob = sys.stdin.buffer.read(65537)
known = (
 (b"java.lang.IllegalAccessError", "ILLEGAL_ACCESS"),
 (b"java.lang.NoClassDefFoundError", "CLASS_MISSING"),
 (b"java.lang.ExceptionInInitializerError", "CLASS_INITIALIZATION"),
 (b"java.lang.LinkageError", "LINKAGE"),
 (b"java.lang.ClassCastException", "CLASS_CAST"),
 (b"java.lang.NullPointerException", "NULL_POINTER"),
 (b"java.lang.IllegalStateException", "ILLEGAL_STATE"),
 (b"java.lang.IllegalArgumentException", "ILLEGAL_ARGUMENT"),
 (b"java.lang.SecurityException", "SECURITY"),
 (b"java.lang.reflect.InaccessibleObjectException", "REFLECTION_ACCESS"),
 (b"jakarta.ws.rs.InternalServerErrorException", "REST_INTERNAL_ERROR"),
 (b"jakarta.ws.rs.WebApplicationException", "REST_APPLICATION_ERROR"),
 (b"com.arman.bank.sso.Bridge$Unavailable", "PROVIDER_UNAVAILABLE"),
)
if len(blob) > 65536:
    print("SERVER_FAILURE: OBSERVER69 LOG_TRUNCATED", flush=True)
else:
    found = [label for token, label in known if token in blob]
    for label in found or ["UNKNOWN"]:
        print("SERVER_FAILURE: OBSERVER69 " + label, flush=True)
'; then
      echo 'SERVER_FAILURE: OBSERVER69 LOG_UNAVAILABLE'
    fi
  fi
  # Only this freshly generated project on the already verified CI daemon.
  if ! "${compose[@]}" down --volumes --remove-orphans --timeout 20 >/dev/null 2>&1; then
    echo 'Disposable observer cleanup failed' >&2
    result=1
    cleanup_ok=false
  fi
  if ! remaining=$(docker ps -aq --filter "label=com.docker.compose.project=$project"); then
    echo 'Cannot verify disposable cleanup' >&2
    result=1
    cleanup_ok=false
  elif [[ -n "$remaining" ]]; then
    echo 'Disposable observer containers remain' >&2
    result=1
    cleanup_ok=false
  fi
  if ! docker image rm "$OBSERVER_ACCEPTANCE_IMAGE" >/dev/null 2>&1; then
    echo 'Disposable observer image cleanup failed' >&2
    result=1
    cleanup_ok=false
  fi
  if [[ "$cleanup_ok" == true ]]; then
    echo 'PASS: observer-owned project absent and unique image removed'
  fi
  exit "$result"
}
trap cleanup EXIT
"${compose[@]}" build sso-a
"${compose[@]}" up --no-build -d observer-db auth-service sso-a sso-b
timeout 420 "${compose[@]}" --profile acceptance run --rm --no-deps acceptance
