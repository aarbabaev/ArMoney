import asyncio
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
        self.assertTrue(all(t['state'] == 'blocked' for t in self.store.snapshot()))

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
                return {'status': 'request_changes', 'summary': 'Fix regression', 'evidence': ['failure reproduced']}
            if task['stage'] == 'implement' and task['attempt'] == 2:
                self.assertEqual('Fix regression', task['result']['finding']['summary'])
            tree = 'fixed' if task['attempt'] == 2 else 'initial'
            return {'status': 'approve' if task['stage'] == 'review' else 'ready', 'head_sha': 'revision', 'tree_sha': tree, 'evidence': ['checked']}
        result = asyncio.run(Scheduler(self.store, runner).run())
        self.assertEqual([('implement', 1), ('qa', 1), ('review', 1), ('implement', 2), ('qa', 2), ('review', 2)], calls)
        self.assertTrue(all(t['state'] == 'succeeded' for t in result))

    def test_completed_failure_retries_are_bounded_and_orphans_not_replayed(self):
        self.store.enqueue(self.spec('owner', max_attempts=2))
        calls = []
        async def runner(task):
            calls.append(task['attempt'])
            return {'status': 'blocked', 'summary': 'local execution unavailable'}
        result = asyncio.run(Scheduler(self.store, runner).run())
        self.assertEqual([1, 2], calls)
        self.assertEqual('blocked', result[0]['state'])
        self.store.enqueue(self.spec('orphan'))
        orphan = self.store.claim_available()[0]
        self.store.complete('orphan', orphan['attempt'], {'status': 'blocked'})
        self.store.route_remediation()
        self.store.claim_available()
        self.store.recover()
        self.store.route_remediation()
        self.assertEqual('interrupted', next(t for t in self.store.snapshot() if t['task_id'] == 'orphan')['state'])
        self.store.reconcile('orphan', orphan_stopped=True)
        self.store.route_remediation()
        self.assertEqual('blocked', next(t for t in self.store.snapshot() if t['task_id'] == 'orphan')['state'])


if __name__ == '__main__':
    unittest.main()
