"""Offline artifact rejection regressions; no database success is simulated."""
import importlib.util
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('restore', Path(__file__).with_name('restore-isolated-backup.py'))
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)


class PreflightTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.manifest = {'format': 1, 'project': r.b.PROJECT, 'status': 'database_restore_verified',
                         'user_shard_coverage': {'included': [], 'configured': [], 'directory': ['primary']},
                         'databases': []}
        for service, (database, user) in r.b.DBS.items():
            archive = self.directory / (service + '.dump')
            archive.write_bytes(b'synthetic artifact, not a PostgreSQL dump')
            self.manifest['databases'].append({'service': service, 'database': database, 'user': user,
                'archive': archive.name, 'bytes': archive.stat().st_size, 'sha256': r.b.checksum(archive),
                'image_id': 'sha256:' + 'a' * 64, 'expected_fixture_count': 200,
                'relations': {'tables': 1, 'indexes': 0, 'sequences': 0},
                'metrics': {'table_rows': {'public.synthetic': 1},
                            'table_fingerprints': {'public.synthetic': 'b' * 32}}})

    def check(self):
        (self.directory / 'manifest.json').write_text(json.dumps(self.manifest), encoding='utf-8')
        with patch.object(r.b, 'docker') as docker:
            try:
                return r.preflight(self.directory)
            finally:
                docker.assert_not_called()

    def test_valid_inventory_is_offline_only(self):
        self.assertEqual(len(self.check()), 6)

    def test_optional_assistant_coverage_and_archive_are_required_together(self):
        record = copy.deepcopy(self.manifest['databases'][0])
        record.update(service='assistant-db', database='assistant', user='assistant_operator', archive='assistant-db.dump')
        (self.directory / record['archive']).write_bytes(b'synthetic artifact, not a PostgreSQL dump')
        self.manifest['databases'].append(record)
        with self.assertRaises(r.b.Blocked):
            self.check()
        self.manifest['optional_database_coverage'] = ['assistant-db']
        self.assertEqual(7, len(self.check()))
        self.manifest['databases'].pop()
        with self.assertRaises(r.b.Blocked):
            self.check()

    def test_wrong_target_and_path_rejected(self):
        record = self.manifest['databases'][0]
        for field, value in [('database', 'production'), ('archive', '../other.dump'),
                             ('image_id', 'postgres:latest')]:
            original = record[field]
            record[field] = value
            with self.assertRaises(r.b.Blocked):
                self.check()
            record[field] = original

    def test_missing_or_invalid_continuity_evidence_rejected(self):
        record = self.manifest['databases'][0]
        original = record['metrics']
        for metrics in [{}, {'table_rows': {}, 'table_fingerprints': {}},
                        {'table_rows': {'public.synthetic': 1}, 'table_fingerprints': {}},
                        {'table_rows': {'public.synthetic': True},
                         'table_fingerprints': {'public.synthetic': 'b' * 32}},
                        {'table_rows': {'public.synthetic': 1},
                         'table_fingerprints': {'public.synthetic': 'invalid'}}]:
            record['metrics'] = metrics
            with self.assertRaises(r.b.Blocked):
                self.check()
        record['metrics'] = original

    def test_contradictory_relation_inventory_rejected_offline(self):
        record = self.manifest['databases'][0]
        for count in (0, 2):
            with self.subTest(count=count):
                record['relations']['tables'] = count
                with self.assertRaises(r.b.Blocked):
                    self.check()

    def test_format_requires_integer_version(self):
        for version in (True, 1.0, '1', None):
            with self.subTest(version=version):
                self.manifest['format'] = version
                with self.assertRaises(r.b.Blocked):
                    self.check()

    def test_malformed_metadata_rejected_offline(self):
        original = self.manifest
        for manifest in [[], dict(original, databases=[None]),
                         dict(original, user_shard_coverage=[]),
                         dict(original, user_shard_coverage={'included': [None]})]:
            self.manifest = manifest
            with self.assertRaises(r.b.Blocked):
                self.check()
        self.manifest = original

    def test_corrupt_and_missing_artifacts_rejected(self):
        archive = self.directory / self.manifest['databases'][0]['archive']
        archive.write_bytes(b'x' * archive.stat().st_size)
        with self.assertRaises(r.b.Blocked):
            self.check()
        archive.unlink()
        with self.assertRaises(r.b.Blocked):
            self.check()

    def test_missing_core_duplicate_and_shard_rejected(self):
        original = self.manifest['databases'][:]
        for records in [original[1:], original + [original[0]]]:
            self.manifest['databases'] = records
            with self.assertRaises(r.b.Blocked):
                self.check()
        self.manifest['databases'] = original
        self.manifest['user_shard_coverage']['configured'] = ['s2']
        with self.assertRaises(r.b.Blocked):
            self.check()

    def test_late_inventory_corruption_rejects_apply_before_any_daemon_access(self):
        record = self.manifest['databases'][-1]
        (self.directory / record['archive']).write_bytes(b'x' * record['bytes'])
        (self.directory / 'manifest.json').write_text(json.dumps(self.manifest), encoding='utf-8')
        with (patch('sys.argv', ['restore', '--directory', str(self.directory), '--apply']),
              patch.object(r.b, 'local_daemon', side_effect=AssertionError('Daemon accessed')),
              patch.object(r.b, 'restore_verify', side_effect=AssertionError('Restore attempted'))):
            with self.assertRaises(r.b.Blocked):
                r.main()

    def test_duplicate_and_unknown_historical_shards_are_rejected(self):
        for coverage in ({'included': [], 'configured': [], 'directory': ['primary', 'primary']},
                         {'included': [], 'configured': [], 'directory': ['s9']},
                         {'included': ['s1'], 'configured': ['s1'], 'directory': ['s1']}):
            with self.subTest(coverage=coverage):
                self.manifest['user_shard_coverage'] = coverage
                with self.assertRaises(r.b.Blocked):
                    self.check()


if __name__ == '__main__':
    unittest.main()
