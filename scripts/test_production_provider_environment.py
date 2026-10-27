"""Offline synthetic configuration tests; no service, Docker, DB or provider calls."""
import base64
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import tempfile
import unittest
from unittest import mock


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


ROOT = Path(__file__).resolve().parent.parent
E = module('production_environment', ROOT / 'scripts/production-provider-fixture-environment.py')
P = module('production_provision_schema', ROOT / 'scripts/provision-production-provider-fixture.py')
A = module('production_acceptance_schema', ROOT / 'sso-service/provider/acceptance-fixture/test_production_provider_acceptance.py')


def env(raw):
    result = {}
    for line in raw.decode('ascii').splitlines():
        match = re.fullmatch(r"([A-Z][A-Z0-9_]*)='([^'\r\n]*)'", line)
        if match is None or match[1] in result:
            raise AssertionError('generated env syntax rejected')
        result[match[1]] = match[2]
    return result


class Configuration(unittest.TestCase):
    def test_exact_eleven_files_and_existing_provision_acceptance_schemas(self):
        output = E.bundle(True, True)
        self.assertTrue(set(output) == E.FILENAMES, 'exact output names')
        provision = P.configuration(output['provision.json'])
        with mock.patch.object(A, 'Path', PurePosixPath):
            acceptance = A.configuration(output['acceptance.json'])
        self.assertTrue(provision['expected_issuer'] == E.ISSUER, 'fixed issuer')
        self.assertTrue(acceptance['version'] == 1 and acceptance['account_client'] == E.ACCOUNT, 'driver input schema')
        self.assertTrue(acceptance['controller_origin'] == E.ORIGIN == acceptance['gateway_origin'], 'owned TLS edge')
        self.assertTrue(set(acceptance['controller_paths']) == set(E.OPERATIONS), 'five operations')
        self.assertTrue(len(provision['purpose_manifest']) == 5, 'five exact redirect purposes')
        self.assertTrue(set(x['client_id'] for x in provision['purpose_manifest']) == set((*E.BANK, E.ACCOUNT)), 'four purpose clients')
        self.assertTrue('synthetic_user' not in provision and 'users' not in provision, 'registration must create account')

    def test_raw_and_decoded_materials_are_independent_and_new_on_each_call(self):
        first, second = E.materials(), E.materials()
        self.assertTrue(set(first) == set((*E.BINARY, *E.OPAQUE)), 'closed role inventory')
        seen = set()
        for name, value in first.items():
            forms = E.aliases(value)
            self.assertFalse(bool(seen.intersection(forms)), 'independent role material')
            seen.update(forms)
            self.assertFalse(bool(forms.intersection(E.aliases(second[name]))), 'fresh role material')
        for name in E.BINARY:
            raw = base64.b64decode(first[name], validate=True)
            self.assertTrue(len(raw) == 32 and base64.b64encode(raw).decode() == first[name], 'canonical binary role')
        self.assertTrue(len(E.BINARY) == 9, 'eight Auth binary keys and separate provider HMAC')

    def test_repeated_entropy_fails_closed_without_static_fallback(self):
        with mock.patch.object(E.secrets, 'token_bytes', return_value=b'\x11' * 32):
            with self.assertRaises(E.Refused):
                E.materials()

    def test_pair_scopes_and_flags_are_exact_for_auth_controller_and_provider(self):
        output = E.bundle(True, True)
        auth, controller, sso = [env(output[name]) for name in ('auth.env', 'controller.env', 'sso.env')]
        managed = json.loads(auth['ARMONEY_SSO_MANAGED_PAIRS'])
        self.assertTrue({x['client_id'] for x in managed} == set(E.BANK), 'native managed scope')
        self.assertTrue(all(x['issuer'] == E.ISSUER for x in managed), 'fixed scope issuer')
        self.assertTrue(auth['ARMONEY_SSO_LEGACY_PAIRS'] == controller['ARMONEY_SSO_LEGACY_PAIRS'] == '[]', 'Auth legacy stays empty')
        self.assertTrue(json.loads(sso['ARMONEY_SSO_LEGACY_PAIRS']) == [{'issuer': E.ISSUER, 'client_id': E.ACCOUNT}], 'dedicated provider legacy scope')
        self.assertTrue(set(sso['SSO_VERIFICATION_CLIENT_IDS'].split(',')) == set(E.BANK), 'account excluded from bank allowlist')
        for name in ('ARMONEY_LOGIN_PROOF_ENABLED', 'ARMONEY_VERIFICATION_ENABLED', 'ARMONEY_CODE_SESSION_OBSERVATION_ENABLED'):
            self.assertTrue(sso[name] == 'true', 'genuine provider status flags')
        self.assertTrue(all(not name.startswith('AUTH_MIGRATION_') for name in auth), 'no dormant migration settings')

    def test_private_key_graph_and_database_credentials_match_only_intended_peers(self):
        output = E.bundle(True, True)
        maps = {name: env(raw) for name, raw in output.items() if name.endswith('.env')}
        auth, user, sso, controller, sms, gateway = [maps[name + '.env'] for name in ('auth', 'user', 'sso', 'controller', 'sms', 'gateway')]
        self.assertTrue(auth['AUTH_TO_USER_VERIFICATION_PROJECTION_KEY'] == user['AUTH_TO_USER_VERIFICATION_PROJECTION_KEY'], 'projection role')
        self.assertTrue(auth['AUTH_VERIFICATION_PROJECTION_WRITER_ID'] == user['USER_VERIFICATION_PROJECTION_WRITER_ID'], 'projection writer')
        for name in ('SSO_LOGIN_PROOF_WRITE_KEY', 'SSO_CODE_INTENT_LOOKUP_KEY', 'SSO_LOGIN_PROOF_CONFIRM_KEY', 'SSO_VERIFICATION_BRIDGE_KEY', 'AUTH_TO_SSO_STATUS_KEY'):
            self.assertTrue(auth[name] == sso[name], 'matching directional role')
        self.assertTrue(controller['VERIFICATION_SMS_SEND_TOKEN'] == sms['SMS_SANDBOX_SEND_TOKEN'], 'actual sandbox sender')
        self.assertTrue(controller['SMS_SANDBOX_READ_TOKEN'] == sms['SMS_SANDBOX_READ_TOKEN'], 'actual sandbox reader')
        self.assertTrue(auth['INTERNAL_AUTH_KEY'] == user['INTERNAL_AUTH_KEY'] == gateway['INTERNAL_AUTH_KEY'], 'documented shared local key')
        for name, database, username in (('auth', 'bank', 'bank'), ('user', 'users', 'users'), ('sso', 'keycloak', 'keycloak')):
            own = maps[name + '-db.env']
            self.assertTrue(own['POSTGRES_DB'] == database and own['POSTGRES_USER'] == username, 'fixed database identity')
            actual = sso['KC_DB_PASSWORD'] if name == 'sso' else maps[name + '.env']['DB_PASSWORD']
            self.assertTrue(own['POSTGRES_PASSWORD'] == actual, 'same private database credential')
        self.assertTrue(auth['SSO_CLIENT_ID'] == 'armoney-auth', 'confidential introspection client')
        self.assertTrue('ARMONEY_CODE_INTENT_CODE_HMAC_KEYS' not in auth and 'ARMONEY_PROVIDER_BINDING_KEY' not in auth, 'provider-only keys stay provider-only')

    def test_actual_mail_credentials_and_old_authority_fixture_are_absent(self):
        output = E.bundle(True, True)
        auth, controller, sso = [env(output[name]) for name in ('auth.env', 'controller.env', 'sso.env')]
        self.assertTrue(auth['AUTH_VERIFICATION_DELIVERY_ENABLED'] == controller['AUTH_VERIFICATION_DELIVERY_ENABLED'] == 'false', 'only controller owns worker')
        self.assertTrue(controller['PORT'] == '8092', 'fixed private controller port')
        self.assertTrue(all(not name.startswith('MAILTRAP_') for name in auth), 'no Auth SMTP configuration')
        self.assertTrue('MAILTRAP_SMTP_HOST' not in controller, 'transport hostname is injected localhost')
        self.assertTrue('AUTH_CODE_INTENT_FIXTURE_PROVIDER_KEY' not in controller, 'no old authority fixture key')
        self.assertTrue(all(not name.startswith('ARMONEY_CODE_INTENT_FIXTURE_') for name in sso), 'no old provider fixture activation')

    def test_generation_has_no_environment_file_network_or_database_reads(self):
        with mock.patch.object(E.os, 'open', side_effect=AssertionError('unexpected file access')), \
             mock.patch.object(E.os, 'getenv', side_effect=AssertionError('unexpected environment access')), \
             mock.patch('builtins.open', side_effect=AssertionError('unexpected file read')):
            output = E.bundle(True, True)
        self.assertTrue(set(output) == E.FILENAMES, 'pure generation')

    def test_ci_isolation_and_ca_path_reject_before_entropy_allocation(self):
        with mock.patch.object(E, 'materials', side_effect=AssertionError('premature entropy')):
            for ci, isolated, path in ((False, True, '/fixture/ca.crt'), (True, False, '/fixture/ca.crt'),
                                        ('true', True, '/fixture/ca.crt'), (True, True, 'relative.crt'),
                                        (True, True, '/fixture/../ca.crt'), (True, True, '/fixture//ca.crt')):
                with self.assertRaises(E.Refused):
                    E.bundle(ci, isolated, path)

    def test_cli_rejects_missing_gate_or_unknown_fields_without_paths_or_values_in_receipt(self):
        with mock.patch.object(E, 'initialize', side_effect=AssertionError('unexpected output allocation')):
            for args in (['--directory', '/fixture/private', '--isolated'],
                         ['--directory', '/fixture/private', '--ci', '--isolated', '--secret', 'never-echo']):
                stream = io.StringIO()
                with contextlib.redirect_stdout(stream):
                    result = E.main(args)
                self.assertTrue(result == 1 and stream.getvalue().strip() == '{"status":"refused"}', 'generic rejected receipt')


