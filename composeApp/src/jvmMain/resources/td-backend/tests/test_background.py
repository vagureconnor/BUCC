from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path
from unittest.mock import patch

from auto_td import background
from auto_td.storage import AppStorage


class BackgroundProcessTest(unittest.TestCase):
    def test_two_concurrent_starts_share_one_worker_lock_owner(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            helper = home / "worker.py"
            helper.write_text(
                textwrap.dedent(
                    """
                    import argparse
                    import time
                    from pathlib import Path

                    from auto_td.background import (
                        acquire_scheduler_worker,
                        release_scheduler_worker,
                        scheduler_stop_requested,
                    )
                    from auto_td.storage import AppStorage

                    parser = argparse.ArgumentParser()
                    parser.add_argument("--home", required=True)
                    parser.add_argument("--daemon-token", required=True)
                    args = parser.parse_args()
                    storage = AppStorage(Path(args.home))
                    owner = acquire_scheduler_worker(storage, args.daemon_token)
                    if owner is None:
                        raise SystemExit(0)
                    try:
                        while not scheduler_stop_requested(storage, args.daemon_token):
                            time.sleep(0.02)
                    finally:
                        release_scheduler_worker(storage, args.daemon_token, owner)
                    """
                ),
                encoding="utf-8",
            )
            starter = textwrap.dedent(
                """
                import json
                import sys
                from pathlib import Path
                from auto_td.background import start_scheduler_process
                from auto_td.storage import AppStorage

                result = start_scheduler_process(
                    AppStorage(Path(sys.argv[1])),
                    worker_command=[sys.executable, sys.argv[2], "--home", sys.argv[1]],
                )
                print(json.dumps({"pid": result.pid, "already_running": result.already_running}))
                """
            )
            command = [sys.executable, "-c", starter, str(home), str(helper)]
            first = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            second = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            worker_pid = None
            try:
                first_stdout, first_stderr = first.communicate(timeout=10)
                second_stdout, second_stderr = second.communicate(timeout=10)
                self.assertEqual(0, first.returncode, first_stderr)
                self.assertEqual(0, second.returncode, second_stderr)
                results = [json.loads(first_stdout), json.loads(second_stdout)]
                worker_pid = results[0]["pid"]

                self.assertEqual(results[0]["pid"], results[1]["pid"])
                self.assertEqual([False, True], sorted(item["already_running"] for item in results))
                self.assertTrue(background.get_scheduler_status(AppStorage(home)).running)

                stopped = background.stop_scheduler_process(AppStorage(home), timeout=3)
                self.assertTrue(stopped.stopped, stopped.message)
                self.assertFalse(background.get_scheduler_status(AppStorage(home)).running)
                stopped_again = background.stop_scheduler_process(AppStorage(home), timeout=0.1)
                self.assertFalse(stopped_again.stopped)
            finally:
                for process in (first, second):
                    if process.poll() is None:
                        process.kill()
                if worker_pid and background.is_process_running(worker_pid):
                    os.kill(worker_pid, 15)

    def test_identity_mismatch_is_stale_and_never_signalled(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            storage = AppStorage(Path(tmp))
            background.write_pid_file(
                storage.pid_path,
                pid=43210,
                token="expected-token",
                started_at="expected-start",
            )

            with (
                patch.object(background, "_scheduler_lock_is_held", return_value=True),
                patch.object(background, "is_process_running", return_value=True),
                patch.object(background, "process_identity_matches", return_value=False),
                patch.object(background, "terminate_process") as terminate,
            ):
                status = background.get_scheduler_status(storage)
                stopped = background.stop_scheduler_process(storage, timeout=0)

            self.assertFalse(status.running)
            self.assertTrue(status.stale)
            self.assertFalse(stopped.stopped)
            terminate.assert_not_called()


if __name__ == "__main__":
    unittest.main()
