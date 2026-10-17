"""Validate a saved backup before fresh isolated restore checks; no live target option.

Archives execute SQL during restoration. Use only trusted operator-created synthetic
archives. Checksums establish integrity, not authenticity or safe SQL execution.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import sys

spec = importlib.util.spec_from_file_location('backup', Path(__file__).with_name('backup-local-baseline.py'))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)


def preflight(directory):
    b.require(not directory.is_symlink(), 'Linked archive directory rejected')
    directory = b.outside_repo(directory)
    b.require(directory.is_dir() and not directory.is_symlink(), 'Invalid archive directory')
    manifest_path = directory / 'manifest.json'
    b.require(manifest_path.is_file() and not manifest_path.is_symlink(), 'Missing or linked manifest')
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    b.require(isinstance(manifest, dict), 'Invalid manifest object')
    b.require(type(manifest.get('format')) is int and manifest['format'] == 1 and manifest.get('project') == b.PROJECT
              and manifest.get('status') == 'database_restore_verified', 'Incomplete or unsupported backup')
    records = manifest.get('databases')
    b.require(isinstance(records, list) and records, 'Missing database inventory')
    services = set()
    for record in records:
        b.require(isinstance(record, dict), 'Invalid database record')
        service = record['service']
        b.require(isinstance(service, str) and b.db_spec(service) is not None
                  and service not in services, 'Unsupported or duplicate database')
        services.add(service)
        b.require((record['database'], record['user']) == b.db_spec(service), 'Wrong database target')
        b.require(record['archive'] == service + '.dump', 'Archive path rejected')
        archive = directory / record['archive']
        b.require(archive.is_file() and not archive.is_symlink(), 'Missing or linked archive')
        b.require(type(record['bytes']) is int and record['bytes'] > 0
                  and archive.stat().st_size == record['bytes'], 'Archive size mismatch')
        b.require(re.fullmatch(r'[0-9a-f]{64}', record['sha256']) is not None
                  and b.checksum(archive) == record['sha256'], 'Archive checksum mismatch')
        b.require(re.fullmatch(r'sha256:[0-9a-f]{64}', record['image_id']) is not None, 'Invalid image ID')
        b.require(type(record['expected_fixture_count']) is int
                  and 1 <= record['expected_fixture_count'] <= 900, 'Invalid fixture count')
        b.require(isinstance(record['relations'], dict) and isinstance(record['metrics'], dict),
                  'Missing verification metrics')
        relations = record['relations']
        b.require(set(relations) == {'tables', 'indexes', 'sequences'} and
                  all(type(value) is int and value >= 0 for value in relations.values()),
                  'Invalid relation inventory')
        rows = record['metrics'].get('table_rows')
        fingerprints = record['metrics'].get('table_fingerprints')
        b.require(isinstance(rows, dict) and bool(rows) and isinstance(fingerprints, dict)
                  and rows.keys() == fingerprints.keys(), 'Missing table continuity evidence')
        b.require(relations['tables'] == len(rows), 'Table inventory and continuity evidence differ')
        b.require(all(isinstance(table, str) and type(count) is int and count >= 0
                      for table, count in rows.items()), 'Invalid table counts')
        b.require(all(isinstance(digest, str) and re.fullmatch(r'[0-9a-f]{32}', digest)
                      for digest in fingerprints.values()), 'Invalid table fingerprints')
    b.require(b.DBS.keys() <= services, 'Missing core database')
    optional = manifest.get('optional_database_coverage', [])
    b.require(isinstance(optional, list) and all(isinstance(s, str) for s in optional)
              and len(optional) == len(set(optional)) and set(optional) <= b.OPTIONAL_DBS.keys()
              and set(optional) == b.OPTIONAL_DBS.keys() & services, 'Missing optional database coverage')
    coverage = manifest['user_shard_coverage']
    b.require(isinstance(coverage, dict), 'Invalid shard coverage')
    for key in ('included', 'configured', 'directory'):
        values = coverage.get(key)
        b.require(isinstance(values, list) and all(isinstance(value, str) for value in values),
                  'Invalid shard coverage list')
        b.require(len(values) == len(set(values)), 'Duplicate shard coverage')
    included = {s.removeprefix('user-shard-') for s in services if s.startswith('user-shard-')}
    b.require(set(coverage['included']) == included and set(coverage['configured']) <= included
              and set(coverage['directory']) <= included | {'primary'}, 'Missing shard coverage')
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    records = preflight(args.directory)
    if not args.apply:
        print('PASS: offline archive preflight; no Docker access')
        return
    b.local_daemon()
    for record in records:
        b.restore_verify(args.directory.resolve() / record['archive'], record)
    print('PASS: fresh isolated database restores; application acceptance remains required')


if __name__ == '__main__':
    try:
        main()
    except (b.Blocked, OSError, ValueError, KeyError, TypeError):
        print('Restore blocked; diagnostic details withheld', file=sys.stderr)
        sys.exit(1)
