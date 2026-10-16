#!/usr/bin/env bash
set -euo pipefail
# Run only in the existing isolated backend CI job, after bank images are built.
# Python owns bounded commands, private staging and ownership-checked cleanup.
python3 -B - <<'PY'
import importlib.util
import json
import os
from pathlib import Path
import secrets
import signal
import shutil
import socket
import subprocess
import tempfile
import time
import uuid

ROOT = Path.cwd().resolve()
loader = importlib.util.spec_from_file_location('metrics_smoke', ROOT / 'scripts/metrics-smoke.py')
smoke = importlib.util.module_from_spec(loader)
loader.loader.exec_module(smoke)


def command(arguments, environment, timeout=40, check=True):
    result = subprocess.run(arguments, env=environment, capture_output=True, text=True, timeout=timeout, check=False)
    smoke.require(len(result.stdout) <= 1048576)
    if check:
        smoke.require(result.returncode == 0)
    return result


def fixture_text(images, port, root):
    smoke.require(set(images) == set(smoke.SERVICES))
    smoke.require(1024 <= port <= 65535)
    lines = ['services:']
    for service in smoke.SERVICES:
        smoke.require(smoke.re.fullmatch(r'sha256:[a-f0-9]{64}', images[service]) is not None)
        lines += ['  ' + service + ':', '    image: ' + images[service]]
        if service == 'app-gateway':
            lines += ['    ports: !override []']
    # Use the actual edge route configuration, without enabling an SSO flow.
    # The excluded metrics path must return 404 before any upstream selection.
    lines += ['  edge:', '    image: caddy:2.11.4-alpine',
              '    environment:', '      ARMONEY_HOST: localhost',
              '    ports: ["127.0.0.1:' + str(port) + ':443"]',
              '    volumes:',
              '      - ' + json.dumps((root / 'infra/edge/Caddyfile').as_posix() + ':/etc/caddy/Caddyfile:ro'),
              '      - edge-data:/data', '      - edge-config:/config',
              '    read_only: true', '    tmpfs: [/tmp]', '    cap_drop: [ALL]',
              '    security_opt: [no-new-privileges:true]', '    networks: [bank]',
              'volumes:', '  edge-data:', '  edge-config:']
    return '\n'.join(lines) + '\n'


def inventory_empty(project, environment):
    for kind, args in (('container', ['ps', '-aq']), ('network', ['network', 'ls', '-q']), ('volume', ['volume', 'ls', '-q'])):
        for selector in ('label=com.docker.compose.project=' + project,
                         'name=' + project):
            result = command(['docker', *args, '--filter', selector], environment)
            smoke.require(not result.stdout.strip())


def cleanup_owned(project, environment, compose):
    # Validate every project-labelled resource before Compose can remove anything.
    # A changed ownership label or unexpected name refuses cleanup and reports it.
    for kind, args in (('container', ['ps', '-aq']), ('network', ['network', 'ls', '-q']),
                       ('volume', ['volume', 'ls', '-q'])):
        identifiers = set()
        for selector in ('label=com.docker.compose.project=' + project,
                         'name=' + project):
            identifiers.update(command(['docker', *args, '--filter', selector], environment).stdout.split())
        for identifier in identifiers:
            item = json.loads(command(['docker', kind, 'inspect', identifier], environment).stdout)[0]
            labels = (item['Config'].get('Labels') if kind == 'container' else item.get('Labels')) or {}
            smoke.require(labels.get('com.docker.compose.project') == project)
            name = item.get('Name', '').lstrip('/')
            smoke.require(name.startswith(project + ('-' if kind == 'container' else '_')))
    command([*compose, 'down', '--volumes', '--remove-orphans', '--timeout', '10'], environment, timeout=90)


def change_owner(path, uid, gid, environment, recursive=False):
    arguments = ['chown', '--no-dereference']
    if recursive:
        arguments.append('-R')
    arguments += [str(uid) + ':' + str(gid), str(path)]
    if os.geteuid() != 0:
        arguments = ['sudo', '-n', *arguments]
    command(arguments, environment)


