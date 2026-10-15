"""GitHub transitions, deduplicated by task/attempt; no periodic polling."""
import json
import os
import re
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
import urllib.request

REPOSITORY = "aarbabaev/ArMoney"
PROJECT = "PVT_kwHOACy5Oc4BmqLK"
STATUS_FIELD = "PVTSSF_lAHOACy5Oc4BmqLKzhlSRS4"
STATUS = {"In Progress": "47fc9ee4", "Review": "3ac1d1f7", "Blocked": "df00b6eb"}


def token():
    value = os.environ.get("GITHUB_PAT_TOKEN")
    if not value and os.name == "nt":
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as key:
            value = winreg.QueryValueEx(key, "GITHUB_PAT_TOKEN")[0]
    if not value:
        raise RuntimeError("GitHub token unavailable")
    return value


class GitHubSink:
    def __init__(self, state):
        self.db = str(state / "github-outbox.sqlite")
        self._token = token()
        with self.connection() as db:
            db.execute("CREATE TABLE IF NOT EXISTS deliveries (key TEXT PRIMARY KEY, payload TEXT NOT NULL, sent INTEGER NOT NULL DEFAULT 0)")
            db.execute("CREATE TABLE IF NOT EXISTS summaries (issue TEXT PRIMARY KEY, body TEXT NOT NULL, sent INTEGER NOT NULL DEFAULT 0)")

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.db)
        try:
            with db:
                yield db
        finally:
            db.close()

    def request(self, endpoint, data=None, method=None):
        if not endpoint.startswith("/repos/" + REPOSITORY + "/") and endpoint != "/graphql":
            raise ValueError("Unexpected GitHub destination")
        request = urllib.request.Request("https://api.github.com" + endpoint,
                    data=None if data is None else json.dumps(data).encode(),
                    headers={"Authorization": "Bearer " + self._token,
                             "Accept": "application/vnd.github+json", "Content-Type": "application/json"}, method=method)
        # Fixed official endpoint; never follow redirects carrying credentials.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args, **kwargs):
                return None
        with urllib.request.build_opener(NoRedirect).open(request, timeout=30) as response:
            content = response.read(1048577)
        if len(content) > 1048576:
            raise ValueError("Oversized GitHub response")
        return json.loads(content)

    def verify_issue(self, number):
        issue = self.request(f"/repos/{REPOSITORY}/issues/{number}")
        if issue.get("pull_request") or issue.get("state") != "open" or issue.get("user", {}).get("login") != "aarbabaev":
            raise ValueError("Issue is not an open owner-created task")
        return issue

    async def __call__(self, event, task, result=None):
        issue = str(task["issue"])
        if not re.fullmatch(r"[1-9][0-9]*", issue):
            return  # Local proof assignments never mutate GitHub.
        status = "Review" if task["stage"] in {"qa", "review"} or event == "completed" else "In Progress"
        if event == 'preflight_blocked':
            status = 'Blocked'
        elif event == "completed" and task.get("state") != "succeeded":
            status = "In Progress" if (result or {}).get("status") == "request_changes" else "Blocked"
        # Never map agent approve to Done/Awaiting merge: required CI and verified merge are external gates.
        key = f"{task['task_id']}:{task['attempt']}:{event}"
        payload = {"issue": issue, "key": key, "status": status, "stage": task["stage"],
                   "role": task["role"], "agent_id": task["agent_id"],
                   "event": event, "time": datetime.now(timezone.utc).isoformat()}
        with self.connection() as db:
            db.execute("INSERT OR IGNORE INTO deliveries(key,payload) VALUES(?,?)", (key, json.dumps(payload)))
        self.flush()

    def flush(self):
        with self.connection() as db:
            rows = db.execute("SELECT key,payload FROM deliveries WHERE sent=0 ORDER BY rowid").fetchall()
        for key, encoded in rows:
            value = json.loads(encoded)
            self.verify_issue(value["issue"])
            marker = "<!-- armoney-dispatcher:" + key + " -->"
            found = False
            for page in range(1, 21):
                comments = self.request(f"/repos/{REPOSITORY}/issues/{value['issue']}/comments?per_page=100&page={page}")
                found = any(marker in comment.get("body", "") for comment in comments)
                if found or len(comments) < 100:
                    break
            else:
                raise RuntimeError("Comment history exceeds deduplication bound; retained for reconciliation")
            if not found:
                message = (f"Dispatcher: {value['event']}; stage={value['stage']}; role={value['role']}; "
                           f"runtime={value['agent_id']}; status={value['status']}; time={value.get('time', 'unknown')}.\n"
                           "Agent completion is evidence only; CI, merge and deployment remain separate gates.\n" + marker)
                self.request(f"/repos/{REPOSITORY}/issues/{value['issue']}/comments", {"body": message})
            self.update_board(value["issue"], value["status"])
            with self.connection() as db:
                db.execute("UPDATE deliveries SET sent=1 WHERE key=?", (key,))
        self.flush_summaries()

    def pending(self):
        with self.connection() as db:
            return db.execute('SELECT COUNT(*) FROM deliveries WHERE sent=0').fetchone()[0] + db.execute('SELECT COUNT(*) FROM summaries WHERE sent=0').fetchone()[0]

    def flush_summaries(self):
        with self.connection() as db:
            rows = db.execute('SELECT issue,body FROM summaries WHERE sent=0 ORDER BY issue').fetchall()
        for issue, body in rows:
            self.verify_issue(issue)
            self.request(f"/repos/{REPOSITORY}/issues/{issue}", {"body": body}, method="PATCH")
            with self.connection() as db:
                db.execute('UPDATE summaries SET sent=1 WHERE issue=? AND body=?', (issue, body))

    def update_board(self, issue, status):
        query = "query { user(login:\"aarbabaev\") { projectV2(number:1) { items(first:100) { nodes { id content { ... on Issue { number repository { nameWithOwner } } } } pageInfo { hasNextPage } } } } }"
        response = self.request("/graphql", {"query": query})
        if response.get("errors"):
            raise RuntimeError("Project read failed")
        items = response["data"]["user"]["projectV2"]["items"]
        matches = [n for n in items["nodes"] if n.get("content") and str(n["content"].get("number")) == issue
                   and n["content"].get("repository", {}).get("nameWithOwner") == REPOSITORY]
        if len(matches) != 1:
            raise RuntimeError("Project task mapping unavailable; transition retained for retry")
        mutation = "mutation($p:ID!,$i:ID!,$f:ID!,$s:String!) { updateProjectV2ItemFieldValue(input:{projectId:$p,itemId:$i,fieldId:$f,value:{singleSelectOptionId:$s}}) { projectV2Item { id } } }"
        response = self.request("/graphql", {"query": mutation, "variables": {
                "p": PROJECT, "i": matches[0]["id"], "f": STATUS_FIELD, "s": STATUS[status]}})
        if response.get("errors"):
            raise RuntimeError("Project transition rejected; retained for retry")

    def summary(self, issue, tasks, capacity=None, activity='observed'):
        if not re.fullmatch(r'[1-9][0-9]*', str(issue)):
            raise ValueError('Invalid status issue')
        capacity = capacity or {'max_agents': 6, 'external_agents': 0, 'external_heavy': 2}
        available = max(0, capacity['max_agents'] - capacity['external_agents'])
        running = [t for t in tasks if t["state"] == "running"]
        blocked = [t for t in tasks if t["state"] in {"blocked", "interrupted"}]
        queued = [t for t in tasks if t["state"] == "queued"]
        body = ("<!-- armoney-dispatcher-status -->\n"
                f"Last observed: {datetime.now(timezone.utc).isoformat()}; dispatcher observation: **{activity}**.\n\n"
                f"Observed active: **{len(running)} / {available}**; queued: **{len(queued)}**; blocked/interrupted: **{len(blocked)}**.\n\n"
                f"Configured agent limit: {capacity['max_agents']}; externally reserved agents: {capacity['external_agents']}; externally reserved heavy slots: {capacity['external_heavy']}.\n\n"
                "This is a timestamped observation, not a live heartbeat. If the dispatcher exits or crashes, this page cannot confirm current activity.\n\n"
                "This is the local dispatcher queue. Native agents in other chats and GitHub CI are separate. "
                "Heavy CI slots are reserved externally by default. Empty queues exit and do not poll.\n\n")
        for task in running + blocked + queued:
            body += f"- #{task['issue']} — {task['role']}, {task['stage']}, {task['state']}\n"
        with self.connection() as db:
            db.execute('INSERT INTO summaries(issue,body,sent) VALUES(?,?,0) ON CONFLICT(issue) DO UPDATE SET body=excluded.body,sent=0', (str(issue), body))
        self.flush_summaries()
