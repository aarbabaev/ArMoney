#!/usr/bin/env python3
"""Conservative changed-path CI selection. Unknown inputs select the complete suite."""
import argparse
import json
from pathlib import Path
import re
import subprocess

FLAGS = ('dispatcher', 'build', 'android', 'web', 'assistant', 'kafka', 'sms', 'baseline',
         'bank_smoke', 'assistant_smoke', 'sharding', 'replication', 'sso')
MODULES = tuple(sorted(('platform-runtime', 'app-gateway', 'auth-service', 'user-service',
                        'wallet-service', 'payment-service', 'ledger-service', 'sms-sandbox')))
LINKS = {
    'app-gateway': ('bank_smoke', 'sso', 'assistant_smoke'),
    'auth-service': ('bank_smoke', 'sso', 'web', 'android'),
    'user-service': ('bank_smoke', 'sharding', 'sso'),
    'wallet-service': ('bank_smoke',), 'payment-service': ('bank_smoke',),
    'ledger-service': ('bank_smoke', 'replication'), 'sms-sandbox': ('sms',),
    'platform-runtime': ('bank_smoke', 'sharding', 'replication', 'sso', 'assistant_smoke'),
}
SMOKES = {
    'smoke.py': ('bank_smoke',), 'auth-smoke.py': ('bank_smoke',),
    'onboarding-smoke.py': ('bank_smoke',), 'provisioning-smoke.py': ('bank_smoke',),
    'ledger-smoke.py': ('bank_smoke',), 'p2p-smoke.py': ('bank_smoke',),
    'assistant-ready.py': ('assistant_smoke',), 'assistant-smoke.py': ('assistant_smoke',),
    'users-sharding-smoke.py': ('sharding',), 'ledger-replication-smoke.py': ('replication',),
    'sso-smoke.py': ('sso',), 'web-smoke.py': ('sso', 'web'),
    'sms-sandbox-smoke.py': ('sms',), 'kafka-smoke.py': ('kafka',),
}
BASELINE = {'backup-local-baseline.py', 'verify-local-baseline.py', 'seed-local-users.py',
            'fund-local-users.py'}


def classify(paths, full=False):
    """Return finite Actions strings plus an encoded, sorted allowlisted module list."""
    selected, modules = set(), set()
    def complete():
        return dict({flag: 'true' for flag in FLAGS}, modules=json.dumps(list(MODULES), separators=(',', ':')))
    def select(*flags):
        selected.update(flags)
        if any(flag in flags for flag in ('bank_smoke', 'assistant_smoke', 'sms', 'baseline', 'sharding', 'replication', 'sso')):
            selected.add('build')
    if full or not isinstance(paths, (list, tuple)):
        return complete()
    for path in paths:
        if (not isinstance(path, str) or not path or path.startswith('/') or '\\' in path or
            any(ord(char) < 32 for char in path) or
            any(part in ('', '.', '..') for part in path.split('/'))):
            return complete()
        parts = path.split('/')
        if ((path.endswith('.md') and (path in ('README.md', 'ARCHITECTURE.md', 'AGENTS.md') or parts[0] == 'docs' or
             (len(parts) == 2 and parts[-1].lower() in ('readme.md', 'agents.md')))) or
            (parts[0] in ('.agents', '.codex') and Path(path).suffix in ('.md', '.toml', '.json')) or
            (parts[0] == 'docs' and Path(path).suffix in ('.txt', '.mermaid', '.svg', '.png', '.jpg'))):
            continue
        if path.endswith('.md'):
            return complete()  # Runtime policy/knowledge markdown is not documentation by extension.
        if parts[0] == 'ios':  # Standing owner pause: no iOS build/runner is enabled.
            continue
        if (parts[0] in ('.github', 'gradle') or path in ('build.gradle', 'settings.gradle', 'gradle.properties',
            'gradlew', 'gradlew.bat', '.dockerignore', '.gitattributes') or 'Dockerfile' in parts[-1] or
            path.startswith('infra/local-ci/') or path.startswith('infra/runtime/')):
            return complete()
        if path.startswith(('infra/dispatcher/', 'infra/agent-dashboard/')):
            select('dispatcher'); continue
        if parts[0] == 'android':
            select('android'); continue
        if parts[0] == 'web':
            if len(parts) > 2 and parts[1] in ('tests', 'e2e'):
                select('web')
            else:
                select('web', 'sso'); modules.add('auth-service')
            continue
        if path.startswith('infra/kafka/') or path.startswith('compose.kafka'):
            select('kafka'); continue
        if parts[0] == 'sso-service' or path.startswith(('compose.sso', 'infra/edge/')):
            select('sso', 'web', 'android'); modules.add('auth-service'); continue
        if parts[0] == 'ai-assistant-service' or path.startswith('infra/assistant-db/') or path.startswith('compose.assistant'):
            select('assistant')
            if not (len(parts) > 2 and (parts[1] == 'tests' or parts[1].endswith('.Tests'))):
                select('assistant_smoke')
            continue
        if path.startswith('infra/ledger-replication/'):
            select('replication'); modules.add('ledger-service'); continue
        module = parts[0]
        if module in MODULES:
            modules.add(module); select('build')
            if parts[1:3] == ['src', 'test'] and len(parts) > 3:
                continue
            if module == 'platform-runtime':
                modules.update(MODULES)
            select(*LINKS[module])
            # Public contract changes are consumed by every client, independent of service ownership.
            if ((path.endswith(('.yaml', '.yml', '.proto', '.json')) and any(
                    token in path.lower() for token in ('openapi', 'contract', 'schema', 'proto'))) or
                    path.endswith(('Routes.java', 'Proxy.java'))):
                select('web', 'android', 'assistant', 'assistant_smoke', 'sso', 'bank_smoke')
                modules.update(MODULES)
            continue
        if parts[0] == 'scripts' and len(parts) == 2:
            name = parts[1]
            if name in ('ci-impact.py', 'test-ci-impact.py') or 'local-ci' in name:
                return complete()
            underlying = name.removeprefix('test-')
            if underlying in SMOKES:
                select(*SMOKES[underlying]); continue
            if underlying in BASELINE:
                select('baseline'); continue
            if name in ('test-kafka-smoke-guards.py', 'test-kafka-snapshot.py'):
                select('kafka'); continue
            return complete()
        if path.endswith(('.proto',)) or any(x in path.lower() for x in ('openapi', '/contracts/', '/schemas/')):
            select('build', 'web', 'android', 'assistant', 'assistant_smoke', 'bank_smoke', 'sso')
            modules.update(MODULES); continue
        return complete()
    return dict({flag: 'true' if flag in selected else 'false' for flag in FLAGS},
                modules=json.dumps(sorted(modules), separators=(',', ':')))


