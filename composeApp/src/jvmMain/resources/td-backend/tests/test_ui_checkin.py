from __future__ import annotations

import threading
import unittest
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from auto_td import ui
from auto_td.models import CheckResponse, User


class UiCheckinTest(unittest.TestCase):
    def setUp(self) -> None:
        with ui._CHECKIN_STATUS_LOCK:
            ui._CHECKIN_ACTIVE_REQUEST_ID = None
            ui._CHECKIN_STATUS.update(active=False, phase=None, remaining_seconds=None)
            ui._CHECKIN_CANCEL_EVENT.clear()
            cancelled_requests = getattr(ui, "_CHECKIN_CANCELLED_REQUESTS", None)
            if cancelled_requests is not None:
                cancelled_requests.clear()

    def tearDown(self) -> None:
        with ui._CHECKIN_STATUS_LOCK:
            ui._CHECKIN_ACTIVE_REQUEST_ID = None
            ui._CHECKIN_STATUS.update(active=False, phase=None, remaining_seconds=None)
            ui._CHECKIN_CANCEL_EVENT.clear()
            cancelled_requests = getattr(ui, "_CHECKIN_CANCELLED_REQUESTS", None)
            if cancelled_requests is not None:
                cancelled_requests.clear()

    def test_cancel_during_preparation_stops_before_td_client_is_created(self) -> None:
        storage_started = threading.Event()
        release_storage = threading.Event()
        storage = self._storage()

        def storage_factory():
            storage_started.set()
            self.assertTrue(release_storage.wait(2), "test did not release storage preparation")
            return storage

        service = ui.UiService(storage_factory=storage_factory)
        outcome: dict[str, object] = {}

        def run_checkin() -> None:
            outcome.update(service.checkin_once(rounds=1, request_id="preparing-request"))

        with self._checkin_dependencies(service) as client_type:
            worker = threading.Thread(target=run_checkin)
            worker.start()
            self.assertTrue(storage_started.wait(2), "check-in did not enter preparation")

            cancellation = service.cancel_checkin(request_id="preparing-request")
            release_storage.set()
            worker.join(2)

        self.assertFalse(worker.is_alive(), "cancelled check-in did not return")
        self.assertTrue(cancellation["cancelled"])
        self.assertTrue(outcome["cancelled"])
        client_type.assert_not_called()

    def test_read_only_modes_reject_backend_checkin_before_td_client_is_created(self) -> None:
        storage = self._storage()
        service = ui.UiService(storage_factory=lambda: storage)
        with (
            patch("auto_td.ui.stop_scheduler_process"),
            patch.object(service, "_storage", return_value=storage),
            self._checkin_dependencies(service) as client_type,
        ):
            for mode in ("webvpn",):
                service.set_connection_mode(mode)
                with self.assertRaisesRegex(ValueError, "仅支持查询"):
                    service.checkin_once(rounds=1, request_id=f"readonly-{mode}")
        client_type.assert_not_called()

    def test_cancel_during_wait_prevents_exit_action(self) -> None:
        storage = self._storage()
        service = ui.UiService(storage_factory=lambda: storage)
        wait_started = threading.Event()
        outcome: dict[str, object] = {}

        def cancellable_wait(_seconds, *, request_id, **_progress) -> None:
            wait_started.set()
            while True:
                ui._raise_if_checkin_cancelled(request_id)
                threading.Event().wait(0.005)

        def run_checkin() -> None:
            outcome.update(service.checkin_once(rounds=1, request_id="waiting-request"))

        with self._checkin_dependencies(service) as client_type:
            with patch("auto_td.ui._wait_with_progress", side_effect=cancellable_wait):
                worker = threading.Thread(target=run_checkin)
                worker.start()
                self.assertTrue(wait_started.wait(2), "check-in did not enter its wait")
                cancellation = service.cancel_checkin(request_id="waiting-request")
                worker.join(2)

        self.assertFalse(worker.is_alive(), "cancelled check-in did not leave its wait")
        self.assertTrue(cancellation["cancelled"])
        self.assertTrue(outcome["cancelled"])
        self.assertEqual(1, client_type.return_value.check.call_count)
        self.assertEqual(1, client_type.return_value.upload_photo.call_count)

    def test_cancel_before_checkin_handler_starts_is_scoped_to_request(self) -> None:
        storage = self._storage()
        service = ui.UiService(storage_factory=lambda: storage)

        cancellation = service.cancel_checkin(request_id="cancelled-request")
        with self._checkin_dependencies(service) as client_type:
            other = service.checkin_once(rounds=1, request_id="other-request")
            cancelled = service.checkin_once(rounds=1, request_id="cancelled-request")

        self.assertTrue(cancellation["cancelled"])
        self.assertTrue(other["success"])
        self.assertTrue(cancelled["cancelled"])
        self.assertEqual(2, client_type.return_value.check.call_count)

    def test_rejected_card_response_stops_before_photo_and_exit(self) -> None:
        storage = self._storage()
        service = ui.UiService(storage_factory=lambda: storage)

        with self._checkin_dependencies(service) as client_type:
            client_type.return_value.check.return_value = CheckResponse(False, "服务器拒绝", None)
            result = service.checkin_once(rounds=1, request_id="rejected-request")

        self.assertFalse(result["success"])
        self.assertEqual(0, result["completed_rounds"])
        self.assertIn("服务器拒绝", result["message"])
        self.assertEqual(1, client_type.return_value.check.call_count)
        client_type.return_value.upload_photo.assert_not_called()

    def test_socket_failure_stops_before_photo_and_exit(self) -> None:
        storage = self._storage()
        service = ui.UiService(storage_factory=lambda: storage)

        with self._checkin_dependencies(service) as client_type:
            client_type.return_value.check.side_effect = ConnectionError("socket closed")
            result = service.checkin_once(rounds=1, request_id="socket-request")

        self.assertFalse(result["success"])
        self.assertEqual(0, result["completed_rounds"])
        self.assertIn("socket closed", result["message"])
        self.assertEqual(1, client_type.return_value.check.call_count)
        client_type.return_value.upload_photo.assert_not_called()

    def test_photo_failure_stops_before_exit(self) -> None:
        storage = self._storage()
        service = ui.UiService(storage_factory=lambda: storage)

        with self._checkin_dependencies(service) as client_type:
            client_type.return_value.upload_photo.side_effect = ConnectionError("photo socket closed")
            result = service.checkin_once(rounds=1, request_id="photo-request")

        self.assertFalse(result["success"])
        self.assertEqual(0, result["completed_rounds"])
        self.assertIn("photo socket closed", result["message"])
        self.assertEqual(1, client_type.return_value.check.call_count)
        self.assertEqual(1, client_type.return_value.upload_photo.call_count)

    def test_progress_uses_structured_round_counts_and_phase_values(self) -> None:
        storage = self._storage()
        storage.get_last_exit_request.side_effect = [
            None,
            {"sent_at": ui.datetime.now(ui.BEIJING_TZ).isoformat(), "cooldown_seconds": 10},
            None,
        ]
        service = ui.UiService(storage_factory=lambda: storage)

        with self._checkin_dependencies(service):
            with patch("auto_td.ui._wait_with_progress") as wait:
                result = service.checkin_once(rounds=3, request_id="progress-request")

        self.assertTrue(result["success"])
        progress = [call.kwargs for call in wait.call_args_list]
        self.assertIn(
            {
                "phase": "entrance_delay",
                "message": "第 1/3 次 · 入口后等待",
                "current_round": 1,
                "completed_rounds": 0,
                "request_id": "progress-request",
            },
            progress,
        )
        self.assertIn(
            {
                "phase": "round_delay",
                "message": "第 2/3 次 · 出口后冷却",
                "current_round": 2,
                "completed_rounds": 1,
                "request_id": "progress-request",
            },
            progress,
        )

    @staticmethod
    def _storage() -> MagicMock:
        storage = MagicMock()
        storage.load_settings.return_value = {"schedule": {"windows": ["00:00-23:59"]}}
        storage.load_config.return_value = {
            "server": {"ip": "127.0.0.1", "port": 1},
            "type": 1,
            "schoolno": "10006",
            "eventno": "802",
            "machine": [],
        }
        storage.get_last_exit_request.return_value = None
        return storage

    def _checkin_dependencies(self, service: ui.UiService):
        user = User(
            student_id="20370001",
            card_id="136D731",
            entrance_machine_id=2,
            exit_machine_id=6,
            entrance_image="entrance.jpg",
            exit_image="exit.jpg",
            rounds=1,
            wait_time_min=0,
            wait_time_max=0,
        )
        stack = _PatchStack()
        client_type = stack.enter(patch("auto_td.ui.TDClient"))
        client_type.return_value.check.return_value = CheckResponse(True, "成功", None)
        stack.enter(patch.object(service, "_configured_account", return_value=(user, "学院路校区")))
        stack.enter(patch("auto_td.ui.in_any_window", return_value=True))
        stack.enter(patch("auto_td.ui.stop_scheduler_process"))
        stack.enter(
            patch(
                "auto_td.ui.get_scheduler_status",
                return_value=SimpleNamespace(running=False),
            )
        )
        stack.enter(patch("auto_td.ui._random_wait_seconds", return_value=0))
        stack.enter(patch("auto_td.ui._random_checkin_photo", return_value=b"photo"))
        stack.result = client_type
        return stack


class _PatchStack:
    def __init__(self) -> None:
        self._patchers = []
        self.result = None

    def enter(self, patcher):
        self._patchers.append(patcher)
        return patcher.start()

    def __enter__(self):
        return self.result

    def __exit__(self, exc_type, exc, traceback) -> None:
        while self._patchers:
            self._patchers.pop().stop()


if __name__ == "__main__":
    unittest.main()
