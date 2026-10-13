"""Offline safety and oracle regressions; not model or banking integration evidence."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('smoke', Path(__file__).with_name('assistant-smoke.py'))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class Safety(unittest.TestCase):
    def marker(self, origin, project='assistant-qa-unit'):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / 'marker.json'
        path.write_text(json.dumps({'disposable': True, 'compose_project': project, 'gateway_origin': origin}), encoding='utf-8')
        return path

    def test_persistent_port_refused_even_with_marker(self):
        for base in ('http://localhost:8080', 'http://127.0.0.1:8080', 'http://[::1]:8080'):
            with self.assertRaises(AssertionError):
                smoke.isolated(base, self.marker(base))

    def test_marker_binds_origin_and_project(self):
        base = 'http://localhost:18043'
        self.assertEqual(base, smoke.isolated(base, self.marker(base)))
        for marker in (self.marker(base, 'arman-bank'), self.marker('http://localhost:18044')):
            with self.assertRaises(AssertionError):
                smoke.isolated(base, marker)

    def test_url_credentials_and_arbitrary_paths_refused(self):
        for base in ('http://user:secret@localhost:18043', 'http://localhost:18043/path', 'http://localhost:18043?x=y'):
            with self.assertRaises(AssertionError):
                smoke.isolated(base, self.marker(base))

    def test_citation_without_provenance_rejected(self):
        answer = {'reply': 'AED', 'outcome': 'answered', 'facts': {'wallets': None, 'last_transaction': None}, 'provenance': [], 'citations': [{'document_id': 'demo', 'version': 'v1', 'section': 'currency', 'excerpt': 'AED'}]}
        with self.assertRaises(AssertionError):
            smoke.answer_shape(answer)

    def test_redirect_does_not_forward_credentials(self):
        self.assertIsNone(smoke.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.example'))


if __name__ == '__main__':
    unittest.main()
