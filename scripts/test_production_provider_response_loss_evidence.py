"""Portable technical tests only; fake DB rows are not runtime/financial acceptance evidence."""
import dataclasses
import importlib.util
import json
from pathlib import Path
import sys
import unittest

SPEC=importlib.util.spec_from_file_location('response_loss_evidence',Path(__file__).with_name('production-provider-response-loss-evidence.py'))
M=importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name]=M
SPEC.loader.exec_module(M)
SUBJECT='10000000-0000-0000-0000-000000000001'
ORIGINAL='20000000-0000-0000-0000-000000000001'
FRESH='30000000-0000-0000-0000-000000000001'
SCOPE={'issuer':M.ISSUER,'subject':SUBJECT}
SELECTOR={'operation':'CONFIRM','issuer':M.ISSUER,'subject':SUBJECT,'client_id':'armoney-android',
          'user_session_id':'private-fixture-session','command_id':'40000000-0000-0000-0000-000000000001',
          'intent_id':'50000000-0000-0000-0000-000000000001','event_id':ORIGINAL,'proof_id':'60000000-0000-0000-0000-000000000001'}

class Cursor:
    def __init__(self,db):self.db=db;self.rows=None
    def __enter__(self):return self
    def __exit__(self,*args):pass
    def execute(self,sql,args):
        self.db.calls.append((sql,args))
        if sql.startswith('select current_setting'):
            self.rows=[self.db.settings]
        elif sql==M.COUNTS_SQL:self.rows=[self.db.counts]
        elif sql==M.EVENTS_SQL:self.rows=[(e,) for e in self.db.events]
        elif sql==M.ORIGINAL_SQL:self.rows=[self.db.original]
        elif sql==M.BANK_EVENTS_SQL:self.rows=list(self.db.bank_events)
        else:raise AssertionError('unexpected SQL')
    def fetchall(self):return self.rows

class DB:
    def __init__(self,*,settings=('on','1s'),counts=(1,0,0,0,0),events=(ORIGINAL,),original=(1,1,1,1,1,1,True,True,'a'*64)):
        self.settings=settings;self.counts=counts;self.events=events;self.original=original;self.calls=[]
        self.bank_events=((events[-1],True),) if counts[2]==1 and events else ()
    def cursor(self):return Cursor(self)

def capture(db=None,selector=SELECTOR):
    return M.capture(db or DB(),SCOPE,selector,clock=lambda:0)

