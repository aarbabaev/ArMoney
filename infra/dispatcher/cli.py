"""Local, explicit queue control. No timer, listener, or polling loop."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from .store import Store
from .scheduler import Scheduler
from .runner import CodexRunner
from .github import GitHubSink
from .intake import import_project


def private_state(path):
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes
        sid = subprocess.check_output(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                  "[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value"], text=True, timeout=15).strip()
        if not re.fullmatch(r"S-1-[0-9-]+", sid):
            raise ValueError("Cannot identify state owner")
        api = ctypes.WinDLL("advapi32", use_last_error=True)
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        convert = api.ConvertStringSecurityDescriptorToSecurityDescriptorW
        convert.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, ctypes.POINTER(ctypes.c_void_p), ctypes.c_void_p]
        convert.restype = wintypes.BOOL
        get = api.GetSecurityDescriptorDacl
        get.argtypes = [ctypes.c_void_p, ctypes.POINTER(wintypes.BOOL), ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(wintypes.BOOL)]
        get.restype = wintypes.BOOL
        set_acl = api.SetNamedSecurityInfoW
        set_acl.argtypes = [wintypes.LPWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.c_void_p,
                           ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
        set_acl.restype = wintypes.DWORD
        kernel.LocalFree.argtypes = [ctypes.c_void_p]
        for target in [path, *path.iterdir()]:
            if target.is_symlink() or (target != path and target.is_dir()):
                raise ValueError("State must be a dedicated directory without links or nested directories")
            descriptor = ctypes.c_void_p()
            flags = "OICI" if target == path else ""
            if not convert(f"D:P(A;{flags};FA;;;{sid})", 1, ctypes.byref(descriptor), None):
                raise ctypes.WinError(ctypes.get_last_error())
            try:
                dacl = ctypes.c_void_p()
                present, defaulted = wintypes.BOOL(), wintypes.BOOL()
                if not get(descriptor, ctypes.byref(present), ctypes.byref(dacl), ctypes.byref(defaulted)):
                    raise ctypes.WinError(ctypes.get_last_error())
                code = set_acl(str(target), 1, 0x80000004, None, None, dacl, None)
                if code:
                    raise ctypes.WinError(code)
            finally:
                kernel.LocalFree(descriptor)
    else:
        path.chmod(0o700)


def load_manifest(path, store, sink=None, state=None):
    path = Path(path).resolve(strict=True)
    if path.stat().st_size > 1048576:
        raise ValueError("Manifest too large")
    specs = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(specs, list) or len(specs) > 200:
        raise ValueError("Manifest must contain at most 200 authorized assignments")
    for spec in specs:
        if state is not None:
            assigned = Path(spec.get("cwd", "")).resolve(strict=True)
            if state == assigned or state.is_relative_to(assigned):
                raise ValueError("State is inside an assigned checkout")
        for key in ("task_id", "agent_id"):
            if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,120}", str(spec.get(key, ""))):
                raise ValueError("Invalid assignment identifier")
        if sink and str(spec.get("issue", "")).isdigit():
            sink.verify_issue(spec["issue"])
    store.enqueue_batch(specs)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True, help="Private state directory outside all checkouts")
    sub = parser.add_subparsers(dest="command", required=True)
    run = sub.add_parser("run")
    run.add_argument("--repository", required=True)
    run.add_argument("--codex", required=True)
    run.add_argument("--manifest")
    run.add_argument("--github", action="store_true")
    run.add_argument("--status-issue", type=int)
    run.add_argument("--max-agents", type=int, choices=range(1, 7), default=6)
    run.add_argument("--external-agents", type=int, choices=range(7), default=0)
    run.add_argument("--external-heavy", type=int, choices=range(3), default=2)
    run.add_argument("--timeout", type=int, default=3600)
    sub.add_parser("status")
    intake = sub.add_parser('import-project', help='One Project snapshot filtered by an operator-approved local catalog; does not run tasks')
    intake.add_argument('--catalog', required=True)
    resume = sub.add_parser("resume")
    resume.add_argument("task_id")
    reconcile = sub.add_parser("reconcile")
    reconcile.add_argument("task_id")
    reconcile.add_argument("--orphan-stopped", action="store_true", required=True)
    sub.add_parser("stop")
    args = parser.parse_args(argv)
    state = Path(args.state).resolve()
    if args.command == "run":
        repo = Path(args.repository).resolve(strict=True)
        if state == repo or state.is_relative_to(repo):
            raise ValueError("State must be outside repository")
        if not 1 <= args.timeout <= 86400:
            raise ValueError("Timeout out of range")
    state.mkdir(parents=True, exist_ok=True)
    private_state(state)
    store = Store(state / "queue.sqlite")
    for task in store.snapshot():
        assigned = Path(task["cwd"]).resolve()
        if state == assigned or state.is_relative_to(assigned):
            raise ValueError("State is inside a persisted assignment checkout")
    if args.command == "stop":
        (state / "STOP").touch()
        return 0
    if args.command == 'import-project':
        print(json.dumps(import_project(args.catalog, store, GitHubSink(state), state), indent=2))
        return 0
    if args.command == "resume":
        store.resume(args.task_id)
        return 0
    if args.command == "reconcile":
        store.reconcile(args.task_id, orphan_stopped=args.orphan_stopped)
        return 0
    if args.command == "run":
        if (state / "STOP").exists():
            raise ValueError("STOP marker present; explicit restart required")
        sink = GitHubSink(state) if args.github else None
        capacity = {"max_agents": args.max_agents, "external_agents": args.external_agents,
                    "external_heavy": args.external_heavy}
        async def event(kind, task, result=None):
            print(json.dumps({"event": kind, "task": task["task_id"], "stage": task["stage"],
                              "state": task["state"]}), flush=True)
            if sink:
                await sink(kind, task, result)
                if args.status_issue:
                    sink.summary(args.status_issue, store.snapshot(), capacity, 'event observed')
        def limits():
            return {"max_agents": 0 if (state / "STOP").exists() else args.max_agents,
                    "max_heavy": 2, "external_agents": args.external_agents,
                    "external_heavy": args.external_heavy}
        with store.leader_lock():
            # A previous writer may still be alive: recovery quarantines its leases.
            store.recover()
            if args.manifest:
                load_manifest(args.manifest, store, sink, state)
            if sink:
                try:
                    sink.flush()  # Retry durable deliveries even when no task can run.
                    if args.status_issue:
                        sink.summary(args.status_issue, store.snapshot(), capacity, 'initialized')
                except Exception as exc:
                    store.record_event_failure('dispatcher', 'startup_reporting', type(exc).__name__)
            scheduler = Scheduler(store, CodexRunner(args.codex, repo, args.timeout), limits, event)
            (state / "READY.json").write_text(json.dumps({"ready": True, "pid": os.getpid()}), encoding="utf-8")
            asyncio.run(scheduler._run_locked())
        if sink and args.status_issue:
            try:
                sink.summary(args.status_issue, store.snapshot(), capacity, 'idle; exiting')
            except Exception as exc:
                store.record_event_failure('dispatcher', 'final_reporting', type(exc).__name__)
    # Do not expose prompts, profile data, raw model messages or tokens in status.
    rows = [{k: task.get(k) for k in ("task_id", "issue", "role", "agent_id", "stage", "state", "attempt")}
            for task in store.snapshot()]
    report = {"tasks": rows, "transition_failures": store.event_failures()}
    if args.command == 'run' and sink:
        report['pending_github_deliveries'] = sink.pending()
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print("Dispatcher stopped: " + type(error).__name__ + ". Queue retained for inspection.", file=sys.stderr)
        sys.exit(1)
