"""Offline safety regressions; never contacts Docker."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('baseline', Path(__file__).with_name('backup-local-baseline.py'))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)


def fixtures():
    result = []
    for index, service in enumerate(sorted(b.DBS.keys() | b.WRITERS)):
        database, user = b.db_spec(service) or ('', '')
        result.append({'Id': f'{index:064x}', 'Image': 'sha256:' + 'a' * 64,
                       'Config': {'Labels': {'com.docker.compose.project': b.PROJECT,
                                            'com.docker.compose.service': service,
                                            'com.docker.compose.oneoff': 'False'},
                                  'Image': 'postgres:17.6-alpine' if database else 'local-service',
                                  'Env': ['POSTGRES_DB=' + database, 'POSTGRES_USER=' + user]},
                       'State': {'Running': True}})
    return result


class SafetyTests(unittest.TestCase):
    def test_default_plan_has_no_docker_or_writes(self):
        with patch.object(b, 'docker') as docker, patch.object(b, 'private_directory') as directory, patch('sys.argv', ['backup']):
            b.main()
        docker.assert_not_called()
        directory.assert_not_called()

    def test_apply_requires_output(self):
        with patch('sys.argv', ['backup', '--apply']), self.assertRaises(b.Blocked):
            b.main()

    def test_inventory_requires_keycloak(self):
        items = [x for x in fixtures() if x['Config']['Labels']['com.docker.compose.service'] != 'sso-db']
        with self.assertRaises(b.Blocked):
            b.validate_inventory(items)

    def test_wrong_project_type_and_database_fail_closed(self):
        for field, value in [('project', 'production'), ('service', 'unknown'), ('oneoff', 'True')]:
            items = fixtures()
            items[0]['Config']['Labels']['com.docker.compose.' + field] = value
            with self.assertRaises(b.Blocked):
                b.validate_inventory(items)
        items = fixtures()
        db = next(x for x in items if x['Config']['Image'].startswith('postgres'))
        db['Config']['Image'] = 'unexpected-image'
        with self.assertRaises(b.Blocked):
            b.validate_inventory(items)
        db['Config']['Image'] = 'postgres:17.6-alpine'
        db['Config']['Env'] = ['POSTGRES_DB=production', 'POSTGRES_USER=bank']
        with self.assertRaises(b.Blocked):
            b.validate_inventory(items)

    def test_discovered_shards_included(self):
        items = fixtures()
        shard = copy.deepcopy(next(x for x in items if x['Config']['Labels']['com.docker.compose.service'] == 'user-db'))
        shard['Id'] = 'f' * 64
        shard['Config']['Labels']['com.docker.compose.service'] = 'user-shard-s3'
        self.assertIn('user-shard-s3', b.validate_inventory(items + [shard]))

    def test_missing_configured_or_historical_shard_blocks_before_dump(self):
        for configured in (True, False):
            with self.subTest(configured=configured):
                found = b.validate_inventory(fixtures())
                if configured:
                    found['user-service']['Config']['Env'].append('USER_SHARD_IDS=s2')
                stopped = copy.deepcopy(found)
                for service in b.WRITERS:
                    stopped[service]['State']['Running'] = False
                with tempfile.TemporaryDirectory() as temp, patch.object(b, 'local_daemon'), \
                     patch.object(b, 'source_revision', return_value={}), \
                     patch.object(b, 'inventory', side_effect=[found, stopped]), \
                     patch.object(b, 'private_directory', return_value=Path(temp)), \
                     patch.object(b, 'sql', return_value='["primary","s2"]') as sql, \
                     patch.object(b, 'docker') as docker:
                    with self.assertRaisesRegex(b.Blocked, 'shard database is missing'):
                        b.backup(Path(temp))
                    self.assertFalse(list(Path(temp).glob('*.dump')))
                    self.assertEqual(json.loads((Path(temp) / 'manifest.json').read_text())['status'], 'incomplete')
                    self.assertFalse(any('pg_dump' in call.args for call in docker.call_args_list))
                    self.assertEqual(sum(call.args[0] == 'start' for call in docker.call_args_list), len(b.WRITERS))
                    if configured:
                        sql.assert_not_called()

    def test_shard_coverage_includes_unconfigured_historical_shard(self):
        found = b.validate_inventory(fixtures())
        for index in (1, 2):
            shard = copy.deepcopy(found['user-db'])
            shard['Id'] = str(index) * 64
            shard['Config']['Labels']['com.docker.compose.service'] = 'user-shard-s' + str(index)
            found['user-shard-s' + str(index)] = shard
        found['user-service']['Config']['Env'] += ['USER_SHARD_IDS=s1',
            'USER_SHARD_S1_DB_URL=jdbc:postgresql://user-shard-s1:5432/bank']
        with patch.object(b, 'sql', return_value='["primary","s2"]'):
            coverage = b.shard_coverage(found)
        self.assertEqual(coverage, {'configured': ['s1'], 'directory': ['primary', 's2'], 'included': ['s1', 's2']})
        found['user-service']['Config']['Env'][-1] = 'USER_SHARD_S1_DB_URL=jdbc:postgresql://remote:5432/bank'
        with patch.object(b, 'sql') as sql, self.assertRaises(b.Blocked):
            b.shard_coverage(found)
        sql.assert_not_called()

    def test_stopped_database_and_duplicate_rejected(self):
        items = fixtures()
        with self.assertRaises(b.Blocked):
            b.validate_inventory(items + [items[0]])
        next(x for x in items if b.db_spec(x['Config']['Labels']['com.docker.compose.service']))['State']['Running'] = False
        with self.assertRaises(b.Blocked):
            b.validate_inventory(items)

    def test_checkout_output_rejected(self):
        with self.assertRaises(b.Blocked):
            b.outside_repo(b.REPO / 'backup')
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / '.git').mkdir()
            with self.assertRaises(b.Blocked):
                b.outside_repo(root / 'backup')

    def test_remote_daemon_rejected(self):
        with patch.dict(b.os.environ, {'DOCKER_HOST': 'tcp://example.org:2375', 'DOCKER_CONTEXT': ''}), self.assertRaises(b.Blocked):
            b.local_daemon()

    def test_remote_named_pipe_and_context_rejected(self):
        with patch.dict(b.os.environ, {'DOCKER_HOST': 'npipe:////remote/pipe/docker_engine', 'DOCKER_CONTEXT': ''}), self.assertRaises(b.Blocked):
            b.local_daemon()
        result = type('Result', (), {'returncode': 0, 'stdout': json.dumps([{'Endpoints': {'docker': {'Host': 'ssh://remote'}}}]).encode()})()
        with patch.dict(b.os.environ, {'DOCKER_HOST': '', 'DOCKER_CONTEXT': 'remote'}), patch.object(b.subprocess, 'run', return_value=result), self.assertRaises(b.Blocked):
            b.local_daemon()

    def test_endpoint_remains_pinned_after_ambient_context_changes(self):
        endpoint = 'npipe:////./pipe/docker_engine'
        result = type('Result', (), {'returncode': 0, 'stdout': b'ok'})()
        with patch.dict(b.os.environ, {'DOCKER_HOST': endpoint, 'DOCKER_CONTEXT': ''}):
            b.local_daemon()
        with patch.dict(b.os.environ, {'DOCKER_HOST': 'tcp://remote:2375', 'DOCKER_CONTEXT': 'production'}), patch.object(b.subprocess, 'run', return_value=result) as run:
            b.docker('ps')
        self.assertEqual(run.call_args.args[0], ['docker', '--host', endpoint, 'ps'])
        self.assertFalse(any(key.upper().startswith('DOCKER_') for key in run.call_args.kwargs['env']))

    def test_sso_fixture_count_uses_phone_link_not_nullable_email(self):
        def sql(container, database, user, query):
            if 'pg_tables' in query:
                return '[]'
            self.assertNotIn('email', query)
            self.assertIn('JOIN external_identities', query)
            self.assertIn('registration_phone', query)
            self.assertIn('generate_series(100,299)', query)
            # Synthetic SSO identities have NULL email; registration phone/link still count.
            return '200'
        with patch.object(b, 'sql', side_effect=sql):
            self.assertEqual(b.snapshot_metrics('fake', 'bank', 'bank', 'auth-db')['fixture_count'], 200)

    def test_missing_sso_fixture_blocks_snapshot(self):
        with patch.object(b, 'sql', side_effect=['[]', '199']), self.assertRaises(b.Blocked):
            b.snapshot_metrics('fake', 'bank', 'bank', 'auth-db')

    def test_cleanup_requires_own_name_and_label(self):
        with patch.object(b, 'docker') as docker, self.assertRaises(b.Blocked):
            b.owned_resource('volume', 'arman-bank_ledger-db', 'a' * 32)
        docker.assert_not_called()
        with patch.object(b, 'docker', return_value=json.dumps([{'Labels': {}}])), self.assertRaises(b.Blocked):
            b.owned_resource('volume', 'armoney-verify-' + 'a' * 32 + '-data', 'a' * 32)

    def test_dump_failure_restarts_only_previously_running(self):
        found = b.validate_inventory(fixtures())
        found['edge']['State']['Running'] = False
        stopped = copy.deepcopy(found)
        for service in b.WRITERS:
            stopped[service]['State']['Running'] = False
        with tempfile.TemporaryDirectory() as temp, patch.object(b, 'local_daemon'), \
             patch.object(b, 'inventory', side_effect=[found, stopped]), \
             patch.object(b, 'private_directory', return_value=Path(temp)), \
             patch.object(b, 'sql', side_effect=b.Blocked('injected dump failure')), \
             patch.object(b, 'docker') as docker:
            with self.assertRaises(b.Blocked):
                b.backup(Path(temp))
        restarted = [call.args[1] for call in docker.call_args_list if call.args[0] == 'start']
        self.assertEqual(set(restarted), {item['Id'] for service, item in found.items() if service in b.WRITERS and item['State']['Running']})

    def test_financial_reconciliation_rejects_projection_error(self):
        financial = dict(accounts=2, owners=1, postings=2, balance_sum_fils=0, posting_sum_fils=0,
                         unbalanced_payments=0, projection_mismatches=1, negative_customer_balances=0,
                         non_aed_accounts=0)
        with patch.object(b, 'sql', side_effect=['[]', json.dumps(financial)]), self.assertRaises(b.Blocked):
            b.snapshot_metrics('fake', 'bank', 'bank', 'ledger-db')

    def test_schema_and_row_metrics_capture_only_aggregates(self):
        tables = [['public', 'profiles'], ['public', 'flyway_schema_history']]
        flyway = [{'version': '1', 'checksum': 123, 'success': True}]
        with patch.object(b, 'sql', side_effect=[json.dumps(tables), '5', '1', json.dumps(flyway)]):
            metrics = b.snapshot_metrics('fake', 'bank', 'bank', 'user-db')
        self.assertEqual(metrics['table_rows'], {'public.profiles': 5, 'public.flyway_schema_history': 1})
        self.assertEqual(metrics['flyway'], flyway)

    def test_restore_checksum_failure_creates_nothing(self):
        with tempfile.TemporaryDirectory() as temp:
            archive = Path(temp) / 'dump'
            archive.write_bytes(b'archive')
            with patch.object(b, 'docker') as docker, self.assertRaises(b.Blocked):
                b.restore_verify(archive, {'database': 'bank', 'user': 'bank', 'image_id': 'sha256:' + 'a' * 64, 'sha256': 'wrong'})
            docker.assert_not_called()

    def test_restore_failure_cleans_only_owned_isolated_resources(self):
        token = 'b' * 32
        calls = []
        def docker(*args, **kwargs):
            calls.append(args)
            if args[:2] == ('exec', '-i'):
                raise b.Blocked('injected restore failure')
            return ''
        def owned(kind, name, actual):
            self.assertEqual(actual, token)
            self.assertTrue(name.startswith('armoney-verify-' + token))
            return {'HostConfig': {'NetworkMode': 'none'}, 'Mounts': [{'Name': 'armoney-verify-' + token + '-data'}]}
        with tempfile.TemporaryDirectory() as temp:
            archive = Path(temp) / 'dump'
            archive.write_bytes(b'archive')
            with patch.object(b.uuid, 'uuid4') as ident, patch.object(b, 'owned_resource', side_effect=owned), patch.object(b, 'docker', side_effect=docker):
                ident.return_value.hex = token
                with self.assertRaises(b.Blocked):
                    b.restore_verify(archive, {'database': 'bank', 'user': 'bank', 'image_id': 'sha256:' + 'a' * 64, 'sha256': b.checksum(archive)})
        self.assertIn(('container', 'rm', '-f', 'armoney-verify-' + token + '-postgres'), calls)
        self.assertIn(('volume', 'rm', 'armoney-verify-' + token + '-data'), calls)
        create = next(c for c in calls if c[0] == 'create')
        self.assertEqual(create[create.index('--network') + 1], 'none')
        self.assertNotIn('-p', create)


if __name__ == '__main__':
    unittest.main()
