"""Portable source invariants for disposable Gradle-based fixture mounts.

No Docker invocation: runtime mount/cleanup proof remains an isolated acceptance gate.
"""
import ast
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ('user-service', 'sms-sandbox', 'auth-service', 'auth-code-intent-fixture')
EXPECTED = ['/tmp:rw,nosuid,nodev,mode=1777',
            '/home/gradle/.gradle:rw,nosuid,nodev,uid=1000,gid=1000,mode=0700,size=16m']


def service_block(source, name):
    match = re.search(r'^  ' + re.escape(name) + r':\n(.*?)(?=^  [a-z][a-z0-9-]*:|^networks:|\Z)', source, re.M | re.S)
    if match is None:
        raise ValueError('missing fixture service')
    return match.group(1)


def verify_mounts(source):
    for name in SERVICES:
        block = service_block(source, name)
        if '    <<: *process\n' not in block:
            raise ValueError('missing security inheritance')
        mounts = re.findall(r'^    tmpfs: (.+)$', block, re.M)
        if len(mounts) != 1 or ast.literal_eval(mounts[0]) != EXPECTED:
            raise ValueError('unbounded or missing disposable mounts')
        if re.search(r'^    (volumes|privileged|cap_add|read_only|cap_drop|security_opt):', block, re.M):
            raise ValueError('unexpected security override')


class CodeIntentComposeTests(unittest.TestCase):
    def setUp(self):
        self.source = (ROOT / 'compose.sso-code-intent.yaml').read_text()

    def test_four_gradle_final_services_shadow_cache_preserving_tmp_and_security(self):
        verify_mounts(self.source)
        dockerfile = (ROOT / 'auth-service/Dockerfile.code-intent-fixture').read_text()
        for target in ('fixture-user', 'fixture-sms', 'fixture-authority'):
            self.assertRegex(dockerfile, r'FROM gradle:8\.14\.3-jdk21@sha256:[0-9a-f]{64} AS ' + target + r'\n')
        self.assertIn('  read_only: true\n  cap_drop: [ALL]\n  security_opt: [no-new-privileges:true]\n', self.source)

    def test_each_service_rejects_missing_shadow_or_missing_tmp(self):
        for name in SERVICES:
            block = service_block(self.source, name)
            for replacement in (repr(EXPECTED[:1]), repr(EXPECTED[1:])):
                changed = re.sub(r'^    tmpfs: .+$', '    tmpfs: ' + replacement, block, flags=re.M)
                with self.subTest(service=name, replacement=replacement), self.assertRaises(ValueError):
                    verify_mounts(self.source.replace(block, changed, 1))

    def test_rejects_unbounded_wrong_owner_or_weakened_cache_flags(self):
        for bad in ('size=0', 'uid=0', 'gid=0', 'mode=0777', 'suid', 'dev'):
            original = {'size=0': 'size=16m', 'uid=0': 'uid=1000', 'gid=0': 'gid=1000',
                        'mode=0777': 'mode=0700', 'suid': 'nosuid', 'dev': 'nodev'}[bad]
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                block = service_block(self.source, 'sms-sandbox')
                verify_mounts(self.source.replace(block, block.replace(original, bad, 1), 1))

    def test_rejects_volume_or_security_override(self):
        for override in ('volumes: []', 'privileged: true', 'read_only: false', 'cap_add: [ALL]'):
            changed = self.source.replace('  sms-sandbox:\n', '  sms-sandbox:\n    ' + override + '\n', 1)
            with self.subTest(override=override), self.assertRaises(ValueError):
                verify_mounts(changed)


if __name__ == '__main__':
    unittest.main()
