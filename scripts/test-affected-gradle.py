import importlib.util
from pathlib import Path
import unittest
s=importlib.util.spec_from_file_location('runner',Path(__file__).with_name('run-affected-gradle.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class Tasks(unittest.TestCase):
    def test_module_scoped_check_includes_integration_via_check(self):
        self.assertEqual(m.command('["payment-service"]','false'),['./gradlew','--no-daemon',':payment-service:check'])
    def test_empty_has_no_gradle(self):
        self.assertEqual(m.command('[]','false'),[])
    def test_fixture_distributions_do_not_expand_test_scope(self):
        args=m.command('["auth-service"]','true')
        self.assertEqual([x for x in args if x.endswith(':check')],[':auth-service:check'])
        self.assertEqual(len([x for x in args if x.endswith(':installDist')]),6)
    def test_reject_injection_unknown_duplicates_and_bad_types(self):
        for raw in ('["--exclude-task=test"]','["payment-service:clean"]','["a"]','{}','null','[1]','["auth-service","auth-service"]'):
            with self.subTest(raw=raw),self.assertRaises(ValueError):m.command(raw,'false')
        with self.assertRaises(ValueError):m.command('[]','yes')
if __name__=='__main__':unittest.main()
