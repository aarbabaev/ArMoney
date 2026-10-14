import asyncio
import json
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .scheduler import Scheduler
from .store import Store


class QueueTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.store = Store(self.root / 'queue.sqlite')

    def tearDown(self):
        self.temp.cleanup()

    def spec(self, task_id, **changes):
        cwd = self.root / task_id
        cwd.mkdir(exist_ok=True)
        value = dict(task_id=task_id, role='ledger_owner', issue='issue', stage='implement', cwd=str(cwd), prompt='authorized work', agent_id=task_id, authorized=True, paths=['src'], dependencies=[], heavy=False)
        value.update(changes)
        return value

    def finish(self, task):
        self.assertTrue(self.store.complete(task['task_id'], task['attempt'], {'status': 'ready', 'evidence': ['tests passed'], 'head_sha': 'revision', 'tree_sha': 'tree'}))

    def test_dedup_atomic_claims(self):
        spec = self.spec('one')
        self.assertTrue(self.store.enqueue(spec))
        self.assertFalse(self.store.enqueue(spec))
        with ThreadPoolExecutor(2) as pool:
            results = list(pool.map(lambda _: Store(self.store.db_path).claim_available(), range(2)))
        self.assertEqual(1, sum(map(len, results)))

    def test_batch_invalid_later_spec_is_atomic(self):
        with self.assertRaises(ValueError):
            self.store.enqueue_batch([self.spec('valid'), self.spec('invalid', authorized=False)])
        self.assertEqual([], self.store.snapshot())
        with self.assertRaises(ValueError):
            self.store.enqueue_batch([self.spec('same'), self.spec('same', prompt='different')])
        self.assertEqual([], self.store.snapshot())
        self.assertEqual([True, False], self.store.enqueue_batch([self.spec('same'), self.spec('same')]))

    def test_dependencies_crash_fencing(self):
        self.store.enqueue(self.spec('a'))
        self.store.enqueue(self.spec('b', dependencies=['a']))
        first = self.store.claim_available()[0]
        self.assertEqual('a', first['task_id'])
        self.assertEqual(1, self.store.recover())
        self.assertEqual([], self.store.claim_available())
        self.store.reconcile('a', orphan_stopped=True)
        self.store.resume('a')
        second = self.store.claim_available()[0]
        self.assertFalse(self.store.complete('a', first['attempt'], {'status': 'ready', 'evidence': ['old']}))
        self.finish(second)
        self.assertEqual('b', self.store.claim_available()[0]['task_id'])

    def test_limits_and_leases(self):
        for i in range(8):
            self.store.enqueue(self.spec(str(i), heavy=True))
        self.assertEqual(1, len(self.store.claim_available({'external_agents': 4, 'external_heavy': 1})))
        self.assertEqual([], self.store.claim_available({'external_agents': 4, 'external_heavy': 1}))

    def test_exclusive_cwd(self):
        first = self.spec('a')
        self.store.enqueue(first)
        self.store.enqueue(self.spec('b', cwd=first['cwd']))
        self.assertEqual(1, len(self.store.claim_available()))

    def test_independence_and_evidence(self):
        self.store.enqueue(self.spec('owner'))
        self.finish(self.store.claim_available()[0])
        self.store.enqueue(self.spec('qa', stage='qa', role='qa_integration', paths=[], read_only=True, agent_id='owner'))
        self.assertEqual([], self.store.claim_available())
        self.store.enqueue(self.spec('qa2', stage='qa', role='qa_integration', paths=[], read_only=True))
        qa = self.store.claim_available()[0]
        self.store.complete(qa['task_id'], qa['attempt'], {'status': 'ready'})
        self.assertEqual('blocked', next(t for t in self.store.snapshot() if t['task_id'] == 'qa2')['state'])

    def test_scheduler_refills_and_exits(self):
        self.store.enqueue(self.spec('a'))
        self.store.enqueue(self.spec('b', dependencies=['a']))
        order = []
        async def runner(task):
            order.append(task['task_id'])
            await asyncio.sleep(0)
            return {'status': 'ready', 'evidence': ['checked']}
        result = asyncio.run(Scheduler(self.store, runner).run())
        self.assertEqual(['a', 'b'], order)
        self.assertTrue(all(t['state'] == 'succeeded' for t in result))

    def test_validation_and_bounded_resume(self):
        with self.assertRaises(ValueError):
            self.store.enqueue(self.spec('bad', authorized=False))
        with self.assertRaises(ValueError):
            self.store.enqueue(self.spec('escape', paths=['../outside']))
        self.store.enqueue(self.spec('bounded', max_attempts=1))
        self.store.claim_available()
        self.store.recover()
        with self.assertRaises(ValueError):
            self.store.resume('bounded')

    def test_interrupted_retains_lease_and_leader_lock(self):
        first = self.spec('a')
        self.store.enqueue(first)
        self.store.enqueue(self.spec('b', cwd=first['cwd']))
        self.store.claim_available()
        self.store.recover()
        self.assertEqual([], self.store.claim_available())
        with self.assertRaises(ValueError):
            self.store.resume('a')
        with self.store.leader_lock():
            with self.assertRaises(RuntimeError):
                with Store(self.store.db_path).leader_lock():
                    pass
        self.store.reconcile('a', orphan_stopped=True)
        self.assertEqual('b', self.store.claim_available()[0]['task_id'])

    def test_stale_revision_blocks_qa(self):
        self.store.enqueue(self.spec('owner'))
        self.finish(self.store.claim_available()[0])
        self.store.enqueue(self.spec('qa', stage='qa', role='qa_integration', paths=[], read_only=True))
        qa = self.store.claim_available()[0]
        self.store.complete('qa', qa['attempt'], {'status': 'ready', 'head_sha': 'stale', 'evidence': ['passed']})
        states = {t['task_id']: t['state'] for t in self.store.snapshot()}
        self.assertEqual({'owner': 'succeeded', 'qa': 'blocked'}, states)

    def test_nested_cwd_glob_and_negative_limits(self):
        a = self.spec('a')
        nested = Path(a['cwd']) / 'nested'
        nested.mkdir()
        self.store.enqueue(a)
        self.store.enqueue(self.spec('b', cwd=str(nested)))
        with self.assertRaises(ValueError):
            self.store.enqueue(self.spec('glob', paths=['module/**']))
        with self.assertRaises(ValueError):
            self.store.claim_available({'external_agents': -1})
        self.assertEqual(1, len(self.store.claim_available()))

    def test_review_rejects_stale_tree(self):
        self.store.enqueue(self.spec('owner'))
        self.finish(self.store.claim_available()[0])
        self.store.enqueue(self.spec('qa', stage='qa', role='qa_integration', paths=[], read_only=True))
        self.finish(self.store.claim_available()[0])
        self.store.enqueue(self.spec('review', stage='review', role='bank_reviewer', paths=[], read_only=True))
        review = self.store.claim_available()[0]
        self.store.complete('review', review['attempt'], {'status': 'approve', 'head_sha': 'revision', 'tree_sha': 'changed', 'evidence': ['checked']})
        self.assertEqual('blocked', next(t for t in self.store.snapshot() if t['task_id'] == 'review')['state'])

    def test_sink_never_receives_rejected_approval(self):
        self.store.enqueue(self.spec('owner'))
        self.finish(self.store.claim_available()[0])
        self.store.enqueue(self.spec('qa', stage='qa', role='qa_integration', paths=[], read_only=True))
        self.finish(self.store.claim_available()[0])
        self.store.enqueue(self.spec('review', stage='review', role='bank_reviewer', paths=[], read_only=True))
        events = []
        async def runner(task):
            return {'status': 'approve', 'head_sha': 'revision', 'tree_sha': 'stale', 'evidence': ['checked']}
        async def sink(event, task, result):
            if event == 'completed':
                events.append((task['state'], result['status']))
        asyncio.run(Scheduler(self.store, runner, transition=sink).run())
        self.assertEqual([('blocked', 'blocked')], events)

    def test_refills_all_stages_and_persists_sink_failures(self):
        self.store.enqueue(self.spec('owner'))
        self.store.enqueue(self.spec('qa', stage='qa', role='qa_integration', paths=[], read_only=True, dependencies=['owner']))
        self.store.enqueue(self.spec('review', stage='review', role='bank_reviewer', paths=[], read_only=True, dependencies=['qa']))
        order = []
        async def runner(task):
            order.append(task['stage'])
            return {'status': 'approve' if task['stage'] == 'review' else 'ready', 'head_sha': 'revision', 'tree_sha': 'tree', 'evidence': ['checked']}
        async def sink(event, task, result):
            raise OSError('offline')
        result = asyncio.run(Scheduler(self.store, runner, transition=sink).run())
        self.assertEqual(['implement', 'qa', 'review'], order)
        self.assertEqual(6, len(self.store.event_failures()))
        self.assertTrue(all(t['state'] == 'succeeded' for t in result))

    def test_review_finding_routes_owner_then_fresh_qa_and_review(self):
        self.store.enqueue(self.spec('owner'))
        self.store.enqueue(self.spec('qa', stage='qa', role='qa_integration', paths=[], read_only=True, dependencies=['owner']))
        self.store.enqueue(self.spec('review', stage='review', role='bank_reviewer', paths=[], read_only=True, dependencies=['qa']))
        calls = []
        async def runner(task):
            calls.append((task['stage'], task['attempt']))
            if task['stage'] == 'review' and task['attempt'] == 1:
                return {'status': 'request_changes', 'summary': 'Fix regression', 'evidence': ['failure reproduced'], 'head_sha': 'revision', 'tree_sha': 'initial'}
            if task['stage'] == 'implement' and task['attempt'] == 2:
                self.assertEqual('Fix regression', task['result']['finding']['summary'])
            tree = 'fixed' if task['attempt'] == 2 else 'initial'
            return {'status': 'approve' if task['stage'] == 'review' else 'ready', 'head_sha': 'revision', 'tree_sha': tree, 'evidence': ['checked']}
        result = asyncio.run(Scheduler(self.store, runner).run())
        self.assertEqual([('implement', 1), ('qa', 1), ('review', 1), ('implement', 2), ('qa', 2), ('review', 2)], calls)
        self.assertTrue(all(t['state'] == 'succeeded' for t in result))

    def test_actionable_failure_retries_are_bounded_and_orphans_not_replayed(self):
        self.store.enqueue(self.spec('owner', max_attempts=2))
        calls = []
        async def runner(task):
            calls.append(task['attempt'])
            return {'status': 'request_changes', 'summary': 'reproduced source defect', 'evidence': ['regression reproduced']}
        result = asyncio.run(Scheduler(self.store, runner).run())
        self.assertEqual([1, 2], calls)
        self.assertEqual('blocked', result[0]['state'])
        self.store.enqueue(self.spec('orphan'))
        orphan = self.store.claim_available()[0]
        self.store.complete('orphan', orphan['attempt'], {'status': 'request_changes', 'evidence': ['regression reproduced']})
        self.store.route_remediation()
        self.store.claim_available()
        self.store.recover()
        self.store.route_remediation()
        self.assertEqual('interrupted', next(t for t in self.store.snapshot() if t['task_id'] == 'orphan')['state'])
        self.store.reconcile('orphan', orphan_stopped=True)
        self.store.route_remediation()
        self.assertEqual('blocked', next(t for t in self.store.snapshot() if t['task_id'] == 'orphan')['state'])

    def test_blocked_qa_does_not_invalidate_or_retry_implementation(self):
        self.store.enqueue(self.spec('owner'))
        self.finish(self.store.claim_available()[0])
        self.store.enqueue(self.spec('qa', stage='qa', role='qa_integration', paths=[], read_only=True))
        calls = []
        async def runner(task):
            calls.append(task['task_id'])
            return {'status': 'blocked', 'summary': 'Sandbox denies TEMP; external CI unavailable'}
        rows = {t['task_id']: t for t in asyncio.run(Scheduler(self.store, runner).run())}
        self.assertEqual(['qa'], calls)
        self.assertEqual('succeeded', rows['owner']['state'])
        self.assertEqual(1, rows['owner']['attempt'])
        self.assertFalse(rows['qa']['result']['auto_remediation'])
        self.assertEqual('operator', rows['qa']['result']['route_to'])

    def test_preflight_quarantines_before_claim_without_attempt_or_launch(self):
        from .runner import PreflightError
        self.store.enqueue(self.spec('invalid'))
        class Runner:
            def preflight(self, task):
                raise PreflightError('REPOSITORY_MISMATCH')
            async def __call__(self, task):
                raise AssertionError('must not launch')
        row = asyncio.run(Scheduler(self.store, Runner()).run())[0]
        self.assertEqual(('blocked', 0), (row['state'], row['attempt']))
        self.assertEqual('REPOSITORY_MISMATCH', row['result']['error_code'])

    def test_unevidenced_request_changes_cannot_trigger_owner_retry(self):
        self.store.enqueue(self.spec('owner'))
        calls = []
        async def runner(task):
            calls.append(task['attempt'])
            return {'status': 'request_changes', 'summary': 'No reproducible finding provided'}
        row = asyncio.run(Scheduler(self.store, runner).run())[0]
        self.assertEqual([1], calls)
        self.assertEqual('FINDING_EVIDENCE_MISSING', row['result']['error_code'])
        self.assertFalse(row['result']['auto_remediation'])

    def test_legacy_owner_route_from_blocked_qa_is_not_replayed(self):
        self.store.enqueue(self.spec('owner'))
        # Synthetic persisted result created by the previous dispatcher version.
        legacy = {'status': 'request_changes', 'auto_remediation': True, 'finding': {'status': 'blocked'}}
        with self.store._connect() as db:
            db.execute("UPDATE tasks SET state='blocked',result=? WHERE id='owner'", (json.dumps(legacy),))
        self.store.route_remediation()
        self.assertEqual('blocked', self.store.snapshot()[0]['state'])

    def test_blank_null_or_malformed_findings_preserve_successful_owner(self):
        self.store.enqueue(self.spec('owner'))
        self.finish(self.store.claim_available()[0])
        for index, evidence in enumerate((None, [], [None], [''], [' \t\n'], ['valid reproduction', None], ['valid reproduction', ' '], 'not-a-list')):
            with self.subTest(evidence=evidence):
                task_id = 'qa-' + str(index)
                self.store.enqueue(self.spec(task_id, stage='qa', role='qa_integration', paths=[], read_only=True))
                qa = self.store.claim_available()[0]
                self.store.complete(task_id, qa['attempt'], {'status': 'request_changes', 'evidence': evidence})
                self.store.route_remediation()
                rows = {t['task_id']: t for t in self.store.snapshot()}
                self.assertEqual(('succeeded', 1), (rows['owner']['state'], rows['owner']['attempt']))
                self.assertEqual('FINDING_EVIDENCE_MISSING', rows[task_id]['result']['error_code'])
                self.assertFalse(rows[task_id]['result']['auto_remediation'])
                self.assertEqual([], self.store.claim_available())

    def test_whitespace_success_evidence_cannot_approve_implementation(self):
        self.store.enqueue(self.spec('owner'))
        owner = self.store.claim_available()[0]
        self.store.complete('owner', owner['attempt'], {'status': 'ready', 'evidence': [' \n\t']})
        self.assertEqual('blocked', self.store.snapshot()[0]['state'])

    def test_stale_or_missing_finding_hashes_do_not_route_qa_or_review_owners(self):
        self.store.enqueue(self.spec('owner'))
        self.finish(self.store.claim_available()[0])
        self.store.enqueue(self.spec('valid-qa', stage='qa', role='qa_integration', paths=[], read_only=True))
        self.finish(self.store.claim_available()[0])
        hashes = ({}, {'head_sha': 'revision'}, {'tree_sha': 'tree'},
                  {'head_sha': 'stale', 'tree_sha': 'tree'},
                  {'head_sha': 'revision', 'tree_sha': 'stale'},
                  {'head_sha': None, 'tree_sha': None})
        for stage in ('qa', 'review'):
            for index, fields in enumerate(hashes):
                with self.subTest(stage=stage, hashes=fields):
                    task_id = stage + '-finding-' + str(index)
                    role = 'qa_integration' if stage == 'qa' else 'bank_reviewer'
                    self.store.enqueue(self.spec(task_id, stage=stage, role=role, paths=[], read_only=True))
                    task = self.store.claim_available()[0]
                    self.store.complete(task_id, task['attempt'], {'status': 'request_changes', 'evidence': ['source defect reproduced'], **fields})
                    self.store.route_remediation()
                    rows = {t['task_id']: t for t in self.store.snapshot()}
                    self.assertEqual(('succeeded', 1), (rows['owner']['state'], rows['owner']['attempt']))
                    self.assertEqual('succeeded', rows['valid-qa']['state'])
                    self.assertEqual('FINDING_REVISION_MISMATCH', rows[task_id]['result']['error_code'])
                    self.assertEqual('operator', rows[task_id]['result']['route_to'])
                    self.assertFalse(rows[task_id]['result']['auto_remediation'])
                    self.assertEqual([], self.store.claim_available())

    def test_review_finding_must_match_successful_qa_as_well_as_owner(self):
        self.store.enqueue(self.spec('owner'))
        self.finish(self.store.claim_available()[0])
        self.store.enqueue(self.spec('qa', stage='qa', role='qa_integration', paths=[], read_only=True))
        self.finish(self.store.claim_available()[0])
        # Synthetic persisted independent evidence for a different tree.
        with self.store._connect() as db:
            db.execute("UPDATE tasks SET result=? WHERE id='qa'", (json.dumps({'status': 'ready', 'head_sha': 'revision', 'tree_sha': 'different-qa-tree', 'evidence': ['independent check']}),))
        self.store.enqueue(self.spec('review', stage='review', role='bank_reviewer', paths=[], read_only=True))
        task = self.store.claim_available()[0]
        self.store.complete('review', task['attempt'], {'status': 'request_changes', 'head_sha': 'revision', 'tree_sha': 'tree', 'evidence': ['source defect reproduced']})
        self.store.route_remediation()
        rows = {t['task_id']: t for t in self.store.snapshot()}
        self.assertEqual('succeeded', rows['owner']['state'])
        self.assertEqual('succeeded', rows['qa']['state'])
        self.assertEqual('FINDING_REVISION_MISMATCH', rows['review']['result']['error_code'])
        self.assertEqual([], self.store.claim_available())

    def test_new_pipeline_on_same_issue_is_independent_of_failed_history(self):
        self.store.enqueue(self.spec('old-owner', pipeline_id='old'))
        old = self.store.claim_available()[0]
        self.store.complete(old['task_id'], old['attempt'], {'status': 'blocked'})
        self.store.enqueue_batch([
            self.spec('new-owner', pipeline_id='new'),
            self.spec('new-qa', pipeline_id='new', stage='qa', role='qa_integration', paths=[], read_only=True, dependencies=['new-owner']),
            self.spec('new-review', pipeline_id='new', stage='review', role='bank_reviewer', paths=[], read_only=True, dependencies=['new-qa'])])
        calls = []
        async def runner(task):
            calls.append(task['task_id'])
            return {'status': 'approve' if task['stage'] == 'review' else 'ready', 'head_sha': 'new-revision', 'tree_sha': 'new-tree', 'evidence': ['checked']}
        rows = {t['task_id']: t for t in asyncio.run(Scheduler(self.store, runner).run())}
        self.assertEqual(['new-owner', 'new-qa', 'new-review'], calls)
        self.assertEqual('blocked', rows['old-owner']['state'])
        self.assertTrue(all(rows[name]['state'] == 'succeeded' for name in calls))
        with self.assertRaises(ValueError):
            self.store.enqueue(self.spec('old-owner', pipeline_id='new'))

    def test_old_pipeline_findings_cannot_invalidate_new_pipeline_owner(self):
        self.store.enqueue(self.spec('old-owner', pipeline_id='old'))
        self.finish(self.store.claim_available()[0])
        self.store.enqueue(self.spec('old-qa', pipeline_id='old', stage='qa', role='qa_integration', paths=[], read_only=True))
        old_qa = self.store.claim_available()[0]
        self.store.enqueue(self.spec('new-owner', pipeline_id='new'))
        new_owner = self.store.claim_available()[0]
        self.store.complete('new-owner', new_owner['attempt'], {'status': 'ready', 'head_sha': 'new-revision', 'tree_sha': 'new-tree', 'evidence': ['checked']})
        self.store.complete('old-qa', old_qa['attempt'], {'status': 'request_changes', 'head_sha': 'new-revision', 'tree_sha': 'new-tree', 'evidence': ['stale old-generation finding']})
        self.store.route_remediation()
        rows = {t['task_id']: t for t in self.store.snapshot()}
        self.assertEqual('succeeded', rows['new-owner']['state'])
        self.assertEqual('succeeded', rows['old-owner']['state'])
        self.assertEqual('FINDING_REVISION_MISMATCH', rows['old-qa']['result']['error_code'])
        self.assertEqual([], self.store.claim_available())
        self.store.resume('old-qa')
        old_qa = self.store.claim_available()[0]
        self.store.complete('old-qa', old_qa['attempt'], {'status': 'request_changes', 'head_sha': 'revision', 'tree_sha': 'tree', 'evidence': ['old-generation defect reproduced']})
        self.store.route_remediation()
        rows = {t['task_id']: t for t in self.store.snapshot()}
        self.assertEqual('queued', rows['old-owner']['state'])
        self.assertEqual(('succeeded', 1), (rows['new-owner']['state'], rows['new-owner']['attempt']))

    def test_pipeline_validation_and_known_dependency_edges_are_atomic(self):
        for pipeline in (None, '', ' \t', 'x' * 121, 12):
            with self.subTest(pipeline=pipeline), self.assertRaises(ValueError):
                self.store.enqueue(self.spec('bad', pipeline_id=pipeline))
        with self.assertRaises(ValueError):
            self.store.enqueue_batch([self.spec('owner', pipeline_id='a'), self.spec('child', pipeline_id='b', dependencies=['owner'])])
        with self.assertRaises(ValueError):
            self.store.enqueue_batch([self.spec('owner'), self.spec('child', issue='other', dependencies=['owner'])])
        self.assertEqual([], self.store.snapshot())
        self.store.enqueue(self.spec('child', pipeline_id='a', dependencies=['future']))
        with self.assertRaises(ValueError):
            self.store.enqueue(self.spec('future', pipeline_id='b'))
        self.assertEqual(['child'], [t['task_id'] for t in self.store.snapshot()])
        self.assertEqual([], self.store.claim_available())

    def test_legacy_specs_keep_issue_group_and_idempotent_enqueue(self):
        spec = self.spec('legacy-owner')
        self.store.enqueue(spec)
        with self.store._connect() as db:
            raw = json.loads(db.execute("SELECT spec FROM tasks WHERE id='legacy-owner'").fetchone()['spec'])
            del raw['pipeline_id']
            db.execute("UPDATE tasks SET spec=? WHERE id='legacy-owner'", (json.dumps(raw, sort_keys=True),))
        self.assertFalse(self.store.enqueue(spec))
        self.assertEqual('issue', self.store.snapshot()[0]['pipeline_id'])
        self.finish(self.store.claim_available()[0])
        self.store.enqueue(self.spec('legacy-qa', stage='qa', role='qa_integration', paths=[], read_only=True, dependencies=['legacy-owner']))
        qa = self.store.claim_available()[0]
        self.assertEqual('legacy-qa', qa['task_id'])
        self.finish(qa)


if __name__ == '__main__':
    unittest.main()
