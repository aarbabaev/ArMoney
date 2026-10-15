"""Completion-driven scheduling; idle queues exit without polling."""
import asyncio
from .runner import PreflightError


class Scheduler:
    def __init__(self, store, runner, limits=None, transition=None):
        self.store, self.runner, self.limits = store, runner, limits
        self.transition = transition

    async def _notify(self, event, task, result=None):
        if self.transition:
            try:
                await self.transition(event, task, result)
            except Exception as exc:
                self.store.record_event_failure(task['task_id'], event, type(exc).__name__)

    async def run(self):
        with self.store.leader_lock():
            return await self._run_locked()

    async def _run_locked(self):
        active = {}
        try:
            while True:
                if hasattr(self.runner, 'preflight'):
                    for task in self.store.snapshot():
                        if task['state'] != 'queued':
                            continue
                        try:
                            self.runner.preflight(task)
                        except PreflightError as exc:
                            if self.store.block_preflight(task['task_id'], exc.result()):
                                await self._notify('preflight_blocked', dict(task, state='blocked'), exc.result())
                limits = self.limits() if callable(self.limits) else self.limits
                for task in self.store.claim_available(limits):
                    active[asyncio.create_task(self.runner(task))] = task
                    await self._notify('started', task)
                if not active:
                    return self.store.snapshot()
                finished, _ = await asyncio.wait(active, return_when=asyncio.FIRST_COMPLETED)
                for future in finished:
                    task = active.pop(future)
                    try:
                        result = future.result()
                    except PreflightError as exc:
                        result = exc.result()
                    except Exception as exc:
                        result = {'status': 'blocked', 'error_code': 'RUNNER_FAILURE', 'reason': type(exc).__name__}
                    try:
                        accepted = self.store.complete(task['task_id'], task['attempt'], result)
                    except (ValueError, TypeError):
                        result = {'status': 'blocked', 'reason': 'runner returned invalid structured result'}
                        accepted = self.store.complete(task['task_id'], task['attempt'], result)
                    if accepted:
                        persisted = next(t for t in self.store.snapshot() if t['task_id'] == task['task_id'] and t['attempt'] == task['attempt'])
                        await self._notify('completed', persisted, persisted['result'])
                        self.store.route_remediation()
        finally:
            for future in active:
                future.cancel()
            if active:
                await asyncio.gather(*active, return_exceptions=True)
                self.store.recover()
