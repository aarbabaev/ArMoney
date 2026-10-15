"""Transactional SQLite queue. Running work is never replayed after a crash."""
import json
import os
import sqlite3
from contextlib import contextmanager
from pathlib import Path

STAGES = {'implement', 'qa', 'review', 'awaiting_merge', 'awaiting_deployment', 'done'}
SUCCESS = {'implement': 'ready', 'qa': 'ready', 'review': 'approve'}


def usable_evidence(value):
    return isinstance(value, list) and bool(value) and all(isinstance(item, str) and bool(item.strip()) for item in value)


def same_pipeline(first, second):
    return first['issue'] == second['issue'] and first.get('pipeline_id', first['issue']) == second.get('pipeline_id', second['issue'])


class Store:
    def __init__(self, db_path):
        self.db_path = str(db_path)
        with self._connect() as db:
            db.execute('CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, spec TEXT NOT NULL, state TEXT NOT NULL, attempt INTEGER NOT NULL DEFAULT 0, result TEXT)')
            db.execute('CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY, task_id TEXT NOT NULL, event TEXT NOT NULL, error TEXT NOT NULL)')

    @contextmanager
    def leader_lock(self):
        """OS-released exclusive lock; never delete the lock file."""
        with open(self.db_path + '.leader', 'a+b') as handle:
            handle.seek(0)
            handle.write(b'0')
            handle.flush()
            handle.seek(0)
            try:
                if os.name == 'nt':
                    import msvcrt
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as exc:
                raise RuntimeError('another scheduler holds the queue leader lock') from exc
            try:
                yield
            finally:
                handle.seek(0)
                if os.name == 'nt':
                    msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    fcntl.flock(handle.fileno(), fcntl.LOCK_UN)

    def record_event_failure(self, task_id, event, error):
        with self._connect() as db:
            db.execute('INSERT INTO events(task_id,event,error) VALUES(?,?,?)', (task_id, event, error))

    def event_failures(self):
        with self._connect() as db:
            return [dict(r) for r in db.execute('SELECT * FROM events ORDER BY id')]

    @contextmanager
    def _connect(self):
        db = sqlite3.connect(self.db_path, timeout=30)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def validate_spec(spec):
        spec = dict(spec)
        if spec.get('authorized') is not True:
            raise ValueError('explicit authorization required')
        for key in ('task_id', 'role', 'issue', 'stage', 'cwd', 'prompt', 'agent_id'):
            if not isinstance(spec.get(key), str) or not spec[key].strip():
                raise ValueError('missing ' + key)
        if spec['stage'] not in SUCCESS:
            raise ValueError('only executable stages can be enqueued')
        pipeline = spec.get('pipeline_id', spec['issue'])
        if not isinstance(pipeline, str) or not pipeline.strip() or len(pipeline) > 120:
            raise ValueError('invalid pipeline_id')
        spec['pipeline_id'] = pipeline
        cwd = Path(spec['cwd'])
        if not cwd.is_absolute() or not cwd.is_dir():
            raise ValueError('cwd must be an existing absolute local directory')
        spec['cwd'] = os.path.normcase(str(cwd.resolve()))
        paths = spec.get('paths', [])
        if not isinstance(paths, list) or any(not isinstance(p, str) for p in paths):
            raise ValueError('paths must be a list')
        normalized = []
        for path in paths:
            if any(char in path for char in '*?[]'):
                raise ValueError('glob leases are unsupported; lease an explicit directory')
            candidate = (cwd / path).resolve()
            if not candidate.is_relative_to(cwd.resolve()):
                raise ValueError('writable path escapes cwd')
            normalized.append(os.path.normcase(str(candidate)))
        spec['paths'] = normalized
        if spec['stage'] in ('qa', 'review'):
            if paths or spec.get('read_only') is not True:
                raise ValueError('QA/review must be explicitly read-only')
            allowed = {'qa': {'qa_integration', 'qa_security', 'security_auditor'}, 'review': {'bank_reviewer', 'security_auditor'}}
            if spec['role'] not in allowed[spec['stage']]:
                raise ValueError('invalid independent role')
        deps = spec.get('dependencies', [])
        if not isinstance(deps, list) or any(not isinstance(d, str) for d in deps) or spec['task_id'] in deps:
            raise ValueError('invalid dependencies')
        spec['dependencies'] = deps
        if not isinstance(spec.get('heavy', False), bool):
            raise ValueError('heavy must be boolean')
        spec.setdefault('heavy', False)
        for key, default in (('priority', 0), ('max_attempts', 3)):
            value = spec.get(key, default)
            if type(value) is not int or (key == 'max_attempts' and not 1 <= value <= 10):
                raise ValueError('invalid ' + key)
            spec[key] = value
        return spec

    def enqueue(self, spec):
        return self.enqueue_batch([spec])[0]

    def enqueue_batch(self, specs):
        """Validate the entire manifest, then insert all or none atomically."""
        normalized = [self.validate_spec(spec) for spec in specs]
        results = []
        with self._connect() as db:
            db.execute('BEGIN IMMEDIATE')
            all_specs = {r['id']: json.loads(r['spec']) for r in db.execute('SELECT id,spec FROM tasks')}
            all_specs.update({spec['task_id']: spec for spec in normalized})
            incoming_ids = {spec['task_id'] for spec in normalized}
            for task in all_specs.values():
                if task['task_id'] not in incoming_ids and not incoming_ids.intersection(task.get('dependencies', [])):
                    continue
                for dependency in task.get('dependencies', []):
                    prior = all_specs.get(dependency)
                    if prior is not None and not same_pipeline(task, prior):
                        raise ValueError('dependency crosses issue or pipeline')
            for spec in normalized:
                old = db.execute('SELECT spec FROM tasks WHERE id=?', (spec['task_id'],)).fetchone()
                encoded = json.dumps(spec, sort_keys=True)
                if old:
                    old_spec = json.loads(old['spec'])
                    old_spec.setdefault('pipeline_id', old_spec['issue'])
                    if json.dumps(old_spec, sort_keys=True) != encoded:
                        raise ValueError('task ID already exists with different payload')
                    results.append(False)
                else:
                    db.execute('INSERT INTO tasks(id,spec,state) VALUES(?,?,?)', (spec['task_id'], encoded, 'queued'))
                    results.append(True)
        return results

    @staticmethod
    def _row(row):
        value = json.loads(row['spec'])
        value.setdefault('pipeline_id', value['issue'])
        value.update(state=row['state'], attempt=row['attempt'], result=json.loads(row['result']) if row['result'] else None)
        return value

    def snapshot(self):
        with self._connect() as db:
            return [self._row(r) for r in db.execute('SELECT * FROM tasks ORDER BY id')]

    def claim_available(self, limits=None):
        limits = limits or {}
        for key, value in limits.items():
            if key not in ('max_agents', 'max_heavy', 'external_agents', 'external_heavy') or type(value) is not int or value < 0:
                raise ValueError('limits must be known nonnegative integers')
        agents = min(6, int(limits.get('max_agents', 6))) - int(limits.get('external_agents', 0))
        heavy = min(2, int(limits.get('max_heavy', 2))) - int(limits.get('external_heavy', 0))
        claimed = []
        with self._connect() as db:
            db.execute('BEGIN IMMEDIATE')
            tasks = [self._row(r) for r in db.execute('SELECT * FROM tasks')]
            index = {t['task_id']: t for t in tasks}
            running = [t for t in tasks if t['state'] in ('running', 'interrupted')]
            agents -= len(running)
            heavy -= sum(t['heavy'] for t in running)
            for task in sorted(tasks, key=lambda t: (-int(t.get('priority', 0)), t['task_id'])):
                if agents <= 0:
                    break
                if task['state'] != 'queued' or task['attempt'] >= int(task.get('max_attempts', 3)):
                    continue
                dependencies = [index.get(d) for d in task['dependencies']]
                if any(d is None or d['state'] != 'succeeded' or not same_pipeline(task, d) for d in dependencies):
                    continue
                earlier = [t for t in tasks if same_pipeline(t, task) and t['stage'] == 'implement']
                if task['stage'] in ('qa', 'review') and (not earlier or any(t['state'] != 'succeeded' or t['agent_id'] == task['agent_id'] for t in earlier)):
                    continue
                if task['stage'] == 'review' and not any(same_pipeline(t, task) and t['stage'] == 'qa' and t['state'] == 'succeeded' and t['agent_id'] != task['agent_id'] for t in tasks):
                    continue
                if task['heavy'] and heavy <= 0:
                    continue
                if any(Path(task['cwd']).is_relative_to(Path(r['cwd'])) or Path(r['cwd']).is_relative_to(Path(task['cwd'])) or any(Path(a).is_relative_to(Path(b)) or Path(b).is_relative_to(Path(a)) for a in task['paths'] for b in r['paths']) for r in running):
                    continue
                db.execute("UPDATE tasks SET state='running',attempt=attempt+1 WHERE id=? AND state='queued'", (task['task_id'],))
                task.update(state='running', attempt=task['attempt'] + 1)
                claimed.append(task)
                running.append(task)
                agents -= 1
                heavy -= task['heavy']
        return claimed

    def complete(self, task_id, attempt, result):
        if not isinstance(result, dict):
            raise ValueError('structured result required')
        with self._connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM tasks WHERE id=?', (task_id,)).fetchone()
            if not row or row['state'] != 'running' or row['attempt'] != attempt:
                return False
            task = self._row(row)
            status = result.get('status')
            if status not in ('ready', 'blocked', 'request_changes', 'approve'):
                raise ValueError('invalid result status')
            evidenced = usable_evidence(result.get('evidence'))
            success = status == SUCCESS[task['stage']] and evidenced
            finding = status == 'request_changes' and evidenced
            provenance_matches = True
            if (success or finding) and task['stage'] in ('qa', 'review'):
                prior_tasks = [self._row(r) for r in db.execute('SELECT * FROM tasks')]
                required_stages = ('implement',) if task['stage'] == 'qa' else ('implement', 'qa')
                for stage in required_stages:
                    prior = [p for p in prior_tasks if same_pipeline(p, task) and p['stage'] == stage and p['state'] == 'succeeded']
                    if not prior or any(not p['result'].get('head_sha') or not p['result'].get('tree_sha') or p['result']['head_sha'] != result.get('head_sha') or p['result']['tree_sha'] != result.get('tree_sha') for p in prior):
                        success = False
                        provenance_matches = False
                        result = dict(result, reason='revision or tree fingerprint does not match independent prerequisites')
            state = 'succeeded' if success else 'blocked'
            result = dict(result)
            if not success and status in ('ready', 'approve'):
                result['reported_status'] = status
                result['status'] = 'blocked'
            if status == 'request_changes' and not evidenced:
                result.update(status='blocked', reported_status=status, error_code='FINDING_EVIDENCE_MISSING')
            if finding and not provenance_matches:
                result.update(status='blocked', reported_status=status, error_code='FINDING_REVISION_MISMATCH')
            actionable = not success and status == 'request_changes' and 'reported_status' not in result
            result['next_stage'] = {'implement': 'qa', 'qa': 'review', 'review': 'awaiting_merge'}[task['stage']] if success else ('implement' if actionable else task['stage'])
            if not success:
                result['route_to'] = 'implementation_owner' if actionable else 'operator'
                # Only returned failures may be retried. Rejected success claims
                # (revision/evidence fencing) remain blocked for inspection.
                result['auto_remediation'] = actionable
                if actionable and task['stage'] in ('qa', 'review'):
                    for candidate in db.execute('SELECT * FROM tasks').fetchall():
                        prior = self._row(candidate)
                        if same_pipeline(prior, task) and prior['stage'] == 'implement' and prior['state'] == 'succeeded':
                            db.execute("UPDATE tasks SET state='blocked',result=? WHERE id=?", (json.dumps({'status': 'request_changes', 'finding_task': task_id, 'finding': result, 'auto_remediation': result['auto_remediation'], 'next_stage': 'implement'}), prior['task_id']))
            if success and task['stage'] == 'implement' and attempt > 1:
                for candidate in db.execute('SELECT * FROM tasks').fetchall():
                    prior = self._row(candidate)
                    if same_pipeline(prior, task) and prior['stage'] in ('qa', 'review') and prior['state'] == 'succeeded':
                        db.execute("UPDATE tasks SET state='blocked',result=? WHERE id=?", (json.dumps({'status': 'blocked', 'auto_remediation': True, 'verification_invalidated': True, 'reason': 'implementation retried; independent verification must be resumed'}), prior['task_id']))
            db.execute('UPDATE tasks SET state=?,result=? WHERE id=?', (state, json.dumps(result), task_id))
        return True

    def block_preflight(self, task_id, result):
        """Quarantine an unsupported queued assignment without consuming an attempt."""
        with self._connect() as db:
            return db.execute("UPDATE tasks SET state='blocked',result=? WHERE id=? AND state='queued'",
                              (json.dumps(dict(result, auto_remediation=False, route_to='operator')), task_id)).rowcount

    def route_remediation(self):
        """Requeue only completed failures within the authorized attempt budget.

        Interrupted work and manually reconciled orphans never enter this path.
        Independent stages wait for all implementation owners to finish again.
        """
        with self._connect() as db:
            db.execute('BEGIN IMMEDIATE')
            tasks = [self._row(r) for r in db.execute('SELECT * FROM tasks')]
            for task in tasks:
                if (task['state'] != 'blocked' or not task['result']
                        or task['result'].get('auto_remediation') is not True
                        or (task['result'].get('status') != 'request_changes' and task['result'].get('verification_invalidated') is not True)
                        or (task['stage'] == 'implement' and 'finding' in task['result'] and task['result']['finding'].get('status') != 'request_changes')
                        or task['attempt'] >= task['max_attempts']):
                    continue
                owners = [p for p in tasks if same_pipeline(p, task) and p['stage'] == 'implement']
                if task['result'].get('status') == 'request_changes':
                    finding = task['result'].get('finding', task['result'])
                    if not isinstance(finding, dict) or not usable_evidence(finding.get('evidence')):
                        continue
                if any(p['state'] == 'interrupted' for p in tasks if same_pipeline(p, task)):
                    continue
                if task['stage'] != 'implement' and (not owners or any(p['state'] != 'succeeded' for p in owners)):
                    continue
                db.execute("UPDATE tasks SET state='queued' WHERE id=? AND state='blocked'", (task['task_id'],))

    def recover(self):
        with self._connect() as db:
            return db.execute("UPDATE tasks SET state='interrupted' WHERE state='running'").rowcount

    def reconcile(self, task_id, *, orphan_stopped=False):
        if orphan_stopped is not True:
            raise ValueError('explicit orphan-stopped confirmation required')
        with self._connect() as db:
            changed = db.execute("UPDATE tasks SET state='blocked',result=? WHERE id=? AND state='interrupted'", (json.dumps({'status': 'blocked', 'reason': 'orphan stopped; explicit resume required'}), task_id)).rowcount
            if not changed:
                raise ValueError('task is not interrupted')

    def resume(self, task_id):
        with self._connect() as db:
            row = db.execute('SELECT * FROM tasks WHERE id=?', (task_id,)).fetchone()
            if not row or row['state'] != 'blocked':
                raise ValueError('task is not resumable')
            task = self._row(row)
            if task['attempt'] >= int(task.get('max_attempts', 3)):
                raise ValueError('attempt limit reached')
            db.execute("UPDATE tasks SET state='queued' WHERE id=?", (task_id,))
