"""Offline pre-I/O ownership regressions; no Docker, Keycloak or browser calls."""
import importlib.util
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("observer_acceptance", Path(__file__).with_name("sso-provider-acceptance.py"))
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)


class OwnershipGuards(unittest.TestCase):
    def environment(self):
        return {**subject.TARGETS, "CI": "true", "OBSERVER_ACCEPTANCE_ISOLATED": "true",
                "OBSERVER_BRIDGE_BIND": "0.0.0.0", "OBSERVER_BRIDGE_PORT": "8090",
                "AUTH_TO_SSO_STATUS_KEY": "a" * 32, "SSO_VERIFICATION_BRIDGE_KEY": "b" * 32,
                "OBSERVER_FIXTURE_CONTROL_KEY": "c" * 32, "ARMONEY_PROVIDER_BINDING_KEY": "d" * 32}

    def rejected_before_dispatch(self, environment):
        for mode in ("bridge", "acceptance"):
            with self.subTest(mode=mode), patch.dict(os.environ, environment, clear=True), \
                    patch.object(sys, "argv", ["sso-provider-acceptance.py", mode]), \
                    patch.object(subject, "acceptance") as browser, \
                    patch.object(subject, "serve_bridge") as bridge, \
                    patch.object(subject, "request") as http, \
                    patch.object(subject.http.server, "ThreadingHTTPServer") as listener:
                with self.assertRaises(SystemExit):
                    subject.main()
                browser.assert_not_called()
                bridge.assert_not_called()
                http.assert_not_called()
                listener.assert_not_called()

    def test_fixed_targets_and_distinct_keys_pass_validation_without_io(self):
        with patch.dict(os.environ, self.environment(), clear=True), patch.object(subject, "request") as http:
            subject.validate_targets()
            subject.validate_keys()
            http.assert_not_called()

    def test_each_arbitrary_or_ambiguous_target_rejected_before_either_mode(self):
        alternatives = ("http://localhost:8080", "http://127.0.0.1:8080", "https://bank.example",
                        "http://sso-a:8080/", "http://sso-a:8080@bank.example", "http://sso-a:8080?x=1",
                        "http://sso-a:8080#fragment", "http://SSO-A:8080", "")
        for name in subject.TARGETS:
            for value in alternatives:
                with self.subTest(name=name, alternative=value):
                    environment = self.environment()
                    environment[name] = value
                    self.rejected_before_dispatch(environment)

    def test_missing_target_rejected_even_in_bridge_mode(self):
        for name in subject.TARGETS:
            environment = self.environment()
            del environment[name]
            self.rejected_before_dispatch(environment)

    def test_listener_cannot_be_retargeted(self):
        for name, value in (("OBSERVER_BRIDGE_BIND", "127.0.0.1"), ("OBSERVER_BRIDGE_PORT", "8080")):
            environment = self.environment()
            environment[name] = value
            self.rejected_before_dispatch(environment)

    def test_all_four_keys_must_be_distinct_printable_and_bounded(self):
        names = ("AUTH_TO_SSO_STATUS_KEY", "SSO_VERIFICATION_BRIDGE_KEY",
                 "OBSERVER_FIXTURE_CONTROL_KEY", "ARMONEY_PROVIDER_BINDING_KEY")
        for name in names:
            for value in ("", "x" * 31, "x" * 513, "x" * 31 + " ", "x" * 31 + "\n", "x" * 31 + "é"):
                environment = self.environment()
                environment[name] = value
                self.rejected_before_dispatch(environment)
        for left in range(len(names)):
            for right in range(left + 1, len(names)):
                environment = self.environment()
                environment[names[left]] = environment[names[right]]
                self.rejected_before_dispatch(environment)


if __name__ == "__main__":
    unittest.main(verbosity=2)
