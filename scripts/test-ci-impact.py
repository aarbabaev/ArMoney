#!/usr/bin/env python3
"""Independent affected-CI acceptance using classifications and real synthetic Git.

No Gradle, Docker, network, production data, or workflow execution is involved.
"""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).with_name('ci-impact.py')
FLAGS = {'dispatcher', 'build', 'android', 'web', 'assistant', 'kafka', 'sms',
         'baseline', 'bank_smoke', 'assistant_smoke', 'sharding', 'replication', 'sso'}
MODULES = {'platform-runtime', 'app-gateway', 'auth-service', 'user-service',
           'wallet-service', 'payment-service', 'ledger-service', 'sms-sandbox'}
spec = importlib.util.spec_from_file_location('independent_ci_impact', SOURCE)
impact = importlib.util.module_from_spec(spec)
spec.loader.exec_module(impact)


def modules(result):
    value = result['modules']
    return json.loads(value) if isinstance(value, str) else value


class Classifications(unittest.TestCase):
    def checked(self, paths, full=False):
        result = impact.classify(paths, full=full)
        self.assertTrue(FLAGS <= result.keys())
        self.assertTrue(all(result[flag] in ('true', 'false') for flag in FLAGS))
        selected = modules(result)
        self.assertIsInstance(selected, list)
        self.assertEqual(len(selected), len(set(selected)))
        self.assertTrue(set(selected) <= MODULES, 'Only fixed Gradle modules may leave selector')
        return result

    def full(self, result):
        self.assertTrue(all(result[flag] == 'true' for flag in FLAGS), result)
        self.assertEqual(set(modules(result)), MODULES)

    def test_unknown_paths_fail_closed_to_full(self):
        for path in ('new-service/src/main/java/Handler.java', 'unmapped-control.sh',
                     '../auth-service/build.gradle', '/etc/passwd', '$(touch injected)/x'):
            with self.subTest(path=path):
                self.full(self.checked([path]))

    def test_shared_build_workflow_and_selector_changes_force_full(self):
        for path in ('settings.gradle', 'build.gradle', 'gradlew', 'gradle/wrapper/gradle-wrapper.properties',
                     '.github/workflows/ci.yml', 'scripts/ci-impact.py'):
            with self.subTest(path=path):
                self.full(self.checked([path]))

    def test_docs_only_avoids_heavy_jobs(self):
        result = self.checked(['README.md', 'ARCHITECTURE.md', 'docs/adr/0011-aed-only.md'])
        self.assertTrue(all(result[flag] == 'false' for flag in FLAGS), result)
        self.assertEqual(modules(result), [])

    def test_explicit_full_override_including_empty_diff(self):
        self.full(self.checked([], full=True))
        self.full(self.checked(['README.md'], full=True))

    def test_service_test_change_runs_service_checks(self):
        result = self.checked(['wallet-service/src/test/groovy/ProvisioningIntegrationSpec.groovy'])
        self.assertEqual(result['build'], 'true')
        self.assertIn('wallet-service', modules(result))
        self.assertEqual(result['android'], 'false')
        self.assertEqual(result['web'], 'false')

    def test_production_contract_and_migration_changes_include_runtime_checks(self):
        for path in ('payment-service/src/main/java/Payment.java',
                     'payment-service/src/main/resources/openapi.yaml',
                     'payment-service/src/main/resources/db/migration/V99__synthetic.sql'):
            with self.subTest(path=path):
                result = self.checked([path])
                self.assertEqual(result['build'], 'true')
                self.assertIn('payment-service', modules(result))
                self.assertEqual(result['bank_smoke'], 'true')

    def test_platform_runtime_expands_java_reverse_dependencies(self):
        result = self.checked(['platform-runtime/src/main/java/Database.java'])
        self.assertEqual(result['build'], 'true')
        self.assertEqual(set(modules(result)), MODULES)
        self.assertEqual(result['bank_smoke'], 'true')

    def test_payment_local_build_runs_only_payment_checks_and_linked_acceptance(self):
        result = self.checked(['payment-service/build.gradle'])
        self.assertEqual(modules(result), ['payment-service'])
        self.assertEqual({flag for flag in FLAGS if result[flag] == 'true'},
                         {'build', 'bank_smoke'})

    def test_platform_local_build_expands_java_closure_and_linked_acceptance(self):
        result = self.checked(['platform-runtime/build.gradle'])
        self.assertEqual(set(modules(result)), MODULES)
        self.assertEqual({flag for flag in FLAGS if result[flag] == 'true'},
                         {'build', 'bank_smoke', 'assistant_smoke', 'sharding',
                          'replication', 'sso'})

    def test_native_web_assistant_scopes_are_separate(self):
        for path, flag in (('android/app/src/main/App.kt','android'), ('web/src/App.tsx','web'),
                           ('ai-assistant-service/src/Program.cs','assistant')):
            with self.subTest(path=path):
                result = self.checked([path])
                self.assertEqual(result[flag], 'true')
                if flag != 'android':
                    self.assertEqual(result['android'], 'false')
                if flag != 'assistant':
                    self.assertEqual(result['assistant'], 'false')

    def test_mixed_changes_union_and_deduplicate(self):
        paths = ['README.md','android/app/src/main/App.kt',
                 'wallet-service/src/test/groovy/WalletSpec.groovy',
                 'wallet-service/src/test/groovy/WalletSpec.groovy']
        first = self.checked(paths)
        second = self.checked(list(reversed(paths)))
        self.assertEqual(first, second)
        self.assertEqual(first['android'], 'true')
        self.assertIn('wallet-service', modules(first))

    def test_malformed_inputs_fail_full_and_path_text_never_becomes_tasks(self):
        for paths in (None, 'android/file.kt', [None], [''], ['a//b'], ['a/./b']):
            with self.subTest(paths=paths):
                self.full(self.checked(paths))
        result = self.checked(['wallet-service/src/test/$(touch synthetic).groovy'])
        self.assertEqual(modules(result), ['wallet-service'])

    def test_nul_delimited_paths_preserve_newlines_and_decode_failure_fails_full(self):
        head, base = 'a'*40, 'b'*40
        for raw, expected in ((b'android/synthetic\nfilename.kt\0', ['android/synthetic\nfilename.kt']),
                              (b'android/invalid-\xff.kt\0', None)):
            with self.subTest(raw=raw):
                with patch.object(impact, 'git', side_effect=[head.encode(), head.encode(), base.encode(), raw]):
                    self.assertEqual(impact.changed_paths(base, head), expected)


