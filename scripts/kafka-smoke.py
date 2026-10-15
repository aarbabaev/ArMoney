"""Disposable Kafka transport acceptance. Never targets the host/persistent daemon.

Owns a fresh UUID project and its cleanup. No bank services or financial fixtures.
"""
import argparse
from collections import Counter
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
HOST = 'unix:///run/inner-docker/docker.sock'
NODES = tuple(f'kafka-{role}-{n}' for role in ('controller', 'broker') for n in (1, 2, 3))
TOPICS = ('armoney.ledger.transfer.commands.v1', 'armoney.ledger.transfer.results.v1',
          'armoney.payment.outcomes.v1')
CONFIG = '/run/kafka-secrets/client.properties'
BOOTSTRAP = ','.join(f'kafka-broker-{n}:9092' for n in (1, 2, 3))
PHASES = frozenset(('prepare', 'format', 'initial-start', 'topics', 'baseline', 'restart',
                   'one-broker-failure', 'two-broker-failure', 'broker-recovery',
                   'controller-failure', 'missing-client-TLS', 'wrong-CA-TLS',
                   'storage-identity', 'allowed-ACL', 'denied-ACL', 'cold-copy',
                   'cold-restore', 'cleanup'))
OPERATIONS = frozenset(('ps', 'inspect', 'volume', 'network', 'compose', 'create', 'start', 'stop', 'rm'))
SUBCOMMANDS = frozenset(('ls', 'inspect', 'rm', 'create', 'run', 'up', 'config'))
ERROR_TYPES = ('AuthorizationException', 'TopicAuthorizationException', 'GroupAuthorizationException',
               'ClusterAuthorizationException', 'SSLHandshakeException', 'SslAuthenticationException',
               'TimeoutException', 'NotEnoughReplicasException', 'NotEnoughReplicasAfterAppendException',
               'ConfigException', 'IllegalArgumentException', 'AccessDeniedException')


class Failed(Exception):
    pass


def require(condition, message):
    if not condition:
        raise Failed(message)


def safe_docker_failure(args, result, phase, env=None):
    # Emit only finite labels. Arguments, output, paths and credential values
    # must never become diagnostic prose, including unexpected command shapes.
    operation = args[0] if args and args[0] in OPERATIONS else 'unknown'
    subcommand = next((arg for arg in args[1:] if arg in SUBCOMMANDS), 'none')
    node = next((arg for arg in args if arg in NODES), 'none')
    principal = (env or {}).get('KAFKA_CLIENT_PRINCIPAL')
    principal = principal if principal in ('admin', 'payment', 'ledger', 'denied') else 'none'
    phase = phase if phase in PHASES else 'unknown'
    code = result.returncode if isinstance(result.returncode, int) else 'unknown'
    output = result.stdout + '\n' + result.stderr
    errors = [error for error in ERROR_TYPES if re.search(r'\b' + error + r'\b', output)]
    return (f'Docker failure: phase={phase} operation={operation} subcommand={subcommand} '
            f'principal={principal} node={node} exit={code} errors=' + (','.join(errors) or 'unclassified'))


@contextmanager
def preserving_cleanup(cleanup):
    try:
        yield
    except BaseException:
        try:
            cleanup()
        except BaseException as error:
            category = ('guard-or-command-failure' if isinstance(error, Failed) else
                        'bounded-timeout' if isinstance(error, subprocess.TimeoutExpired) else 'unclassified')
            print('Kafka cleanup also failed: category=' + category + '; primary failure preserved', file=sys.stderr)
        raise
    else:
        # Cleanup failure still fails an otherwise successful acceptance run.
        cleanup()


def guard(project, env, root=ROOT):
    require(re.fullmatch(r'armoney-kafka-test-[0-9a-f]{32}', project), 'Dedicated UUID project required')
    require(env.get('CI') == 'true' and env.get('ARMONEY_DISPOSABLE_CI') == 'true', 'Disposable CI markers required')
    require(env.get('DOCKER_HOST') == HOST and not env.get('DOCKER_CONTEXT'), 'Dedicated daemon required')
    workspace = Path(env.get('GITHUB_WORKSPACE', '')).resolve()
    root = root.resolve()
    require(workspace.as_posix().startswith('/work/') and workspace == root, 'Exact isolated checkout required')
    require(not any((root / name).exists() for name in
                    ('.env', 'compose.override.yaml', 'compose.override.yml', 'docker-compose.override.yml')),
            'Local overrides forbidden')


def clean_environment(env):
    return {k: v for k, v in env.items()
            if not k.upper().startswith(('DOCKER_', 'COMPOSE_', 'KAFKA_'))}


