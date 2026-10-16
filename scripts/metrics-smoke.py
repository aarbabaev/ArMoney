"""Read-only probes and one reversible DB outage on an owned disposable CI stack.

Provision externally with explicit Compose files including compose.metrics.yaml.
Requires a same-project HTTPS edge using a loopback certificate and trusted CA.
Never starts a stack, migrates, seeds, removes volumes or accepts CI=true alone.
"""
import argparse
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ('app-gateway', 'auth-service', 'user-service', 'wallet-service', 'payment-service', 'ledger-service')


def require(condition):
    if not condition:
        raise ValueError('Disposable metrics acceptance prerequisite failed')


def guard(project, environment, root=ROOT):
    require(bool(re.fullmatch(r'armoney-metrics-test-[a-f0-9]{32}', project)))
    require(environment.get('DOCKER_HOST') == 'unix:///run/inner-docker/docker.sock')
    require(not any(environment.get(name) for name in ('DOCKER_CONTEXT', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH')))
    require(environment.get('ARMONEY_DISPOSABLE_CI') == 'true')
    workspace = environment.get('GITHUB_WORKSPACE', '')
    require(workspace.startswith('/work/') and str(root.resolve()) == str(Path(workspace).resolve()))
    require(not (root / '.env').exists() and not (root / 'compose.override.yaml').exists())


def validate_inventory(items, project):
    require(isinstance(items, list) and bool(items))
    owned = {}
    for item in items:
        labels = item['Config']['Labels'] or {}
        require(labels.get('com.docker.compose.project') == project)
        service = labels.get('com.docker.compose.service')
        require(isinstance(service, str) and service not in owned)
        require(item['State']['Running'])
        require(re.fullmatch(r'[a-f0-9]{64}', item['Id']) is not None)
        owned[service] = item
    require(set(SERVICES + ('payment-db', 'metrics-collector', 'edge')).issubset(owned))
    collector = owned['metrics-collector']
    require(collector['Config']['Labels'].get('com.armoney.metrics.disposable') == 'true')
    require(not collector['HostConfig'].get('PortBindings'))
    require(not collector['HostConfig'].get('Privileged'))
    require(collector['HostConfig'].get('ReadonlyRootfs') is True)
    require(collector['Config']['User'] == '10001:10001')
    for service in SERVICES:
        bindings = owned[service]['HostConfig'].get('PortBindings') or {}
        if service != 'app-gateway':
            require(not bindings)
        else:
            require(all(binding['HostIp'] in ('127.0.0.1', '::1') for group in bindings.values() for binding in (group or [])))
    return owned


def edge_target(url, item):
    parsed = urllib.parse.urlsplit(url)
    require(parsed.scheme == 'https' and parsed.hostname in ('localhost', '127.0.0.1', '::1'))
    require(parsed.username is None and parsed.password is None and parsed.path in ('', '/') and not parsed.query and not parsed.fragment)
    require(parsed.port is not None)
    bindings = item['HostConfig'].get('PortBindings') or {}
    require(any(binding['HostIp'] in ('127.0.0.1', '::1') and binding['HostPort'] == str(parsed.port)
                for binding in (bindings.get('443/tcp') or [])))
    return url.rstrip('/') + '/internal/metrics'


def check_edge(endpoint, ca):
    context = ssl.create_default_context(cafile=ca)
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *unused):
            return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                urllib.request.HTTPSHandler(context=context), NoRedirect())
    try:
        with opener.open(endpoint, timeout=5) as response:
            require(response.status == 404)
    except urllib.error.HTTPError as error:
        require(error.code == 404)
        error.close()


class Acceptance:
    def __init__(self, project):
        self.project = project
        self.deadline = time.monotonic() + 600

    def run(self, *arguments, source=None, timeout=40):
        remaining = self.deadline - time.monotonic()
        require(remaining > 0)
        result = subprocess.run(['docker', *arguments], input=source, text=True,
                                capture_output=True, timeout=min(timeout, remaining), check=False)
        require(result.returncode == 0 and len(result.stdout) <= 1048576)
        return result.stdout

    def inventory(self):
        ids = self.run('ps', '-aq', '--filter', 'label=com.docker.compose.project=' + self.project).split()
        require(1 <= len(ids) <= 30 and all(re.fullmatch(r'[a-f0-9]{12,64}', identifier) for identifier in ids))
        owned = validate_inventory(json.loads(self.run('inspect', *ids)), self.project)
        # Validate every attached network and named volume before any mutation.
        networks, volumes = set(), set()
        for item in owned.values():
            networks.update(item['NetworkSettings']['Networks'])
            volumes.update(mount['Name'] for mount in item.get('Mounts', []) if mount['Type'] == 'volume')
        require(self.project + '_bank' in networks)
        for kind, names in (('network', networks), ('volume', volumes)):
            for name in names:
                require(name.startswith(self.project + '_'))
                resource = json.loads(self.run(kind, 'inspect', name))[0]
                require((resource.get('Labels') or {}).get('com.docker.compose.project') == self.project)
        for service in SERVICES + ('metrics-collector',):
            require(self.project + '_bank' in owned[service]['NetworkSettings']['Networks'])
        return owned

    def restore(self, identifier):
        # Restoration has its own bounded budget even if acceptance exhausted its
        # deadline. Only the exact container validated before stop is accepted.
        require(re.fullmatch(r'[a-f0-9]{64}', identifier) is not None)
        result = subprocess.run(['docker', 'start', identifier], capture_output=True,
                                text=True, timeout=40, check=False)
        require(result.returncode == 0)

    def probe(self, collector, failure=False, auth=False):
        # No endpoint, key or raw response enters argv/stdout. The trusted collector
        # validates/parses samples; this child emits only a finite success marker.
        source = '''import sys,os,json,datetime as dt
sys.path.insert(0,'/collector')
import collector as c
config=c.validate(json.load(open('/config/collector.json')))
assert {t['service'] for t in config['targets']} == c.SERVICES
assert all(t['url']=='http://'+t['service']+':8080/internal/metrics' for t in config['targets'])
assert all(t['key_env']=='INTERNAL_METRICS_KEY' for t in config['targets'])
key=os.environ['INTERNAL_METRICS_KEY']
'''
        if auth:
            source += '''for target in config['targets']:
 for supplied in ('', 'invalid-synthetic-key'):
  try: c.fetch(target['url'],supplied,config['timeout_seconds'])
  except c.ObservationError as error: assert error.code=='AUTH_REJECTED'
  else: raise AssertionError()
'''
        if failure:
            source += '''import urllib.request,urllib.error
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),c.NoRedirect())
try:
 with opener.open('http://payment-service:8080/health/ready',timeout=5) as response: raise AssertionError()
except urllib.error.HTTPError as error:
 assert error.code==503
 error.close()
'''
        source += '''result=c.collect(config,os.environ)
for target in result['targets']:
 values=target['metrics']
 if FAILED and target['service']=='payment-service':
  # Pool acquisition in both observation and readiness can exceed the scrape
  # budget. A bounded timeout is unavailable/UNKNOWN, never false health.
  assert (target['availability']==0 and all(value is None for value in values.values())) or (target['availability']==1 and values['armoney_ready']==0)
 else:
  assert target['availability']==1 and values['armoney_ready']==1
 if target['service']=='payment-service':
  assert all((values[name] is None if FAILED else values[name]==0) for name in ('armoney_recovery_backlog','armoney_outbox_backlog'))
 assert values['armoney_replica_lag_seconds'] is None
 assert values['armoney_recovery_failures_total'] is None
 assert values['armoney_outbox_failures_total'] is None
assert not c.fresh(result,dt.datetime.fromisoformat(result['valid_until']))
snapshot=json.load(open('/status/metrics-status.json'))
assert c.fresh(snapshot,dt.datetime.now(dt.timezone.utc))
assert not c.fresh(snapshot,dt.datetime.fromisoformat(snapshot['valid_until']))
print('PASS')
'''.replace('FAILED', repr(failure))
        require(self.run('exec', '-i', collector, 'python', '-B', '-', source=source, timeout=100).strip() == 'PASS')

    def wait_ready(self, collector):
        until = min(self.deadline, time.monotonic() + 180)
        while time.monotonic() < until:
            try:
                self.probe(collector)
                return
            except ValueError:
                time.sleep(2)
        raise ValueError('Metrics readiness acceptance failed')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--project', required=True)
    parser.add_argument('--edge-url', required=True)
    parser.add_argument('--edge-ca', required=True, type=Path)
    args = parser.parse_args()
    stopped = None
    acceptance = None
    try:
        guard(args.project, os.environ)
        acceptance = Acceptance(args.project)
        owned = acceptance.inventory()
        endpoint = edge_target(args.edge_url, owned['edge'])
        # The subprocess deadline bounds even drip-fed TLS/headers. No proxy,
        # redirect or certificate bypass; URL and CA path stay out of argv.
        edge = subprocess.run([sys.executable, '-B', str(Path(__file__).resolve()), '--edge-worker'],
            input=json.dumps([endpoint, str(args.edge_ca)]), capture_output=True,
            text=True, timeout=10, check=False)
        require(edge.returncode == 0 and edge.stdout == '')
        collector = owned['metrics-collector']['Id']
        acceptance.wait_ready(collector)
        acceptance.probe(collector, auth=True)
        # Revalidate ownership immediately before the only reversible mutation.
        current = acceptance.inventory()
        require(current['payment-db']['Id'] == owned['payment-db']['Id'])
        require(current['metrics-collector']['Id'] == collector)
        stopped = owned['payment-db']['Id']
        acceptance.run('stop', '--time', '10', stopped)
        try:
            acceptance.probe(collector, failure=True)
        finally:
            acceptance.restore(stopped)
            stopped = None
        acceptance.wait_ready(collector)
        print('Metrics disposable acceptance PASS')
    except (OSError, ValueError, KeyError, TypeError, AssertionError, subprocess.SubprocessError):
        raise SystemExit('Metrics disposable acceptance failed; no raw diagnostics emitted') from None
    finally:
        if stopped is not None and acceptance is not None:
            # Best-effort restore of the exact previously validated container.
            try:
                acceptance.restore(stopped)
            except (OSError, ValueError, subprocess.SubprocessError):
                raise SystemExit('Metrics acceptance failed; owned payment DB restoration requires CI operator attention') from None


if __name__ == '__main__':
    if sys.argv[1:] == ['--edge-worker']:
        try:
            check_edge(*json.loads(sys.stdin.read(4096)))
        except Exception:
            raise SystemExit(1) from None
    else:
        main()
