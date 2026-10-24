"""Manual read-only Mac backend check; session material arrives in memory on stdin."""
import json
import sys
from datetime import date

from auto_td.healthcloud import HealthCloudClient

try:
    session = json.load(sys.stdin)
    client = HealthCloudClient(webvpn=True, gateway_cookies=session["cookies"])
    original_open = client.opener.open

    def read_only_open(request, *args, **kwargs):
        if request.data and b"password=" in request.data:
            raise RuntimeError("credential submission is forbidden in this saved-session probe")
        return original_open(request, *args, **kwargs)

    client.opener.open = read_only_open
    client.query_today_valid_exit_count(session["username"], "must-not-be-transmitted", date.today())
    print("RESULT mac-python-health OK")
except Exception as error:
    print("RESULT mac-python-health FAILED " + type(error).__name__)
    sys.exit(1)