class EvidenceTests(unittest.TestCase):
    def test_exact_selectors_bound_and_no_sensitive_public_fields(self):
        db=DB();snapshot=capture(db)
        self.assertEqual(len(db.calls),5)
        for sql,args in db.calls:
            self.assertNotIn(SUBJECT,sql)
            self.assertNotIn(ORIGINAL,sql)
            self.assertEqual(sql.count('%s'),len(args))
        result=M.public_receipt(snapshot)
        self.assertTrue(all(type(v) in (int,bool) for v in result.values()))
        encoded=json.dumps(result)
        for secret in (SUBJECT,ORIGINAL,SELECTOR['intent_id'],SELECTOR['proof_id'],SELECTOR['user_session_id'],'a'*64):
            self.assertNotIn(secret,encoded)
            self.assertNotIn(secret,repr(snapshot))

    def test_unknown_extra_malformed_selectors_reject_before_database(self):
        cases=[dict(SELECTOR,extra='ignored'),dict(SELECTOR,event_id=ORIGINAL.upper().replace('20000000','abcdefab')),
               dict(SELECTOR,proof_id=None),dict(SELECTOR,client_id='account'),dict(SELECTOR,user_session_id='bad session'),
               dict(SELECTOR,issuer='https://other.invalid/sso/realms/armoney'),dict(SELECTOR,operation='KEY')]
        # Explicit uppercase hex must be noncanonical rather than a different valid UUID.
        cases[1]=dict(SELECTOR,event_id='ABCDEFAB-0000-0000-0000-000000000001')
        for value in cases:
            with self.subTest(keys=sorted(value)):
                db=DB()
                with self.assertRaisesRegex(M.Rejected,'^response_loss_evidence_rejected$'):capture(db,value)
                self.assertEqual(db.calls,[])

    def test_read_only_and_one_second_query_limit_are_required(self):
        for settings in [('off','1s'),('on','0'),('on','2s'),('on','1001ms')]:
            db=DB(settings=settings)
            with self.assertRaises(M.Rejected):capture(db)
            self.assertEqual(len(db.calls),1)
        capture(DB(settings=('on','500ms')))

    def test_whole_deadline_does_not_start_late_statement(self):
        times=iter([0,0,0,2.1])
        db=DB()
        with self.assertRaises(M.Rejected):M.capture(db,SCOPE,SELECTOR,clock=lambda:next(times))
        self.assertEqual(len(db.calls),1)

    def test_owner_ambiguity_overflow_duplicates_and_retention_fail_closed(self):
        cases=[DB(counts=(0,0,0,0,0)),DB(counts=(2,0,0,0,0)),DB(counts=(1,65,0,0,0)),
               DB(events=(ORIGINAL,ORIGINAL)),DB(events=tuple(str(__import__('uuid').UUID(int=i+1)) for i in range(17))),
               DB(original=(1,1,1,1,1,1,False,True,'a'*64)),DB(original=(1,1,1,1,1,1,True,False,'a'*64)),
               DB(original=(1,1,1,2,1,1,True,True,'a'*64))]
        for db in cases:
            with self.subTest(case=cases.index(db)):
                with self.assertRaises(M.Rejected):capture(db)

    def test_stage_uses_only_actual_stage_selectors_and_requires_no_confirmation(self):
        stage=dict(SELECTOR,operation='STAGE',proof_id=None)
        value=capture(DB(events=(),original=(1,1,1,0,0,0,True,False,'b'*64)),stage)
        self.assertEqual(M.public_receipt(value)['original_confirmations'],0)
        with self.assertRaises(M.Rejected):capture(DB(),stage)

    def test_comparison_accepts_fresh_unique_result_only_and_never_returns_private_hash(self):
        before=capture(DB(events=()),None)
        dropped=capture()
        fresh=capture(DB(counts=(1,2,1,1,2),events=(ORIGINAL,FRESH)))
        result=M.compare(before,dropped,fresh)
        self.assertTrue(result['verified'])
        self.assertTrue(all(type(v) is bool for v in result.values()))
        self.assertNotIn(ORIGINAL,json.dumps(result))

    def test_mutation_response_replay_extra_event_and_original_bank_write_do_not_verify(self):
        before=capture(DB(events=()),None);dropped=capture()
        good=capture(DB(counts=(1,1,1,1,1),events=(ORIGINAL,FRESH)))
        altered=dataclasses.replace(good,_fingerprint='b'*64)
        reused=dataclasses.replace(good,_events=(ORIGINAL,))
        extra=dataclasses.replace(good,_events=(ORIGINAL,FRESH,'70000000-0000-0000-0000-000000000001'))
        wrong_bank=dataclasses.replace(good,_bank_events=((ORIGINAL,True),))
        for value in (altered,reused,extra,wrong_bank):self.assertFalse(M.compare(before,dropped,value)['verified'])
        written=dataclasses.replace(dropped,_counts=(1,0,0,0))
        self.assertFalse(M.compare(before,written,good)['lost_attempt_no_banking_writes'])
        already=dataclasses.replace(before,_events=(ORIGINAL,))
        self.assertFalse(M.compare(already,dropped,good)['dropped_event_inventory_matches'])

    def test_selector_and_scope_changes_reject_and_errors_redact(self):
        before=capture(DB(events=()),None);dropped=capture();fresh=capture(DB(counts=(1,1,1,1,1),events=(ORIGINAL,FRESH)))
        with self.assertRaises(M.Rejected):M.compare(before,dropped,dataclasses.replace(fresh,_selector_hash=b'other'))
        with self.assertRaises(M.Rejected):M.compare(before,dropped,dataclasses.replace(fresh,_scope_hash=b'other'))
        class Broken:
            def cursor(self):raise RuntimeError('private token contact secret')
        try:capture(Broken())
        except M.Rejected as error:
            self.assertEqual(str(error),'response_loss_evidence_rejected')
            self.assertTrue(error.__suppress_context__)
        else:self.fail('expected sanitized rejection')

if __name__=='__main__':unittest.main()
