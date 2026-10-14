import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from contextlib import redirect_stdout
import io

from .github import PROJECT, REPOSITORY
from .intake import import_project
from .store import Store


class IntakeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.cwd = self.root / 'worktree'
        self.cwd.mkdir()
        self.state = self.root / 'state'
        self.state.mkdir()
        self.store = Store(self.state / 'queue.sqlite')
        self.catalog = self.root / 'catalog.json'
        self.calls = []
        self.pages = []

    def tearDown(self):
        self.temp.cleanup()

    def spec(self, issue='21', **changes):
        result = dict(task_id='task-' + issue, issue=issue, role='ledger_owner', stage='implement',
                      cwd=str(self.cwd), agent_id='owner-' + issue, prompt='PRIVATE_SCOPE', paths=['src'], authorized=True)
        result.update(changes)
        return result

    def node(self, number=21, status='Ready', owner='aarbabaev', repo=REPOSITORY, labels=(), kind='Issue', state='OPEN'):
        return {'status': {'name': status}, 'content': {'__typename': kind, 'number': number,
                 'state': state, 'author': {'login': owner}, 'repository': {'nameWithOwner': repo},
                 'labels': {'nodes': [{'name': label} for label in labels], 'pageInfo': {'hasNextPage': False}}}}

    def request(self, endpoint, data):
        self.calls.append((endpoint, data))
        nodes = self.pages[len(self.calls) - 1]
        more = len(self.calls) < len(self.pages)
        return {'data': {'user': {'projectV2': {'id': PROJECT, 'items': {'nodes': nodes,
                'pageInfo': {'hasNextPage': more, 'endCursor': str(len(self.calls))}}}}}}

    def run_import(self, specs, pages):
        self.catalog.write_text(json.dumps(specs), encoding='utf-8')
        self.pages = pages
        return import_project(self.catalog, self.store, self, self.state)

    def test_paginated_owner_catalog_pipeline_only_and_no_execution(self):
        specs = [self.spec(), self.spec(task_id='qa-21', stage='qa', role='qa_integration', paths=[], read_only=True, agent_id='qa', dependencies=['task-21'])]
        result = self.run_import(specs, [[self.node(99)], [self.node()]])
        self.assertEqual(['21'], result['eligible_issues'])
        self.assertEqual(2, result['imported_tasks'])
        self.assertEqual('not_started', result['execution'])
        self.assertTrue(all(t['attempt'] == 0 and t['state'] == 'queued' for t in self.store.snapshot()))
        self.assertEqual('1', self.calls[1][1]['variables']['cursor'])
        self.assertNotIn('PRIVATE_SCOPE', json.dumps(result))

    def test_owner_state_repository_status_hold_and_draft_gates(self):
        cases = [self.node(21, owner='other'), self.node(22, state='CLOSED'), self.node(23, repo='other/repo'),
                 self.node(24, status='In Progress'), self.node(25, status='Review'), self.node(26, status='Blocked'),
                 self.node(27, status='Done'), self.node(28, status='Awaiting deployment'), self.node(29, labels=['paused']),
                 self.node(30, kind='PullRequest'), self.node(31, kind='DraftIssue'), self.node(32, status='Backlog'), self.node(33, status=None)]
        result = self.run_import([self.spec(str(n)) for n in range(21, 34)], [cases])
        self.assertEqual(['32', '33'], result['eligible_issues'])
        self.assertEqual(11, len(result['skipped']))

    def test_invalid_catalog_is_atomic_before_network(self):
        with self.assertRaises(ValueError):
            self.run_import([self.spec(), self.spec('22', authorized=False)], [[self.node()]])
        self.assertEqual([], self.calls)
        self.assertEqual([], self.store.snapshot())

    def test_duplicate_tasks_and_prior_issue_claim_do_not_create_generation(self):
        self.store.enqueue(self.spec())
        result = self.run_import([self.spec()], [[self.node()]])
        self.assertEqual('existing_task_id', result['skipped'][0]['reason'])
        self.store.claim_available()
        self.calls.clear()
        result = self.run_import([self.spec(task_id='new-generation')], [[self.node()]])
        self.assertEqual('existing_issue_generation', result['skipped'][0]['reason'])
        self.assertEqual(1, len(self.store.snapshot()))

    def test_renamed_tasks_cannot_duplicate_an_existing_queued_pipeline(self):
        self.store.enqueue(self.spec())
        result = self.run_import([self.spec(task_id='renamed-owner'),
                                  self.spec(task_id='renamed-qa', stage='qa', role='qa_integration',
                                            agent_id='qa', paths=[], read_only=True, dependencies=['renamed-owner'])], [[self.node()]])
        self.assertEqual([], result['eligible_issues'])
        self.assertEqual('existing_issue_generation', result['skipped'][0]['reason'])
        self.assertEqual(['task-21'], [t['task_id'] for t in self.store.snapshot()])

    def test_ios_pause_denies_ancestor_directory_but_allows_exact_shared_file(self):
        (self.cwd / 'ios').mkdir()
        (self.cwd / 'nested' / 'ios').mkdir(parents=True)
        shared = self.cwd / 'ARCHITECTURE.md'
        shared.write_text('synthetic shared file', encoding='utf-8')
        result = self.run_import([self.spec(paths=['.']), self.spec('22', paths=['ARCHITECTURE.md']), self.spec('23', paths=['nested'])], [[self.node(), self.node(22), self.node(23)]])
        self.assertEqual(['22'], result['eligible_issues'])
        self.assertEqual('ios_owner_pause', result['skipped'][0]['reason'])
        self.assertEqual('ios_owner_pause', result['skipped'][1]['reason'])
        self.assertEqual(['task-22'], [t['task_id'] for t in self.store.snapshot()])

    def test_cross_issue_unknown_dependency_stays_queued_and_ios_pause(self):
        result = self.run_import([self.spec(dependencies=['task-22']), self.spec('22', role='ios_owner')], [[self.node(), self.node(22)]])
        self.assertEqual(['21'], result['eligible_issues'])
        self.assertEqual('ios_owner_pause', result['skipped'][0]['reason'])

    def test_cli_import_snapshot_never_starts_runner(self):
        from . import cli
        self.catalog.write_text(json.dumps([self.spec()]), encoding='utf-8')
        self.pages = [[self.node()]]
        output = io.StringIO()
        with patch.object(cli, 'GitHubSink', return_value=self), patch.object(cli, 'CodexRunner') as runner, redirect_stdout(output):
            self.assertEqual(0, cli.main(['--state', str(self.state), 'import-project', '--catalog', str(self.catalog)]))
            runner.assert_not_called()
        self.assertEqual('not_started', json.loads(output.getvalue())['execution'])
        self.assertNotIn('PRIVATE_SCOPE', output.getvalue())

    def test_pagination_bound_fails_without_partial_import(self):
        with self.assertRaises(ValueError):
            self.run_import([self.spec()], [[self.node()]] * 21)
        self.assertEqual(20, len(self.calls))
        self.assertEqual([], self.store.snapshot())
        self.assertEqual([], self.store.claim_available())
        self.calls.clear()
        result = self.run_import([self.spec('23', paths=['ios/App'])], [[self.node(23)]])
        self.assertEqual('ios_owner_pause', result['skipped'][0]['reason'])


if __name__ == '__main__':
    unittest.main()
