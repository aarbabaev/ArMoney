"""Portable codec/schema tests; POSIX file tests use synthetic private temporary bundles only."""
import dataclasses
import importlib.util
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC=importlib.util.spec_from_file_location('loss_snapshot',Path(__file__).with_name('production-provider-response-loss-snapshot.py'))
M=importlib.util.module_from_spec(SPEC);sys.modules[SPEC.name]=M;SPEC.loader.exec_module(M)
E=M.E
SUBJECT='10000000-0000-0000-0000-000000000001'
EVENT='20000000-0000-0000-0000-000000000001'
NEW='30000000-0000-0000-0000-000000000001'
SCOPE={'issuer':E.ISSUER,'subject':SUBJECT}
ORIGINAL={'operation':'CONFIRM','issuer':E.ISSUER,'subject':SUBJECT,'client_id':'armoney-android','user_session_id':'private-session',
          'command_id':'40000000-0000-0000-0000-000000000001','intent_id':'50000000-0000-0000-0000-000000000001','event_id':EVENT,'proof_id':'60000000-0000-0000-0000-000000000001'}
DB={'hostaddr':'172.18.0.2','port':5432,'database':'bank','user':'bank','password':'x'*43}

def snapshots():
    scope=E._digest([SCOPE['issuer'],SUBJECT]);selector=E._digest([str(ORIGINAL[k]) for k in sorted(E.SELECTOR_KEYS)])
    before=E.Snapshot(scope,(0,0,0,0),())
    dropped=E.Snapshot(scope,(0,0,0,0),(EVENT,),(1,1,1,1,1,1),'a'*64,selector,EVENT,True,())
    fresh=E.Snapshot(scope,(1,1,1,1),tuple(sorted((EVENT,NEW))),(1,1,1,1,1,1),'a'*64,selector,EVENT,True,((NEW,True),))
    return before,dropped,fresh

def document(directory,action):
    return {'version':1,'action':action,'directory':str(directory),'db':None if action=='compare' else dict(DB),'scope':dict(SCOPE),
            'original':None if action=='baseline' else dict(ORIGINAL)}

class CodecTests(unittest.TestCase):
    def test_round_trip_exact_private_fields_without_arbitrary_deserialization(self):
        for snapshot in snapshots():
            raw=M.encode(snapshot)
            self.assertEqual(M.decode(raw),snapshot)
            self.assertLessEqual(len(raw),16384)
            self.assertNotIn(EVENT,repr(M.decode(raw)))
            public=E.public_receipt(M.decode(raw))
            self.assertTrue(all(type(x) in (bool,int) for x in public.values()))
            self.assertNotIn(EVENT,json.dumps(public))

    def test_duplicate_unknown_nonfinite_oversized_and_trailing_json_rejected(self):
        for raw in (b'{"version":1,"version":1}',b'NaN',b'{} {}',b'x'*16385,b'\xff'):
            with self.assertRaises(M.Rejected):M.decode(raw)
        data=json.loads(M.encode(snapshots()[1]));data['unknown']=True
        with self.assertRaises(M.Rejected):M.decode(json.dumps(data).encode())

    def test_codec_rejects_wrong_types_unsorted_ids_and_inconsistent_shapes(self):
        base=json.loads(M.encode(snapshots()[1]))
        for field,value in [('version',True),('counts',[0,True,0,0]),('scope_hash','A'*64),('events',[EVENT,EVENT]),
                            ('events',[NEW,EVENT]),('original_counts',[1,1,1,1,0,1]),('retention_current',False),
                            ('bank_events',[[EVENT,1]]),('bank_events',[[NEW,True]])]:
            bad=dict(base);bad[field]=value
            with self.subTest(field=field),self.assertRaises(M.Rejected):M.decode(json.dumps(bad).encode())
        baseline=json.loads(M.encode(snapshots()[0]));baseline['fingerprint']='a'*64
        with self.assertRaises(M.Rejected):M.decode(json.dumps(baseline).encode())

    def test_connection_rejects_public_dns_roles_ports_and_bad_password_before_import(self):
        for delta in ({'hostaddr':'example.test'},{'hostaddr':'127.0.0.1'},{'hostaddr':'169.254.1.1'},{'hostaddr':'8.8.8.8'},
                      {'hostaddr':'::1'},{'port':True},{'port':5433},{'database':'users'},{'user':'postgres'},{'password':'x'*42},{'extra':'bad'}):
            with self.subTest(delta=delta),self.assertRaises(M.Rejected):M.connect(dict(DB,**delta))

    def test_unknown_action_or_schema_never_connects(self):
        connect=lambda _:self.fail('must not connect')
        for value in ({},dict(document('/tmp/private','baseline'),extra=True),dict(document('/tmp/private','baseline'),action='replay')):
            with self.assertRaises(M.Rejected):M.run(value,connector=connect)

