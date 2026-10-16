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
import sys
import tempfile
import time
import uuid

ROOT = Path.cwd().resolve()
loader = importlib.util.spec_from_file_location('metrics_smoke', ROOT / 'scripts/metrics-smoke.py')
smoke = importlib.util.module_from_spec(loader)
try:
    loader.loader.exec_module(smoke)
except Exception:
    print(json.dumps({'code': 'METRICS_ACCEPTANCE_FAILED', 'phase': 'guard',
                      'category': 'internal', 'cleanup': 'not_needed'}), file=sys.stderr)
    raise SystemExit(1) from None
STATE = {'phase': 'guard', 'cleanup': 'not_needed', 'child': None}
KNOWN_SERVICES = frozenset(smoke.SERVICES + ('auth-db', 'user-db', 'wallet-db', 'payment-db', 'ledger-db', 'metrics-collector', 'edge'))


class CommandFailure(ValueError):
    def __init__(self, category, reason):
        super().__init__('Bounded command failed')
        self.category, self.reason = category, reason


def failure_reason(stderr):
    text = stderr.lower()
    for reason, markers in (
        ('image_unavailable', ('manifest unknown', 'pull access denied', 'failed to resolve reference', 'no matching manifest')),
        ('mount_failure', ('invalid mount config', 'error while mounting', 'bind source path does not exist')),
        ('port_unavailable', ('address already in use', 'port is already allocated')),
        ('container_unhealthy', ('unhealthy',)),
        ('container_exited', ('exited', 'is not running'))):
        if any(marker in text for marker in markers):
            return reason
    return 'unknown'


def command(arguments, environment, timeout=40, check=True):
    result = subprocess.run(arguments, env=environment, capture_output=True, text=True, timeout=timeout, check=False)
    if len(result.stdout) > 1048576 or len(result.stderr) > 1048576:
        raise CommandFailure('output_bound', 'unknown')
    if check:
        if result.returncode != 0:
            raise CommandFailure('command_nonzero', failure_reason(result.stderr))
    return result


