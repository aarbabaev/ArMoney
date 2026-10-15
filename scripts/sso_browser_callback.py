"""Browser callback observation shared by disposable acceptance and regression tests."""
import urllib.parse


class BrowserCallbackCapture:
    """Observe redirect-chain requests before the SPA can consume their query."""
    def __init__(self, page, callback):
        self.page = page
        self.callback = urllib.parse.urlsplit(callback)
        self.query = None

    def matches(self, request):
        url = urllib.parse.urlsplit(request.url)
        return (request.is_navigation_request() and
                (url.scheme, url.netloc, url.path) ==
                (self.callback.scheme, self.callback.netloc, self.callback.path))

    def observe(self, request):
        if self.matches(request):
            self.query = urllib.parse.parse_qs(urllib.parse.urlsplit(request.url).query)

    def __enter__(self):
        self.page.on("request", self.observe)
        return self

    def wait(self):
        if self.query is None:
            self.observe(self.page.wait_for_event("request", predicate=self.matches, timeout=30000))
        return self.query

    def __exit__(self, *args):
        self.page.remove_listener("request", self.observe)