@unittest.skipUnless(os.name=='posix','POSIX ownership/NOFOLLOW required; no unsafe filesystem fallback')
class FileTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.directory=Path(self.temp.name);os.chmod(self.directory,0o700)
    def tearDown(self):self.temp.cleanup()
    def persist(self,name,snapshot):
        fd=M._directory(str(self.directory))
        try:M._write(fd,name,snapshot)
        finally:os.close(fd)

    def test_three_phase_files_exclusive_private_then_compare_without_database(self):
        before,dropped,fresh=snapshots()
        class Connection:
            def __enter__(self):return self
            def __exit__(self,*args):pass
        for action,value in zip(('baseline','drop','fresh'),(before,dropped,fresh)):
            with patch.object(E,'capture',return_value=value):result=M.run(document(self.directory,action),connector=lambda _:Connection())
            self.assertTrue(all(type(x) in (bool,int) for x in result.values()))
            self.assertEqual(stat.S_IMODE((self.directory/(action+'.json')).stat().st_mode),0o600)
        result=M.run(document(self.directory,'compare'),connector=lambda _:self.fail('compare must not connect'))
        self.assertTrue(result['verified'])
        self.assertNotIn(EVENT,json.dumps(result))
        with self.assertRaises(M.Rejected):M.run(document(self.directory,'baseline'),connector=lambda _:self.fail('must not reconnect'))

    def test_symlink_permissions_hardlink_nonregular_and_oversize_fail_closed(self):
        before=snapshots()[0];self.persist('baseline.json',before)
        fd=M._directory(str(self.directory))
        try:
            with self.assertRaises(FileExistsError):M._write(fd,'baseline.json',before)
            os.chmod(self.directory/'baseline.json',0o644)
            with self.assertRaises(M.Rejected):M._read(fd,'baseline.json')
            os.chmod(self.directory/'baseline.json',0o600)
            os.link(self.directory/'baseline.json',self.directory/'linked.json')
            with self.assertRaises(M.Rejected):M._read(fd,'baseline.json')
            os.symlink('baseline.json',self.directory/'symlink.json')
            with self.assertRaises(OSError):M._read(fd,'symlink.json')
            os.mkfifo(self.directory/'pipe.json',0o600)
            with self.assertRaises(M.Rejected):M._read(fd,'pipe.json')
            (self.directory/'big.json').write_bytes(b'x'*16385);os.chmod(self.directory/'big.json',0o600)
            with self.assertRaises(M.Rejected):M._read(fd,'big.json')
        finally:os.close(fd)
        alias=self.directory.parent/(self.directory.name+'-alias')
        alias.symlink_to(self.directory,target_is_directory=True)
        try:
            with self.assertRaises(M.Rejected):M._directory(str(alias))
        finally:alias.unlink()
        os.chmod(self.directory,0o755)
        with self.assertRaises(M.Rejected):M._directory(str(self.directory))

    def test_phase_order_scope_selector_and_compare_credentials_rejected_without_connection(self):
        stop=lambda _:self.fail('must not connect')
        with self.assertRaises(M.Rejected):M.run(document(self.directory,'fresh'),connector=stop)
        before,dropped,fresh=snapshots()
        for name,value in zip(M.FILES,(before,dropped,fresh)):self.persist(name,value)
        bad=document(self.directory,'compare');bad['db']=dict(DB)
        with self.assertRaises(M.Rejected):M.run(bad,connector=stop)
        bad=document(self.directory,'compare');bad['original']['event_id']=NEW
        with self.assertRaises(M.Rejected):M.run(bad,connector=stop)
        bad=document(self.directory,'compare');bad['scope']['subject']='70000000-0000-0000-0000-000000000001'
        with self.assertRaises(M.Rejected):M.run(bad,connector=stop)

if __name__=='__main__':unittest.main()