def check_container(item, project, root, secrets, known=None):
    labels = item['Config'].get('Labels') or {}
    service = labels.get('com.docker.compose.service')
    require(labels.get('com.docker.compose.project') == project, 'Container project mismatch')
    require(service in (*NODES, 'kafka-client', 'kafka-init'), 'Unexpected service')
    oneoff = labels.get('com.docker.compose.oneoff', '').lower() == 'true'
    name = item['Name'].lstrip('/')
    require((not oneoff and service in NODES and name == f'{project}-{service}-1') or
            (oneoff and name == project + '-probe'), 'Unexpected container identity')
    require(labels.get('com.docker.compose.project.config_files') == str(root / 'compose.kafka.yaml'),
            'Unexpected Compose source')
    require(re.fullmatch('[0-9a-f]{64}', item['Id']), 'Invalid container ID')
    if known is not None and not oneoff:
        require(known.get(service) == item['Id'], 'Container replaced during acceptance')
    host = item['HostConfig']
    require(not host.get('PortBindings') and not host.get('Privileged') and
            host.get('NetworkMode') != 'host' and
            not host.get('Devices') and host.get('ReadonlyRootfs'), 'Unsafe container configuration')
    # Validate resolved mounts regardless of Docker's short/long syntax representation.
    mounts = item['Mounts']
    expected = {'/opt/armoney', '/run/kafka-secrets'}
    if service in NODES:
        expected.add('/var/lib/kafka/data')
    # Docker may report configured tmpfs in Mounts as well as HostConfig.Tmpfs.
    persistent = [m for m in mounts if m['Type'] != 'tmpfs']
    require({m['Destination'] for m in persistent} == expected and len(persistent) == len(expected),
            'Unexpected persistent mount')
    for mount in persistent:
        target = mount['Destination']
        if target == '/var/lib/kafka/data':
            require(mount['Type'] == 'volume' and mount.get('Name') == f'{project}_{service}-data',
                    'Foreign data volume')
        else:
            require(mount['Type'] == 'bind' and not mount['RW'], 'Read-only fixture bind required')
            path = Path(mount['Source']).resolve()
            if target == '/opt/armoney':
                require(path == (root / 'infra/kafka').resolve(), 'Foreign source mount')
            else:
                allowed = (service,) if service in NODES else ('admin', 'payment', 'ledger', 'denied')
                require(path in {(secrets / principal).resolve() for principal in allowed}, 'Foreign credentials mount')
    networks = item['NetworkSettings'].get('Networks') or {}
    require(set(networks) == {project + '_kafka'}, 'Foreign container network')
    return service, item['Id'], oneoff


def topic_state(text, expected_isr):
    for topic in TOPICS:
        header = next((line for line in text.splitlines() if f'Topic: {topic}\t' in line
                       and 'PartitionCount:' in line), '')
        require(re.search(r'PartitionCount:\s+3\b', header) and
                re.search(r'ReplicationFactor:\s+3\b', header), 'Topic partition/replication drift')
        tokens = set(re.split(r'[,\s]+', header))
        require({'min.insync.replicas=2', 'retention.ms=604800000', 'cleanup.policy=delete',
                 'unclean.leader.election.enable=false', 'max.message.bytes=32768'} <= tokens,
                'Topic configuration drift')
        rows = [line for line in text.splitlines() if f'Topic: {topic}\t' in line and 'Partition:' in line]
        require(len(rows) == 3, 'Expected three described partitions')
        partitions = set()
        for row in rows:
            match = re.search(r'Partition:\s+(\d+).*Leader:\s+(-?\d+).*Replicas:\s+([\d,]+).*Isr:\s+([\d,]+)', row)
            require(match is not None, 'Unrecognized partition description')
            partition, leader, replicas, isr = match.groups()
            partitions.add(int(partition))
            require(len(replicas.split(',')) == 3 and
                    set(replicas.split(',')) == {'101', '102', '103'} and
                    len(isr.split(',')) == len(expected_isr) and
                    set(isr.split(',')) == expected_isr and leader in expected_isr,
                    'Partition not at required ISR/leader state')
        require(partitions == {0, 1, 2}, 'Partition identity mismatch')


def rejected(result, reason, probe='producer rejection'):
    # The frozen producer wrapper uses --sync: an exception must fail the process.
    # A generic timeout, missing tool or lost connection is never sufficient.
    output = result.stdout + result.stderr
    explicit = reason in output
    if reason == 'NotEnoughReplicas':
        # Retried broker responses use enum names; the final exception can be
        # TimeoutException after delivery.timeout.ms. The enum is required
        # evidence of a broker response, never a timeout-only substitute.
        explicit = re.search(r'\b(?:NotEnoughReplicas(?:AfterAppend)?Exception|'
                             r'NOT_ENOUGH_REPLICAS(?:_AFTER_APPEND)?)\b', output) is not None
    require(result.returncode != 0 and explicit,
            f'{probe}: nonzero exit and explicit {reason} rejection required (exit={result.returncode})')


