"""Read-only bounded readiness gate for explicitly isolated assistant QA projects."""
import argparse
import re
import subprocess
import time

BANK_SERVICES = ('app-gateway', 'auth-service', 'user-service', 'wallet-service', 'payment-service', 'ledger-service')


def validate_project(project):
    if not re.fullmatch(r'assistant-qa-[a-z0-9][a-z0-9-]{0,48}', project):
        raise ValueError('Expected isolated assistant-qa project name')
    return project


def probe(project, service, path, expected, remaining, runner=subprocess.run):
    command = ['docker', 'run', '--rm', '--network', project + '_bank',
               'curlimages/curl:8.16.0', '--silent', '--output', '/dev/null',
               '--write-out', '%{http_code}', '--connect-timeout', '2',
               '--max-time', '3', 'http://' + service + ':8080' + path]
    try:
        result = runner(command, capture_output=True, text=True, timeout=min(12, remaining))
        return result.returncode == 0 and result.stdout.strip() == str(expected)
    except (subprocess.SubprocessError, OSError):
        return False


def wait_ready(project, timeout=180, clock=time.monotonic,
               sleep=time.sleep, check=probe):
    validate_project(project)
    if not 0 < timeout <= 180:
        raise ValueError('Readiness deadline must be within 180 seconds')
    pending = {(service, '/health/ready', 200) for service in BANK_SERVICES}
    pending.add(('ai-assistant-service', '/health/ready', 200))
    deadline = clock() + timeout
    while pending:
        for item in sorted(pending):
            remaining = deadline - clock()
            if remaining <= 0:
                break
            if check(project, *item, remaining):
                pending.remove(item)
        if not pending:
            return
        remaining = deadline - clock()
        if remaining <= 0:
            raise RuntimeError('Readiness deadline exceeded: ' + ', '.join(sorted({item[0] for item in pending})))
        sleep(min(1, remaining))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', required=True)
    parser.add_argument('--timeout', type=float, default=180)
    args = parser.parse_args()
    wait_ready(args.project, args.timeout)
    print('PASS: isolated bank readiness and declared assistant health state')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError) as error:
        print('FAIL: ' + str(error))
        raise SystemExit(1)
