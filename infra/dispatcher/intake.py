"""One-shot Project snapshot filtered by a local operator-approved catalog."""
import json
from pathlib import Path
import re

from .github import PROJECT, REPOSITORY
from .store import Store


def paused_ios_lease(spec):
    if spec['stage'] != 'implement':
        return False
    if spec['role'] == 'ios_owner':
        return True
    for lease in map(Path, spec['paths']):
        if 'ios' in [part.lower() for part in lease.parts]:
            return True
        # An ancestor directory lease grants writes to its entire subtree.
        # Exact shared-file leases are allowed while iOS remains paused.
        if lease.is_dir() and any(child.is_dir() for child in lease.rglob('ios')):
            return True
    return False


def project_snapshot(sink):
    query = '''query($cursor:String) { user(login:"aarbabaev") { projectV2(number:1) {
      id items(first:100,after:$cursor) { nodes {
        status: fieldValueByName(name:"Status") { ... on ProjectV2ItemFieldSingleSelectValue { name } }
        content { __typename ... on Issue { number state author { login }
          repository { nameWithOwner } labels(first:100) { nodes { name } pageInfo { hasNextPage } } } }
      } pageInfo { hasNextPage endCursor } }
    } } }'''
    nodes, cursor, seen = [], None, set()
    for _ in range(20):
        response = sink.request('/graphql', {'query': query, 'variables': {'cursor': cursor}})
        if response.get('errors'):
            raise RuntimeError('Project snapshot failed')
        project = response['data']['user']['projectV2']
        if project['id'] != PROJECT:
            raise ValueError('Unexpected Project')
        items = project['items']
        nodes.extend(items['nodes'])
        page = items['pageInfo']
        if not page['hasNextPage']:
            return nodes
        cursor = page['endCursor']
        if not cursor or cursor in seen:
            raise ValueError('Invalid Project pagination')
        seen.add(cursor)
    raise ValueError('Project snapshot exceeds pagination bound')


def import_project(catalog_path, store, sink, state):
    path = Path(catalog_path).resolve(strict=True)
    if path.stat().st_size > 1048576:
        raise ValueError('Catalog too large')
    catalog = json.loads(path.read_text(encoding='utf-8-sig'))
    if not isinstance(catalog, list) or len(catalog) > 200:
        raise ValueError('Catalog must contain at most 200 explicit assignments')
    groups, identifiers = {}, set()
    # Validate every catalog entry before reading GitHub or modifying the queue.
    for spec in catalog:
        normalized = Store.validate_spec(spec)
        issue = normalized['issue']
        if not re.fullmatch(r'[1-9][0-9]*', issue):
            raise ValueError('Catalog issue must be a repository issue number')
        for key in ('task_id', 'agent_id'):
            if not re.fullmatch(r'[A-Za-z0-9_.:-]{1,120}', normalized[key]):
                raise ValueError('Invalid catalog identifier')
        if normalized['task_id'] in identifiers:
            raise ValueError('Duplicate catalog task ID')
        identifiers.add(normalized['task_id'])
        assigned = Path(normalized['cwd'])
        if state == assigned or state.is_relative_to(assigned):
            raise ValueError('State is inside catalog checkout')
        groups.setdefault(issue, []).append(normalized)
    items = project_snapshot(sink)
    candidates = {}
    for item in items:
        content = item.get('content') or {}
        if content.get('__typename') != 'Issue' or content.get('repository', {}).get('nameWithOwner') != REPOSITORY:
            continue
        number = str(content.get('number'))
        reason = None
        if content.get('state') != 'OPEN' or (content.get('author') or {}).get('login') != 'aarbabaev':
            reason = 'not_open_owner_issue'
        elif (item.get('status') or {}).get('name') not in (None, '', 'Backlog', 'Ready'):
            reason = 'status_ineligible'
        labels = content.get('labels') or {}
        if labels.get('pageInfo', {}).get('hasNextPage'):
            reason = 'labels_incomplete'
        if any(label.get('name', '').lower() in {'hold', 'paused', 'on hold', 'on-hold'} for label in labels.get('nodes', [])):
            reason = 'held_or_paused'
        if number in candidates:
            reason = 'duplicate_project_issue'
        candidates[number] = reason
    selected, eligible, skipped = [], [], []
    with store.leader_lock():
        existing = store.snapshot()
        for issue, specs in sorted(groups.items(), key=lambda pair: int(pair[0])):
            reason = candidates.get(issue, 'not_in_project')
            if any(paused_ios_lease(s) for s in specs):
                reason = 'ios_owner_pause'
            if any(t['task_id'] in {s['task_id'] for s in specs} for t in existing):
                reason = 'existing_task_id'
            elif any(t['issue'] == issue for t in existing):
                reason = 'existing_issue_generation'
            if reason:
                skipped.append({'issue': issue, 'reason': reason})
            else:
                eligible.append(issue)
                selected.extend(specs)
        store.enqueue_batch(selected)
    return {'eligible_issues': eligible, 'skipped': skipped, 'imported_tasks': len(selected), 'execution': 'not_started'}