def denied_consumer(result, probe='consumer authorization denial'):
    # Kafka 4.3.1 ConsoleConsumer.process logs receive failures and returns,
    # which can exit zero. Require an actual authorization exception on stderr
    # and no stdout records; successful consumption can never count as denial.
    explicit = re.search(r'\b(?:Topic|Group|Cluster)?AuthorizationException\b', result.stderr) is not None
    require(not result.stdout.strip() and explicit,
            f'{probe}: explicit authorization denial and zero records required (exit={result.returncode})')


def require_tls_rejection(result):
    require(result.returncode != 0 and re.search(
        r'SSLHandshakeException|SslAuthenticationException|certificate_required|bad_certificate',
        result.stdout + result.stderr), 'TLS negative probe did not produce explicit TLS rejection')


def storage_probe_script():
    # Backup stays on the same disposable data volume. Never touch log segments.
    # Restoration failure fails the probe; interruption cannot be reported as recovery.
    return r"""set -euo pipefail
meta=/var/lib/kafka/data/meta.properties
backup=/var/lib/kafka/data/meta.properties.armoney-smoke-backup
[[ -s "$meta" && ! -e "$backup" ]]
cp -p -- "$meta" "$backup"
restore() { cp -p -- "$backup" "$meta" && cmp -s -- "$backup" "$meta" && rm -- "$backup"; }
trap restore EXIT
trap 'exit 1' HUP INT TERM
case "$1" in
  missing) rm -- "$meta"; expected='Kafka storage is not initialized; explicit format or recovery required' ;;
  cluster) sed 's/^cluster.id=.*/cluster.id=synthetic-invalid-cluster/' "$backup" > "$meta"
           expected='Kafka storage identity mismatch; refusing startup' ;;
  node) sed 's/^node.id=.*/node.id=999/' "$backup" > "$meta"
        expected='Kafka storage identity mismatch; refusing startup' ;;
  *) exit 2 ;;
esac
if [[ "$1" != missing ]]; then before=$(sha256sum "$meta"); fi
set +e
output=$(timeout 20 bash /opt/armoney/start.sh 2>&1)
result=$?
set -e
[[ "$result" != 0 && "$result" != 124 && "$result" != 137 ]]
printf '%s\n' "$output" | grep -Fxq "$expected"
if [[ "$1" == missing ]]; then [[ ! -e "$meta" ]]; else [[ "$(sha256sum "$meta")" == "$before" ]]; fi
restore
trap - EXIT HUP INT TERM
printf 'STARTUP_REJECTED_AND_RESTORED_%s\n' "$1"
"""


