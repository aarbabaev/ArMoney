"""Exercise urllib redirect/error processing with an in-memory HTTPS transport."""
import io
import unittest
from email.message import Message
from unittest.mock import patch
import urllib.error
import urllib.request
import urllib.response

from .github import GitHubSink, REPOSITORY


class TransportTests(unittest.TestCase):
    def test_redirect_never_replays_authorization_to_another_host(self):
        requests = []

        class SyntheticHTTPS(urllib.request.HTTPSHandler):
            def https_open(self, request):
                requests.append(request)
                headers = Message()
                headers['Location'] = 'https://attacker.invalid/credential-sink'
                response = urllib.response.addinfourl(
                    io.BytesIO(b'{}'), headers, request.full_url, 302)
                response.msg = 'Found'
                return response

        sink = object.__new__(GitHubSink)
        sink._token = 'synthetic-transport-token'
        original = urllib.request.build_opener

        def in_memory_opener(*handlers):
            return original(SyntheticHTTPS(), *handlers)

        with patch('infra.dispatcher.github.urllib.request.build_opener',
                   side_effect=in_memory_opener):
            with self.assertRaises(urllib.error.HTTPError) as caught:
                sink.request('/repos/' + REPOSITORY + '/issues/12')
            self.assertEqual(302, caught.exception.code)
            caught.exception.close()
        self.assertEqual(1, len(requests))
        self.assertEqual('api.github.com', requests[0].host)
        self.assertEqual('Bearer synthetic-transport-token',
                         requests[0].get_header('Authorization'))


if __name__ == '__main__':
    unittest.main()
