#!/usr/bin/env python3
"""Generate NEW private disposable fixture configuration; never operate any service.

CI/isolation/exclusive-writer fields are caller assertions, not attestations.
No environment files, process environment credentials, CA bytes, network, Docker,
database or existing realm are read. Only --directory and explicit CLI assertions
select the output. Failed/partial bundles must be disposed of by their owner.
"""
import argparse
import base64
import json
import os
from pathlib import PurePosixPath
import re
import secrets
import stat
import sys

ORIGIN = 'https://fixture.local:8443'
ISSUER = ORIGIN + '/sso/realms/armoney'
BANK = ('armoney-android', 'armoney-ios', 'armoney-web')
ACCOUNT = 'armoney-migration-account'
OPERATIONS = ('capabilities', 'outcomes', 'read_delivery', 'remove_policy', 'restore_policy')
BINARY = ('otp', 'delivery', 'authority-retention', 'projection-retention', 'proof-retention',
          'intent-retention', 'intent-encryption', 'managed-banking', 'code-hmac')
OPAQUE = ('internal', 'verification-bridge', 'provider-status', 'stage', 'lookup', 'confirm',
          'provider-binding', 'projection-transport', 'introspection', 'acceptance-admin',
          'controller', 'smtp-user', 'smtp-password', 'registration-password', 'bootstrap-admin',
          'auth-db', 'user-db', 'sso-db', 'sms-send', 'sms-read')
FILENAMES = frozenset(('auth.env', 'user.env', 'sso.env', 'sms.env', 'controller.env', 'gateway.env',
                       'auth-db.env', 'user-db.env', 'sso-db.env', 'provision.json', 'acceptance.json'))


class Refused(Exception):
    def __init__(self):
        super().__init__('fixture_environment_refused')


def require(ok):
    if not ok:
        raise Refused() from None


def packed(value):
    return json.dumps(value, separators=(',', ':'), ensure_ascii=True, allow_nan=False).encode('ascii')


def aliases(value):
    raw = value.encode('ascii')
    forms = {raw}
    for decoder in (base64.b64decode, base64.urlsafe_b64decode):
        try:
            decoded = decoder(raw + b'=' * ((-len(raw)) % 4))
            canonical = (base64.b64encode(decoded), base64.b64encode(decoded).rstrip(b'='),
                         base64.urlsafe_b64encode(decoded), base64.urlsafe_b64encode(decoded).rstrip(b'='))
            if raw in canonical:
                forms.add(decoded)
        except (ValueError, TypeError):
            pass
    return forms


def materials():
    roles, seen = {}, set()
    for name in (*BINARY, *OPAQUE):
        for _ in range(8):
            raw = secrets.token_bytes(32)
            require(type(raw) is bytes and len(raw) == 32)
            value = (base64.b64encode(raw) if name in BINARY else base64.urlsafe_b64encode(raw).rstrip(b'=')).decode('ascii')
            forms = aliases(value)
            if not seen.intersection(forms):
                roles[name] = value
                seen.update(forms)
                break
        else:
            raise Refused()
    return roles


def absolute_path(value):
    require(type(value) is str and 1 < len(value) <= 4096 and value.startswith('/') and not value.endswith('/'))
    require(re.fullmatch(r'/[A-Za-z0-9_./-]+', value) is not None and '//' not in value)
    require(all(part not in ('.', '..') for part in value.split('/')[1:]))
    require(str(PurePosixPath(value)) == value)
    return value


def env_bytes(values):
    lines = []
    for name, value in sorted(values.items()):
        require(re.fullmatch(r'[A-Z][A-Z0-9_]*', name) is not None and type(value) is str)
        require(not any(char in value for char in "\r\n\0'"))
        lines.append(name + "='" + value + "'\n")
    return ''.join(lines).encode('ascii')