def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.DEVNULL, timeout=30)


def changed_paths(base, head, pull_request=False):
    """None means uncertain base => full CI. Head uncertainty must stop selection."""
    if not isinstance(head, str) or not re.fullmatch(r'[0-9a-fA-F]{40}', head) or head == '0' * 40:
        raise ValueError('Verified checkout head required')
    head = head.lower()
    try:
        if (git('rev-parse', '--verify', head + '^{commit}').decode().strip() != head or
                git('rev-parse', 'HEAD').decode().strip() != head):
            raise ValueError('Checkout differs from requested head')
    except (OSError, subprocess.SubprocessError, UnicodeError):
        raise ValueError('Checkout head unavailable') from None
    if not isinstance(base, str) or not re.fullmatch(r'[0-9a-fA-F]{40}', base) or base == '0' * 40:
        return None
    base = base.lower()
    try:
        if git('rev-parse', '--verify', base + '^{commit}').decode().strip() != base:
            return None
        if pull_request:
            base = git('merge-base', base, head).decode().strip()
            if not re.fullmatch(r'[0-9a-f]{40}', base):
                return None
        raw = git('diff', '--name-only', '-z', '--no-renames', base, head, '--')
        return raw.rstrip(b'\0').decode('utf-8', errors='strict').split('\0') if raw else []
    except (OSError, subprocess.SubprocessError, UnicodeError):
        return None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='')
    parser.add_argument('--head', required=True)
    parser.add_argument('--full', action='store_true')
    parser.add_argument('--pull-request', action='store_true')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--summary', type=Path)
    parser.add_argument('--reason', choices=('pull-request', 'push', 'manual', 'unknown'), default='unknown')
    args = parser.parse_args(argv)
    try:
        paths = changed_paths(args.base, args.head, args.pull_request)
    except ValueError:
        parser.error('Checkout head verification failed; selection withheld')
    result = classify(paths, full=args.full)
    lines = ''.join(key + '=' + value + '\n' for key, value in result.items())
    if args.output:
        with args.output.open('a', encoding='utf-8', newline='\n') as stream:
            stream.write(lines)
    else:
        print(lines, end='')
    if args.summary:
        text = '### Affected CI selection\n\nTrigger: ' + args.reason + '.\n\n'
        text += 'Checks: ' + ', '.join(flag for flag in FLAGS if result[flag] == 'true') + '.\n\n'
        text += 'Modules: ' + ', '.join(json.loads(result['modules'])) + '.\n'
        with args.summary.open('a', encoding='utf-8', newline='\n') as stream:
            stream.write(text)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
