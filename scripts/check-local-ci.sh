#!/usr/bin/env bash
set -euo pipefail
# Refuse the user's host Docker daemon even if the runner is misconfigured.
[[ "${DOCKER_HOST:-}" == unix:///run/inner-docker/docker.sock ]] || { echo 'Dedicated CI Docker daemon required' >&2; exit 1; }
[[ "${ARMONEY_DISPOSABLE_CI:-}" == true ]] || { echo 'Disposable CI marker missing' >&2; exit 1; }
[[ "${GITHUB_WORKSPACE:-}" == /work/* ]] || { echo 'Isolated workspace required' >&2; exit 1; }
[[ ! -e .env && ! -e compose.override.yaml ]] || { echo 'Local overrides are forbidden in CI' >&2; exit 1; }
java -version
python3 --version
docker info --format '{{.Name}}'
docker compose version