def create_failure_snapshot(project, environment, error):
    category = error.category if isinstance(error, CommandFailure) else 'other'
    reason = error.reason if isinstance(error, CommandFailure) else 'unknown'
    if category not in ('output_bound', 'command_nonzero', 'other'):
        category = 'other'
    if reason not in ('image_unavailable', 'mount_failure', 'port_unavailable', 'container_unhealthy', 'container_exited', 'unknown'):
        reason = 'unknown'
    result = {'code': 'METRICS_CREATE_STATE', 'category': category, 'reason': reason,
              'inspection': 'unavailable', 'services': []}
    try:
        smoke.require(smoke.re.fullmatch(r'armoney-metrics-test-[a-f0-9]{32}', project) is not None)
        ids = command(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project],
                      environment, timeout=15).stdout.split()
        smoke.require(len(ids) <= 30 and all(smoke.re.fullmatch(r'[a-f0-9]{12,64}', value) for value in ids))
        items = json.loads(command(['docker', 'inspect', *ids], environment, timeout=15).stdout) if ids else []
        smoke.require(isinstance(items, list) and len(items) == len(ids))
        services = []
        for item in items:
            labels = item['Config'].get('Labels') or {}
            service = labels.get('com.docker.compose.service')
            smoke.require(labels.get('com.docker.compose.project') == project and service in KNOWN_SERVICES)
            smoke.require(item.get('Name') == '/' + project + '-' + service + '-1')
            smoke.require(isinstance(item.get('Id'), str) and smoke.re.fullmatch(r'[a-f0-9]{64}', item['Id']) and
                          any(item['Id'].startswith(value) for value in ids))
            state = item.get('State', {})
            status = state.get('Status')
            health = (state.get('Health') or {}).get('Status', 'absent')
            services.append({'service': service,
                             'state': status if status in ('created', 'running', 'paused', 'restarting', 'removing', 'exited', 'dead') else 'unknown',
                             'health': health if health in ('absent', 'starting', 'healthy', 'unhealthy') else 'unknown',
                             'exit': ('zero' if state['ExitCode'] == 0 else 'nonzero') if type(state.get('ExitCode')) is int else 'unknown'})
        smoke.require(len({item['service'] for item in services}) == len(services))
        result.update(inspection='checked', services=sorted(services, key=lambda item: item['service']))
    except (ValueError, KeyError, TypeError, AssertionError):
        result['inspection'] = 'rejected'
    except (OSError, subprocess.SubprocessError):
        pass
    except Exception:
        # Diagnostic parsing must never replace the original startup failure.
        result['inspection'] = 'rejected'
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
    STATE.update(phase='guard', cleanup='not_needed', child=None)
    project = 'armoney-metrics-test-' + uuid.uuid4().hex
    environment = dict(os.environ)
    smoke.guard(project, environment, ROOT)
    # No default/implicit Compose files, profiles or caller project are allowed.
    for name in ('COMPOSE_FILE', 'COMPOSE_PROJECT_NAME', 'COMPOSE_PROFILES', 'COMPOSE_ENV_FILES'):
        environment.pop(name, None)
    STATE['phase'] = 'inventory'
    inventory_empty(project, environment)
    images = {}
    for service in smoke.SERVICES:
        STATE['phase'] = 'images'
        image = json.loads(command(['docker', 'image', 'inspect', 'arman-bank-' + service], environment).stdout)[0]
        images[service] = image['Id']
    STATE['phase'] = 'staging'
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
        STATE['phase'] = 'config'
        command([*compose, 'config', '--quiet'], environment)
        # Generated project identity and inventory gate precede the first mutation.
        inventory_empty(project, environment)
        created = True
        STATE['phase'] = 'create'
        command([*compose, 'up', '--no-build', '--pull', 'missing', '-d', '--wait', '--wait-timeout', '180'], environment, timeout=240)
        STATE['phase'] = 'certificate'
        ca = staging / 'edge-root.crt'
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            copied = command([*compose, 'cp', 'edge:/data/caddy/pki/authorities/local/root.crt', str(ca)], environment, check=False)
            if copied.returncode == 0 and ca.is_file():
                break
            time.sleep(2)
        smoke.require(ca.is_file())
        STATE['phase'] = 'application'
        result = command(['python3', '-B', str(ROOT / 'scripts/metrics-smoke.py'), '--project', project,
                 '--edge-url', 'https://localhost:' + str(port), '--edge-ca', str(ca)], environment, timeout=660, check=False)
        if result.returncode != 0:
            # Accept only a finite diagnostic tuple; raw child output stays private.
            for line in result.stderr.splitlines():
                try:
                    item = json.loads(line)
                except ValueError:
                    continue
                if (isinstance(item, dict) and set(item) == {'code', 'phase', 'category', 'cleanup'} and
                    all(isinstance(value, str) for value in item.values()) and
                    item['code'] == 'METRICS_ACCEPTANCE_FAILED' and item['phase'] in smoke.PHASES and
                    item['category'] in ('timeout', 'process', 'transport', 'assertion', 'prerequisite', 'internal') and
                    item['cleanup'] in smoke.CLEANUP_STATES):
                    STATE['child'] = item
            smoke.require(False)
    except Exception as error:
        if STATE['phase'] == 'create':
            print(json.dumps(create_failure_snapshot(project, environment, error)), file=sys.stderr)
        raise
    finally:
        original_failure = sys.exc_info()[0] is not None
        original_phase = STATE['phase']
        STATE['phase'] = 'cleanup'
        if created:
            try:
                cleanup_owned(project, environment, compose)
            except Exception:
                cleanup_failed = True
        try:
            if not cleanup_failed:
                status = staging / 'status'
                if status.exists():
                    change_owner(status, os.getuid(), os.getgid(), environment, recursive=True)
                shutil.rmtree(staging)
        except Exception:
            cleanup_failed = True
        STATE['cleanup'] = 'failed' if cleanup_failed else 'checked'
        if original_failure:
            STATE['phase'] = original_phase
        if cleanup_failed:
            if not original_failure:
                raise RuntimeError('Owned metrics CI cleanup requires operator attention')
    print('Metrics CI acceptance PASS')


if __name__ == '__main__':
    def interrupted(signum, frame):
        raise RuntimeError('Interrupted metrics acceptance')
    signal.signal(signal.SIGTERM, interrupted)
    try:
        main()
    except Exception as error:
        diagnostic = smoke.diagnostic(STATE['phase'], error, STATE['cleanup'])
        if STATE['child'] is not None:
            print(json.dumps(STATE['child']), file=sys.stderr)
        print(json.dumps(diagnostic), file=sys.stderr)
        raise SystemExit(1) from None
PY