def bundle(ci, isolated, ca_file='/fixture/ca.crt'):
    require(ci is True and isolated is True)
    absolute_path(ca_file)
    role = materials()
    managed = [{'issuer': ISSUER, 'client_id': client} for client in BANK]
    legacy = [{'issuer': ISSUER, 'client_id': ACCOUNT}]
    manifest = [
        {'issuer': ISSUER, 'client_id': 'armoney-android', 'redirect_uri': 'com.armoney.android:/oauth/callback', 'purpose': 'BANK_NATIVE'},
        {'issuer': ISSUER, 'client_id': 'armoney-ios', 'redirect_uri': 'com.armoney.ios:/oauth/callback', 'purpose': 'BANK_NATIVE'},
        {'issuer': ISSUER, 'client_id': 'armoney-web', 'redirect_uri': ORIGIN + '/oauth/callback', 'purpose': 'BANK_NATIVE'},
        {'issuer': ISSUER, 'client_id': 'armoney-android', 'redirect_uri': ORIGIN + '/migration/managed/callback', 'purpose': 'MIGRATION_MANAGED'},
        {'issuer': ISSUER, 'client_id': ACCOUNT, 'redirect_uri': ORIGIN + '/migration/legacy/account-oidc/callback', 'purpose': 'MIGRATION_ACCOUNT'}]
    # The driver alone creates this synthetic account through genuine production registration.
    phone = '+971501234567'
    email = 'registration-' + secrets.token_hex(12) + '@fixture.test'
    common = {'SSO_ISSUER': ISSUER, 'SSO_VERIFICATION_CLIENT_IDS': ','.join(BANK),
              'ARMONEY_SSO_MANAGED_PAIRS': packed(managed).decode(), 'ARMONEY_SSO_LEGACY_PAIRS': '[]',
              'SSO_CODE_INTENT_ACTORS': 'sso-provider', 'SSO_LOGIN_PROOF_WRITER_ID': 'sso-provider',
              'SSO_CODE_INTENT_CODE_KEY_IDS': 'fixture-code-v1',
              'SSO_LOGIN_PROOF_WRITE_KEY': role['stage'], 'SSO_CODE_INTENT_LOOKUP_KEY': role['lookup'],
              'SSO_LOGIN_PROOF_CONFIRM_KEY': role['confirm'], 'SSO_VERIFICATION_BRIDGE_KEY': role['verification-bridge'],
              'AUTH_TO_SSO_STATUS_KEY': role['provider-status']}
    auth = dict(common)
    for name in ('AUTH_VERIFICATION_AUTHORITY_BRIDGE_ENABLED', 'AUTH_REGISTRATION_AUTHORITY_COMPLETION_ENABLED',
                 'AUTH_REGISTRATION_ELIGIBILITY_GRANTS_ENABLED', 'AUTH_VERIFICATION_PROJECTION_ENABLED',
                 'AUTH_SSO_CODE_INTENT_STAGE_ENABLED', 'AUTH_SSO_CODE_INTENT_CONFIRMATION_ENABLED',
                 'AUTH_MANAGED_BANKING_ENABLED', 'AUTH_BANKING_ELIGIBILITY_ENFORCED'):
        auth[name] = 'true'
    for name in ('AUTH_VERIFICATION_DELIVERY_ENABLED', 'AUTH_VERIFICATION_BRIDGE_ENABLED',
                 'AUTH_SSO_LOGIN_PROOF_ENABLED', 'AUTH_LOGIN_PROOF_BRIDGE_ENABLED'):
        auth[name] = 'false'
    auth.update({'DB_URL': 'jdbc:postgresql://auth-db:5432/bank', 'DB_USER': 'bank', 'DB_PASSWORD': role['auth-db'],
        'PORT': '8080', 'INTERNAL_AUTH_KEY': role['internal'], 'USER_BASE_URL': 'http://user-service:8080',
        'SSO_ENROLLMENT_STATUS_ORIGIN': 'http://sso-service:8080', 'SSO_CLIENT_ID': 'armoney-auth',
        'SSO_CLIENT_SECRET': role['introspection'], 'SSO_INTROSPECTION_URL': 'http://sso-service:8080/sso/realms/armoney/protocol/openid-connect/token/introspect',
        'VERIFICATION_KEY_ID': 'fixture-otp-v1', 'VERIFICATION_OTP_HMAC_KEY': role['otp'],
        'VERIFICATION_DELIVERY_ENCRYPTION_KEY': role['delivery'], 'AUTH_VERIFICATION_AUTHORITY_RETENTION_KEY': role['authority-retention'],
        'AUTH_VERIFICATION_PROJECTION_WRITER_ID': 'auth-projection', 'AUTH_TO_USER_VERIFICATION_PROJECTION_KEY': role['projection-transport'],
        'AUTH_VERIFICATION_PROJECTION_RETENTION_KEY': role['projection-retention'], 'SSO_LOGIN_PROOF_RETENTION_KEY': role['proof-retention'],
        'SSO_LOGIN_PROOF_RETENTION_HMAC_KEY_ID': 'fixture-retention-v1', 'SSO_LOGIN_PROOF_RETENTION_HMAC_KEY': role['intent-retention'],
        'SSO_CODE_INTENT_DETAIL_ENCRYPTION_KEY_ID': 'fixture-aead-v1', 'SSO_CODE_INTENT_DETAIL_ENCRYPTION_KEY': role['intent-encryption'],
        'AUTH_MANAGED_BANKING_KEY_ID': 'fixture-bank-v1', 'AUTH_MANAGED_BANKING_KEY': role['managed-banking']})
    user = {'DB_URL': 'jdbc:postgresql://user-db:5432/users', 'DB_USER': 'users', 'DB_PASSWORD': role['user-db'], 'PORT': '8080',
            'INTERNAL_AUTH_KEY': role['internal'], 'USER_VERIFICATION_PROJECTION_ENABLED': 'true',
            'USER_VERIFICATION_PROJECTION_WRITER_ID': 'auth-projection', 'AUTH_TO_USER_VERIFICATION_PROJECTION_KEY': role['projection-transport']}
    sso = dict(common, ARMONEY_SSO_LEGACY_PAIRS=packed(legacy).decode(), ARMONEY_LOGIN_PROOF_ENABLED='true',
        ARMONEY_VERIFICATION_ENABLED='true', ARMONEY_CODE_SESSION_OBSERVATION_ENABLED='true',
        ARMONEY_AUTH_BRIDGE_URL='http://auth-service:8080', ARMONEY_PROVIDER_BINDING_KEY=role['provider-binding'],
        ARMONEY_CODE_INTENT_CODE_HMAC_KEYS=packed({'fixture-code-v1': role['code-hmac']}).decode(),
        ARMONEY_CODE_INTENT_PURPOSE_MANIFEST=packed(manifest).decode(),
        KC_DB='postgres', KC_DB_URL='jdbc:postgresql://sso-db:5432/keycloak', KC_DB_USERNAME='keycloak', KC_DB_PASSWORD=role['sso-db'],
        KC_BOOTSTRAP_ADMIN_USERNAME='fixture-admin', KC_BOOTSTRAP_ADMIN_PASSWORD=role['bootstrap-admin'],
        KC_HTTP_ENABLED='true', KC_HTTP_RELATIVE_PATH='/sso', KC_HOSTNAME=ORIGIN + '/sso', KC_PROXY_HEADERS='xforwarded')
    sms = {'SMS_SANDBOX_SEND_TOKEN': role['sms-send'], 'SMS_SANDBOX_READ_TOKEN': role['sms-read'], 'SMS_SANDBOX_DATA_DIR': '/tmp/sms-inbox', 'PORT': '8080'}
    controller = dict(auth, PORT='8092', CI='true', PRODUCTION_PROVIDER_ACCEPTANCE_ISOLATED='true',
        PRODUCTION_PROVIDER_ACCEPTANCE_FRESH_FIXTURE='true', PRODUCTION_PROVIDER_ACCEPTANCE_EXCLUSIVE_POLICY_WRITER='true',
        AUTH_PRODUCTION_ACCEPTANCE_KEY=role['controller'], AUTH_PRODUCTION_ACCEPTANCE_ADMIN_SECRET=role['acceptance-admin'],
        AUTH_PRODUCTION_ACCEPTANCE_USERNAME=phone, AUTH_PRODUCTION_ACCEPTANCE_EMAIL=email,
        VERIFICATION_SMS_BASE_URL='http://sms-sandbox:8080', VERIFICATION_SMS_SEND_TOKEN=role['sms-send'],
        SMS_SANDBOX_READ_TOKEN=role['sms-read'], MAILTRAP_SMTP_USERNAME=role['smtp-user'], MAILTRAP_SMTP_PASSWORD=role['smtp-password'],
        MAILTRAP_FROM_EMAIL='sender@fixture.test', MAILTRAP_FROM_NAME='ArMoney disposable acceptance')
    gateway = {'AUTH_BASE_URL': 'http://auth-service:8080', 'USER_BASE_URL': 'http://user-service:8080',
               'WALLET_BASE_URL': 'http://wallet-service:8080', 'PAYMENT_BASE_URL': 'http://payment-service:8080',
               'INTERNAL_AUTH_KEY': role['internal'], 'PORT': '8080'}
    provision = {'CI': 'true', 'CODE_SESSION_ACCEPTANCE_ISOLATED': 'true', 'fresh_realm': True,
        'expected_issuer': ISSUER, 'admin_username': 'fixture-admin', 'admin_password': role['bootstrap-admin'],
        'introspection_secret': role['introspection'], 'acceptance_admin_secret': role['acceptance-admin'], 'purpose_manifest': manifest}
    acceptance = {'version': 1, 'isolated': True, 'origin': ORIGIN, 'ca_file': ca_file, 'controller_origin': ORIGIN,
        'controller_key': role['controller'], 'controller_paths': {name: '/fixture/production-provider/' + name for name in OPERATIONS},
        'gateway_origin': ORIGIN, 'introspection_client': 'armoney-auth', 'introspection_secret': role['introspection'],
        'username': phone, 'email': email, 'password': role['registration-password'], 'first_name': 'Synthetic', 'last_name': 'Acceptance',
        'migration_callbacks': {'account': ORIGIN + '/migration/legacy/account-oidc/callback', 'managed': ORIGIN + '/migration/managed/callback'},
        'account_client': ACCOUNT}
    maps = {'auth.env': auth, 'user.env': user, 'sso.env': sso, 'sms.env': sms, 'controller.env': controller, 'gateway.env': gateway}
    for name, db, username in (('auth', 'bank', 'bank'), ('user', 'users', 'users'), ('sso', 'keycloak', 'keycloak')):
        maps[name + '-db.env'] = {'POSTGRES_DB': db, 'POSTGRES_USER': username, 'POSTGRES_PASSWORD': role[name + '-db']}
    output = {name: env_bytes(values) for name, values in maps.items()}
    output.update({'provision.json': packed(provision), 'acceptance.json': packed(acceptance)})
    require(set(output) == FILENAMES and sum(map(len, output.values())) <= 131072)
    return output


