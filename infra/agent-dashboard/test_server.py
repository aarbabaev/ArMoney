import datetime as dt
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

loader=importlib.util.spec_from_file_location('dashboard',Path(__file__).with_name('server.py'))
dashboard=importlib.util.module_from_spec(loader);loader.loader.exec_module(dashboard)

class Observations(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.state=Path(self.tmp.name)
        (self.state/'process.json').write_text('{}')
        db=sqlite3.connect(self.state/'queue.sqlite')
        db.execute('CREATE TABLE tasks(spec TEXT,state TEXT)')
        self.spec={'task_id':'real-task','agent_id':'worker','role':'auth_owner','issue':'37','stage':'implement','prompt':'SECRET_PROMPT'}
        db.execute('INSERT INTO tasks VALUES(?,?)',(json.dumps(self.spec),'running'));db.commit();db.close()
    def tearDown(self):self.tmp.cleanup()
    def native(self,age):
        observed=(dt.datetime.now(dt.timezone.utc)-dt.timedelta(seconds=age)).isoformat()
        (self.state/'native-agents.json').write_text(json.dumps({'observed_at':observed,'agents':[{'id':'native','role':'reviewer','state':'running','task':'review','token':'SECRET_TOKEN'}]}))
    def test_stale_native_never_counted_and_secret_fields_excluded(self):
        self.native(120); result=dashboard.snapshot(self.state,lambda r:(True,1))
        self.assertEqual(result['counts']['active'],1);self.assertTrue(result['native']['stale'])
        self.assertNotIn('SECRET',json.dumps(result))
    def test_dead_process_does_not_make_persisted_running_claim_active(self):
        self.native(1);result=dashboard.snapshot(self.state,lambda r:(False,0))
        self.assertEqual(result['counts']['active'],1)
        self.assertEqual(result['dispatcher']['agents'][0]['state'],'unknown')
    def test_missing_live_child_does_not_count_queue_claim_as_confirmed(self):
        self.native(120);result=dashboard.snapshot(self.state,lambda r:(True,0))
        self.assertEqual(result['counts']['active'],0);self.assertTrue(result['warnings'])
    def test_missing_database_reports_unknown_not_false_success(self):
        (self.state/'queue.sqlite').unlink();result=dashboard.snapshot(self.state,lambda r:(False,0))
        self.assertIsNotNone(result['dispatcher']['error']);self.assertEqual(result['counts']['active'],0)

if __name__=='__main__':unittest.main()
