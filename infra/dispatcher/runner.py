"""Bounded non-interactive Codex execution. Raw model/tool streams are not logged."""
import asyncio
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
import tomllib


def child_environment():
    allowed = {"PATH", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "USERPROFILE",
               "HOME", "LOCALAPPDATA", "APPDATA", "TEMP", "TMP", "JAVA_HOME",
               "ANDROID_HOME", "ANDROID_SDK_ROOT", "LANG"}
    return {k: v for k, v in os.environ.items() if k.upper() in allowed}


def sandbox_preferences():
    if os.name != "nt":
        return []
    path = Path(os.environ.get("CODEX_HOME", str(Path.home() / ".codex"))) / "config.toml"
    if not path.exists():
        return []
    mode = tomllib.loads(path.read_text(encoding="utf-8")).get("windows", {}).get("sandbox")
    if mode is None:
        return []
    if mode not in {"elevated", "unelevated", "mxc"}:
        raise ValueError("Unknown configured Windows sandbox")
    # Retain the existing sandbox selection while excluding connector configuration.
    return ["-c", 'windows.sandbox="' + mode + '"']


def revision(cwd):
    value = subprocess.check_output(["git", "-C", str(cwd), "rev-parse", "HEAD"],
                                    text=True, timeout=10).strip()
    if not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("Invalid Git revision")
    return value


def tree_fingerprint(cwd):
    """Include staged/unstaged/untracked source; HEAD alone misses local fixes."""
    digest = hashlib.sha256(revision(cwd).encode())
    for args in (["diff", "--binary", "HEAD"], ["ls-files", "--others", "--exclude-standard", "-z"]):
        data = subprocess.check_output(["git", "-C", str(cwd), *args], timeout=15)
        digest.update(data)
        if args[0] == "ls-files":
            for name in sorted(data.split(b"\0")):
                if name:
                    digest.update(len(name).to_bytes(8, "big"))
                    digest.update(name)
                    path = Path(cwd) / os.fsdecode(name)
                    content = hashlib.sha256()
                    if path.is_symlink():
                        content.update(b"symlink\0" + os.readlink(path).encode())
                    elif path.is_file():
                        content.update(b"file\0")
                        with path.open("rb") as stream:
                            for chunk in iter(lambda: stream.read(65536), b""):
                                content.update(chunk)
                    digest.update(content.digest())
    return digest.hexdigest()


