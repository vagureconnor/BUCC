from __future__ import annotations

import unittest
from datetime import datetime
from unittest.mock import MagicMock, patch

from auto_td import ui
from auto_td.constants import BEIJING_TZ
from auto_td.models import User


class UiSessionCredentialTest(unittest.TestCase):
    def test_gateway_state_does_not_request_platform_password_vault(self) -> None:
        credential_store = MagicMock()
        credential_store.get.side_effect = AssertionError("gateway state must not consult Keychain")
        service = ui.UiService(credential_store=credential_store)
        for mode in ("webvpn",):
            service._connection_mode = mode
            self.assertFalse(service._has_healthcloud_password("fixture"))
        credential_store.get.assert_not_called()

    def test_refresh_counts_selects_direct_and_webvpn_routes_by_mode(self) -> None:
        storage = MagicMock()
        storage.get_daily_td_count_info.return_value = None
        storage.get_healthcloud_total_info.return_value = None
        storage.load_config.return_value = {"eventno": "802"}
        credential_store = MagicMock()
        healthcloud = MagicMock()
        healthcloud.query_counts.return_value = {
            "today_count": 0,
            "term": "2026-2027-1",
            "total": None,
            "total_error": None,
        }
        user = User(
            student_id="20370001",
            card_id="136D731",
            entrance_machine_id=2,
            exit_machine_id=6,
            entrance_image="entrance.jpg",
            exit_image="exit.jpg",
        )
        now = datetime(2026, 10, 12, 9, 0, tzinfo=BEIJING_TZ)
        with (
            patch("auto_td.ui.HealthCloudClient") as client_factory,
            patch.object(ui.UiService, "_storage", return_value=storage),
            patch.object(ui.UiService, "_configured_account", return_value=(user, "学院路")),
            patch.object(ui.UiService, "_healthcloud_username", return_value=user.student_id),
            patch("auto_td.ui._healthcloud_cache_is_recent", return_value=False),
        ):
            client_factory.return_value = healthcloud
            service = ui.UiService(credential_store=credential_store)
            service.set_connection_mode("direct")
            service.refresh_counts(
                now=now,
                credentials={"student_id": user.student_id, "password": "session-only", "connection_mode": "direct"},
            )
            service.set_connection_mode("webvpn")
            service.refresh_counts(
                now=now,
                credentials={
                    "student_id": user.student_id,
                    "password": "session-only",
                    "connection_mode": "webvpn",
                    "gateway_cookies": [{"name": "SESSION", "value": "cookie-fixture"}],
                },
            )
            with self.assertRaises(ValueError):
                service.refresh_counts(
                    now=now,
                    credentials={"student_id": user.student_id, "password": "session-only", "connection_mode": "unknown"},
                )
            with self.assertRaisesRegex(ValueError, "连接模式已变化"):
                service.refresh_counts(
                    now=now,
                    credentials={"student_id": user.student_id, "password": "session-only", "connection_mode": "direct"},
                )

        self.assertEqual(
            [
                unittest.mock.call(allow_vpn_fallback=False, webvpn=False, gateway_cookies=None),
                unittest.mock.call(allow_vpn_fallback=False, webvpn=True, gateway_cookies=[{"name": "SESSION", "value": "cookie-fixture"}]),
            ],
            client_factory.call_args_list,
        )
        credential_store.set.assert_not_called()

    def test_legacy_count_refreshes_follow_active_mode_and_never_use_direct_in_read_only_modes(self) -> None:
        storage = MagicMock()
        storage.get_daily_td_count_info.return_value = {"count": 0, "updated_at": None}
        storage.get_healthcloud_total_info.return_value = {}
        storage.load_config.return_value = {"eventno": "802"}
        credential_store = MagicMock()
        credential_store.get.return_value = "session-only"
        user = User(
            student_id="20370001",
            card_id="136D731",
            entrance_machine_id=2,
            exit_machine_id=6,
            entrance_image="entrance.jpg",
            exit_image="exit.jpg",
        )
        now = datetime(2026, 10, 12, 9, 0, tzinfo=BEIJING_TZ)
        with (
            patch("auto_td.ui.HealthCloudClient") as client_factory,
            patch.object(ui.UiService, "_storage", return_value=storage),
            patch.object(ui.UiService, "_configured_account", return_value=(user, "学院路")),
            patch.object(ui.UiService, "_healthcloud_username", return_value=user.student_id),
            patch("auto_td.ui._healthcloud_cache_is_recent", return_value=False),
            patch("auto_td.ui.stop_scheduler_process"),
        ):
            client_factory.return_value.query_today_valid_exit_count.return_value = 1
            client_factory.return_value.query_semester_valid_exit_count.return_value = {
                "term": "2026-2027-1", "count": 1, "days": {}
            }
            service = ui.UiService(storage_factory=lambda: storage, credential_store=credential_store)
            service.refresh_account_count(now=now)
            service.refresh_total_count(now=now)
            service.set_connection_mode("webvpn")
            service.refresh_account_count(now=now)
            service.refresh_total_count(now=now)

        self.assertEqual(
            [
                unittest.mock.call(allow_vpn_fallback=False, webvpn=False, gateway_cookies=None),
                unittest.mock.call(allow_vpn_fallback=False, webvpn=False, gateway_cookies=None),
                unittest.mock.call(allow_vpn_fallback=False, webvpn=True, gateway_cookies=None),
                unittest.mock.call(allow_vpn_fallback=False, webvpn=True, gateway_cookies=None),
            ],
            client_factory.call_args_list,
        )

    def test_vpn_refresh_without_main_account_gateway_cookies_makes_no_healthcloud_request(self) -> None:
        storage = MagicMock()
        storage.get_daily_td_count_info.return_value = None
        storage.get_healthcloud_total_info.return_value = None
        credential_store = MagicMock()
        healthcloud = MagicMock()
        service = ui.UiService(
            storage_factory=lambda: storage,
            credential_store=credential_store,
            healthcloud_client_factory=lambda: healthcloud,
        )
        user = User(
            student_id="20370001",
            card_id="136D731",
            entrance_machine_id=2,
            exit_machine_id=6,
            entrance_image="entrance.jpg",
            exit_image="exit.jpg",
        )

        with (
            patch.object(service, "_storage", return_value=storage),
            patch.object(service, "_configured_account", return_value=(user, "学院路")),
            patch.object(service, "_healthcloud_username", return_value=user.student_id),
        ):
            results = []
            for mode in ("webvpn",):
                service.set_connection_mode(mode)
                results.append(
                    service.refresh_counts(
                        now=datetime(2026, 10, 12, 9, 0, tzinfo=BEIJING_TZ),
                        credentials={
                            "student_id": user.student_id,
                            "password": "healthcloud-password-fixture",
                            "connection_mode": mode,
                        },
                    )
                )

        self.assertEqual(
            [["WebVPN 网关会话不可用，请重新登录主账号后手动刷新健康云次数"]],
            [result["errors"] for result in results],
        )
        healthcloud.query_counts.assert_not_called()
        credential_store.get.assert_not_called()

    def test_vpn_refresh_uses_existing_gateway_session_without_saved_healthcloud_password(self) -> None:
        storage = MagicMock()
        storage.get_daily_td_count_info.return_value = None
        storage.get_healthcloud_total_info.return_value = None
        storage.load_config.return_value = {"eventno": "802"}
        credential_store = MagicMock()
        credential_store.get.side_effect = AssertionError("gateway refresh must not consult Keychain")
        healthcloud = MagicMock()
        healthcloud.query_counts.return_value = {
            "today_count": 1,
            "term": "2026-2027-1",
            "total": {"term": "2026-2027-1", "count": 1, "days": {}},
            "total_error": None,
        }
        user = User(
            student_id="20370001",
            card_id="136D731",
            entrance_machine_id=2,
            exit_machine_id=6,
            entrance_image="entrance.jpg",
            exit_image="exit.jpg",
        )
        service = ui.UiService(
            storage_factory=lambda: storage,
            credential_store=credential_store,
            healthcloud_client_factory=lambda: healthcloud,
        )
        with (
            patch.object(service, "_storage", return_value=storage),
            patch.object(service, "_configured_account", return_value=(user, "学院路")),
            patch.object(service, "_healthcloud_username", return_value=user.student_id),
            patch("auto_td.ui._healthcloud_cache_is_recent", return_value=False),
        ):
            service.set_connection_mode("webvpn")
            result = service.refresh_counts(
                now=datetime(2026, 10, 12, 9, 0, tzinfo=BEIJING_TZ),
                credentials={
                    "student_id": user.student_id,
                    "connection_mode": "webvpn",
                    "gateway_cookies": [{"name": "SESSION", "value": "cookie-fixture"}],
                },
            )

        self.assertEqual(1, result["daily"]["count"])
        self.assertEqual([], result["errors"])
        self.assertIsNone(healthcloud.query_counts.call_args.args[1])
        credential_store.get.assert_not_called()
        credential_store.set.assert_not_called()

    def test_direct_refresh_still_stops_when_healthcloud_password_is_missing(self) -> None:
        storage = MagicMock()
        storage.get_daily_td_count_info.return_value = None
        storage.get_healthcloud_total_info.return_value = None
        credential_store = MagicMock()
        credential_store.get.return_value = None
        healthcloud = MagicMock()
        service = ui.UiService(
            storage_factory=lambda: storage,
            credential_store=credential_store,
            healthcloud_client_factory=lambda: healthcloud,
        )
        user = User(
            student_id="20370001",
            card_id="136D731",
            entrance_machine_id=2,
            exit_machine_id=6,
            entrance_image="entrance.jpg",
            exit_image="exit.jpg",
        )
        with (
            patch.object(service, "_storage", return_value=storage),
            patch.object(service, "_configured_account", return_value=(user, "学院路")),
            patch.object(service, "_healthcloud_username", return_value=user.student_id),
        ):
            service.set_connection_mode("direct")
            result = service.refresh_counts(
                now=datetime(2026, 10, 12, 9, 0, tzinfo=BEIJING_TZ),
                credentials={"student_id": user.student_id, "connection_mode": "direct"},
            )

        self.assertEqual(["健康云凭据不可用，请检查设置中的 TD 健康云账号"], result["errors"])
        healthcloud.query_counts.assert_not_called()

    def test_refresh_counts_uses_request_credential_without_persisting_it(self) -> None:
        storage = MagicMock()
        storage.get_daily_td_count_info.return_value = None
        storage.get_healthcloud_total_info.return_value = None
        storage.load_config.return_value = {"eventno": "802"}
        credentials_store = MagicMock()
        healthcloud = MagicMock()
        healthcloud.query_counts.return_value = {
            "today_count": 2,
            "term": "2026-2027-1",
            "total": None,
            "total_error": "cached total unavailable",
        }
        service = ui.UiService(
            storage_factory=lambda: storage,
            credential_store=credentials_store,
            healthcloud_client_factory=lambda: healthcloud,
        )
        user = User(
            student_id="20370001",
            card_id="136D731",
            entrance_machine_id=2,
            exit_machine_id=6,
            entrance_image="entrance.jpg",
            exit_image="exit.jpg",
        )

        with (
            patch.object(service, "_storage", return_value=storage),
            patch.object(service, "_configured_account", return_value=(user, "学院路")),
            patch.object(service, "_healthcloud_username", return_value=user.student_id),
            patch("auto_td.ui._healthcloud_cache_is_recent", return_value=False),
        ):
            result = service.refresh_counts(
                now=datetime(2026, 10, 12, 9, 0, tzinfo=BEIJING_TZ),
                credentials={"student_id": "20370001", "password": "session-only"},
            )

        self.assertEqual(2, result["daily"]["count"])
        self.assertEqual("session-only", healthcloud.query_counts.call_args.args[1])
        credentials_store.get.assert_not_called()
        credentials_store.set.assert_not_called()

    def test_refresh_counts_does_not_apply_credentials_for_another_student(self) -> None:
        credential_store = MagicMock()
        credential_store.get.return_value = None
        service = ui.UiService(credential_store=credential_store)
        user = User(
            student_id="20370001",
            card_id="136D731",
            entrance_machine_id=2,
            exit_machine_id=6,
            entrance_image="entrance.jpg",
            exit_image="exit.jpg",
        )
        storage = MagicMock()
        storage.get_daily_td_count_info.return_value = None
        storage.get_healthcloud_total_info.return_value = None

        with patch.object(service, "_configured_account", return_value=(user, "学院路")), patch.object(
            service, "_storage", return_value=storage
        ), patch.object(service, "_healthcloud_username", return_value=user.student_id), patch("auto_td.ui._healthcloud_cache_is_recent", return_value=False):
            result = service.refresh_counts(
                now=datetime(2026, 10, 12, 9, 0, tzinfo=BEIJING_TZ),
                credentials={"student_id": "20370002", "password": "wrong-account"},
            )

        self.assertEqual(["健康云凭据不可用，请检查设置中的 TD 健康云账号"], result["errors"])
        credential_store.get.assert_called_once_with("20370001")

    def test_refresh_counts_uses_custom_healthcloud_username_and_matching_session(self) -> None:
        storage = MagicMock()
        storage.get_daily_td_count_info.return_value = None
        storage.get_healthcloud_total_info.return_value = None
        storage.load_config.return_value = {"eventno": "802"}
        credential_store = MagicMock()
        healthcloud = MagicMock()
        healthcloud.query_counts.return_value = {
            "today_count": 1,
            "term": "2026-2027-1",
            "total": None,
            "total_error": "cached total unavailable",
        }
        service = ui.UiService(
            storage_factory=lambda: storage,
            credential_store=credential_store,
            healthcloud_client_factory=lambda: healthcloud,
        )
        user = User(
            student_id="20370001",
            card_id="136D731",
            entrance_machine_id=2,
            exit_machine_id=6,
            entrance_image="entrance.jpg",
            exit_image="exit.jpg",
        )

        with (
            patch.object(service, "_storage", return_value=storage),
            patch.object(service, "_configured_account", return_value=(user, "学院路")),
            patch.object(service, "_healthcloud_username", return_value="20370002"),
            patch("auto_td.ui._healthcloud_cache_is_recent", return_value=False),
        ):
            result = service.refresh_counts(
                now=datetime(2026, 10, 12, 9, 0, tzinfo=BEIJING_TZ),
                credentials={"student_id": "20370002", "password": "session-only"},
            )

        self.assertEqual(1, result["daily"]["count"])
        self.assertEqual("20370002", healthcloud.query_counts.call_args.args[0])
        self.assertEqual("session-only", healthcloud.query_counts.call_args.args[1])
        credential_store.get.assert_not_called()

    def test_saving_healthcloud_override_keeps_td_device_account_separate(self) -> None:
        storage = MagicMock()
        storage.load_settings.return_value = {
            "account": {"student_id": "20370001", "campus": "学院路"}
        }
        credentials = MagicMock()
        credentials.get.return_value = "saved-password"
        service = ui.UiService(storage_factory=lambda: storage, credential_store=credentials)
        user = User(
            student_id="20370001",
            card_id="136D731",
            entrance_machine_id=2,
            exit_machine_id=6,
            entrance_image="entrance.jpg",
            exit_image="exit.jpg",
        )

        with patch.object(service, "_storage", return_value=storage), patch.object(
            service, "_configured_account", return_value=(user, "学院路")
        ):
            result = service.save_healthcloud_account({"username": "20370002", "password": "new-secret"})

        credentials.set.assert_called_once_with("20370002", "new-secret")
        storage.save_settings.assert_called_once_with(
            {"account": {"student_id": "20370001", "campus": "学院路", "healthcloud_username": "20370002"}}
        )
        self.assertEqual("20370001", result["student_id"])
        self.assertEqual("20370002", result["healthcloud_username"])
        credentials.delete.assert_called_once_with("20370001")


if __name__ == "__main__":
    unittest.main()