def private_directory(directory):
    require(os.name == 'posix')
    absolute_path(directory)
    require(hasattr(os, 'O_NOFOLLOW') and hasattr(os, 'O_DIRECTORY'))
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    current = os.open('/', flags)
    try:
        parts = PurePosixPath(directory).parts[1:]
        for component in parts[:-1]:
            child = os.open(component, flags, dir_fd=current)
            os.close(current)
            current = child
        try:
            os.mkdir(parts[-1], 0o700, dir_fd=current)
        except FileExistsError:
            pass
        child = os.open(parts[-1], flags, dir_fd=current)
        info = os.fstat(child)
        if not (stat.S_ISDIR(info.st_mode) and info.st_uid == os.geteuid() and stat.S_IMODE(info.st_mode) == 0o700 and not os.listdir(child)):
            os.close(child)
            raise Refused()
        return child
    finally:
        os.close(current)


def initialize(directory, ci, isolated, ca_file='/fixture/ca.crt'):
    try:
        require(os.name == 'posix' and ci is True and isolated is True)
        absolute_path(directory)
        output = bundle(ci, isolated, ca_file)
        root = private_directory(directory)
        try:
            for name, value in sorted(output.items()):
                fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=root)
                with os.fdopen(fd, 'wb') as stream:
                    os.fchmod(stream.fileno(), 0o600)
                    stream.write(value)
                    stream.flush()
                    os.fsync(stream.fileno())
            os.fsync(root)
        finally:
            os.close(root)
        return sorted(output)
    except Exception:
        raise Refused() from None


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise Refused() from None


def main(argv=None):
    parser = Parser(description=__doc__, allow_abbrev=False)
    parser.add_argument('--directory', required=True)
    parser.add_argument('--ci', action='store_true', required=True)
    parser.add_argument('--isolated', action='store_true', required=True)
    parser.add_argument('--ca-file', default='/fixture/ca.crt')
    try:
        args = parser.parse_args(argv)
        names = initialize(args.directory, args.ci, args.isolated, args.ca_file)
        print(json.dumps({'status': 'generated', 'files': names, 'roles': sorted((*BINARY, *OPAQUE)), 'isolation': 'caller_asserted'}, separators=(',', ':')))
        return 0
    except Exception:
        print('{"status":"refused"}')
        return 1


if __name__ == '__main__':
    sys.exit(main())
