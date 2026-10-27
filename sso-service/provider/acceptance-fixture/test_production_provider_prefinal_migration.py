#!/usr/bin/env python3
"""Actual browser callback phase only. No OTP/STAGE/FINAL submission or preservation claim."""
import argparse
from html.parser import HTMLParser
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import time
import urllib.parse
import uuid

SPEC = importlib.util.spec_from_file_location("prefinal_core", Path(__file__).with_name("test_production_provider_acceptance.py"))
C = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(C)
ORIGIN = "https://fixture.local:8443"
FORM, PASSWORD, ACCOUNT_START = "/migration/legacy", "/migration/legacy/password", "/migration/legacy/account-oidc/start"
MANAGED_START, STAGE = "/migration/managed/start", "/migration/legacy/verify/stage"
EMAIL_CANDIDATE = "/migration/legacy/email"
ACCOUNT_CALLBACK, MANAGED_CALLBACK = "/migration/legacy/account-oidc/callback", "/migration/managed/callback"
COOKIE_FORM, COOKIE_ACCOUNT, COOKIE_ATTEMPT = "__Secure-ArMoneyMigrationForm", "__Secure-ArMoneyMigrationAccount", "__Secure-ArMoneyMigrationAttempt"


def configuration(raw):
    value = C.parse_json(raw)
    C.require(type(value) is dict and set(value) == {"mode", "acceptance", "original_owner", "original_session", "legacy_login", "legacy_password"}
              and value.get("mode") in {"local", "sso"}, "prefinal_config")
    data = C.configuration(json.dumps(value["acceptance"]).encode())
    C.require(all(data[k] == ORIGIN for k in ("origin", "gateway_origin", "controller_origin")), "prefinal_origin")
    C.require(type(value["original_owner"]) is str and str(uuid.UUID(value["original_owner"])) == value["original_owner"], "owner_format")
    C.require(type(value["original_session"]) is str and re.fullmatch(r"[A-Za-z0-9_-]{43}", value["original_session"]), "session_format")
    C.require(type(value["legacy_login"]) is str and re.fullmatch(r"[a-z0-9._+-]+@[a-z0-9.-]+\.(test|invalid)", value["legacy_login"]), "legacy_login")
    C.secret(value["legacy_password"])
    return value, data


def receipt(mode, passed=False):
    return {"local_mode": mode == "local", "sso_mode": mode == "sso", "prefinal_browser_verified": passed,
        "otp_submitted": False, "final_submitted": False, "db_preservation_verified": False, "whole_migration_verified": False}


class Forms(HTMLParser):
    def __init__(self, raw):
        super().__init__(convert_charrefs=True); self.forms = {}; self.current = None
        C.require(type(raw) is bytes and len(raw) <= C.LIMIT, "form_bound")
        self.feed(raw.decode("utf-8", "strict")); C.require(self.current is None, "form_closed")
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "form":
            C.require(self.current is None and len(self.forms) < 8 and a.get("method", "").lower() == "post", "form_shape")
            action = a.get("action")
            C.require(action in {PASSWORD, ACCOUNT_START, MANAGED_START, EMAIL_CANDIDATE, STAGE} and action not in self.forms, "form_action")
            self.current = {}; self.forms[action] = self.current
        elif tag == "input" and self.current is not None and a.get("type") == "hidden":
            name, value = a.get("name"), a.get("value")
            C.require(name in {"csrf", "command_id"} and name not in self.current and type(value) is str, "hidden_field")
            self.current[name] = value
    def handle_endtag(self, tag):
        if tag == "form":
            C.require(self.current is not None and set(self.current) == {"csrf", "command_id"}, "form_fields")
            C.require(re.fullmatch(r"[A-Za-z0-9_-]{43}", self.current["csrf"])
                      and str(uuid.UUID(self.current["command_id"])) == self.current["command_id"]
                      and uuid.UUID(self.current["command_id"]).int != 0, "form_binding")
            self.current = None


def cookie(wire, name):
    matches = [c for c in wire.cookies if c.name == name]
    C.require(len(matches) == 1, "migration_cookie")
    c = matches[0]
    C.require(c.domain.lstrip(".") == "fixture.local" and c.secure and c.path == "/migration/" and c.has_nonstandard_attr("HttpOnly")
              and c.get_nonstandard_attr("SameSite") == "Lax" and c.expires is not None
              and 0 < c.expires - time.time() <= 120 and re.fullmatch(r"[A-Za-z0-9_-]{43}", c.value), "migration_cookie")
    return c.value, c.expires


def forms_response(response):
    status, headers, raw = response; C.expected_status(status, 200)
    C.require(headers.get("Content-Type", "").lower().split(";", 1)[0] == "text/html", "html_response")
    return Forms(raw).forms


def post(wire, path, forms, extra=None):
    C.require(path in forms and path in {PASSWORD, ACCOUNT_START, MANAGED_START, EMAIL_CANDIDATE}, "allowed_continuation")
    body = dict(forms[path]); extra = {} if extra is None else extra
    C.require(not set(body).intersection(extra), "form_override")
    C.require(set(extra) == ({"legacy_login", "password"} if path == PASSWORD else {"email"} if path == EMAIL_CANDIDATE else set()), "continuation_fields")
    body.update(extra)
    return wire.request(ORIGIN + path, form=body, headers={"Origin": ORIGIN})


