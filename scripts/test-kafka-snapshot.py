"""Real Bash snapshot regressions on isolated synthetic directories; no Docker.

Run with uid/gid 1000 on Linux/WSL: python3 -B scripts/test-kafka-snapshot.py.
Only a temporary copy of the helper has its fixed mount roots relocated.
"""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parents[1] / 'infra/kafka/snapshot.sh'
CLUSTER = 'A' * 22
NODE = 'kafka-broker-1'
PAYLOAD = b'synthetic acknowledged fixture'
FOREIGN_PAYLOAD = b'isolated synthetic foreign-owned Kafka fixture'


def fingerprint(tree):
    result = []
    for path in [tree, *sorted(tree.rglob('*'))]:
        info = path.lstat()
        value = os.readlink(path) if path.is_symlink() else path.read_bytes() if path.is_file() else None
        result.append((str(path.relative_to(tree)), info.st_mode, info.st_uid,
                       info.st_gid, info.st_nlink, value))
    return result


@unittest.skipUnless(os.name == 'posix' and hasattr(os, 'getuid'), 'Linux/WSL Bash fixtures required')
class SnapshotTests(unittest.TestCase):
    def setUp(self):
        self.assertEqual((1000, 1000), (os.getuid(), os.getgid()))
        self.temp = tempfile.TemporaryDirectory(prefix='armoney-kafka-security-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.source, self.archive, self.target = [self.root / name for name in ('source', 'archive', 'target')]
        for path in (self.source, self.archive, self.target):
            path.mkdir()
        (self.source / 'meta.properties').write_text(f'cluster.id={CLUSTER}\nnode.id=101\n')
        (self.source / 'topic-0').mkdir()
        self.log = self.source / 'topic-0/00000000000000000000.log'
        self.log.write_bytes(PAYLOAD)
        (self.source / 'empty-directory').mkdir()
        self.outside = self.root / 'outside-sentinel'
        self.outside.write_bytes(b'untouched synthetic sentinel')
        self.original_hash = hashlib.sha256(SOURCE.read_bytes()).hexdigest()
        self.script = self.root / 'snapshot.sh'
        text = SOURCE.read_text()
        helper = self.root / 'prepare-empty-storage.sh'
        helper_source = (SOURCE.parent / 'prepare-empty-storage.sh').read_text()
        text = text.replace('/opt/armoney/prepare-empty-storage.sh', str(helper))
        for name, replacement in (('source', self.source), ('archive', self.archive), ('target', self.target)):
            text = re.sub('/' + name + r'(?![A-Za-z0-9_-])', str(replacement), text)
            helper_source = re.sub('/' + name + r'(?![A-Za-z0-9_-])', str(replacement), helper_source)
        helper.write_text(helper_source)
        self.script.write_text(text)
        self.bundle = self.archive / NODE

    def tearDown(self):
        self.assertEqual(self.original_hash, hashlib.sha256(SOURCE.read_bytes()).hexdigest(), 'Production helper changed')
        self.assertEqual(b'untouched synthetic sentinel', self.outside.read_bytes())

    def command(self, mode, expected=None):
        args = ['bash', str(self.script), mode, NODE, CLUSTER]
        if mode == 'restore':
            args.append(self.digest if expected is None else expected)
        return subprocess.run(args, capture_output=True, text=True, timeout=10)

    def archive_ok(self):
        before = fingerprint(self.source)
        result = self.command('archive')
        self.assertEqual(0, result.returncode, result.stderr)
        self.digest = result.stdout.strip()
        self.assertRegex(self.digest, r'^[a-f0-9]{64}$')
        self.assertEqual(before, fingerprint(self.source))

    def reject_restore(self, mutate, expected=None):
        self.archive_ok()
        mutate()
        before_source, before_target = fingerprint(self.source), fingerprint(self.target)
        result = self.command('restore', expected)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertNotIn('RESTORE_VERIFIED', result.stdout)
        self.assertEqual(before_source, fingerprint(self.source))
        self.assertEqual(before_target, fingerprint(self.target))

    def reject_archive(self, mutate):
        mutate()
        before = fingerprint(self.source)
        result = self.command('archive')
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertEqual(before, fingerprint(self.source))
        self.assertEqual([], list(self.target.iterdir()))

    def test_round_trip_exact_content_and_empty_directory(self):
        self.archive_ok()
        result = self.command('restore')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('RESTORE_VERIFIED', result.stdout.strip())
        self.assertEqual(PAYLOAD, (self.target / self.log.relative_to(self.source)).read_bytes())
        self.assertTrue((self.target / 'empty-directory').is_dir())
        self.assertEqual(fingerprint(self.source), fingerprint(self.target))

    def test_snapshot_guard_and_command_failures_emit_static_stage_markers(self):
        metadata = self.source / 'meta.properties'
        metadata.write_text(f'cluster.id={CLUSTER}\nnode.id=102\n')
        result = self.command('archive')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('SNAPSHOT_FAILURE_SOURCE_IDENTITY', result.stderr)
        metadata.write_text(f'cluster.id={CLUSTER}\nnode.id=101\n')
        self.archive.chmod(0o500)
        try:
            result = self.command('archive')
            self.assertNotEqual(0, result.returncode)
            self.assertIn('SNAPSHOT_FAILURE_COPY', result.stderr)
        finally:
            self.archive.chmod(0o700)

    def test_extra_file(self):
        self.reject_restore(lambda: (self.bundle / 'data/extra.log').write_text('extra'))

    def test_extra_directory(self):
        self.reject_restore(lambda: (self.bundle / 'data/extra-directory').mkdir())

    def test_deleted_empty_directory(self):
        self.reject_restore(lambda: (self.bundle / 'data/empty-directory').rmdir())

    def test_missing_metadata(self):
        self.reject_restore(lambda: (self.bundle / 'data/meta.properties').unlink())

    def test_tampered_record(self):
        self.reject_restore(lambda: (self.bundle / 'data/topic-0/00000000000000000000.log').write_text('tampered'))

    def test_tampered_manifest(self):
        self.reject_restore(lambda: (self.bundle / 'manifest').write_text('node.id=102\n'))

    def test_tampered_checksum_inventory_traversal(self):
        self.reject_restore(lambda: (self.bundle / 'checksums').write_text('0' * 64 + '  ../../outside-sentinel\n'))

    def test_restore_symlink(self):
        self.reject_restore(lambda: (self.bundle / 'data/escape').symlink_to(self.outside))

    def test_restore_hardlink(self):
        self.reject_restore(lambda: os.link(self.bundle / 'data/meta.properties', self.bundle / 'data/linked'))

    def test_restore_hardlinked_manifest_is_rejected(self):
        def linked_manifest():
            manifest = self.bundle / 'manifest'
            twin = self.root / 'manifest-twin'
            twin.write_bytes(manifest.read_bytes())
            manifest.unlink()
            os.link(twin, manifest)
        self.reject_restore(linked_manifest)

    def test_restore_hardlinked_checksums_is_rejected(self):
        def linked_checksums():
            checksums = self.bundle / 'checksums'
            twin = self.root / 'checksums-twin'
            twin.write_bytes(checksums.read_bytes())
            checksums.unlink()
            os.link(twin, checksums)
        self.reject_restore(linked_checksums)

    def test_nonempty_target_preserved(self):
        self.reject_restore(lambda: (self.target / 'existing').write_text('preserve'))

    def test_wrong_metadata_preserved(self):
        self.reject_restore(lambda: (self.bundle / 'data/meta.properties').write_text(f'cluster.id={CLUSTER}\nnode.id=102\n'))

    def test_wrong_cluster_metadata(self):
        self.reject_restore(lambda: (self.bundle / 'data/meta.properties').write_text('cluster.id=' + 'B' * 22 + '\nnode.id=101\n'))

    def test_untrusted_manifest_digest(self):
        self.reject_restore(lambda: None, '0' * 64)

    def test_archive_symlink(self):
        self.reject_archive(lambda: (self.source / 'escape').symlink_to(self.outside))

    def test_archive_hardlink(self):
        self.reject_archive(lambda: os.link(self.log, self.source / 'linked'))

    def test_archive_unsafe_newline(self):
        self.reject_archive(lambda: (self.source / 'bad\npath').write_text('unsafe'))

    def test_archive_wrong_metadata(self):
        self.reject_archive(lambda: (self.source / 'meta.properties').write_text(f'cluster.id={CLUSTER}\nnode.id=102\n'))

    def test_archive_conflicting_duplicate_identity_is_rejected(self):
        self.reject_archive(lambda: (self.source / 'meta.properties').write_text(
            f'cluster.id={CLUSTER}\nnode.id=101\ncluster.id=' + 'B' * 22 + '\nnode.id=102\n'))

    def test_restore_duplicate_identity_with_recomputed_trusted_manifest(self):
        def duplicate():
            metadata = self.bundle / 'data/meta.properties'
            metadata.write_text(f'cluster.id={CLUSTER}\nnode.id=101\ncluster.id=' + 'B' * 22 + '\nnode.id=102\n')
            # Rebind both hashes so rejection proves identity validation, not
            # merely the outer checksum mismatch from changing the metadata.
            checksums = self.bundle / 'checksums'
            lines = checksums.read_text().splitlines()
            checksum = hashlib.sha256(metadata.read_bytes()).hexdigest()
            lines = [checksum + '  meta.properties' if line.endswith('  meta.properties') else line for line in lines]
            checksums.write_text('\n'.join(lines) + '\n')
            manifest = self.bundle / 'manifest'
            checksum = hashlib.sha256(checksums.read_bytes()).hexdigest()
            lines = [f'checksums.sha256={checksum}' if line.startswith('checksums.sha256=') else line
                     for line in manifest.read_text().splitlines()]
            manifest.write_text('\n'.join(lines) + '\n')
            self.digest = hashlib.sha256(manifest.read_bytes()).hexdigest()
        self.reject_restore(duplicate)

    def test_archive_duplicate_colon_identity(self):
        self.reject_archive(lambda: (self.source / 'meta.properties').write_text(
            f'cluster.id={CLUSTER}\nnode.id=101\nnode.id:102\n'))

    def test_archive_escaped_property_identity(self):
        self.reject_archive(lambda: (self.source / 'meta.properties').write_text(
            f'cluster.id={CLUSTER}\nnode.id=101\nnode\\.id=102\n'))

    def test_archive_special_file(self):
        self.reject_archive(lambda: os.mkfifo(self.source / 'named-pipe'))

    def test_invalid_source_owner_is_rejected_without_repair(self):
        # This optional fixture helper changes only the newly allocated file.
        # WSL supplies root without modifying the host or requiring sudo login.
        owned = self.source / 'wrong-owner'
        self.assertTrue(owned.resolve().is_relative_to(self.root))
        inherited = os.environ.get('_ARMONEY_KAFKA_SYNTHETIC_FOREIGN_FILE')
        if inherited:
            fixture = Path(inherited)
            self.assertEqual('foreign-owner', fixture.name)
            self.assertTrue(fixture.parent.name.startswith('armoney-kafka-foreign-'))
            self.assertFalse(fixture.is_symlink())
            self.assertEqual(FOREIGN_PAYLOAD, fixture.read_bytes())
            self.assertEqual((0, 0), (fixture.stat().st_uid, fixture.stat().st_gid))
            fixture.rename(owned)
        else:
            wsl = Path('/mnt/c/Windows/System32/wsl.exe')
            if not wsl.is_file():
                self.skipTest('Root-owned fixture requires root launcher or WSL helper')
            owned.write_bytes(FOREIGN_PAYLOAD)
            result = subprocess.run([str(wsl), '-u', 'root', '-e', 'chown', '0:0', str(owned)],
                                    capture_output=True, timeout=15)
            self.assertEqual(0, result.returncode, 'Cannot allocate root-owned disposable fixture')
        self.assertEqual(0, owned.stat().st_uid)
        self.reject_archive(lambda: None)
        self.assertEqual((0, 0), (owned.stat().st_uid, owned.stat().st_gid))


@unittest.skipUnless(os.name == 'posix', 'Linux/WSL Bash fixtures required')
class FreshFormatTests(unittest.TestCase):
    """Actual Bash format ordering; image ownership utilities are controlled stubs.

    These tests do not establish real image chgrp/Kafka compatibility. The
    separately leased pinned-image probe must verify those operations.
    """
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='armoney-kafka-format-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.data = self.root / 'data'
        self.data.mkdir()
        self.target = self.root / 'target'
        self.target.mkdir()
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.calls = self.root / 'calls'
        self.normalized = self.root / 'normalized'
        (self.root / 'cluster.id').write_text(CLUSTER)
        self.prepare = self.root / 'prepare.sh'
        self.prepare.write_text('exit 0\n')
        self.storage = self.root / 'storage.sh'
        self.storage.write_text('#!/usr/bin/env bash\nprintf "storage\\n" >> "$FORMAT_CALLS"\n')
        self.storage.chmod(0o755)
        source = (SOURCE.parent / 'format.sh').read_text()
        self.helper = self.root / 'prepare-empty-storage.sh'
        self.helper.write_text((SOURCE.parent / 'prepare-empty-storage.sh').read_text().replace(
            '/var/lib/kafka/data', str(self.data)).replace('/target', str(self.target)))
        for before, after in (
                ('/opt/armoney/prepare-empty-storage.sh', str(self.helper)),
                ('/opt/armoney/prepare-config.sh', str(self.prepare)),
                ('/var/lib/kafka/data', str(self.data)),
                ('/run/kafka-secrets/cluster.id', str(self.root / 'cluster.id')),
                ('/opt/kafka/bin/kafka-storage.sh', str(self.storage))):
            source = source.replace(before, after)
        self.script = self.root / 'format.sh'
        self.script.write_text(source)
        (self.bin / 'stat').write_text('''#!/usr/bin/env bash
printf 'stat\n' >> "$FORMAT_CALLS"
if [[ "$FORMAT_OWNER" == 1000:0 && -e "$FORMAT_NORMALIZED" ]]; then
  printf '1000:1000\n'
else
  printf '%s\n' "$FORMAT_OWNER"
fi
''')
        (self.bin / 'chgrp').write_text('''#!/usr/bin/env bash
printf 'chgrp\n' >> "$FORMAT_CALLS"
[[ $# == 2 && "$1" == 1000 && "$2" == "$FORMAT_DATA" ]] || exit 3
[[ "${FORMAT_FAIL_CHGRP:-}" != true ]] || exit 1
touch "$FORMAT_NORMALIZED"
''')
        for path in self.bin.iterdir():
            path.chmod(0o755)

    def execute(self, owner='1000:0', fail=False):
        environment = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                           FORMAT_OWNER=owner, FORMAT_CALLS=str(self.calls), FORMAT_DATA=str(self.data),
                           FORMAT_NORMALIZED=str(self.normalized), FORMAT_FAIL_CHGRP=str(fail).lower(),
                           KAFKA_NODE_ROLE='broker', KAFKA_NODE_ID='101')
        return subprocess.run(['bash', str(self.script)], capture_output=True, text=True,
                              timeout=10, env=environment)

    def operations(self):
        return self.calls.read_text().splitlines() if self.calls.exists() else []

    def test_fresh_image_root_group_normalizes_once_nonrecursively_before_format(self):
        result = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['stat', 'chgrp', 'stat', 'storage'], self.operations())
        self.assertEqual([], list(self.data.iterdir()))

    def test_already_correct_empty_root_never_changes_group(self):
        result = self.execute('1000:1000')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['stat', 'stat', 'storage'], self.operations())

    def test_existing_matching_metadata_returns_without_ownership_or_storage_operations(self):
        (self.data / 'meta.properties').write_text(f'cluster.id={CLUSTER}\nnode.id=101\n')
        before = fingerprint(self.data)
        result = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], self.operations())
        self.assertEqual(before, fingerprint(self.data))

    def test_nonempty_unrecognized_data_and_mismatched_metadata_refuse_untouched(self):
        metadata = self.data / 'meta.properties'
        metadata.write_text(f'cluster.id={CLUSTER}\nnode.id=102\n')
        for name in ('meta.properties', 'unexpected-log'):
            if name != 'meta.properties':
                metadata.rename(self.data / name)
            before = fingerprint(self.data)
            result = self.execute()
            self.assertNotEqual(0, result.returncode)
            self.assertEqual([], self.operations())
            self.assertEqual(before, fingerprint(self.data))

    def test_foreign_empty_root_owner_or_group_never_normalizes_or_formats(self):
        for owner in ('0:0', '1001:1000', '1000:999'):
            if self.calls.exists():
                self.calls.unlink()
            before = fingerprint(self.data)
            result = self.execute(owner)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(['stat'], self.operations())
            self.assertEqual(before, fingerprint(self.data))
            self.assertFalse(self.normalized.exists())

    def test_failed_group_change_never_invokes_storage_format(self):
        result = self.execute(fail=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(['stat', 'chgrp'], self.operations())

    def test_both_allowed_empty_routes_normalize_only_the_root(self):
        for root in (self.data, self.target):
            self.calls.unlink(missing_ok=True)
            self.normalized.unlink(missing_ok=True)
            environment = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                               FORMAT_OWNER='1000:0', FORMAT_CALLS=str(self.calls), FORMAT_DATA=str(root),
                               FORMAT_NORMALIZED=str(self.normalized))
            result = subprocess.run(['bash', str(self.helper), str(root)], capture_output=True,
                                    text=True, timeout=10, env=environment)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(['stat', 'chgrp', 'stat'], self.operations())
            sentinel = root / 'existing-node-data'
            sentinel.write_text('preserve')
            before = fingerprint(root)
            self.calls.unlink()
            result = subprocess.run(['bash', str(self.helper), str(root)], capture_output=True,
                                    text=True, timeout=10, env=environment)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual([], self.operations())
            self.assertEqual(before, fingerprint(root))

    def test_helper_refuses_arbitrary_and_symlink_roots_without_mutation(self):
        other = self.root / 'other'
        other.mkdir()
        self.target.rmdir()
        self.target.symlink_to(other, target_is_directory=True)
        for root in (other, self.target):
            before = fingerprint(other)
            result = subprocess.run(['bash', str(self.helper), str(root)], capture_output=True,
                                    text=True, timeout=10)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual([], self.operations())
            self.assertEqual(before, fingerprint(other))

    def test_unreadable_target_is_not_treated_as_empty_or_repaired(self):
        (self.target / 'existing-node-data').write_text('preserve')
        before = fingerprint(self.target)
        self.target.chmod(0)
        try:
            result = subprocess.run(['bash', str(self.helper), str(self.target)], capture_output=True,
                                    text=True, timeout=10)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(0, self.target.stat().st_mode & 0o777)
            self.assertEqual([], self.operations())
        finally:
            self.target.chmod(0o755)
        self.assertEqual(before, fingerprint(self.target))


if __name__ == '__main__':
    if os.name == 'posix' and os.getuid() == 0:
        # Root CI invokes the suite at the helper's actual required identity.
        # Only newly allocated synthetic fixtures receive ownership changes.
        with tempfile.TemporaryDirectory(prefix='armoney-kafka-foreign-') as temporary:
            foreign_root = Path(temporary)
            fixture = foreign_root / 'foreign-owner'
            fixture.write_bytes(FOREIGN_PAYLOAD)
            fixture.chmod(0o644)
            os.chown(foreign_root, 1000, 1000)
            def fixture_identity():
                os.setgroups([])
                os.setgid(1000)
                os.setuid(1000)
            environment = dict(os.environ, _ARMONEY_KAFKA_SYNTHETIC_FOREIGN_FILE=str(fixture))
            result = subprocess.run([sys.executable, '-B', str(Path(__file__).resolve()), *sys.argv[1:]],
                                    env=environment, preexec_fn=fixture_identity)
        sys.exit(result.returncode)
    unittest.main(verbosity=2)
