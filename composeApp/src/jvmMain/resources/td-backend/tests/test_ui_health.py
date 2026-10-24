import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.request import urlopen
from unittest.mock import MagicMock

from auto_td.ui import _make_handler


class UiHealthTest(unittest.TestCase):
    def test_readiness_does_not_read_account_state_or_password_vault(self):
        service = MagicMock()
        service.get_state.side_effect = AssertionError("readiness must not read credentials")
        server = ThreadingHTTPServer(("127.0.0.1", 0), _make_handler(service))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with urlopen(f"http://127.0.0.1:{server.server_port}/api/health") as response:
                self.assertEqual({"ready": True}, json.load(response))
            service.get_state.assert_not_called()
        finally:
            server.shutdown()
            server.server_close()
            thread.join()
