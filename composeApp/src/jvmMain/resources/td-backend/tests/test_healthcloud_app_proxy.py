import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from auto_td.healthcloud import HealthCloudClient

class AppProxyTest(unittest.TestCase):
    def test_explicit_loopback_proxy_routes_campus_request_without_system_changes(self):
        seen = []
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                seen.append(self.path)
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"campus-fixture")
            def log_message(self, *args):
                pass
        server = HTTPServer(("127.0.0.1", 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            client = HealthCloudClient(proxy_url=f"http://127.0.0.1:{server.server_port}")
            with client.opener.open("http://campus-fixture.invalid/status", timeout=2) as response:
                self.assertEqual(b"campus-fixture", response.read())
            self.assertEqual(["http://campus-fixture.invalid/status"], seen)
        finally:
            server.shutdown()
            server.server_close()
            worker.join(2)

    def test_non_loopback_proxy_is_rejected(self):
        with self.assertRaises(ValueError):
            HealthCloudClient(proxy_url="http://192.0.2.1:8080")
