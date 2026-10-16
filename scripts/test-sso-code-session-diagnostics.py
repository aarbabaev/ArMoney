"""Offline/synthetic checks; no Keycloak, browser, Docker or banking requests."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location("code_session_harness", Path(__file__).with_name("sso-code-session-acceptance.py"))
H = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(H)
PRIVATE = "synthetic-private-code-contact@example.invalid"
BINDING = {"issuer":"http://sso-a:8080/realms/code-session-fixture", "subject":"synthetic-subject",
           "client_id":"armoney-ios", "user_session_id":"synthetic-session"}


def value():
    return {**BINDING,"username":"synthetic-user","email":None,"phone_number":None,"enabled":True,
            "user_session_active":True,"client_session_active":True,"observed_at":"2026-10-17T00:00:00Z",
            "valid_until":"2026-10-17T00:00:05Z"}


class CodeSessionDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        H.case_stop = H.stop = None

    def test_exact_twelve_and_disabled_negative_with_null_contacts(self):
        self.assertEqual(value(),H.validate(value(),BINDING))
        negative = dict(value(),enabled=False)
        self.assertEqual(negative,H.validate(negative,BINDING,enabled=False))
        with self.assertRaises(AssertionError): H.validate(negative,BINDING)

    def test_old_shapes_binding_mismatch_types_and_noncanonical_contacts_deny(self):
        for invalid in ({k:v for k,v in value().items() if k!="valid_until"},
                        {k:v for k,v in value().items() if k not in {"valid_until","observed_at","client_session_active","client_id"}},
                        dict(value(),extra=PRIVATE),dict(value(),subject=PRIVATE),dict(value(),user_session_active=1),
                        dict(value(),client_session_active=False),dict(value(),email="Person@example.invalid"),
                        dict(value(),phone_number=PRIVATE)):
            with self.subTest(keys=set(invalid)):
                with self.assertRaises(AssertionError): H.validate(invalid,BINDING)

    def test_timestamp_delta_floor_shape_expiry_and_budget_boundaries(self):
        for until in ("2026-10-17T00:00:00Z","2026-10-17T00:00:06Z","2026-10-17T00:00:05.0Z",
                      "2026-10-17T00:00:05+00:00",PRIVATE):
            with self.assertRaises((AssertionError,ValueError)):
                H.validate(dict(value(),valid_until=until),BINDING)

    def test_expiry_requires_actual_unavailable_not_timeout_or_wrong_status(self):
        good=(200,value(),{"Cache-Control":"no-store"})
        expired=(503,None,{"Cache-Control":"no-store"})
        with patch.dict(H.os.environ,{"AUTH_TO_SSO_STATUS_KEY":"synthetic"}), \
                patch.object(H,"exchange",side_effect=[good,expired]) as exchange,patch.object(H.time,"sleep"):
            H.wait_expired("http://sso-b:8080",BINDING)
            self.assertEqual(2,exchange.call_count)
        with patch.dict(H.os.environ,{"AUTH_TO_SSO_STATUS_KEY":"synthetic"}), \
                patch.object(H,"exchange",return_value=(404,None,{"Cache-Control":"no-store"})):
            with self.assertRaises(AssertionError): H.wait_expired("http://sso-b:8080",BINDING)

    def test_http_decodes_strict_json_and_rejects_redirect_oversize_and_slow_body(self):
        for payload,status,clock,success in ((json.dumps(value()).encode(),200,None,True),
                (b'{"issuer":"a","issuer":"b"}',200,None,False),
                (b'x'*12289,200,None,False),(b'',302,None,False),
                (b'{}',200,[0,0,0,0,0,5],False)):
            chunks=[payload[i:i+4096] for i in range(0,len(payload),4096)]+[b'']
            response=SimpleNamespace(status=status,headers={"Cache-Control":"no-store","Content-Type":"application/json"},read1=Mock(side_effect=chunks))
            connection=Mock(sock=Mock(),getresponse=Mock(return_value=response))
            with patch.object(H.http.client,"HTTPConnection",return_value=connection), \
                    patch.object(H.time,"monotonic",side_effect=clock) if clock else contextlib.nullcontext(), \
                    patch.dict(H.os.environ,{"AUTH_TO_SSO_STATUS_KEY":"synthetic"}):
                if success:
                    self.assertEqual(value(),H.observe("http://sso-b:8080",BINDING))
                else:
                    with self.assertRaises((AssertionError,ValueError)):
                        H.observe("http://sso-b:8080",BINDING)
            connection.close.assert_called_once()
            self.assertEqual(1,connection.request.call_count,"No redirect or retry")

    def test_failure_reports_only_finite_case_and_never_exception_text(self):
        output=io.StringIO()
        with patch.object(H,"acceptance",side_effect=RuntimeError(PRIVATE)),contextlib.redirect_stdout(output),contextlib.redirect_stderr(output):
            with self.assertRaises(SystemExit) as error: H.main()
        self.assertEqual("FAIL: CODE_SESSION CONFIGURATION",str(error.exception))
        self.assertNotIn(PRIVATE,output.getvalue()+str(error.exception))
        with self.assertRaises(AssertionError): H.case(PRIVATE)

    def test_session_requires_completed_browser_and_cancels_hold_on_failure(self):
        controls=[]
        def control(node,operation,body):
            controls.append(operation)
            return {"experiment_token":"synthetic-token"}
        with patch.object(H.V,"control",side_effect=control), \
                patch.object(H.V,"wait_held",return_value={"subject":BINDING["subject"],"client_id":"armoney-ios","user_session_id":"session"}), \
                patch.object(H.V,"browser_login",side_effect=lambda *a:a[-1].finish(failed=True)), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(AssertionError): H.session("http://sso-a:8080",BINDING["subject"],PRIVATE,PRIVATE)
        self.assertEqual(["arm","release","cancel"],controls)


if __name__ == "__main__":
    unittest.main()