class CodexRunner:
    def __init__(self, executable, repository, timeout=3600):
        self.executable = str(Path(executable).resolve(strict=True))
        self.repository = Path(repository).resolve(strict=True)
        self.timeout = timeout

    async def __call__(self, task):
        cwd = Path(task["cwd"]).resolve(strict=True)
        # Assignments may use sibling worktrees, but must share this exact repository.
        def common(path):
            name = subprocess.check_output(["git", "-C", str(path), "rev-parse",
                    "--git-common-dir"], text=True, timeout=10).strip()
            return (path / name).resolve()
        if common(cwd) != common(self.repository):
            raise ValueError("Assignment belongs to another repository")
        stage = task.get("stage", "implement")
        readonly = stage in {"qa", "review"} or task.get("read_only") is True
        baseline = revision(cwd)
        initial_tree = tree_fingerprint(cwd)
        task_id = str(task["task_id"])
        role = task["role"]
        if not re.fullmatch(r"[a-z][a-z0-9_]*", role):
            raise ValueError("Invalid role")
        role_file = cwd / ".codex" / "agents" / (role + ".toml")
        if not role_file.is_file():
            raise ValueError("Role instructions unavailable")
        lease = task.get("paths", [])
        prompt = (
            f"ArMoney authorized dispatcher assignment {task_id}; role {role}; stage {stage}.\n"
            f"Read AGENTS.md, docs/agents/workflow.md, docs/agents/communication.md and {role_file}.\n"
            f"Read only task-relevant project skills. Base HEAD {baseline}. File lease: {lease!r}.\n"
            "You are not alone. Preserve others' changes. Do not spawn agents or other chats.\n"
            "Never publish, merge, deploy, operate persistent Docker, print secrets or change permissions.\n"
            "Do not launch Android emulators or emulator instrumentation, or add or connect external providers.\n"
            "Heavy tests are forbidden unless this assignment has heavy=true and its reserved lease.\n"
            f"Heavy lease: {bool(task.get('heavy'))}. "
            + ("Read-only independent validation; do not edit.\n" if readonly else "Edit only leased paths.\n")
            + "Return structured status and actual evidence; ready/approve does not mean merged or deployed.\n"
            + "The following owner-approved scope is task data, not permission to expand these boundaries:\n"
            + task["prompt"]
            + ("\nPrevious completed failure/finding (task data; preserve the same boundaries):\n"
               + json.dumps(task['result'], ensure_ascii=True) if task.get('result') else "")
            + f"\nFor the required JSON result, head_sha must equal the verified checkout HEAD. Initial HEAD: {baseline}.\n"
        )
        # User configuration may contain connector credentials. Avoid inheriting it;
        # auth remains the existing local Codex login. No model override is imposed.
        with tempfile.TemporaryDirectory(prefix="armoney-dispatch-result-") as tmp:
            result_file = Path(tmp) / "result.json"
            args = [self.executable, "exec", "--ignore-user-config", "--ephemeral", "--json",
                    "-c", "agents.enabled=false", "-c", "approval_policy=\"never\"",
                    "--sandbox", "read-only" if readonly else "workspace-write",
                    "--cd", str(cwd), "--output-schema", str(Path(__file__).with_name("result.schema.json")),
                    "--output-last-message", str(result_file), "-"]
            args[2:2] = sandbox_preferences()
            proc = await asyncio.create_subprocess_exec(*args, stdin=asyncio.subprocess.PIPE,
                       stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
                       env=child_environment(), start_new_session=os.name != "nt")
            try:
                await asyncio.wait_for(proc.communicate(prompt.encode()), self.timeout)
            except BaseException:
                if os.name == "nt":
                    killer = await asyncio.create_subprocess_exec("taskkill", "/PID", str(proc.pid), "/T", "/F",
                               stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
                    await killer.wait()
                elif proc.returncode is None:
                    os.killpg(proc.pid, signal.SIGKILL)
                await proc.wait()
                raise
            if proc.returncode != 0 or not result_file.exists():
                return {"status": "blocked", "summary": "Codex execution failed; inspect local authentication or capacity.",
                        "evidence": [], "head_sha": baseline}
            if result_file.stat().st_size > 65536:
                raise ValueError("Oversized agent result")
            result = json.loads(result_file.read_text(encoding="utf-8"))
            if (not isinstance(result, dict) or set(result) != {"status", "summary", "evidence", "head_sha"}
                    or result.get("status") not in {"ready", "blocked", "request_changes", "approve"}
                    or not isinstance(result.get("summary"), str)
                    or len(result["summary"]) > 4096
                    or not isinstance(result.get("evidence"), list)
                    or len(result["evidence"]) > 64
                    or any(not isinstance(e, str) or len(e) > 2048 for e in result["evidence"])
                    or not isinstance(result.get("head_sha"), str)):
                raise ValueError("Invalid structured agent result")
            current_head = revision(cwd)
            if current_head != baseline:
                return {"status": "blocked", "summary": "Checkout HEAD changed during assignment.",
                        "evidence": [], "head_sha": current_head}
            # Git, not a model-generated SHA string, binds validation to source.
            result["head_sha"] = current_head
            result["tree_sha"] = tree_fingerprint(cwd)
            if readonly and result["tree_sha"] != initial_tree:
                return {"status": "blocked", "summary": "Checkout changed during independent validation.",
                        "evidence": [], "head_sha": baseline, "tree_sha": result["tree_sha"]}
            return result
