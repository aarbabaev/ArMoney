"""Actual browser confirmation regression with an ephemeral loopback fixture.

No provider, credentials or bank services. Requires Playwright and Chromium;
ARMONEY_TEST_BROWSER optionally selects an existing Chromium executable.
"""
import ast
import http.server
import os
from pathlib import Path
import threading
import unittest
import urllib.parse

from playwright.sync_api import sync_playwright, expect, TimeoutError as BrowserTimeout


LOGOUT_PATH = '/sso/realms/armoney/protocol/openid-connect/logout'


class Fixture(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        if path in (LOGOUT_PATH, '/unexpected-provider'):
            body = ('<form method="post" action="/confirm">'
                    '<input id="kc-logout" name="confirmLogout" type="submit" value="Log out">'
                    '</form>')
        else:
            body = '<button>Sign in securely</button>'
        self.send_response(200)
        self.send_header('Content-Type', 'text/html')
        self.end_headers()
        self.wfile.write(body.encode())

    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length', '0')))
        self.server.confirmations += 1
        self.send_response(303)
        self.send_header('Location', self.server.return_path)
        self.end_headers()

    def log_message(self, *args):
        pass


def actual_logout_helper(origin):
    # Importing web-smoke would execute its CI/admin/bank operations.
    source = Path(__file__).with_name('web-smoke.py')
    tree = ast.parse(source.read_text(encoding='utf-8'))
    functions = [node for node in tree.body if isinstance(node, ast.FunctionDef)
                 and node.name == 'complete_provider_logout']
    if len(functions) != 1:
        raise AssertionError('Expected actual smoke provider logout helper')
    namespace = {'PUBLIC': origin, 'expect': expect}
    exec(compile(ast.Module(body=functions, type_ignores=[]), str(source), 'exec'), namespace)
    return namespace['complete_provider_logout']


class LogoutTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
        cls.addClassCleanup(cls.server.server_close)
        thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        thread.start()
        cls.addClassCleanup(thread.join, 5)
        cls.addClassCleanup(cls.server.shutdown)
        cls.origin = 'http://127.0.0.1:' + str(cls.server.server_port)
        cls.playwright = sync_playwright().start()
        cls.addClassCleanup(cls.playwright.stop)
        cls.browser = cls.playwright.chromium.launch(executable_path=os.environ.get('ARMONEY_TEST_BROWSER'))
        cls.addClassCleanup(cls.browser.close)

    def setUp(self):
        self.server.confirmations = 0
        self.server.return_path = '/home'
        context = self.browser.new_context()
        self.addCleanup(context.close)
        self.page = context.new_page()
        self.page.set_default_timeout(1000)
        self.complete = actual_logout_helper(self.origin)

    def test_confirmation_is_required_before_app_welcome(self):
        self.page.goto(self.origin + LOGOUT_PATH + '?client_id=synthetic-client')
        # This is the previous smoke assertion: it fails on the provider page.
        with self.assertRaises(AssertionError):
            expect(self.page.get_by_role('button', name='Sign in securely')).to_be_visible(timeout=150)
        self.assertEqual(self.server.confirmations, 0)
        self.complete(self.page)
        self.assertEqual(self.server.confirmations, 1)
        self.assertEqual(self.page.url, self.origin + '/home')

    def test_confirmation_control_on_unexpected_path_is_not_clicked(self):
        self.page.goto(self.origin + '/unexpected-provider')
        with self.assertRaises(BrowserTimeout):
            self.complete(self.page)
        self.assertEqual(self.server.confirmations, 0)

    def test_welcome_on_wrong_return_path_cannot_pass(self):
        self.server.return_path = '/unexpected-return'
        self.page.goto(self.origin + LOGOUT_PATH)
        with self.assertRaises(BrowserTimeout):
            self.complete(self.page)
        self.assertEqual(self.server.confirmations, 1)
        expect(self.page.get_by_role('button', name='Sign in securely')).to_be_visible()
        self.assertEqual(self.page.url, self.origin + '/unexpected-return')


if __name__ == '__main__':
    unittest.main(verbosity=2)