class Smoke:
    def __init__(self, project, env=None, root=ROOT):
        self.env = dict(os.environ if env is None else env)
        self.root = root.resolve()
        guard(project, self.env, self.root)
        self.project = project
        self.env = clean_environment(self.env)
        self.owned = False
        self.known = None
        self.secret_parent = None
        self.secrets = None
        self.hash = hashlib.sha256((self.root / 'compose.kafka.yaml').read_bytes()).digest()
        self.phase = 'prepare'

    def stage(self, phase):
        require(phase in PHASES, 'Invalid diagnostic phase')
        self.phase = phase
        print('Kafka phase: ' + phase, flush=True)

    def docker(self, *args, check=True, timeout=90, input=None, env=None):
        try:
            result = subprocess.run(['docker', '--host', HOST, *args], env=env or self.env,
                                    input=input, capture_output=True, text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            result = subprocess.CompletedProcess([], -1, '', 'TimeoutException')
            raise Failed(safe_docker_failure(args, result, getattr(self, 'phase', 'unknown'), env)) from None
        if check:
            require(result.returncode == 0,
                    safe_docker_failure(args, result, getattr(self, 'phase', 'unknown'), env))
        return result

    def resources(self):
        selector = 'label=com.docker.compose.project=' + self.project
        containers = self.docker('ps', '-aq', '--no-trunc', '--filter', selector).stdout.split()
        volumes = self.docker('volume', 'ls', '-q', '--filter', selector).stdout.split()
        networks = self.docker('network', 'ls', '-q', '--no-trunc', '--filter', selector).stdout.split()
        return containers, volumes, networks

    def compose(self, *args, principal='denied', check=True, timeout=120, input=None):
        require(self.owned, 'Project has not passed freshness check')
        require(hashlib.sha256((self.root / 'compose.kafka.yaml').read_bytes()).digest() == self.hash,
                'Compose source changed during test')
        require(principal in ('admin', 'payment', 'ledger', 'denied'), 'Unknown fixture principal')
        env = dict(self.env, KAFKA_SECRETS_DIR=str(self.secrets), KAFKA_CLIENT_PRINCIPAL=principal)
        return self.docker('compose', '--project-directory', str(self.root), '--env-file',
                           str(self.secret_parent / 'empty.env'), '-p', self.project,
                           '-f', str(self.root / 'compose.kafka.yaml'), *args,
                           check=check, timeout=timeout, input=input, env=env)

    def inventory(self, complete=True):
        containers, volumes, networks = self.resources()
        node_ids = {}
        for cid in containers:
            item = json.loads(self.docker('inspect', cid).stdout)[0]
            service, identity, oneoff = check_container(item, self.project, self.root, self.secrets, self.known)
            if not oneoff:
                require(service not in node_ids, 'Duplicate node')
                node_ids[service] = identity
        expected_volumes = {f'{self.project}_{node}-data' for node in NODES}
        require(set(volumes) <= expected_volumes, 'Unexpected project volume')
        for volume in volumes:
            item = json.loads(self.docker('volume', 'inspect', volume).stdout)[0]
            labels = item.get('Labels') or {}
            require(labels.get('com.docker.compose.project') == self.project and
                    labels.get('com.docker.compose.volume') == volume[len(self.project) + 1:] and
                    item.get('Driver') == 'local' and not item.get('Options'), 'Foreign volume ownership/options')
        require(len(networks) <= 1, 'Unexpected project network')
        for network in networks:
            item = json.loads(self.docker('network', 'inspect', network).stdout)[0]
            require(item['Name'] == self.project + '_kafka' and item.get('Internal') is True and
                    item.get('Driver') == 'bridge' and
                    (item.get('Labels') or {}).get('com.docker.compose.project') == self.project and
                    set(item.get('Containers') or {}) <= set(containers), 'Foreign network or member')
        if complete:
            require(set(node_ids) == set(NODES) and set(volumes) == expected_volumes and len(networks) == 1,
                    'Incomplete cluster resources')
        return containers, volumes, networks, node_ids

    def probe(self, principal, *args, input=None, check=True, raw=False):
        self.inventory()
        command = list(args) if raw else ['bash', '/opt/armoney/client.sh', *args]
        return self.compose('run', '--rm', '--no-deps', '-T', '--name', self.project + '-probe',
                            'kafka-client', *command, principal=principal, input=input, check=check, timeout=75)

    def eventually(self, action, label, seconds=180):
        deadline = time.monotonic() + seconds
        while True:
            try:
                action()
                return
            except Failed:
                if time.monotonic() >= deadline:
                    raise Failed(label + ' timed out') from None
                time.sleep(2)

    def wait_isr(self, ids):
        self.eventually(lambda: topic_state(self.probe('admin', 'topics').stdout, set(ids)), 'ISR convergence')

    def change(self, action, *nodes):
        require(action in ('stop', 'start') and set(nodes) <= set(NODES), 'Unapproved lifecycle operation')
        self.inventory()
        self.docker(action, *[self.known[node] for node in nodes], timeout=120)

    def quorum(self, cluster=None, stopped=()):
        output = self.probe('admin', 'quorum').stdout
        found = re.search(r'ClusterId:\s+(\S+)', output)
        leader = re.search(r'LeaderId:\s+([123])\b', output)
        require(found and leader and 'kafka-controller-' + leader.group(1) not in stopped,
                'Metadata quorum unavailable or stale leader')
        if cluster is not None:
            require(found.group(1) == cluster, 'Cluster identity changed')
        # Broker faults default to all controllers; controller faults declare the exact stopped node.
        for node in NODES[:3]:
            item = json.loads(self.docker('inspect', self.known[node]).stdout)[0]
            require(item['State']['Running'] == (node not in stopped), 'Unexpected controller running state')
        return found.group(1)

    def controller_failure(self, cluster, publish):
        output = self.probe('admin', 'quorum').stdout
        leader = re.search(r'LeaderId:\s+([123])\b', output)
        require(leader is not None, 'Cannot identify controller leader')
        node = 'kafka-controller-' + leader.group(1)
        self.change('stop', node)
        try:
            self.eventually(lambda: self.quorum(cluster, stopped=(node,)), 'Controller re-election')
            self.wait_isr({'101', '102', '103'})
            publish('controller-leader-down')
        finally:
            self.change('start', node)
        self.eventually(lambda: self.quorum(cluster), 'Controller recovery')
        self.wait_isr({'101', '102', '103'})
        publish('controller-recovered')

    def missing_tls(self):
        # Keep CA trust and hostname verification to isolate missing client identity.
        command = r"""set -euo pipefail
awk '!/^ssl\.(keystore\.|key\.password=)/' /run/kafka-secrets/client.properties > /tmp/no-client-certificate.properties
exec /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server kafka-broker-1:9092 --command-config /tmp/no-client-certificate.properties
"""
        require_tls_rejection(self.probe('admin', 'bash', '-c', command, raw=True, check=False))
        # An authorized request after the denial distinguishes authentication from an outage.
        self.probe('admin', 'topics')

    def invalid_tls(self):
        # Retain the valid client identity, but trust only an unrelated synthetic CA.
        # All generated material lives in this disposable client's bounded tmpfs.
        command = r"""set -euo pipefail
keytool -genkeypair -alias unrelated -keyalg RSA -keysize 2048 -validity 1 -dname CN=synthetic-unrelated-ca -storetype PKCS12 -keystore /tmp/unrelated-key.p12 -storepass synthetic-fixture -keypass synthetic-fixture >/dev/null 2>&1
keytool -exportcert -alias unrelated -keystore /tmp/unrelated-key.p12 -storepass synthetic-fixture -file /tmp/unrelated.crt >/dev/null 2>&1
keytool -importcert -noprompt -alias unrelated -storetype PKCS12 -keystore /tmp/unrelated-trust.p12 -storepass synthetic-fixture -file /tmp/unrelated.crt >/dev/null 2>&1
awk '!/^ssl\.truststore\.(location|password)=/' /run/kafka-secrets/client.properties > /tmp/invalid-ca.properties
printf '%s\n' 'ssl.truststore.location=/tmp/unrelated-trust.p12' 'ssl.truststore.password=synthetic-fixture' >> /tmp/invalid-ca.properties
exec /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server kafka-broker-1:9092 --command-config /tmp/invalid-ca.properties
"""
        require_tls_rejection(self.probe('admin', 'bash', '-c', command, raw=True, check=False))
        self.probe('admin', 'topics')

    def storage_negative(self):
        node = 'kafka-broker-3'
        self.change('stop', node)
        for mode in ('missing', 'cluster', 'node'):
            self.inventory()
            item = json.loads(self.docker('inspect', self.known[node]).stdout)[0]
            require(not item['State']['Running'], 'Storage probe requires a stopped broker')
            result = self.compose('run', '--rm', '--no-deps', '-T', '--name', self.project + '-probe',
                                  node, 'bash', '-s', '--', mode, input=storage_probe_script(), timeout=45)
            require(result.stdout.strip() == 'STARTUP_REJECTED_AND_RESTORED_' + mode,
                    'Storage rejection/restoration was not confirmed')
        # A failed probe leaves this node stopped for guarded fixture cleanup.
        self.change('start', node)
        self.wait_isr({'101', '102', '103'})

    def consume(self, principal, topic, group, expected, uncertain=()):
        result = self.probe(principal, 'consume', topic, group, str(len(expected) + len(uncertain) + 1),
                            '10000', check=False)
        require(result.returncode == 0 or 'TimeoutException' in result.stderr,
                'Consumer failed before bounded end-of-data')
        require('AuthorizationException' not in result.stdout + result.stderr,
                'Unexpected consumer authorization failure')
        actual = Counter(line for line in result.stdout.splitlines() if line)
        require(all(actual[value] == count for value, count in Counter(expected).items()),
                'Acknowledged record missing or duplicated')
        require(set(actual) <= set(expected) | set(uncertain), 'Unexpected fixture record')
        require(all(count == 1 for count in actual.values()), 'Duplicate fixture record')
        return actual

    def prepare(self, credentials=None):
        require(not any(self.resources()), 'Project already exists; refusing adoption or cleanup')
        # Labels alone miss an existing resource that Compose might adopt by name.
        for command in (('ps', '-aq', '--filter', 'name=' + self.project + '-'),
                        ('volume', 'ls', '-q', '--filter', 'name=' + self.project + '_'),
                        ('network', 'ls', '-q', '--filter', 'name=' + self.project + '_')):
            require(not self.docker(*command).stdout.strip(), 'Reserved resource name already exists')
        # /work is shared between the runner and inner daemon; /tmp is not.
        self.secret_parent = Path(tempfile.mkdtemp(prefix=self.project + '-', dir='/work'))
        self.secrets = self.secret_parent / 'credentials'
        (self.secret_parent / 'empty.env').write_text('')
        self.owned = True
        if credentials is None:
            result = subprocess.run(['bash', str(self.root / 'infra/kafka/generate-local-credentials.sh'),
                                     str(self.secrets)], env=self.env, capture_output=True, text=True, timeout=180)
            require(result.returncode == 0, 'Synthetic credential generation failed; diagnostics withheld')
        else:
            require(credentials.is_dir() and not credentials.is_symlink(), 'Unsafe credential source')
            require(all(not path.is_symlink() and (path.is_file() or path.is_dir())
                        for path in credentials.rglob('*')), 'Unsafe credential copy inventory')
            shutil.copytree(credentials, self.secrets)
            for path in (self.secrets, *self.secrets.rglob('*')):
                require(not path.is_symlink(), 'Credential copy contains symbolic link')
                os.chown(path, 1000, 1000)

    def run(self):
        self.stage('prepare')
        self.prepare()
        for principal in ('payment', 'ledger'):
            properties = dict(line.split('=', 1) for line in
                              (self.secrets / principal / 'client.properties').read_text().splitlines()
                              if '=' in line and not line.startswith('#'))
            require(all(properties.get(key) == value for key, value in {
                'enable.idempotence': 'true', 'acks': 'all', 'security.protocol': 'SSL',
                'ssl.endpoint.identification.algorithm': 'https', 'enable.auto.commit': 'false',
                'allow.auto.create.topics': 'false'}.items()), 'Client safety configuration drift')
        self.compose('create', *NODES)
        _, _, _, self.known = self.inventory()
        self.stage('format')
        for node in NODES:
            self.inventory()
            self.compose('run', '--rm', '--no-deps', '-T', '--name', self.project + '-probe',
                         node, 'bash', '/opt/armoney/format.sh')
        self.stage('initial-start')
        self.compose('up', '-d', '--wait', '--wait-timeout', '240', *NODES, timeout=300)
        self.inventory()
        self.stage('topics')
        self.compose('run', '--rm', '--no-deps', '-T', '--name', self.project + '-probe', 'kafka-init')
        self.wait_isr({'101', '102', '103'})
        cluster = self.quorum()
        commands = []
        recovered_uncertain = []

        def publish(label):
            record = str(uuid.uuid4()) + ':' + json.dumps({'synthetic': label}, separators=(',', ':'))
            result = self.probe('payment', 'produce', TOPICS[0], input=record + '\n')
            require('ERROR' not in result.stderr, 'Producer reported a failed send')
            commands.append(record)
            self.consume('ledger', TOPICS[0], 'ledger-transfer-v1', commands, recovered_uncertain)

        self.stage('baseline')
        publish('baseline')
        self.stage('restart')
        self.change('stop', *NODES)
        self.change('start', *NODES)
        self.wait_isr({'101', '102', '103'})
        self.quorum(cluster)
        self.consume('ledger', TOPICS[0], 'ledger-transfer-v1', commands, recovered_uncertain)
        publish('after-restart')
        self.stage('one-broker-failure')
        self.change('stop', 'kafka-broker-1')
        self.wait_isr({'102', '103'})
        self.quorum(cluster)
        publish('one-broker-down')
        self.stage('two-broker-failure')
        self.change('stop', 'kafka-broker-2')
        self.wait_isr({'103'})
        self.quorum(cluster)
        refused = str(uuid.uuid4()) + ':{"synthetic":"insufficient-isr"}'
        rejected(self.probe('payment', 'produce', TOPICS[0], input=refused + '\n', check=False),
                 'NotEnoughReplicas', 'minimum-ISR produce')
        self.stage('broker-recovery')
        self.change('start', 'kafka-broker-1', 'kafka-broker-2')
        self.wait_isr({'101', '102', '103'})
        self.quorum(cluster)
        observed = self.consume('ledger', TOPICS[0], 'ledger-transfer-v1', commands, [refused])
        print('Recovered acknowledged records; unacknowledged probe present=' + str(refused in observed).lower())
        # Keep any uncertain record in subsequent reconciliation, never assume timeout means absence.
        if refused in observed:
            recovered_uncertain.append(refused)
        publish('after-recovery')
        self.stage('controller-failure')
        self.controller_failure(cluster, publish)
        self.stage('missing-client-TLS')
        self.missing_tls()
        self.stage('wrong-CA-TLS')
        self.invalid_tls()
        self.stage('storage-identity')
        self.storage_negative()
        self.quorum(cluster)
        self.consume('ledger', TOPICS[0], 'ledger-transfer-v1', commands, recovered_uncertain)
        publish('after-storage-identity-probes')
        # Exercise the remaining permitted routes as well as the command route.
        self.stage('allowed-ACL')
        for writer, reader, topic, group in (
                ('ledger', 'payment', TOPICS[1], 'payment-ledger-result-v1'),
                ('payment', 'admin', TOPICS[2], 'synthetic-outcomes-verification')):
            record = str(uuid.uuid4()) + ':{"synthetic":"permitted-acl-route"}'
            result = self.probe(writer, 'produce', topic, input=record + '\n')
            require('ERROR' not in result.stderr, 'Producer reported a failed send')
            self.consume(reader, topic, group, [record])
        self.stage('denied-ACL')
        for principal, topic in (('denied', TOPICS[0]), ('ledger', TOPICS[0]), ('payment', TOPICS[1])):
            rejected(self.probe(principal, 'produce', topic, input='synthetic:{"denied":true}\n', check=False),
                     'AuthorizationException', principal + ' unauthorized produce')
        denied_consumer(self.probe('denied', 'consume', TOPICS[0], 'denied-group', '1', '5000', check=False),
                        'denied-principal consume')
        denied_consumer(self.probe('payment', 'consume', TOPICS[0], 'payment-ledger-result-v1', '1', '5000', check=False),
                        'payment cross-topic consume')
        self.consume('ledger', TOPICS[0], 'ledger-transfer-v1', commands, recovered_uncertain)
        self.cold_restore(cluster, commands, recovered_uncertain)
        print('Kafka transport assertions passed; disposable cleanup pending')

    def stopped(self):
        self.inventory()
        for node in NODES:
            item = json.loads(self.docker('inspect', self.known[node]).stdout)[0]
            require(not item['State']['Running'], 'Cold copy requires all six nodes stopped')

    def offsets(self):
        values = {}
        for topic in TOPICS:
            result = self.probe('admin', '/opt/kafka/bin/kafka-get-offsets.sh',
                                '--bootstrap-server', BOOTSTRAP, '--command-config', CONFIG,
                                '--topic', topic, '--time', '-1', raw=True)
            for line in result.stdout.splitlines():
                match = re.fullmatch(re.escape(topic) + r':([012]):(\d+)', line)
                require(match is not None, 'Unrecognized end-offset evidence')
                key = topic, int(match.group(1))
                require(key not in values, 'Duplicate partition offset')
                values[key] = int(match.group(2))
        require(len(values) == 9, 'Incomplete end-offset evidence')
        return values

    def snapshot(self, mode, node, cluster, archive, image, digest=None):
        self.stopped()
        require(mode in ('archive', 'restore') and node in NODES, 'Invalid snapshot operation')
        name = self.project + '-snapshot-' + node
        target = '/source' if mode == 'archive' else '/target'
        mounts = [
            ('bind', str(self.root / 'infra/kafka'), '/opt/armoney', False),
            ('bind', str(archive), '/archive', mode == 'archive'),
            ('volume', self.project + '_' + node + '-data', target, mode == 'restore')]
        command = ['create', '--name', name, '--label', 'armoney.kafka.snapshot.project=' + self.project,
                   '--network', 'none', '--read-only', '--cap-drop', 'ALL', '--security-opt',
                   'no-new-privileges', '--user', '1000:1000', '--tmpfs', '/tmp:rw,size=64m,mode=1777',
                   '--tmpfs', '/etc/kafka/secrets:rw,size=1m,uid=1000,gid=1000,mode=0700',
                   '--tmpfs', '/mnt/shared/config:rw,size=1m,uid=1000,gid=1000,mode=0700',
                   '--tmpfs', '/var/lib/kafka/data:rw,size=1m,uid=1000,gid=1000,mode=0700',
                   '--entrypoint', 'bash']
        for kind, source, destination, writable in mounts:
            command += ['--mount', f'type={kind},source={source},target={destination}' +
                        ('' if writable else ',readonly')]
        command += [image, '/opt/armoney/snapshot.sh', mode, node, cluster]
        if digest is not None:
            command.append(digest)
        cid = self.docker(*command).stdout.strip()
        require(re.fullmatch('[0-9a-f]{64}', cid), 'Invalid snapshot helper identity')
        def validate():
            item = json.loads(self.docker('inspect', cid).stdout)[0]
            config, host = item['Config'], item['HostConfig']
            require(item['Name'] == '/' + name and config['Image'] == image and
                    config['User'] == '1000:1000' and
                    (config.get('Labels') or {}).get('armoney.kafka.snapshot.project') == self.project and
                    not (config.get('Labels') or {}).get('com.docker.compose.project') and
                    config['Entrypoint'] == ['bash'] and config['Cmd'] == command[command.index(image) + 1:],
                    'Snapshot helper identity mismatch')
            require(host['NetworkMode'] == 'none' and host['ReadonlyRootfs'] and
                    not host.get('Privileged') and not host.get('PortBindings') and not host.get('Devices') and
                    host.get('CapDrop') == ['ALL'] and 'no-new-privileges' in host.get('SecurityOpt', []),
                    'Unsafe snapshot helper configuration')
            actual = {(m['Type'], m.get('Name') if m['Type'] == 'volume' else m['Source'],
                       m['Destination'], m['RW']) for m in item['Mounts'] if m['Type'] != 'tmpfs'}
            require(actual == set(mounts) and len(actual) == 3, 'Foreign snapshot helper mount')
        def cleanup_helper():
            validate()
            self.docker('rm', '--force', cid)
        with preserving_cleanup(cleanup_helper):
            validate()
            self.stopped()
            result = self.docker('start', '--attach', cid, timeout=180)
            state = json.loads(self.docker('inspect', cid).stdout)[0]['State']
            require(not state['Running'] and state['ExitCode'] == 0,
                    'Snapshot helper did not complete successfully')
            output = result.stdout.strip()
            require(re.fullmatch('[0-9a-f]{64}', output) if mode == 'archive' else
                    output == 'RESTORE_VERIFIED', 'Snapshot helper confirmation missing')
            return output

    def cold_restore(self, cluster, commands, uncertain):
        self.stage('cold-copy')
        watermark = self.offsets()
        self.change('stop', *NODES)
        archive = self.secret_parent / 'cold-snapshot'
        archive.mkdir(mode=0o700)
        os.chown(archive, 1000, 1000)
        config = json.loads(self.compose('config', '--format', 'json').stdout)
        image = config['services']['kafka-broker-1']['image']
        require(re.fullmatch(r'apache/kafka:4\.3\.1@sha256:[0-9a-f]{64}', image), 'Snapshot image must be pinned')
        digests = {node: self.snapshot('archive', node, cluster, archive, image) for node in NODES}
        restored = Smoke('armoney-kafka-test-' + uuid.uuid4().hex,
                         dict(self.env, DOCKER_HOST=HOST), self.root)
        started = time.monotonic()
        with preserving_cleanup(restored.cleanup):
            restored.stage('cold-restore')
            restored.prepare(self.secrets)
            restored.compose('create', *NODES)
            _, _, _, restored.known = restored.inventory()
            for node in NODES:
                self.stopped()
                restored.snapshot('restore', node, cluster, archive, image, digests[node])
            # Startup must consume restored metadata; never call format or regenerate IDs.
            restored.compose('up', '-d', '--wait', '--wait-timeout', '240', *NODES, timeout=300)
            restored.wait_isr({'101', '102', '103'})
            restored.quorum(cluster)
            require(restored.offsets() == watermark, 'Cold restore end-offset mismatch')
            restored.consume('ledger', TOPICS[0], 'ledger-transfer-v1', commands, uncertain)
            print(f'Cold restore verified: nine partition offsets and acknowledged IDs; elapsed={time.monotonic() - started:.3f}s')

    def cleanup(self):
        if not self.owned:
            return
        self.stage('cleanup')
        containers, volumes, networks, _ = self.inventory(complete=False)
        # Validate the full inventory before the first destructive command.
        if containers:
            self.docker('rm', '--force', *containers, timeout=120)
        if volumes:
            self.docker('volume', 'rm', *volumes)
        if networks:
            self.docker('network', 'rm', *networks)
        require(not any(self.resources()), 'Disposable resource cleanup incomplete')
        if self.secret_parent:
            parent = self.secret_parent.resolve()
            require(parent.parent == Path('/work') and parent.name.startswith(self.project + '-'),
                    'Credential cleanup path rejected')
            shutil.rmtree(parent)
        self.owned = False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', required=True)
    args = parser.parse_args()
    smoke = Smoke(args.project)
    with preserving_cleanup(smoke.cleanup):
        smoke.run()
    print('PASS: isolated Kafka publish/consume, retained restart, one-broker survival, '
          'two-broker ISR refusal, controller re-election/recovery, missing-client and wrong-CA TLS rejection, '
          'storage identity rejection/recovery, principal ACL isolation, cold restore and cleanup')


if __name__ == '__main__':
    try:
        main()
    except (Failed, subprocess.TimeoutExpired) as error:
        # Never print command arrays, stderr, config files or credential values.
        print('Kafka acceptance failed: ' + (str(error) if isinstance(error, Failed) else 'bounded command timeout'),
              file=sys.stderr)
        sys.exit(1)
    except Exception:
        print('Kafka acceptance failed; sensitive diagnostics withheld', file=sys.stderr)
        sys.exit(1)