@unittest.skipUnless(os.name == 'posix', 'Private descriptor/file-mode checks require actual POSIX support')
class PrivateFiles(unittest.TestCase):
    def test_eleven_private_files_new_or_empty_directory_and_repeat_refusal(self):
        for existing in (False, True):
            with tempfile.TemporaryDirectory() as parent:
                directory = Path(parent) / 'bundle'
                if existing:
                    directory.mkdir(mode=0o700)
                names = E.initialize(str(directory), True, True)
                self.assertTrue(set(names) == E.FILENAMES, 'exact output inventory')
                self.assertTrue(stat.S_IMODE(directory.stat().st_mode) == 0o700, 'private directory mode')
                before = {}
                for path in directory.iterdir():
                    self.assertTrue(path.is_file() and not path.is_symlink(), 'regular private file')
                    self.assertTrue(stat.S_IMODE(path.stat().st_mode) == 0o600 and path.stat().st_uid == os.geteuid(), 'private owner and file mode')
                    before[path.name] = path.read_bytes()
                with self.assertRaises(E.Refused):
                    E.initialize(str(directory), True, True)
                self.assertTrue(all(path.read_bytes() == before[path.name] for path in directory.iterdir()), 'existing bundle preserved')

    def test_nonempty_public_or_symlink_directory_is_never_modified(self):
        with tempfile.TemporaryDirectory() as parent:
            root = Path(parent)
            occupied = root / 'occupied'; occupied.mkdir(mode=0o700)
            sentinel = occupied / '.env'; sentinel.write_text('synthetic untouched sentinel')
            public = root / 'public'; public.mkdir(mode=0o755); public.chmod(0o755)
            target = root / 'target'; target.mkdir(mode=0o700)
            link = root / 'link'; link.symlink_to(target, target_is_directory=True)
            for directory in (occupied, public, link, link / 'child'):
                with self.assertRaises(E.Refused):
                    E.initialize(str(directory), True, True)
            self.assertTrue(sentinel.read_text() == 'synthetic untouched sentinel', 'existing files untouched')
            self.assertTrue(not list(target.iterdir()), 'symlink target untouched')

    def test_cli_receipt_lists_only_fixed_file_and_role_names(self):
        with tempfile.TemporaryDirectory() as parent:
            directory = Path(parent) / 'bundle'
            stream = io.StringIO()
            with contextlib.redirect_stdout(stream):
                result = E.main(['--directory', str(directory), '--ci', '--isolated'])
            receipt = json.loads(stream.getvalue())
            self.assertTrue(result == 0 and set(receipt) == {'status', 'files', 'roles', 'isolation'}, 'receipt schema')
            self.assertTrue(receipt['isolation'] == 'caller_asserted', 'no isolation attestation')
            self.assertTrue(set(receipt['files']) == E.FILENAMES, 'receipt file names')
            self.assertTrue(set(receipt['roles']) == set((*E.BINARY, *E.OPAQUE)), 'receipt role names')


if __name__ == '__main__':
    unittest.main()
