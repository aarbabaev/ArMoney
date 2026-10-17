#!/usr/bin/env bash
set -euo pipefail
[[ "${RECOVERY_DAEMON:-}" == unix:///run/recovery-docker/docker.sock ]] || { echo 'Explicit dedicated recovery endpoint required' >&2; exit 1; }
[[ "${ARMONEY_DISPOSABLE_CI:-}" == true ]] || { echo 'Disposable CI marker required' >&2; exit 1; }
[[ "${GITHUB_WORKSPACE:-}" == /work/* ]] || { echo 'Isolated workspace marker required' >&2; exit 1; }
[[ ! -e .env && ! -e compose.override.yaml ]] || { echo 'Local overrides forbidden' >&2; exit 1; }
# Every Docker command below names the fixed endpoint; no ambient host/context fallback.
unset DOCKER_HOST DOCKER_CONTEXT DOCKER_TLS_VERIFY DOCKER_CERT_PATH DOCKER_CONFIG COMPOSE_FILE COMPOSE_PROJECT_NAME
python3 -B - <<'PY'
import json
import importlib.util
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ENDPOINT = 'unix:///run/recovery-docker/docker.sock'
LABEL = 'io.armoney.disposable-recovery=true'
SERVICES = ('app-gateway', 'auth-service', 'user-service', 'wallet-service', 'payment-service', 'ledger-service')
# Images must be explicitly provisioned into this new daemon before staging.
# No pull or daemon cleanup is implemented here.
DEPENDENCIES = ('eclipse-temurin:21-jdk', 'eclipse-temurin:21-jre', 'postgres:17.6-alpine',
                'quay.io/keycloak/keycloak:26.7.4', 'caddy:2.11.4-alpine', 'python:3.13.7-alpine',
                'pgvector/pgvector:pg17@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d')
repo = Path.cwd().resolve()
loader = importlib.util.spec_from_file_location('recovery_stage', repo / 'scripts/stage-application-recovery-ci.py')
staging = importlib.util.module_from_spec(loader)
sys.path.insert(0, str(repo / 'scripts'))
loader.loader.exec_module(staging)

class Rejected(RuntimeError):
    pass

def command(argv, timeout=30):
    result = subprocess.run(argv, cwd=repo, capture_output=True, timeout=timeout)
    if result.returncode:
        raise Rejected('Recovery staging command failed; output withheld')
    return result.stdout

def docker(*args, timeout=30):
    return command(['docker', '--host', ENDPOINT, *args], timeout)

def source():
    revision = command(['git', 'rev-parse', '--verify', 'HEAD']).decode().strip()
    if not re.fullmatch('[0-9a-f]{40}', revision) or command(['git', 'status', '--porcelain=v1', '--untracked-files=all']).strip():
        raise Rejected('Clean exact source revision required')
    return revision

def preflight(expected=None):
    info = json.loads(docker('info', '--format', '{{json .}}'))
    identity = info.get('ID')
    if not isinstance(identity, str) or not identity or (expected is not None and identity != expected):
        raise Rejected('Recovery daemon identity unavailable or changed')
    if LABEL not in (info.get('Labels') or []) or info.get('Containers') != 0:
        raise Rejected('Labelled initially empty recovery daemon required')
    if docker('volume', 'ls', '--quiet').strip():
        raise Rejected('Recovery daemon contains volumes')
    if set(docker('network', 'ls', '--format', '{{.Name}}').decode().split()) != {'bridge', 'host', 'none'}:
        raise Rejected('Recovery daemon contains custom networks')
    return identity

def image(reference, revision=None, platform=False):
    rows = json.loads(docker('image', 'inspect', reference))
    if not isinstance(rows, list) or len(rows) != 1:
        raise Rejected('Preprovisioned image unavailable')
    row = rows[0]
    if not re.fullmatch('sha256:[0-9a-f]{64}', row.get('Id', '')):
        raise Rejected('Immutable image identity unavailable')
    if platform and (row.get('Os') != 'linux' or row.get('Architecture') != 'amd64'):
        raise Rejected('Dependency platform differs')
    if revision is not None and (row.get('Config', {}).get('Labels') or {}).get('org.opencontainers.image.revision') != revision:
        raise Rejected('Staged image revision differs from source')
    return row['Id']

try:
    revision = source()
    identity = preflight()
    dependencies = staging.load_dependencies(Path('/work/recovery-dependencies.json'), revision)
    if set(dependencies['images']) != set(DEPENDENCIES):
        raise Rejected('Dependency inventory differs')
    for dependency in DEPENDENCIES:
        expected = dependencies['images'][dependency]['config_id']
        if image(expected, platform=True) != expected:
            raise Rejected('Imported dependency identity differs')
    images = {}
    print('RECOVERY_STAGE:build', flush=True)
    for service in SERVICES:
        if source() != revision:
            raise Rejected('Source changed during staging')
        preflight(identity)
        for base in ('eclipse-temurin:21-jdk', 'eclipse-temurin:21-jre'):
            if image(base, platform=True) != dependencies['images'][base]['config_id']:
                raise Rejected('Build base tag changed')
        tag = 'armoney-recovery-stage/' + service + ':' + revision
        docker('build', '--pull=false', '--label', 'org.opencontainers.image.revision=' + revision,
               '--build-arg', 'SERVICE=' + service, '--tag', tag, '--file', str(repo / 'Dockerfile'), str(repo), timeout=1800)
        images[service] = image(tag, revision)
    if source() != revision:
        raise Rejected('Source changed after staging')
    preflight(identity)
    with tempfile.TemporaryDirectory(prefix='armoney-recovery-stage-') as temporary:
        directory = Path(temporary).resolve()
        if directory.is_relative_to(repo):
            raise Rejected('Image manifest must be outside source')
        directory.chmod(0o700)
        manifest = directory / 'bank-images.json'
        manifest.write_text(json.dumps({'revision': revision, 'images': images}), encoding='utf-8')
        manifest.chmod(0o600)
        # The real acceptance retains its ownership/cleanup guards and controls fixtures.
        print('RECOVERY_STAGE:application', flush=True)
        code = subprocess.call([sys.executable, '-B', str(repo / 'scripts/backup-application-recovery-smoke.py'),
                                '--apply', '--daemon', ENDPOINT, '--bank-images', str(manifest),
                                '--dependency-images', '/work/recovery-dependencies.json'], cwd=repo)
        preflight(identity)
        raise SystemExit(code)
except (Rejected, staging.GuardError, OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
    raise SystemExit('Application recovery staging failed; private diagnostics withheld') from None
PY
