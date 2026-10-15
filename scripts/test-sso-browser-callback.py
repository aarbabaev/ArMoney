"""Synthetic loopback browser regression; no Keycloak, bank stack or credentials.

Run after installing Playwright and Chromium. --browser-executable optionally
selects an existing local Chromium executable without downloading another one.
"""
import argparse
import http.server
import threading
import urllib.parse

from playwright.sync_api import sync_playwright
from sso_browser_callback import BrowserCallbackCapture


class Fixture(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        path = urllib.parse.urlsplit(self.path)
        if path.path == "/authorize":
            self.send_response(302)
            self.send_header("Location", "/oauth/callback?" + path.query)
            self.end_headers()
        else:
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.end_headers()
            self.wfile.write(b'<script>history.replaceState(null,"","/home")</script>synthetic callback')

    def log_message(self, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--browser-executable")
    args = parser.parse_args()
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    origin = "http://127.0.0.1:" + str(server.server_port)
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(executable_path=args.browser_executable)
            page = browser.new_page()
            page.set_default_timeout(5000)
            old_capture = []

            def original_callback(route):
                old_capture.append(True)
                route.fulfill(body="intercepted")

            page.route("**/oauth/callback**", original_callback)
            page.goto(origin + "/authorize?error=invalid_request&state=synthetic")
            assert old_capture == [], "Original route defect must reproduce on an HTTP redirect"
            page.unroute("**/oauth/callback**", original_callback)
            for query in ("error=invalid_request&state=synthetic", "code=synthetic-code&state=synthetic"):
                with BrowserCallbackCapture(page, origin + "/oauth/callback") as capture:
                    page.goto(origin + "/authorize?" + query)
                    assert capture.wait() == urllib.parse.parse_qs(query)
                    assert page.url == origin + "/home", "Capture must survive SPA URL cleanup"
                previous = capture.query.copy()
                page.goto(origin + "/authorize?state=another")
                assert capture.query == previous, "Listener must be removed between cases"
            for other in (origin + "/oauth/callback/extra",
                          origin.replace("127.0.0.1", "localhost") + "/oauth/callback"):
                with BrowserCallbackCapture(page, other) as capture:
                    page.goto(origin + "/authorize?code=synthetic-code&state=synthetic")
                    assert capture.query is None, "Callback must match exact origin and path"
            with BrowserCallbackCapture(page, origin + "/oauth/callback") as capture:
                page.goto(origin + "/home")
                page.evaluate("fetch('/oauth/callback?code=synthetic-code')")
                assert capture.query is None, "Subresource requests must not count as callbacks"
            browser.close()
        print("PASS: original redirect-route defect reproduced; error/code capture, SPA cleanup, "
              "listener cleanup, exact origin/path and navigation-only checks passed")
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


if __name__ == "__main__":
    main()
