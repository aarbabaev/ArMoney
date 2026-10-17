"""Offline/synthetic checks; no Keycloak, browser, Docker or banking requests."""
import contextlib
import importlib.util
import io
import http.server
import ipaddress
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
import threading
import time
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
        H.failure_diagnostic = None

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
                (b'{}',200,"slow",False)):
            chunks=[payload[i:i+4096] for i in range(0,len(payload),4096)]+[b'']
            response=SimpleNamespace(status=status,headers={"Cache-Control":"no-store","Content-Type":"application/json"},read1=Mock(side_effect=chunks))
            connection=Mock(sock=Mock(),getresponse=Mock(return_value=response))
            if clock: response.read1.side_effect=TimeoutError("synthetic timeout")
            def owned(address,guard):
                guard.physical=connection.sock
                return connection
            with patch.object(H,"OwnedConnection",side_effect=owned), \
                    patch.object(H,"resolve",return_value=ipaddress.ip_address("127.0.0.1")), \
                    patch.dict(H.os.environ,{"AUTH_TO_SSO_STATUS_KEY":"synthetic"}):
                if success:
                    self.assertEqual(value(),H.observe("http://sso-b:8080",BINDING))
                else:
                    with self.assertRaises((AssertionError,ValueError,TimeoutError)):
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

    def test_actual_connection_close_status_and_shape_have_finite_diagnostics(self):
        original = H.OwnedConnection
        for status,payload,expected_stage in ((200,json.dumps(value()).encode(),None),
                (503,b'',"STATUS"),(200,json.dumps({k:v for k,v in value().items() if k!="valid_until"}).encode(),"DTO_FIELDS"),
                (200,json.dumps(value()).encode(),"HTTP_BODY")):
            class Handler(http.server.BaseHTTPRequestHandler):
                def log_message(self,*args): pass
                def do_POST(self):
                    self.rfile.read(int(self.headers.get("Content-Length","0")))
                    self.send_response(status)
                    self.send_header("Content-Type","application/json")
                    self.send_header("Cache-Control","no-store")
                    self.send_header("Content-Length",str(len(payload)+(1 if expected_stage=="HTTP_BODY" else 0)))
                    self.send_header("Connection","close")
                    self.end_headers()
                    self.wfile.write(payload)
            server=http.server.HTTPServer(("127.0.0.1",0),Handler)
            worker=threading.Thread(target=server.serve_forever,kwargs={"poll_interval":0.01})
            worker.start()
            output=io.StringIO()
            try:
                def local(address,guard):
                    connection=original(address,guard)
                    connection.port=server.server_port
                    return connection
                with patch.object(H,"OwnedConnection",side_effect=local),patch.object(H,"resolve",return_value=ipaddress.ip_address("127.0.0.1")), \
                        patch.dict(H.os.environ,{"AUTH_TO_SSO_STATUS_KEY":"synthetic"}),contextlib.redirect_stdout(output),contextlib.redirect_stderr(output):
                    if expected_stage is None:
                        self.assertEqual(value(),H.observe("http://sso-b:8080",BINDING))
                    else:
                        with self.assertRaises(AssertionError) as error: H.observe("http://sso-b:8080",BINDING)
                        H.diagnostic(error.exception)
                        self.assertIn("BOUNDARY: CODE_SESSION "+expected_stage+" HTTP_200 HTTP_"+str(status)+" ASSERT",output.getvalue())
            finally:
                server.shutdown()
                server.server_close()
                worker.join(3)
            self.assertFalse(worker.is_alive())
            self.assertNotIn(PRIVATE,output.getvalue())

    def test_original_dto_failure_category_survives_realm_cleanup_transport_error(self):
        def request(method,target,data=None,headers=None,form=False):
            if method=="DELETE": raise OSError(PRIVATE)
            if target.endswith("/token"): return 200,{"access_token":"synthetic"},{}
            if target.endswith("openid-configuration"): return 200,{},{}
            if method=="GET" and "/users?" in target: return 200,[{"id":"synthetic-subject"}],{}
            if method=="GET" and "/clients?" in target: return 200,[{"id":"synthetic-client"}],{}
            if method=="GET": return 404,None,{}
            return 201,None,{}
        def observe(*args,**kwargs):
            H.checkpoint("DTO_FIELDS")
            raise AssertionError(PRIVATE)
        output=io.StringIO()
        with patch.dict(H.os.environ,{"CODE_SESSION_ACCEPTANCE_ISOLATED":"true", "SSO_ADMIN_PASSWORD":"synthetic",
                "VISIBILITY_NODE_A_URL":"http://sso-a:8080","VISIBILITY_NODE_B_URL":"http://sso-b:8080",
                "VISIBILITY_ADMIN_URL":"http://sso-a:8080"}),patch.object(H.V,"validate_configuration"), \
                patch.object(H.HTTP,"request",side_effect=request),patch.object(H,"session",return_value=BINDING), \
                patch.object(H,"observe",side_effect=observe),contextlib.redirect_stdout(output),contextlib.redirect_stderr(output):
            with self.assertRaises(SystemExit): H.main()
        self.assertIn("BOUNDARY: CODE_SESSION DTO_FIELDS HTTP_NONE HTTP_NONE ASSERT",output.getvalue())
        self.assertNotIn("TRANSPORT",output.getvalue())
        self.assertNotIn(PRIVATE,output.getvalue())

    def test_real_drip_headers_are_cut_off_by_absolute_deadline_and_timer_is_joined(self):
        stopped=threading.Event()
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_POST(self):
                try:
                    self.connection.sendall(b"HTTP/1.1 200 OK\r\nX-Drip: ")
                    for unused in range(60):
                        if stopped.wait(0.1): break
                        self.connection.sendall(b"x")
                except OSError: pass
        server=http.server.HTTPServer(("127.0.0.1",0),Handler)
        worker=threading.Thread(target=server.serve_forever,kwargs={"poll_interval":0.01})
        worker.start()
        original=H.OwnedConnection
        guards=[]
        def local(address,guard):
            guards.append(guard)
            connection=original(address,guard)
            connection.port=server.server_port
            return connection
        started=time.monotonic()
        try:
            with patch.object(H,"OwnedConnection",side_effect=local),patch.object(H,"resolve",return_value=ipaddress.ip_address("127.0.0.1")):
                with self.assertRaises(TimeoutError): H.exchange("http://sso-b:8080",BINDING,"synthetic")
            self.assertLess(time.monotonic()-started,4.5)
            self.assertTrue(guards[0].expired.is_set())
            self.assertFalse(guards[0].timer.is_alive())
            self.assertEqual(-1,guards[0].physical.fileno())
        finally:
            stopped.set()
            server.shutdown()
            server.server_close()
            worker.join(3)
        self.assertFalse(worker.is_alive())

    def test_stalled_resolver_child_is_killed_reaped_and_eof_fails_closed(self):
        original=H.subprocess.Popen
        children=[]
        def spawn(*args,**kwargs):
            child=original(*args,**kwargs)
            children.append(child)
            return child
        for program,expected in (("import time;time.sleep(10)",TimeoutError),("",ValueError)):
            guard=H.Deadline(time.monotonic()+0.2)
            started=time.monotonic()
            try:
                with patch.object(H,"RESOLVER",program),patch.object(H.subprocess,"Popen",side_effect=spawn):
                    with self.assertRaises(expected): H.resolve("sso-b",guard)
                self.assertLess(time.monotonic()-started,1.5)
                self.assertIsNotNone(children[-1].poll())
                self.assertIsNone(guard.child)
            finally: guard.close()
            self.assertFalse(guard.timer.is_alive())

    def test_real_close_delimited_drip_body_is_interrupted_on_captured_socket(self):
        stopped=threading.Event()
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_POST(self):
                try:
                    self.connection.sendall(b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n")
                    for unused in range(60):
                        if stopped.wait(0.1): break
                        self.connection.sendall(b"x")
                except OSError: pass
        server=http.server.HTTPServer(("127.0.0.1",0),Handler)
        worker=threading.Thread(target=server.serve_forever,kwargs={"poll_interval":0.01})
        worker.start()
        original=H.OwnedConnection
        guards=[]
        connections=[]
        def local(address,guard):
            guards.append(guard)
            connection=original(address,guard)
            connection.port=server.server_port
            connections.append(connection)
            return connection
        started=time.monotonic()
        try:
            with patch.object(H,"OwnedConnection",side_effect=local),patch.object(H,"resolve",return_value=ipaddress.ip_address("127.0.0.1")):
                with self.assertRaises(TimeoutError): H.exchange("http://sso-b:8080",BINDING,"synthetic")
            self.assertLess(time.monotonic()-started,4.5)
            self.assertIsNone(connections[0].sock)
            self.assertFalse(guards[0].timer.is_alive())
            self.assertEqual(-1,guards[0].physical.fileno())
        finally:
            stopped.set()
            server.shutdown()
            server.server_close()
            worker.join(3)
        self.assertFalse(worker.is_alive())

    def test_resolver_rejects_special_use_public_and_mixed_answers_before_any_tcp(self):
        invalid = ("127.0.0.1","127.255.255.254","::1","0.0.0.0","::","169.254.169.254","fe80::1",
            "224.0.0.1","ff02::1","240.0.0.1","2001:db8::1","8.8.8.8","2001:4860:4860::8888",
            "192.0.0.1","172.15.255.255","172.32.0.1","192.169.0.1","::ffff:10.0.0.1")
        for address in invalid:
            for answers in ([address],["10.20.30.40",address]):
                with self.subTest(answers=answers):
                    child=Mock(returncode=0,poll=Mock(return_value=0),
                        communicate=Mock(return_value=(json.dumps(answers).encode(),None)))
                    with patch.object(H.subprocess,"Popen",return_value=child),patch.object(H,"OwnedConnection") as tcp:
                        with self.assertRaises(AssertionError): H.exchange("http://sso-b:8080",BINDING,"synthetic")
                        tcp.assert_not_called()
                    self.assertGreaterEqual(child.communicate.call_count,2,"Resolver must be reaped")

    def test_resolver_accepts_only_rfc1918_and_ula_without_runtime_test_bypass(self):
        for address in ("10.0.0.1","172.16.0.1","172.31.255.254","192.168.0.1","fc00::1","fdff::1"):
            child=Mock(returncode=0,poll=Mock(return_value=0),
                communicate=Mock(return_value=(json.dumps([address]).encode(),None)))
            guard=H.Deadline(time.monotonic()+4)
            try:
                with patch.object(H.subprocess,"Popen",return_value=child):
                    self.assertEqual(ipaddress.ip_address(address),H.resolve("sso-b",guard))
            finally: guard.close()
        # The earlier actual loopback HTTP tests patch resolve in unittest only;
        # no environment switch or production resolver exception admits loopback.
        self.assertFalse(H.fixture_address(ipaddress.ip_address("127.0.0.1")))

    def test_null_contact_fixture_requires_blank_clear_readback_and_restoration(self):
        for mode in ("success","retained","empty","observer_mismatch","read_failure","restore_mismatch"):
            with self.subTest(mode=mode):
                H.failure_diagnostic=None
                state={"email":"synthetic@example.invalid"}
                calls=[]
                reads=[]
                def command(method,path,data=None,expected=204):
                    calls.append((method,data,expected))
                    if method=="PUT":
                        if data["email"]=="":
                            state["email"]=("synthetic@example.invalid" if mode=="retained" else "" if mode=="empty" else None)
                        else: state["email"]="wrong@example.invalid" if mode=="restore_mismatch" else data["email"]
                    else:
                        reads.append(True)
                        if mode=="read_failure" and len(reads)==1: raise OSError(PRIVATE)
                        return {"id":BINDING["subject"],"username":"legacy-user","email":state["email"]}
                def observe(*args):
                    self.assertEqual(1,len(reads),"Readback is mandatory before observer")
                    self.assertIsNone(state["email"])
                    result=value()
                    if mode=="observer_mismatch": result["email"]="retained@example.invalid"
                    return H.validate(result,BINDING)
                output=io.StringIO()
                with patch.object(H,"observe",side_effect=observe) as observer,contextlib.redirect_stdout(output),contextlib.redirect_stderr(output):
                    if mode=="success":
                        H.null_contacts(command,"http://sso-b:8080",BINDING,"legacy-user","synthetic@example.invalid")
                    else:
                        with self.assertRaises((AssertionError,OSError)) as error:
                            H.null_contacts(command,"http://sso-b:8080",BINDING,"legacy-user","synthetic@example.invalid")
                        H.diagnostic(error.exception)
                self.assertEqual(("PUT",{"email":""},204),calls[0])
                self.assertEqual(("PUT",{"email":"synthetic@example.invalid"},204),calls[-2])
                self.assertEqual("GET",calls[-1][0],"Restoration needs readback")
                self.assertEqual(2,len(reads))
                if mode in {"retained","empty","read_failure"}: observer.assert_not_called()
                if mode=="observer_mismatch": self.assertIn("BOUNDARY: CODE_SESSION NULL_CONTACT_VALUE",output.getvalue())
                if mode in {"retained","empty","read_failure","restore_mismatch"}: self.assertIn("BOUNDARY: CODE_SESSION ADMIN_CONTACT_READBACK",output.getvalue())
                self.assertNotIn(PRIVATE,output.getvalue())
                self.assertNotIn("synthetic@example.invalid",output.getvalue())

    def test_session_browser_failure_category_survives_cancel_transport_error(self):
        def control(node,operation,body):
            if operation=="cancel": raise OSError(PRIVATE)
            return {"experiment_token":"synthetic"}
        def held(*args):
            raise AssertionError(PRIVATE)
        output=io.StringIO()
        with patch.object(H.V,"control",side_effect=control),patch.object(H.V,"wait_held",side_effect=held), \
                patch.object(H.V,"browser_login",return_value=None),contextlib.redirect_stdout(output),contextlib.redirect_stderr(output):
            with self.assertRaises(OSError) as error: H.session("http://sso-a:8080","subject",PRIVATE,PRIVATE)
            H.diagnostic(error.exception)
        self.assertIn("BOUNDARY: CODE_SESSION SESSION_HELD HTTP_NONE HTTP_NONE ASSERT",output.getvalue())
        self.assertNotIn("TRANSPORT",output.getvalue())
        self.assertNotIn(PRIVATE,output.getvalue())


if __name__ == "__main__":
    unittest.main()
