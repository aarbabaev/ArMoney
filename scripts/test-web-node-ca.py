"""Node TLS regression for web-smoke's Playwright driver trust configuration.

Runs only ephemeral loopback HTTPS. Never imports the executable smoke script,
installs a root certificate, or contacts a provider. Requires Node and OpenSSL
on PATH, or explicit ARMONEY_TEST_NODE / ARMONEY_TEST_OPENSSL executable paths.
"""
import ast
import http.server
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import tempfile
import threading
import types
import unittest


NODE_REQUEST = r"""
const https = require('node:https');
const req = https.get({
  hostname: process.argv[1], port: Number(process.argv[2]), path: '/',
  lookup: (_host, options, done) => options.all
    ? done(null, [{address: '127.0.0.1', family: 4}])
    : done(null, '127.0.0.1', 4)
}, response => {
  let body = '';
  response.on('data', chunk => body += chunk);
  response.on('end', () => console.log(JSON.stringify({
    status: response.statusCode, authorized: response.socket.authorized, body
  })));
});
req.setTimeout(5000, () => req.destroy(new Error('fixture timeout')));
req.on('error', error => {
  console.log(JSON.stringify({code: error.code || 'FIXTURE_ERROR'}));
  process.exitCode = 2;
});
"""


def executable(variable, name):
    value = os.environ.get(variable) or shutil.which(name)
    if not value:
        raise RuntimeError(f'{name} required: set {variable} or add it to PATH')
    return value


def smoke_tls_setup(ca_file):
    """Execute only the three TLS assignments parsed from the actual source."""
    source = Path(__file__).with_name('web-smoke.py')
    tree = ast.parse(source.read_text(encoding='utf-8'), filename=str(source))
    selected = []
    for statement in tree.body:
        if not isinstance(statement, ast.Assign) or len(statement.targets) != 1:
            continue
        target = statement.targets[0]
        if (isinstance(target, ast.Name) and target.id in {'TLS_CA_FILE', 'TLS'}
                or isinstance(target, ast.Subscript)
                and ast.dump(target.value) == ast.dump(ast.parse('os.environ', mode='eval').body)
                and isinstance(target.slice, ast.Constant)
                and target.slice.value == 'NODE_EXTRA_CA_CERTS'):
            selected.append(statement)
    if len(selected) != 3:
        raise AssertionError('Expected the actual smoke CA path, SSL context and Node CA assignments')
    starts = [node.lineno for node in ast.walk(tree) if isinstance(node, ast.Call)
              and isinstance(node.func, ast.Name) and node.func.id == 'sync_playwright']
    if not starts or max(node.lineno for node in selected) >= min(starts):
        raise AssertionError('Node trust must be configured before Playwright starts its driver')
    environment = {'SSO_CA_FILE': str(ca_file)}
    namespace = {'os': types.SimpleNamespace(path=os.path, environ=environment), 'ssl': ssl}
    exec(compile(ast.Module(body=selected, type_ignores=[]), str(source), 'exec'), namespace)
    return namespace, environment


class NodeTrustTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.node = executable('ARMONEY_TEST_NODE', 'node')
        openssl = executable('ARMONEY_TEST_OPENSSL', 'openssl')
        cls.temp = tempfile.TemporaryDirectory(prefix='armoney-node-ca-')
        cls.addClassCleanup(cls.temp.cleanup)
        root = Path(cls.temp.name)
        cls.ca = root / 'root.pem'
        def generate(*args):
            subprocess.run([openssl, *args], cwd=root, check=True, timeout=20,
                           stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        generate('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                 '-subj', '/CN=ArMoney ephemeral test CA', '-keyout', 'root.key', '-out', 'root.pem',
                 '-addext', 'basicConstraints=critical,CA:TRUE',
                 '-addext', 'keyUsage=critical,keyCertSign,cRLSign')
        generate('req', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=localhost',
                 '-keyout', 'server.key', '-out', 'server.csr')
        (root / 'server.ext').write_text('basicConstraints=critical,CA:FALSE\n'
                                       'keyUsage=critical,digitalSignature,keyEncipherment\n'
                                       'extendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost\n')
        generate('x509', '-req', '-in', 'server.csr', '-CA', 'root.pem', '-CAkey', 'root.key',
                 '-CAcreateserial', '-days', '1', '-extfile', 'server.ext', '-out', 'server.pem')
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                payload = b'ephemeral loopback proof'
                self.send_response(200)
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args):
                pass
        cls.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.addClassCleanup(cls.server.server_close)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(root / 'server.pem', root / 'server.key')
        cls.server.socket = context.wrap_socket(cls.server.socket, server_side=True)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.addClassCleanup(cls.thread.join, 5)
        cls.addClassCleanup(cls.server.shutdown)

    def request(self, host='localhost', extra=None):
        # Ambient Node overrides must not manufacture a passing TLS result.
        env = {key: value for key, value in os.environ.items()
               if not key.upper().startswith(('NODE_', 'SSL_CERT_', 'OPENSSL_'))}
        env.update(extra or {})
        result = subprocess.run([self.node, '-e', NODE_REQUEST, host, str(self.server.server_port)],
                                env=env, capture_output=True, text=True, timeout=10)
        return result.returncode, json.loads(result.stdout)

    def test_missing_ca_is_rejected_by_node(self):
        code, result = self.request()
        self.assertEqual(code, 2)
        self.assertIn(result['code'], {'UNABLE_TO_VERIFY_LEAF_SIGNATURE',
                                      'UNABLE_TO_GET_ISSUER_CERT_LOCALLY'})

    def test_exact_smoke_setup_trusts_node_without_disabling_verification(self):
        namespace, env = smoke_tls_setup(self.ca)
        self.assertEqual(env['NODE_EXTRA_CA_CERTS'], str(self.ca.resolve()))
        self.assertEqual(namespace['TLS'].verify_mode, ssl.CERT_REQUIRED)
        self.assertTrue(namespace['TLS'].check_hostname)
        self.assertNotIn('NODE_TLS_REJECT_UNAUTHORIZED', env)
        code, result = self.request(extra=env)
        self.assertEqual(code, 0, result)
        self.assertEqual(result, {'status': 200, 'authorized': True, 'body': 'ephemeral loopback proof'})

    def test_trusted_ca_does_not_accept_wrong_hostname(self):
        _, env = smoke_tls_setup(self.ca)
        code, result = self.request(host='wrong.invalid', extra=env)
        self.assertEqual(code, 2)
        self.assertEqual(result['code'], 'ERR_TLS_CERT_ALTNAME_INVALID')


if __name__ == '__main__':
    unittest.main(verbosity=2)