def authorization(location, managed):
    endpoint = ORIGIN + "/sso/realms/armoney/protocol/openid-connect/auth"
    C.require(type(location) is str and location.startswith(endpoint + "?") and "#" not in location and len(location) <= 8192, "authorization")
    try:
        pairs = urllib.parse.parse_qsl(urllib.parse.urlsplit(location).query, strict_parsing=True, keep_blank_values=True, max_num_fields=10)
    except (ValueError, UnicodeError):
        raise C.Rejected("authorization_fields") from None
    fields = dict(pairs)
    C.require(len(fields) == len(pairs) and set(fields) == {"client_id", "redirect_uri", "response_type", "scope", "state", "nonce", "code_challenge", "code_challenge_method", "prompt", "max_age"}, "authorization_fields")
    C.require(fields["client_id"] == (C.CLIENT if managed else C.ACCOUNT_CLIENT)
              and fields["redirect_uri"] == ORIGIN + (MANAGED_CALLBACK if managed else ACCOUNT_CALLBACK)
              and fields["response_type"] == "code" and fields["scope"] == "openid" and fields["code_challenge_method"] == "S256"
              and fields["prompt"] == "login" and fields["max_age"] == "0", "authorization_contract")
    C.require(all(re.fullmatch(r"[A-Za-z0-9_-]{43}", fields[k]) for k in ("state", "nonce", "code_challenge")), "authorization_tokens")
    return fields["state"]


def provider_callback(wire, response, managed, account_value=None):
    status, headers, _ = response; C.expected_status(status, 303)
    location = headers.get("Location", ""); state = authorization(location, managed)
    original_attempt, original_attempt_expiry = cookie(wire, COOKIE_ATTEMPT)
    if managed: C.require(secrets.compare_digest(cookie(wire, COOKIE_ACCOUNT)[0], account_value), "original_account_cookie")
    status, headers, raw = wire.request(location)
    page = C.follow(wire, (status, headers, raw, location))
    page = C.follow(wire, C.submit(wire, page, "kc-form-login", {"username": wire.data["username"], "password": wire.data["password"]}))
    C.expected_status(page[0], 302)
    callback = page[1].get("Location", "")
    C.callback(callback, ORIGIN + (MANAGED_CALLBACK if managed else ACCOUNT_CALLBACK), state, ORIGIN + "/sso/realms/armoney")
    current_attempt, current_attempt_expiry = cookie(wire, COOKIE_ATTEMPT)
    C.require(secrets.compare_digest(current_attempt, original_attempt) and current_attempt_expiry == original_attempt_expiry, "original_attempt_cookie")
    if managed: C.require(secrets.compare_digest(cookie(wire, COOKIE_ACCOUNT)[0], account_value), "original_account_cookie")
    # Request the ACTUAL untouched callback with the original cookie jar. Never redeem the code ourselves.
    return forms_response(wire.request(callback))


def execute(value, data, *, factory=C.Wire, deadline=None):
    deadline = min(time.monotonic() + 120, deadline if deadline is not None else float("inf"))
    C.require(time.monotonic() < deadline, "deadline")
    wire = factory(data); wire.deadline = deadline
    forms = forms_response(wire.request(ORIGIN + FORM))
    C.require(set(forms) == {PASSWORD, ACCOUNT_START}, "initial_forms")
    _, expires = cookie(wire, COOKIE_FORM)
    wire.deadline = min(wire.deadline, time.monotonic() + expires - time.time())
    if value["mode"] == "local":
        forms = forms_response(post(wire, PASSWORD, forms, {"legacy_login": value["legacy_login"], "password": value["legacy_password"]}))
    else:
        forms = provider_callback(wire, post(wire, ACCOUNT_START, forms), False)
    if set(forms) == {EMAIL_CANDIDATE}:
        C.require(value["mode"] == "sso", "email_candidate_mode")
        original_account = cookie(wire, COOKIE_ACCOUNT)
        forms = forms_response(post(wire, EMAIL_CANDIDATE, forms, {"email": data["email"]}))
        current_account = cookie(wire, COOKIE_ACCOUNT)
        C.require(secrets.compare_digest(original_account[0], current_account[0]) and current_account[1] <= original_account[1], "original_account_cookie")
    C.require(set(forms) == {MANAGED_START}, "account_complete")
    account_value, _ = cookie(wire, COOKIE_ACCOUNT)
    forms = provider_callback(wire, post(wire, MANAGED_START, forms), True, account_value)
    C.require(set(forms) == {STAGE} and time.monotonic() < wire.deadline, "prefinal_stage")
    return receipt(value["mode"], True)


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--input", required=True); parser.add_argument("--result", required=True)
    args = parser.parse_args(); result, fd = receipt("local"), None
    try:
        value, data = configuration(C.read_private(args.input)); result = receipt(value["mode"])
        fd = os.open(args.result, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        os.write(fd, json.dumps(result).encode()); os.fsync(fd)
        result = execute(value, data)
    except Exception: pass
    finally:
        if fd is not None:
            os.lseek(fd, 0, os.SEEK_SET); os.ftruncate(fd, 0)
            os.write(fd, json.dumps(result, separators=(",", ":")).encode()); os.fsync(fd); os.close(fd)
    return 0 if result["prefinal_browser_verified"] else 1


if __name__ == "__main__": raise SystemExit(main())
