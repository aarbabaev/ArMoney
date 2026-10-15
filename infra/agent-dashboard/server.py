"""Loopback-only, read-only local agent observations. No GitHub credentials."""
import argparse
import datetime as dt
import json
from pathlib import Path
import sqlite3
import subprocess
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def timestamp():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def processes(record):
    pid = int(record['pid'])
    command = ('$p=Get-Process -Id '+str(pid)+' -ErrorAction SilentlyContinue; '
               '$c=Get-CimInstance Win32_Process -Filter "ProcessId = '+str(pid)+'"; '
               'if($p -and $c){@{name=$p.ProcessName;exe=$c.ExecutablePath;command=$c.CommandLine;started=$p.StartTime.ToUniversalTime().ToString("o");'
               'children=@(Get-CimInstance Win32_Process | Where-Object {$_.ParentProcessId -eq '+str(pid)+
               ' -and $_.Name -eq "codex.exe"}).Count}|ConvertTo-Json -Compress}')
    out = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', command],
                         capture_output=True, text=True, timeout=8, check=True).stdout.strip()
    if not out:
        return False, 0
    observed = json.loads(out)
    actual = dt.datetime.fromisoformat(observed['started'].replace('Z', '+00:00'))
    expected = dt.datetime.fromisoformat(record['start_time'].replace('Z', '+00:00'))
    normalized = observed['command'].lower().replace('\\', '/')
    alive = (observed['name'].lower() == 'python'
             and abs((actual-expected).total_seconds()) < 0.01
             and Path(observed['exe']).resolve() == Path(record['python']).resolve()
             and '-m infra.dispatcher.cli' in normalized
             and str(record['state']).lower().replace('\\', '/') in normalized)
    return alive, int(observed['children']) if alive else 0


def snapshot(state, process_probe=processes):
    warnings = []
    archives = {}
    try:
        raw_archives = json.loads((state/'archived-tasks.json').read_text(encoding='utf-8-sig'))
        if not isinstance(raw_archives, dict):
            raise ValueError('Invalid archive mapping')
        for task_id, entry in raw_archives.items():
            if not isinstance(task_id, str) or not isinstance(entry, dict):
                raise ValueError('Invalid archive entry')
            reason, archived_at = entry['reason'], entry['archived_at']
            if not isinstance(reason, str) or not reason.strip() or len(reason) > 1000:
                raise ValueError('Invalid archive reason')
            if not isinstance(archived_at, str):
                raise ValueError('Invalid archive timestamp')
            observed_archive = dt.datetime.fromisoformat(archived_at.replace('Z', '+00:00'))
            if observed_archive.utcoffset() is None:
                raise ValueError('Archive timestamp requires timezone')
        archives = raw_archives
    except FileNotFoundError:
        pass  # Optional operator annotation; no automatic archiving.
    except (OSError, ValueError, KeyError, TypeError):
        warnings.append('Archive annotations unavailable or invalid; all queue attempts remain current.')
    native = {'observed_at': None, 'stale': True, 'agents': []}
    try:
        raw = json.loads((state/'native-agents.json').read_text(encoding='utf-8-sig'))
        observed = dt.datetime.fromisoformat(raw['observed_at'].replace('Z', '+00:00'))
        age = (dt.datetime.now(dt.timezone.utc)-observed).total_seconds()
        native = {'observed_at': raw['observed_at'], 'stale': not 0 <= age <= 90,
                  'agents': [{k: str(a.get(k, ''))[:200] for k in ('id','role','state','task')}
                             for a in raw['agents'][:100]]}
    except (OSError, ValueError, KeyError, TypeError):
        warnings.append('Native chat observations are unavailable.')
    if native['stale']:
        warnings.append('Native chat activity is unknown until the orchestrator refreshes its snapshot.')
    dispatcher = {'alive': False, 'agents': [], 'history': [], 'error': None}
    try:
        record = json.loads((state/'process.json').read_text(encoding='utf-8-sig'))
        alive, children = process_probe(record)
        dispatcher['alive'] = alive
        db = sqlite3.connect((state/'queue.sqlite').resolve().as_uri()+'?mode=ro', uri=True, timeout=2)
        try:
            rows = db.execute('SELECT spec,state FROM tasks').fetchall()
        finally:
            db.close()
        for encoded, status in rows:
            spec = json.loads(encoded)
            if spec['task_id'].startswith('dispatcher-live-'):
                continue  # Historical protocol probes are not product workers.
            agent = {'id': spec['agent_id'], 'task_id': spec['task_id'], 'role': spec['role'],
                'issue': spec['issue'], 'stage': spec['stage'],
                'state': status if status != 'running' or alive else 'unknown'}
            if spec['task_id'] in archives:
                agent.update(state=status, reason=archives[spec['task_id']]['reason'],
                             archived_at=archives[spec['task_id']]['archived_at'])
                dispatcher['history'].append(agent)
            else:
                dispatcher['agents'].append(agent)
        running = sum(a['state'] == 'running' for a in dispatcher['agents'])
        confirmed = min(running, children) if alive else 0
        if running != confirmed:
            warnings.append('Queue running claims exceed observed live CLI children; activity is uncertain.')
    except (OSError, ValueError, KeyError, TypeError, sqlite3.Error, subprocess.SubprocessError):
        dispatcher['error'] = 'Dispatcher observations unavailable; no activity inferred.'
        confirmed = 0
        warnings.append(dispatcher['error'])
    agents = native['agents']+dispatcher['agents']
    counts = {'total': len({('native',a['id']) for a in native['agents']})+
                       len({('cli',a['id']) for a in dispatcher['agents']+dispatcher['history']}),
              'active': confirmed+(0 if native['stale'] else sum(a['state']=='running' for a in native['agents'])),
              'queued': sum(a['state']=='queued' for a in agents),
              'blocked': sum(a['state'] in ('blocked','interrupted') for a in agents)}
    return {'observed_at':timestamp(), 'native':native, 'dispatcher':dispatcher,
            'counts':counts, 'warnings':warnings}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--state', required=True, type=Path)
    parser.add_argument('--port', type=int, default=8765)
    args = parser.parse_args()
    state = args.state.resolve(strict=True)
    document = Path(__file__).with_name('index.html').read_bytes()
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass
        def do_GET(self):
            if self.headers.get('Host') not in (f'127.0.0.1:{args.port}', f'localhost:{args.port}'):
                self.send_error(403); return
            if self.path == '/':
                data, content = document, 'text/html; charset=utf-8'
            elif self.path == '/api/status':
                data, content = json.dumps(snapshot(state)).encode(), 'application/json'
            else:
                self.send_error(404); return
            self.send_response(200)
            self.send_header('Content-Type', content)
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'")
            self.send_header('Content-Length', str(len(data)))
            self.end_headers(); self.wfile.write(data)
    ThreadingHTTPServer(('127.0.0.1', args.port), Handler).serve_forever()


if __name__ == '__main__':
    main()
