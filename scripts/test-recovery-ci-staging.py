#!/usr/bin/env python3
"""Black-box staging safety checks; no Docker daemon or financial success fixture.

Run with Python -B inside the approved isolated Linux runtime. Fake executables
record subprocess boundaries; the acceptance sentinel always fails deliberately.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
ENDPOINT = 'unix:///run/recovery-docker/docker.sock'
REVISION = 'a' * 40
SERVICES = ('app-gateway', 'auth-service', 'user-service', 'wallet-service',
            'payment-service', 'ledger-service')

STUB = r'''
import json, os, sys
kind, *args = sys.argv[1:]
with open(os.environ['QA_CALLS'], 'a') as stream:
    stream.write(json.dumps([kind, args])+'\n')
if kind == 'git':
    if 'rev-parse' in args: print('a'*40)
    elif 'status' in args: print(' M dirty' if os.environ.get('QA_DIRTY') else '', end='')
    else: sys.exit(90)
elif kind == 'docker':
    if args[:2] != ['--host', 'unix:///run/recovery-docker/docker.sock']: sys.exit(91)
    args = args[2:]
    if args[:1] == ['info']:
        print(json.dumps({'ID':'qa-daemon', 'Labels':[] if os.environ.get('QA_BAD_LABEL') else
              ['io.armoney.disposable-recovery=true'], 'Containers':int(os.environ.get('QA_CONTAINERS','0'))}))
    elif args[:2] == ['volume','ls']: print('foreign-volume' if os.environ.get('QA_VOLUME') else '')
    elif args[:2] == ['network','ls']:
        print('bridge\nhost\nnone' + ('\nforeign-network' if os.environ.get('QA_NETWORK') else ''))
    elif args[:1] == ['build']:
        if os.environ.get('QA_BUILD_FAIL'): sys.exit(92)
    elif args[:2] == ['image','inspect']:
        image_id='sha256:'+'b'*64
        platform='amd64'
        reference=args[-1]
        if reference.startswith('sha256:'):
            if os.environ.get('QA_BAD_DEPENDENCY_ID'): image_id='sha256:'+'d'*64
            if os.environ.get('QA_BAD_DEPENDENCY_PLATFORM'): platform='arm64'
        if reference==os.environ.get('QA_CHANGED_BASE_TAG'): image_id='sha256:'+'e'*64
        label='c'*40 if os.environ.get('QA_BAD_REVISION') else 'a'*40
        if '--format' in args:
            fmt=args[args.index('--format')+1]
            print(label if 'revision' in fmt else image_id)
        else: print(json.dumps([{'Id':image_id,'Os':'linux','Architecture':platform,'Config':{'Labels':{'org.opencontainers.image.revision':label}}}]))
    else: sys.exit(93)  # No pulls, cleanup, container creation, or implicit fallback.
'''

SENTINEL = r'''
import json, os, pathlib, sys
args=sys.argv[1:]
manifest=pathlib.Path(args[args.index('--bank-images')+1])
with open(os.environ['QA_CALLS'],'a') as stream:
    stream.write(json.dumps(['acceptance',args,json.loads(manifest.read_text()),str(manifest)])+'\n')
sys.exit(73)  # Never simulate successful financial/application acceptance.
'''

PROVENANCE_STUB = r'''
import importlib.util, os
from pathlib import Path
spec=importlib.util.spec_from_file_location('real_recovery_stage',Path(__file__).with_name('verified-stage.py'))
real=importlib.util.module_from_spec(spec)
spec.loader.exec_module(real)
GuardError=real.GuardError
def load_dependencies(path, revision):
    assert str(path)=='/work/recovery-dependencies.json'
    # Only redirect the fixed mount path into this test's private directory;
    # validation is the actual production parser, not an always-valid stub.
    return real.load_dependencies(Path(os.environ['QA_MANIFEST']), revision)
'''


class RecoveryStaging(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='armoney-staging-qa-')
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.repo = self.directory / 'repo'
        scripts = self.repo / 'scripts'
        scripts.mkdir(parents=True)
        for name in ('run-application-recovery-ci.sh', 'recovery_fixture_runtime.py'):
            shutil.copyfile(ROOT / 'scripts' / name, scripts / name)
        shutil.copyfile(ROOT / 'scripts/stage-application-recovery-ci.py', scripts / 'verified-stage.py')
        (scripts / 'backup-application-recovery-smoke.py').write_text(SENTINEL)
        # Redirect only the fixed mount path; use the actual manifest parser.
        (scripts / 'stage-application-recovery-ci.py').write_text(PROVENANCE_STUB)
        self.manifest = self.directory / 'dependencies.json'
        self.valid_manifest = {'revision': REVISION, 'platform': 'linux/amd64', 'images':
            {ref: {'outer_id': 'sha256:' + 'c' * 64, 'config_id': 'sha256:' + 'b' * 64} for ref in
             ('eclipse-temurin:21-jdk', 'eclipse-temurin:21-jre', 'postgres:17.6-alpine',
              'quay.io/keycloak/keycloak:26.7.4', 'caddy:2.11.4-alpine', 'python:3.13.7-alpine',
              'pgvector/pgvector:pg17@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d')}}
        self.manifest.write_text(json.dumps(self.valid_manifest))
        self.manifest.chmod(0o600)
        self.bin = self.directory / 'bin'
        self.bin.mkdir()
        self.calls = self.directory / 'calls.jsonl'
        helper = self.directory / 'stub.py'
        helper.write_text(STUB)
        import shlex
        for name in ('git', 'docker'):
            executable = self.bin / name
            executable.write_text('#!/bin/sh\nexec ' + shlex.quote(sys.executable) +
                                  ' -B ' + shlex.quote(str(helper)) + ' ' + name + ' "$@"\n')
            executable.chmod(0o700)
        python = self.bin / 'python3'
        python.write_text('#!/bin/sh\nexec ' + shlex.quote(sys.executable) + ' -B "$@"\n')
        python.chmod(0o700)
        self.env = dict(PATH=str(self.bin)+':/usr/bin:/bin', HOME=str(self.directory),
                        TMPDIR=str(self.directory), QA_CALLS=str(self.calls),
                        QA_MANIFEST=str(self.manifest),
                        RECOVERY_DAEMON=ENDPOINT, ARMONEY_DISPOSABLE_CI='true',
                        GITHUB_WORKSPACE='/work/synthetic/recovery')

    def run_wrapper(self, changes=None):
        env = dict(self.env)
        for key, value in (changes or {}).items():
            if value is None:
                env.pop(key, None)
            else:
                env[key] = value
        return subprocess.run(['bash', str(self.repo/'scripts/run-application-recovery-ci.sh')],
                              cwd=self.repo, env=env, capture_output=True, text=True, timeout=30)

    def records(self):
        return [json.loads(line) for line in self.calls.read_text().splitlines()] if self.calls.exists() else []

    def assert_no_build_acceptance_or_cleanup(self, result):
        self.assertNotEqual(result.returncode, 0)
        for kind, args, *rest in self.records():
            self.assertNotEqual(kind, 'acceptance')
            if kind == 'docker':
                # Wrapper rejection leaves an empty owned daemon for the outer
                # stage's cleanup. It must not create, remove or discover peers.
                self.assertIn(args[2:3], (['info'], ['volume'], ['network'], ['image']))
                self.assertNotIn('build', args)
                if args[2] in ('volume', 'network'):
                    self.assertEqual(args[3], 'ls')
                if args[2] == 'image':
                    self.assertEqual(args[3], 'inspect')

    def test_missing_and_malformed_provenance_fail_before_any_build_or_acceptance(self):
        invalids = [None, b'not-json', b'{"revision":"a","revision":"b"}',
                    json.dumps(dict(self.valid_manifest, revision='d' * 40)).encode(),
                    json.dumps(dict(self.valid_manifest, images={})).encode(),
                    json.dumps(dict(self.valid_manifest, platform='linux/arm64')).encode(),
                    json.dumps(dict(self.valid_manifest, extra='forbidden')).encode()]
        for content in invalids:
            with self.subTest(content=content):
                self.calls.unlink(missing_ok=True)
                self.manifest.unlink(missing_ok=True)
                if content is not None:
                    self.manifest.write_bytes(content)
                    self.manifest.chmod(0o600)
                self.assert_no_build_acceptance_or_cleanup(self.run_wrapper())

    def test_dependency_config_id_and_platform_mismatch_fail_before_build(self):
        for changes in ({'QA_BAD_DEPENDENCY_ID': '1'}, {'QA_BAD_DEPENDENCY_PLATFORM': '1'}):
            with self.subTest(changes=changes):
                self.calls.unlink(missing_ok=True)
                self.assert_no_build_acceptance_or_cleanup(self.run_wrapper(changes))
                self.assertTrue(any(row[0] == 'docker' and row[1][2:4] == ['image', 'inspect'] for row in self.records()))

    def test_changed_jdk_or_jre_tag_fails_before_first_build(self):
        for base in ('eclipse-temurin:21-jdk', 'eclipse-temurin:21-jre'):
            with self.subTest(base=base):
                self.calls.unlink(missing_ok=True)
                self.assert_no_build_acceptance_or_cleanup(self.run_wrapper({'QA_CHANGED_BASE_TAG': base}))
                self.assertTrue(any(row[0] == 'docker' and row[1][-1] == base for row in self.records()))

    def test_invalid_endpoint_or_context_never_reaches_docker(self):
        for changes in ({'RECOVERY_DAEMON':None}, {'RECOVERY_DAEMON':'unix:///var/run/docker.sock'},
                        {'RECOVERY_DAEMON':'tcp://localhost:2375'}, {'ARMONEY_DISPOSABLE_CI':None}):
            with self.subTest(changes=changes):
                self.calls.unlink(missing_ok=True)
                self.assertNotEqual(self.run_wrapper(changes).returncode, 0)
                self.assertFalse(any(row[0] in ('docker','acceptance') for row in self.records()))

    def test_unlabelled_or_nonempty_daemon_never_builds_or_cleans(self):
        for changes in ({'QA_BAD_LABEL':'1'}, {'QA_CONTAINERS':'1'},
                        {'QA_VOLUME':'1'}, {'QA_NETWORK':'1'}):
            with self.subTest(changes=changes):
                self.calls.unlink(missing_ok=True)
                self.assertNotEqual(self.run_wrapper(changes).returncode, 0)
                for kind, args, *rest in self.records():
                    self.assertNotEqual(kind, 'acceptance')
                    if kind == 'docker':
                        self.assertIn(args[2:3], (['info'], ['volume'], ['network']))
                        if args[2] in ('volume','network'): self.assertEqual(args[3], 'ls')

    def test_dirty_source_or_failed_build_never_runs_acceptance(self):
        for changes in ({'QA_DIRTY':'1'}, {'QA_BUILD_FAIL':'1'}, {'QA_BAD_REVISION':'1'}):
            with self.subTest(changes=changes):
                self.calls.unlink(missing_ok=True)
                self.assertNotEqual(self.run_wrapper(changes).returncode, 0)
                self.assertFalse(any(row[0]=='acceptance' for row in self.records()))

    def test_staging_passes_exact_immutable_manifest_and_propagates_failure(self):
        result = self.run_wrapper({'DOCKER_HOST':'tcp://foreign:2375','DOCKER_CONTEXT':'foreign'})
        self.assertNotEqual(result.returncode, 0, 'Acceptance sentinel failure must not become success')
        records=self.records()
        acceptance=[row for row in records if row[0]=='acceptance']
        self.assertEqual(len(acceptance), 1, result.stderr)
        _, args, manifest, path=acceptance[0]
        self.assertIn('--apply',args)
        self.assertEqual(args[args.index('--daemon')+1],ENDPOINT)
        self.assertEqual(args[args.index('--dependency-images')+1], '/work/recovery-dependencies.json')
        self.assertEqual(set(manifest), {'revision','images'})
        self.assertEqual(manifest['revision'], REVISION)
        self.assertEqual(set(manifest['images']),set(SERVICES))
        self.assertTrue(all(value=='sha256:'+'b'*64 for value in manifest['images'].values()))
        self.assertFalse(Path(path).is_relative_to(self.repo))
        builds=[row[1][2:] for row in records if row[0]=='docker' and row[1][2:3]==['build']]
        self.assertEqual(len(builds),6)
        self.assertEqual({args[args.index('--build-arg')+1] for args in builds},
                         {'SERVICE='+service for service in SERVICES})
        for args in builds:
            self.assertIn('org.opencontainers.image.revision='+REVISION,args)
            self.assertIn('--pull=false',args)


class RecoveryCompose(unittest.TestCase):
    def setUp(self):
        # Explicit read dependency also permits a three-script temporary copy:
        # the Compose source is inspected, never passed to a Docker executable.
        source = Path(os.environ.get('ARMONEY_TEST_RECOVERY_COMPOSE',
                                     str(ROOT/'infra/local-ci/compose.recovery.yaml')))
        self.config = json.loads(source.read_text(encoding='utf-8'))

    def test_dedicated_daemon_endpoint_label_and_no_host_exposure(self):
        self.assertEqual(set(self.config['services']),{'daemon','runner'})
        daemon=self.config['services']['daemon']
        self.assertEqual(daemon['image'],'docker:29.8.1-dind')
        self.assertEqual(daemon['command'][0],'dockerd')
        listeners=[arg for arg in daemon['command'] if arg.startswith('--host=')]
        self.assertEqual(listeners,['--host='+ENDPOINT])
        self.assertIn('--label=io.armoney.disposable-recovery=true',daemon['command'])
        self.assertTrue(daemon['privileged'], 'Only the explicitly dedicated DinD daemon needs privilege')
        self.assertEqual(daemon['healthcheck']['test'],['CMD','docker','--host='+ENDPOINT,'info'])
        runner=self.config['services']['runner']
        self.assertEqual(runner['environment']['RECOVERY_DAEMON'],ENDPOINT)
        self.assertEqual(runner['environment']['ARMONEY_DISPOSABLE_CI'],'true')
        self.assertEqual(runner['network_mode'],'service:daemon')
        self.assertFalse(runner.get('privileged'))
        for service in self.config['services'].values():
            self.assertEqual(service['restart'],'no')
            self.assertEqual(service['pull_policy'],'never')
            self.assertFalse(service.get('ports'))
            self.assertFalse(service.get('devices'))
            self.assertFalse(service.get('cap_add'))
            self.assertFalse(service.get('secrets'))
            self.assertNotEqual(service.get('network_mode'),'host')
            self.assertNotEqual(service.get('pid'),'host')
            self.assertFalse(service.get('build'))

    def test_only_project_scoped_named_volumes_no_host_or_bank_mounts(self):
        self.assertTrue(self.config['name'].startswith('${RECOVERY_PROJECT:?'))
        declarations=self.config['volumes']
        self.assertEqual(set(declarations),{'recovery-data','recovery-socket','recovery-workspace'})
        self.assertTrue(all(value=={} for value in declarations.values()),
                        'External/name/driver-option volumes can escape project isolation')
        expected={'daemon':{'recovery-data:/var/lib/docker','recovery-socket:/run/recovery-docker'},
                  'runner':{'recovery-socket:/run/recovery-docker','recovery-workspace:/work'}}
        for name,service in self.config['services'].items():
            self.assertEqual(set(service['volumes']),expected[name])
            for mount in service['volumes']:
                source,destination=mount.split(':')
                self.assertIn(source,declarations)
                self.assertTrue(destination.startswith('/'))
        self.assertFalse(self.config.get('secrets'))


if __name__ == '__main__':
    if os.name != 'posix' or shutil.which('bash') is None:
        raise SystemExit('BLOCKED: approved isolated Linux runtime with Bash required; no tests skipped')
    unittest.main(verbosity=2)