class GitRanges(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='armoney-impact-git-')
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.environment = dict(os.environ, GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull,
                                GIT_AUTHOR_NAME='Synthetic QA', GIT_AUTHOR_EMAIL='qa@example.test',
                                GIT_COMMITTER_NAME='Synthetic QA', GIT_COMMITTER_EMAIL='qa@example.test')
        self.git('init', '-q', '-b', 'main')
        self.write('README.md', 'base\n')
        self.base = self.commit()

    def git(self, *args):
        result = subprocess.run(['git', *args], cwd=self.repo, env=self.environment,
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def write(self, path, data='synthetic\n'):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(data)

    def commit(self):
        self.git('add', '-A')
        self.git('commit', '-qm', 'Synthetic selector acceptance')
        return self.git('rev-parse', 'HEAD')

    def select(self, base, head, pr=False):
        self.git('checkout', '-q', head)
        output = self.repo / 'selector-output'
        output.unlink(missing_ok=True)
        command = [sys.executable, '-B', str(SOURCE), '--base', base, '--head', head,
                   '--output', str(output)]
        if pr:
            command.append('--pull-request')
        result = subprocess.run(command, cwd=self.repo, env=self.environment,
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stderr)
        values = dict(line.split('=', 1) for line in output.read_text().splitlines())
        self.assertTrue(FLAGS <= values.keys())
        return values

    def test_push_uses_entire_before_after_range(self):
        self.write('android/app/src/main/A.kt')
        self.commit()
        self.write('web/src/App.tsx')
        head = self.commit()
        result = self.select(self.base, head)
        self.assertEqual(result['android'], 'true')
        self.assertEqual(result['web'], 'true')

    def test_pr_uses_merge_base_and_excludes_base_only_changes(self):
        self.git('checkout', '-qb', 'feature')
        self.write('android/app/src/main/A.kt')
        head = self.commit()
        self.git('checkout', '-q', 'main')
        self.write('web/src/App.tsx')
        advanced_base = self.commit()
        result = self.select(advanced_base, head, pr=True)
        self.assertEqual(result['android'], 'true')
        self.assertEqual(result['web'], 'false', 'Base-only changes must not enter PR selection')

    def test_deletion_selects_deleted_module(self):
        self.write('wallet-service/src/test/groovy/OldSpec.groovy')
        before = self.commit()
        (self.repo/'wallet-service/src/test/groovy/OldSpec.groovy').unlink()
        after = self.commit()
        result = self.select(before, after)
        self.assertIn('wallet-service', modules(result))

    def test_cross_module_rename_selects_old_and_new_owners(self):
        old = 'wallet-service/src/test/groovy/OldSpec.groovy'
        new = 'payment-service/src/test/groovy/NewSpec.groovy'
        self.write(old)
        before = self.commit()
        (self.repo/new).parent.mkdir(parents=True)
        self.git('mv', old, new)
        after = self.commit()
        self.assertTrue({'wallet-service','payment-service'} <= set(modules(self.select(before,after))))

    def test_space_in_filename_is_not_split_into_unknown_path(self):
        self.write('android/app/src/main/Synthetic screen.kt')
        head = self.commit()
        result = self.select(self.base,head)
        self.assertEqual(result['android'],'true')
        self.assertEqual(result['build'],'false')

    def test_missing_and_zero_base_select_full(self):
        for base in ('f'*40, '0'*40):
            with self.subTest(base=base):
                result = self.select(base,self.base)
                self.assertTrue(all(result[flag]=='true' for flag in FLAGS),result)
                self.assertEqual(set(modules(result)),MODULES)

    def test_unrelated_history_merge_base_failure_selects_full(self):
        self.git('checkout','--orphan','unrelated')
        self.git('rm','-rf','.')
        self.write('docs/synthetic.md')
        head = self.commit()
        result = self.select(self.base,head,pr=True)
        self.assertTrue(all(result[flag]=='true' for flag in FLAGS),result)

    def test_unverified_or_malformed_head_cannot_emit_selection(self):
        self.write('android/app/src/main/A.kt')
        self.commit()
        output = self.repo/'withheld-output'
        for head in (self.base, '0'*40, 'f'*40, 'HEAD;touch injected'):
            with self.subTest(head=head):
                result = subprocess.run([sys.executable, '-B', str(SOURCE), '--base', self.base,
                                         '--head', head, '--output', str(output)],
                                        cwd=self.repo, env=self.environment, capture_output=True,
                                        text=True, timeout=20)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(output.exists())
                self.assertFalse((self.repo/'injected').exists())


if __name__ == '__main__':
    if shutil.which('git') is None:
        raise SystemExit('BLOCKED: Git required; no tests skipped')
    unittest.main(verbosity=2)