def main():
    project = 'armoney-metrics-test-' + uuid.uuid4().hex
    environment = dict(os.environ)
    smoke.guard(project, environment, ROOT)
    print(json.dumps({'code': 'METRICS_CI_STARTED', 'project': project}), flush=True)
    # No default/implicit Compose files, profiles or caller project are allowed.
    for name in ('COMPOSE_FILE', 'COMPOSE_PROJECT_NAME', 'COMPOSE_PROFILES', 'COMPOSE_ENV_FILES'):
        environment.pop(name, None)
    inventory_empty(project, environment)
    images = {}
    for service in smoke.SERVICES:
        image = json.loads(command(['docker', 'image', 'inspect', 'arman-bank-' + service], environment).stdout)[0]
        images[service] = image['Id']
    staging = Path(tempfile.mkdtemp(prefix='.metrics-ci-' + project + '-', dir=ROOT))
    compose = None
    created = False
    cleanup_failed = False
    try:
        smoke.require(staging.parent == ROOT and staging.name.startswith('.metrics-ci-' + project + '-'))
        staging.chmod(0o700)
        config = staging / 'collector.json'
        config.write_bytes((ROOT / 'infra/observability/collector.example.json').read_bytes())
        config.chmod(0o444)
        status = staging / 'status'
        status.mkdir(mode=0o700)
        change_owner(status, 10001, 10001, environment)
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0))
            port = reservation.getsockname()[1]
        fixture = staging / 'compose.fixture.yaml'
        fixture.write_text(fixture_text(images, port, ROOT), encoding='utf-8')
        environment.update(INTERNAL_METRICS_KEY=secrets.token_urlsafe(48),
            INTERNAL_AUTH_KEY=secrets.token_urlsafe(48), LOCAL_DB_PASSWORD=secrets.token_urlsafe(36),
            EMAIL_DELIVERY_ENABLED='false', METRICS_DISPOSABLE_CI='true',
            METRICS_CONFIG_PATH=str(config), METRICS_STATUS_DIR=str(status))
        compose = ['docker', 'compose', '--project-directory', str(ROOT), '--env-file', '/dev/null',
                   '-p', project, '-f', str(ROOT / 'compose.yaml'), '-f', str(ROOT / 'compose.metrics.yaml'), '-f', str(fixture)]
        command([*compose, 'config', '--quiet'], environment)
        # Generated project identity and inventory gate precede the first mutation.
        inventory_empty(project, environment)
        created = True
        command([*compose, 'up', '--no-build', '--pull', 'missing', '-d', '--wait', '--wait-timeout', '180'], environment, timeout=240)
        ca = staging / 'edge-root.crt'
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            copied = command([*compose, 'cp', 'edge:/data/caddy/pki/authorities/local/root.crt', str(ca)], environment, check=False)
            if copied.returncode == 0 and ca.is_file():
                break
            time.sleep(2)
        smoke.require(ca.is_file())
        command(['python3', '-B', str(ROOT / 'scripts/metrics-smoke.py'), '--project', project,
                 '--edge-url', 'https://localhost:' + str(port), '--edge-ca', str(ca)], environment, timeout=660)
        print('Metrics CI acceptance PASS')
    finally:
        if created:
            try:
                cleanup_owned(project, environment, compose)
            except Exception:
                cleanup_failed = True
        if not cleanup_failed:
            status = staging / 'status'
            if status.exists():
                change_owner(status, os.getuid(), os.getgid(), environment, recursive=True)
            shutil.rmtree(staging)
        if cleanup_failed:
            print(json.dumps({'code': 'METRICS_CI_CLEANUP_REQUIRED', 'project': project}), flush=True)
            raise RuntimeError('Owned metrics CI cleanup requires operator attention')


if __name__ == '__main__':
    def interrupted(signum, frame):
        raise RuntimeError('Interrupted metrics acceptance')
    signal.signal(signal.SIGTERM, interrupted)
    try:
        main()
    except Exception:
        raise SystemExit('Metrics CI acceptance failed; raw diagnostics suppressed; inspect isolated CI privately') from None
PY
