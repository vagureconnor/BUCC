from __future__ import annotations

import json
import mimetypes
import re
import tempfile
import threading
import time
import uuid
import webbrowser
from dataclasses import dataclass
from datetime import datetime, timedelta
from email import policy
from email.parser import BytesParser
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib import resources
from pathlib import Path
from typing import Any, Callable, Optional
from urllib.parse import quote, unquote, urlparse

from .background import get_scheduler_status, start_scheduler_process, stop_scheduler_process
from .credentials import CredentialStore
from .constants import BEIJING_TZ, DEFAULT_WINDOWS
from .client import TDClient
from .healthcloud import HealthCloudClient, WEBVPN_SESSION_REQUIRED
from .logging_utils import setup_logging
from .models import User
from .quick import build_quick_user
from .runner import run_all_users
from .scheduler import _random_checkin_photo, _random_wait_seconds, clear_today_errors, in_any_window
from .storage import AppStorage


StorageFactory = Callable[[], AppStorage]
_CHECKIN_LOCK = threading.Lock()
_CHECKIN_STATUS_LOCK = threading.Lock()
_CHECKIN_STATUS = {
    "active": False,
    "phase": None,
    "message": None,
    "remaining_seconds": None,
    "current_round": 0,
    "completed_rounds": 0,
}
_CHECKIN_CANCEL_EVENT = threading.Event()
_CHECKIN_ACTIVE_REQUEST_ID: Optional[str] = None
_CHECKIN_CANCELLED_REQUESTS: set[str] = set()
TOTAL_COUNT_CACHE_TTL = timedelta(minutes=3)


class CheckinCancelled(Exception):
    pass


@dataclass(frozen=True)
class UploadedFile:
    filename: str
    content: bytes
    overwrite: bool = False


class UiService:
    def __init__(
        self,
        storage_factory: StorageFactory = AppStorage,
        credential_store: Optional[CredentialStore] = None,
        healthcloud_client_factory: Optional[Callable[[], HealthCloudClient]] = None,
    ):
        self.storage_factory = storage_factory
        self.credential_store = credential_store or CredentialStore()
        self.healthcloud_client_factory = healthcloud_client_factory or HealthCloudClient
        self._uses_default_healthcloud_client_factory = healthcloud_client_factory is None
        self._healthcloud_refresh_lock = threading.Lock()
        self._connection_mode = "direct"

    def _healthcloud_client(
        self,
        allow_vpn_fallback: bool = False,
        webvpn: bool = False,
        gateway_cookies: Optional[list[dict[str, str]]] = None,
    ) -> HealthCloudClient:
        if self._uses_default_healthcloud_client_factory:
            return self.healthcloud_client_factory(
                allow_vpn_fallback=allow_vpn_fallback,
                webvpn=webvpn,
                gateway_cookies=gateway_cookies,
            )
        return self.healthcloud_client_factory()

    def set_connection_mode(self, mode: str) -> dict[str, Any]:
        mode = str(mode or "").strip().lower()
        if mode not in {"direct", "webvpn"}:
            raise ValueError("TD 连接模式无效")
        self._connection_mode = mode
        if mode != "direct":
            _request_checkin_cancellation(None)
            storage = self._storage()
            stop_scheduler_process(storage)
        return {"mode": mode}

    def _require_direct_mode(self) -> None:
        if self._connection_mode != "direct":
            raise ValueError("当前连接模式仅支持查询 TD 健康云次数，打卡功能仅限直连模式")

    def get_state(self) -> dict[str, Any]:
        storage = self._storage()
        today_key = datetime.now(BEIJING_TZ).date().isoformat()
        background = get_scheduler_status(storage)
        users = []
        user, campus = self._configured_account(storage)
        if user is not None:
            healthcloud_username = self._healthcloud_username(storage, user)
            count_info = storage.get_daily_td_count_info(healthcloud_username, today_key) or {}
            total_info = storage.get_healthcloud_total_info(healthcloud_username) or {}
            users.append(
                {
                    "student_id": user.student_id,
                    "card_id": user.card_id,
                    "entrance_machine_id": user.entrance_machine_id,
                    "exit_machine_id": user.exit_machine_id,
                    "rounds": user.rounds,
                    "td_count": count_info.get("count"),
                    "td_count_updated_at": count_info.get("updated_at"),
                    "total_count": total_info.get("count"),
                    "total_count_updated_at": total_info.get("updated_at"),
                    "healthcloud_username": healthcloud_username,
                    "healthcloud_username_overridden": healthcloud_username != user.student_id,
                    "healthcloud_password_configured": self._has_healthcloud_password(healthcloud_username),
                    "today": _today_payload(user.rounds, count_info.get("count")),
                    "campus": campus,
                }
            )
        return {
            "home": str(storage.home),
            "refreshed_at": datetime.now(BEIJING_TZ).isoformat(timespec="seconds"),
            "background": {
                "running": background.running,
                "pid": background.pid,
                "stale": background.stale,
                "message": background.message,
            },
            "users": users,
            "checkin_status": _get_checkin_status(),
            "account_configured": user is not None,
            "images": self.list_images(),
            "check_windows": DEFAULT_WINDOWS,
        }

    def refresh_account_count(self, now: Optional[datetime] = None) -> dict[str, Any]:
        storage = self._storage()
        user, _campus = self._configured_account(storage)
        if user is None:
            raise ValueError("请先在设置中保存学号和校区")
        now = now or datetime.now(BEIJING_TZ)
        healthcloud_username = self._healthcloud_username(storage, user)
        day = now.astimezone(BEIJING_TZ).date()
        count_info = storage.get_daily_td_count_info(healthcloud_username, day.isoformat())
        password = self.credential_store.get(healthcloud_username)
        if not password:
            if count_info is None:
                raise ValueError("健康云凭据不可用，请检查设置中的 TD 健康云账号")
            return {
                "student_id": healthcloud_username,
                "count": count_info["count"],
                "cached": True,
                "updated_at": count_info.get("updated_at"),
                "refresh_error": "健康云凭据不可用，请检查设置中的 TD 健康云账号",
            }
        try:
            config = storage.load_config()
            with self._healthcloud_refresh_lock:
                count = self._healthcloud_client(
                    webvpn=self._connection_mode == "webvpn",
                ).query_today_valid_exit_count(
                    healthcloud_username,
                    password,
                    day,
                    event_no=str(config.get("eventno", "802")),
                )
        except Exception as exc:
            if count_info is None:
                raise
            return {
                "student_id": healthcloud_username,
                "count": count_info["count"],
                "cached": True,
                "updated_at": count_info.get("updated_at"),
                "refresh_error": str(exc),
            }
        storage.set_daily_td_count(healthcloud_username, day.isoformat(), count, when=now)
        return {"student_id": healthcloud_username, "count": count, "cached": False, "updated_at": now.isoformat(timespec="seconds")}

    def refresh_counts(
        self,
        now: Optional[datetime] = None,
        credentials: Optional[dict[str, Any]] = None,
    ) -> dict[str, Any]:
        storage = self._storage()
        requested_mode = str((credentials or {}).get("connection_mode") or self._connection_mode).strip().lower()
        if requested_mode not in {"direct", "webvpn"}:
            raise ValueError("TD 连接模式无效")
        if requested_mode != self._connection_mode:
            raise ValueError("TD 连接模式已变化，请刷新后重试")
        user, _campus = self._configured_account(storage)
        if user is None:
            raise ValueError("请先在设置中保存学号和校区")
        now = now or datetime.now(BEIJING_TZ)
        healthcloud_username = self._healthcloud_username(storage, user)
        through_day = now.astimezone(BEIJING_TZ).date()
        day_key = through_day.isoformat()
        daily_cache = storage.get_daily_td_count_info(healthcloud_username, day_key)
        total_cache = storage.get_healthcloud_total_info(healthcloud_username) or {}
        total_cache_recent = _healthcloud_cache_is_recent(total_cache, now)
        cached_total_result = _cached_total_result(healthcloud_username, total_cache)
        gateway_cookies = (credentials or {}).get("gateway_cookies") or []
        if requested_mode == "webvpn" and not gateway_cookies:
            return {
                "daily": _cached_daily_result(healthcloud_username, daily_cache),
                "total": cached_total_result,
                "errors": [WEBVPN_SESSION_REQUIRED],
            }
        session_password = None
        session_username = str((credentials or {}).get("healthcloud_username") or (credentials or {}).get("student_id", ""))
        if credentials and session_username == healthcloud_username:
            session_password = str(credentials.get("password", "") or "").strip() or None
        can_use_gateway_session = requested_mode == "webvpn" and bool(gateway_cookies)
        password = session_password
        if not password and not can_use_gateway_session:
            password = self.credential_store.get(healthcloud_username)
        if not password and not can_use_gateway_session:
            return {
                "daily": _cached_daily_result(healthcloud_username, daily_cache),
                "total": cached_total_result,
                "errors": ["健康云凭据不可用，请检查设置中的 TD 健康云账号"],
            }

        config = storage.load_config()
        try:
            with self._healthcloud_refresh_lock:
                refreshed = self._healthcloud_client(
                    webvpn=requested_mode == "webvpn",
                    gateway_cookies=gateway_cookies if requested_mode == "webvpn" else None,
                ).query_counts(
                    healthcloud_username,
                    password,
                    through_day,
                    event_no=str(config.get("eventno", "802")),
                    cached_term=total_cache.get("term"),
                    cached_day_counts=total_cache.get("days"),
                    refresh_total=not total_cache_recent,
                )
        except Exception as exc:
            return {
                "daily": _cached_daily_result(healthcloud_username, daily_cache),
                "total": cached_total_result,
                "errors": [str(exc)],
            }

        storage.set_daily_td_count(healthcloud_username, day_key, refreshed["today_count"], when=now)
        daily_result = {
            "student_id": healthcloud_username,
            "count": refreshed["today_count"],
            "cached": False,
            "updated_at": now.isoformat(timespec="seconds"),
        }
        total_result = cached_total_result
        errors = []
        if refreshed.get("total") is not None:
            total = refreshed["total"]
            storage.set_healthcloud_total(
                healthcloud_username,
                total["term"],
                total["count"],
                total["days"],
                when=now,
            )
            total_result = {
                "student_id": healthcloud_username,
                "count": total["count"],
                "term": total["term"],
                "cached": False,
                "updated_at": now.isoformat(timespec="seconds"),
            }
        elif refreshed.get("total_error"):
            errors.append(refreshed["total_error"])
        elif total_cache_recent and total_cache.get("term") == refreshed.get("term"):
            cached_days = dict(total_cache.get("days") or {})
            cached_days[day_key] = min(3, max(0, int(refreshed["today_count"])))
            cached_count = sum(cached_days.values())
            if cached_count != int(total_cache["count"]) or cached_days != total_cache.get("days"):
                cached_updated_at = datetime.fromisoformat(total_cache["updated_at"])
                if cached_updated_at.tzinfo is None:
                    cached_updated_at = cached_updated_at.replace(tzinfo=BEIJING_TZ)
                storage.set_healthcloud_total(
                    healthcloud_username,
                    total_cache["term"],
                    cached_count,
                    cached_days,
                    when=cached_updated_at,
                )
                total_result = {
                    "student_id": healthcloud_username,
                    "count": cached_count,
                    "term": total_cache["term"],
                    "cached": True,
                    "updated_at": total_cache["updated_at"],
                }
        return {"daily": daily_result, "total": total_result, "errors": errors}

    def refresh_total_count(self, now: Optional[datetime] = None) -> dict[str, Any]:
        storage = self._storage()
        user, _campus = self._configured_account(storage)
        if user is None:
            raise ValueError("请先在设置中保存学号和校区")
        healthcloud_username = self._healthcloud_username(storage, user)
        now = now or datetime.now(BEIJING_TZ)
        through_day = now.astimezone(BEIJING_TZ).date()
        cached = storage.get_healthcloud_total_info(healthcloud_username) or {}
        if _healthcloud_cache_is_recent(cached, now):
            return _cached_total_result(healthcloud_username, cached) or {"student_id": healthcloud_username, "count": 0, "cached": True}
        password = self.credential_store.get(healthcloud_username)
        if not password:
            result = _cached_total_result(healthcloud_username, cached)
            if result is None:
                raise ValueError("健康云凭据不可用，请检查设置中的 TD 健康云账号")
            result["refresh_error"] = "健康云凭据不可用，请检查设置中的 TD 健康云账号"
            return result
        try:
            with self._healthcloud_refresh_lock:
                total = self._healthcloud_client(
                    webvpn=self._connection_mode == "webvpn",
                ).query_semester_valid_exit_count(
                    healthcloud_username,
                    password,
                    through_day,
                    event_no=str(storage.load_config().get("eventno", "802")),
                    cached_term=cached.get("term"),
                    cached_day_counts=cached.get("days"),
                )
        except Exception as exc:
            result = _cached_total_result(healthcloud_username, cached)
            if result is None:
                raise
            result["refresh_error"] = str(exc)
            return result
        storage.set_healthcloud_total(
            healthcloud_username,
            total["term"],
            total["count"],
            total["days"],
            when=now,
        )
        return {"student_id": healthcloud_username, "count": total["count"], "term": total["term"], "cached": False}

    def checkin_once(self, rounds: int = 1, request_id: Optional[str] = None) -> dict[str, Any]:
        self._require_direct_mode()
        if rounds not in (1, 3):
            raise ValueError("打卡次数只能为 1 或 3")
        request_id = _normalize_checkin_request_id(request_id)
        if not _CHECKIN_LOCK.acquire(blocking=False):
            return {"success": False, "message": "已有一次打卡正在执行，请稍后再试。"}
        storage: Optional[AppStorage] = None
        completed = 0
        _begin_checkin(request_id)
        try:
            _raise_if_checkin_cancelled(request_id)
            storage = self._storage()
            _raise_if_checkin_cancelled(request_id)
            user, _campus = self._configured_account(storage)
            _raise_if_checkin_cancelled(request_id)
            if user is None:
                stop_scheduler_process(storage)
                return {"success": False, "message": "请先在设置中保存学号和校区。"}
            settings = storage.load_settings()
            _raise_if_checkin_cancelled(request_id)
            windows = settings.get("schedule", {}).get("windows", DEFAULT_WINDOWS)
            if not in_any_window(datetime.now(BEIJING_TZ), windows):
                stop_scheduler_process(storage)
                return {"success": False, "message": "当前不在打卡时间内，打卡失败，后台已关闭。"}

            stop_scheduler_process(storage)
            _raise_if_checkin_cancelled(request_id)
            scheduler_running = get_scheduler_status(storage).running
            _raise_if_checkin_cancelled(request_id)
            if scheduler_running:
                return {"success": False, "message": "后台任务未能停止，本次打卡未发送。"}

            client = TDClient(storage.load_config())
            for index in range(rounds):
                _raise_if_checkin_cancelled(request_id)
                if index and not in_any_window(datetime.now(BEIJING_TZ), windows):
                    break

                cooldown = storage.get_last_exit_request(user.student_id)
                if cooldown:
                    try:
                        exit_at = datetime.fromisoformat(cooldown["sent_at"])
                        elapsed = (datetime.now(BEIJING_TZ) - exit_at.astimezone(BEIJING_TZ)).total_seconds()
                        remaining = max(0, int(cooldown.get("cooldown_seconds", 0)) - int(elapsed))
                    except (KeyError, TypeError, ValueError):
                        remaining = 0
                    if remaining:
                        _wait_with_progress(
                            remaining,
                            phase="round_delay",
                            message=f"第 {index + 1}/{rounds} 次 · 出口后冷却",
                            current_round=index + 1,
                            completed_rounds=completed,
                            request_id=request_id,
                        )

                if index and not in_any_window(datetime.now(BEIJING_TZ), windows):
                    break

                _set_checkin_status(
                    phase="entrance",
                    message=f"第 {index + 1}/{rounds} 次 · 正在发送入口请求",
                    current_round=index + 1,
                    completed_rounds=completed,
                )
                self._send_card_attempt(
                    client,
                    storage,
                    user,
                    user.entrance_machine_id,
                    request_id,
                    photo_phase="entrance_photo",
                    photo_message=f"第 {index + 1}/{rounds} 次 · 正在上传入口照片",
                    current_round=index + 1,
                    completed_rounds=completed,
                )
                _wait_with_progress(
                    _random_wait_seconds(user),
                    phase="entrance_delay",
                    message=f"第 {index + 1}/{rounds} 次 · 入口后等待",
                    current_round=index + 1,
                    completed_rounds=completed,
                    request_id=request_id,
                )
                _raise_if_checkin_cancelled(request_id)
                _set_checkin_status(
                    phase="exit",
                    message=f"第 {index + 1}/{rounds} 次 · 正在发送出口请求",
                    current_round=index + 1,
                    completed_rounds=completed,
                )
                self._send_card_attempt(
                    client,
                    storage,
                    user,
                    user.exit_machine_id,
                    request_id,
                    photo_phase="exit_photo",
                    photo_message=f"第 {index + 1}/{rounds} 次 · 正在上传出口照片",
                    current_round=index + 1,
                    completed_rounds=completed,
                )
                exited_at = datetime.now(BEIJING_TZ)
                storage.set_last_exit_request(user.student_id, exited_at, _random_wait_seconds(user))
                completed += 1
                _set_checkin_status(
                    phase="round_delay" if completed < rounds else "exit_photo",
                    message=f"已完成 {completed}/{rounds} 次",
                    current_round=index + 1,
                    completed_rounds=completed,
                )

            complete = completed == rounds
            if rounds == 1:
                message = "入口和出口请求已各成功发送一次；今日有效次数以健康云流水为准。"
            elif complete:
                message = "三次打卡的入口和出口请求已依次成功发送；今日有效次数以健康云流水为准。"
            else:
                message = f"已完成 {completed}/{rounds} 次；后续打卡因不在有效时段内而停止。"
            result = {"success": complete, "completed_rounds": completed, "message": message}
        except CheckinCancelled:
            result = {
                "success": False,
                "cancelled": True,
                "completed_rounds": completed,
                "message": f"已取消；完成 {completed}/{rounds} 轮，未发送后续动作。",
            }
        except Exception as exc:
            result = {
                "success": False,
                "completed_rounds": completed,
                "message": str(exc).strip() or "TD 打卡失败",
            }
        finally:
            try:
                if storage is not None:
                    stop_scheduler_process(storage)
            finally:
                _finish_checkin(request_id)
                _CHECKIN_LOCK.release()
        return result

    def cancel_checkin(self, request_id: Optional[str] = None) -> dict[str, Any]:
        accepted = _request_checkin_cancellation(request_id)
        return {
            "cancelled": accepted,
            "message": "已请求取消，当前网络请求结束后不会发送后续动作。" if accepted else "当前没有进行中的打卡。",
        }

    @staticmethod
    def _send_card_attempt(
        client: TDClient,
        storage: AppStorage,
        user: User,
        machine_id: int,
        request_id: Optional[str] = None,
        photo_phase: str = "entrance_photo",
        photo_message: str = "正在上传照片",
        current_round: int = 0,
        completed_rounds: int = 0,
    ) -> None:
        _raise_if_checkin_cancelled(request_id)
        timestamp = time.time()
        response = client.check(user, machine_id, timestamp=timestamp, enforce_time_window=False)
        if not response.success:
            raise RuntimeError(response.server_message or "TD 打卡请求未成功")
        _raise_if_checkin_cancelled(request_id)
        _set_checkin_status(
            phase=photo_phase,
            message=photo_message,
            current_round=current_round,
            completed_rounds=completed_rounds,
        )
        photo = _random_checkin_photo(storage)
        _raise_if_checkin_cancelled(request_id)
        client.upload_photo(machine_id, photo, timestamp=timestamp)

    def list_images(self) -> list[dict[str, Any]]:
        storage = self._storage()
        images = []
        for name in storage.list_images():
            path = storage.image_path(name)
            stat = path.stat()
            images.append(
                {
                    "name": name,
                    "size": stat.st_size,
                    "url": f"/images/{quote(name)}",
                    "updated_at": datetime.fromtimestamp(stat.st_mtime, BEIJING_TZ).isoformat(timespec="seconds"),
                }
            )
        return images

    def save_uploaded_image(self, filename: str, content: bytes, overwrite: bool = False) -> str:
        if not content:
            raise ValueError("图片内容不能为空")
        with tempfile.NamedTemporaryFile(delete=False) as tmp:
            tmp.write(content)
            tmp_path = Path(tmp.name)
        try:
            return self._storage().add_image(tmp_path, name=Path(filename).name, overwrite=overwrite)
        finally:
            try:
                tmp_path.unlink()
            except FileNotFoundError:
                pass

    def delete_image(self, name: str) -> dict[str, Any]:
        deleted = self._storage().delete_image(name)
        return {"deleted": deleted, "name": name}

    def add_user(self, payload: dict[str, Any]) -> dict[str, Any]:
        student_id = str(payload.get("student_id", "")).strip()
        campus = str(payload.get("campus", "")).strip()
        card_id = str(payload.get("card_id", "") or "").strip()
        if not student_id:
            raise ValueError("学号不能为空")
        if campus not in ("沙河", "学院路"):
            raise ValueError("校区只能选择 沙河 或 学院路")
        storage = self._storage()
        user = build_quick_user(storage, student_id, campus, card_id=card_id)
        storage.save_user(user)
        return user.to_dict()

    def save_account(self, payload: dict[str, Any]) -> dict[str, Any]:
        student_id = str(payload.get("student_id", "")).strip()
        campus = str(payload.get("campus", "")).strip()
        if not re.fullmatch(r"[0-9]+", student_id):
            raise ValueError("学号必须为数字")
        if campus not in ("沙河", "学院路"):
            raise ValueError("校区只能选择 沙河 或 学院路")

        storage = self._storage()
        current_user, current_campus = self._configured_account(storage)
        previous_healthcloud_username = (
            self._healthcloud_username(storage, current_user) if current_user else None
        )
        password = str(payload.get("healthcloud_password", "") or "")
        if password:
            self.credential_store.set(student_id, password)
        if current_user and current_user.student_id == student_id and current_campus == campus:
            return {"student_id": student_id, "campus": campus, "healthcloud_password_configured": self._has_healthcloud_password(student_id)}

        same_identity = current_user is not None and current_user.student_id == student_id
        stop_scheduler_process(storage)
        chosen = build_quick_user(storage, student_id, campus)
        if same_identity and current_user:
            chosen = User.from_dict(
                {
                    **current_user.to_dict(),
                    "entrance_machine_id": chosen.entrance_machine_id,
                    "exit_machine_id": chosen.exit_machine_id,
                }
            )

        storage.save_users({student_id: chosen.to_dict()})
        settings = storage.load_settings()
        account_settings = {"student_id": student_id, "campus": campus}
        if same_identity and previous_healthcloud_username and previous_healthcloud_username != student_id:
            account_settings["healthcloud_username"] = previous_healthcloud_username
        settings["account"] = account_settings
        storage.save_settings(settings)

        state = storage.load_state()
        counts = state.setdefault("counts", {})
        daily_counts = state.setdefault("daily_td_counts", {})
        daily_runs = state.setdefault("daily_runs", {})
        if not same_identity:
            counts.clear()
            daily_counts.clear()
            state.setdefault("healthcloud_totals", {}).clear()
            state.setdefault("exit_requests", {}).clear()
            if current_user:
                self.credential_store.delete(current_user.student_id)
                if previous_healthcloud_username and previous_healthcloud_username != current_user.student_id:
                    self.credential_store.delete(previous_healthcloud_username)
        for daily_run in daily_runs.values():
            day_users = daily_run.get("users", {})
            if not same_identity or current_campus != campus:
                day_users.clear()
        storage.save_state(state)
        return {
            "student_id": student_id,
            "campus": campus,
            "healthcloud_password_configured": self._has_healthcloud_password(student_id),
        }

    def save_healthcloud_account(self, payload: dict[str, Any]) -> dict[str, Any]:
        storage = self._storage()
        user, _campus = self._configured_account(storage)
        if user is None:
            raise ValueError("请先在设置中保存 TD 账号")
        requested_student_id = str(payload.get("student_id", "") or "").strip()
        if requested_student_id and requested_student_id != user.student_id:
            raise ValueError("TD 账号已变化，请重新加载设置后修改")
        username = str(payload.get("username", "") or "").strip()
        if username and not re.fullmatch(r"[0-9]+", username):
            raise ValueError("健康云账号必须为数字")
        settings = storage.load_settings()
        account = dict(settings.get("account", {}))
        previous_username = self._healthcloud_username(storage, user)
        effective_username = username or user.student_id
        password = str(payload.get("password", "") or "")
        if password:
            self.credential_store.set(effective_username, password)
        if previous_username != effective_username:
            self.credential_store.delete(previous_username)
            if username:
                account["healthcloud_username"] = username
            else:
                account.pop("healthcloud_username", None)
            settings["account"] = account
            storage.save_settings(settings)
        return {
            "student_id": user.student_id,
            "healthcloud_username": effective_username,
            "healthcloud_username_overridden": effective_username != user.student_id,
            "healthcloud_password_configured": self._has_healthcloud_password(effective_username),
        }

    def delete_user(self, student_id: str) -> dict[str, Any]:
        storage = self._storage()
        deleted = storage.delete_user(student_id)
        if not deleted:
            raise KeyError(f"用户不存在: {student_id}")
        return {"deleted": True, "student_id": str(student_id)}

    def toggle_background(self) -> dict[str, Any]:
        self._require_direct_mode()
        storage = self._storage()
        status = get_scheduler_status(storage)
        if status.running:
            return self.stop_background()
        return self.start_background()

    def start_background(self) -> dict[str, Any]:
        self._require_direct_mode()
        storage = self._storage()
        clear_today_errors(storage)
        result = start_scheduler_process(storage)
        return {
            "action": "already_running" if result.already_running else "started",
            "pid": result.pid,
            "message": f"后台定时检测已在运行 PID={result.pid}"
            if result.already_running
            else f"后台定时检测已启动 PID={result.pid}",
        }

    def stop_background(self) -> dict[str, Any]:
        result = stop_scheduler_process(self._storage())
        return {
            "action": "stopped" if result.stopped else "already_stopped",
            "pid": result.pid,
            "message": result.message,
            "stopped": result.stopped,
        }

    def run_once(self) -> dict[str, Any]:
        self._require_direct_mode()
        storage = self._storage()
        logger = setup_logging(storage)
        result = run_all_users(storage, logger=logger)
        return {
            "total": result.total,
            "success": result.success_count,
            "failure": result.failure_count,
            "results": [
                {
                    "index": item.index,
                    "student_id": item.student_id,
                    "success": item.success,
                    "message": item.message,
                }
                for item in result.results
            ],
        }

    def _storage(self) -> AppStorage:
        storage = self.storage_factory()
        if not storage.home.exists() or not storage.config_path.exists() or not storage.state_path.exists():
            storage.initialize()
        return storage

    def _configured_account(self, storage: AppStorage) -> tuple[Optional[User], Optional[str]]:
        settings = storage.load_settings()
        account = settings.get("account", {})
        student_id = str(account.get("student_id", "") or "")
        users = storage.load_users()
        if student_id and student_id in users:
            return User.from_dict(users[student_id]), str(account.get("campus", "") or self._infer_campus(storage, users[student_id]))
        if not student_id and len(users) == 1:
            data = next(iter(users.values()))
            user = User.from_dict(data)
            campus = self._infer_campus(storage, data)
            settings["account"] = {"student_id": user.student_id, "campus": campus}
            storage.save_settings(settings)
            return user, campus
        return None, None

    @staticmethod
    def _infer_campus(storage: AppStorage, user_data: dict[str, Any]) -> str:
        machine_id = int(user_data.get("entrance_machine_id", 0))
        for machine in storage.load_config().get("machine", []):
            if int(machine.get("id", 0)) == machine_id:
                return "沙河" if "沙河" in str(machine.get("location", "")) else "学院路"
        return "学院路"

    def _has_healthcloud_password(self, student_id: str) -> bool:
        # Read-only gateway queries use their authenticated session, not the separate password vault.
        if self._connection_mode == "webvpn":
            return False
        try:
            return bool(self.credential_store.get(student_id))
        except Exception:
            return False

    @staticmethod
    def _healthcloud_username(storage: AppStorage, user: User) -> str:
        configured = str(storage.load_settings().get("account", {}).get("healthcloud_username", "") or "").strip()
        return configured or user.student_id


def serve_ui(host: str = "127.0.0.1", port: int = 8765, open_browser: bool = True) -> int:
    service = UiService()
    handler = _make_handler(service)
    server = ThreadingHTTPServer((host, int(port)), handler)
    url = f"http://{host}:{server.server_address[1]}"
    print(f"autoTD UI running at {url}")
    if open_browser:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nautoTD UI stopped")
    finally:
        server.server_close()
    return 0


def _set_checkin_status(
    phase: Optional[str],
    message: Optional[str],
    remaining_seconds: Optional[int] = None,
    current_round: int = 0,
    completed_rounds: int = 0,
    active: bool = True,
) -> None:
    with _CHECKIN_STATUS_LOCK:
        _CHECKIN_STATUS.update(
            active=active,
            phase=phase,
            message=message,
            remaining_seconds=remaining_seconds,
            current_round=current_round,
            completed_rounds=completed_rounds,
        )


def _get_checkin_status() -> dict[str, Any]:
    with _CHECKIN_STATUS_LOCK:
        return dict(_CHECKIN_STATUS)


def _wait_with_progress(
    seconds: int,
    *,
    phase: str,
    message: str,
    current_round: int,
    completed_rounds: int,
    request_id: Optional[str] = None,
) -> None:
    deadline = time.monotonic() + max(0, int(seconds))
    while True:
        _raise_if_checkin_cancelled(request_id)
        remaining = max(0, deadline - time.monotonic())
        remaining_seconds = int(remaining + 0.999)
        _set_checkin_status(
            phase=phase,
            message=message,
            remaining_seconds=remaining_seconds,
            current_round=current_round,
            completed_rounds=completed_rounds,
        )
        if remaining_seconds == 0:
            return
        time.sleep(min(1.0, remaining))


def _raise_if_checkin_cancelled(request_id: Optional[str] = None) -> None:
    with _CHECKIN_STATUS_LOCK:
        cancelled = (
            request_id in _CHECKIN_CANCELLED_REQUESTS
            if request_id is not None
            else _CHECKIN_CANCEL_EVENT.is_set()
        )
    if cancelled:
        raise CheckinCancelled()


def _normalize_checkin_request_id(request_id: Optional[str]) -> str:
    normalized = str(request_id or "").strip()
    return normalized or uuid.uuid4().hex


def _begin_checkin(request_id: str) -> None:
    global _CHECKIN_ACTIVE_REQUEST_ID
    with _CHECKIN_STATUS_LOCK:
        _CHECKIN_ACTIVE_REQUEST_ID = request_id
        _CHECKIN_CANCEL_EVENT.clear()
        _CHECKIN_STATUS.update(
            active=True,
            phase="preparing",
            message="正在准备打卡",
            remaining_seconds=None,
            current_round=0,
            completed_rounds=0,
        )


def _finish_checkin(request_id: str) -> None:
    global _CHECKIN_ACTIVE_REQUEST_ID
    with _CHECKIN_STATUS_LOCK:
        _CHECKIN_CANCELLED_REQUESTS.discard(request_id)
        if _CHECKIN_ACTIVE_REQUEST_ID == request_id:
            _CHECKIN_ACTIVE_REQUEST_ID = None
            _CHECKIN_CANCEL_EVENT.clear()
            _CHECKIN_STATUS.update(
                active=False,
                phase=None,
                message=None,
                remaining_seconds=None,
                current_round=0,
                completed_rounds=0,
            )


def _request_checkin_cancellation(request_id: Optional[str]) -> bool:
    normalized = str(request_id or "").strip()
    with _CHECKIN_STATUS_LOCK:
        if normalized:
            _CHECKIN_CANCELLED_REQUESTS.add(normalized)
            return True
        if _CHECKIN_ACTIVE_REQUEST_ID is None:
            return False
        _CHECKIN_CANCELLED_REQUESTS.add(_CHECKIN_ACTIVE_REQUEST_ID)
        _CHECKIN_CANCEL_EVENT.set()
        return True


def _today_payload(rounds: int, server_count: Optional[int]) -> dict[str, Any]:
    completed_rounds = int(server_count) if server_count is not None else None
    return {
        "status": "completed" if completed_rounds is not None and completed_rounds >= rounds else "pending",
        "completed_rounds": completed_rounds,
        "rounds": rounds,
        "next_action": None,
        "due_at": None,
        "message": "次数来自健康云当天有效出口流水；打卡请求结果不作为计数依据。",
    }


def _healthcloud_cache_is_recent(cached: dict[str, Any], now: datetime) -> bool:
    cached_at = cached.get("updated_at")
    if not cached_at or cached.get("count") is None:
        return False
    try:
        updated_at = datetime.fromisoformat(cached_at)
        if updated_at.tzinfo is None:
            updated_at = updated_at.replace(tzinfo=BEIJING_TZ)
        now_beijing = now.astimezone(BEIJING_TZ)
        cache_age = now_beijing - updated_at.astimezone(BEIJING_TZ)
    except (TypeError, ValueError):
        return False
    today_key = now_beijing.date().isoformat()
    return (
        timedelta(0) <= cache_age < TOTAL_COUNT_CACHE_TTL
        and today_key in (cached.get("days") or {})
    )


def _cached_total_result(student_id: str, cached: dict[str, Any]) -> Optional[dict[str, Any]]:
    if cached.get("count") is None:
        return None
    return {
        "student_id": student_id,
        "count": int(cached["count"]),
        "term": cached.get("term"),
        "cached": True,
        "updated_at": cached.get("updated_at"),
    }


def _cached_daily_result(student_id: str, cached: Optional[dict[str, Any]]) -> Optional[dict[str, Any]]:
    if not cached or cached.get("count") is None:
        return None
    return {
        "student_id": student_id,
        "count": int(cached["count"]),
        "cached": True,
        "updated_at": cached.get("updated_at"),
    }


def _make_handler(service: UiService) -> type[BaseHTTPRequestHandler]:
    class UiRequestHandler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            parsed = urlparse(self.path)
            try:
                if parsed.path == "/":
                    self._send_static("index.html", "text/html; charset=utf-8")
                elif parsed.path == "/static/styles.css":
                    self._send_static("styles.css", "text/css; charset=utf-8")
                elif parsed.path == "/static/app.js":
                    self._send_static("app.js", "application/javascript; charset=utf-8")
                elif parsed.path == "/api/health":
                    self._send_json({"ready": True})
                elif parsed.path == "/api/state":
                    self._send_json(service.get_state())
                elif parsed.path == "/api/count":
                    storage = service._storage()
                    user, _campus = service._configured_account(storage)
                    self._send_json({"count": storage.get_last_count(user.student_id) if user else None})
                elif parsed.path == "/api/images":
                    self._send_json({"images": service.list_images()})
                elif parsed.path.startswith("/images/"):
                    self._send_image(unquote(parsed.path.removeprefix("/images/")))
                else:
                    self._send_error(HTTPStatus.NOT_FOUND, "资源不存在")
            except Exception as exc:
                self._send_exception(exc)

        def do_POST(self) -> None:
            parsed = urlparse(self.path)
            try:
                if parsed.path == "/api/users":
                    self._send_json({"user": service.add_user(self._read_json())}, status=HTTPStatus.CREATED)
                elif parsed.path == "/api/images":
                    upload = self._read_multipart_upload()
                    name = service.save_uploaded_image(upload.filename, upload.content, overwrite=upload.overwrite)
                    self._send_json({"image": name}, status=HTTPStatus.CREATED)
                elif parsed.path == "/api/background/toggle":
                    self._send_json(service.toggle_background())
                elif parsed.path == "/api/background/start":
                    self._send_json(service.start_background())
                elif parsed.path == "/api/background/stop":
                    self._send_json(service.stop_background())
                elif parsed.path == "/api/run-once":
                    self._send_json(service.run_once())
                elif parsed.path == "/api/count/refresh":
                    self._send_json(service.refresh_account_count())
                elif parsed.path == "/api/count/total/refresh":
                    self._send_json(service.refresh_total_count())
                elif parsed.path == "/api/counts/refresh":
                    self._send_json(service.refresh_counts(credentials=self._read_json()))
                elif parsed.path == "/api/connection-mode":
                    connection = self._read_json()
                    self._send_json(service.set_connection_mode(str(connection.get("mode", ""))))
                elif parsed.path == "/api/checkin":
                    payload = self._read_json()
                    self._send_json(
                        service.checkin_once(
                            rounds=int(payload.get("rounds", 1)),
                            request_id=payload.get("request_id"),
                        )
                    )
                elif parsed.path == "/api/checkin/cancel":
                    payload = self._read_json()
                    self._send_json(service.cancel_checkin(request_id=payload.get("request_id")))
                elif parsed.path == "/api/settings/account":
                    self._send_json({"account": service.save_account(self._read_json())})
                elif parsed.path == "/api/settings/healthcloud":
                    self._send_json({"account": service.save_healthcloud_account(self._read_json())})
                else:
                    self._send_error(HTTPStatus.NOT_FOUND, "资源不存在")
            except Exception as exc:
                self._send_exception(exc)

        def do_DELETE(self) -> None:
            parsed = urlparse(self.path)
            try:
                if parsed.path.startswith("/api/users/"):
                    self._send_json(service.delete_user(unquote(parsed.path.removeprefix("/api/users/"))))
                elif parsed.path.startswith("/api/images/"):
                    self._send_json(service.delete_image(unquote(parsed.path.removeprefix("/api/images/"))))
                else:
                    self._send_error(HTTPStatus.NOT_FOUND, "资源不存在")
            except Exception as exc:
                self._send_exception(exc)

        def log_message(self, _format: str, *_args: Any) -> None:
            return

        def _read_json(self) -> dict[str, Any]:
            length = int(self.headers.get("Content-Length", "0") or "0")
            if length <= 0:
                return {}
            raw = self.rfile.read(length).decode("utf-8")
            return json.loads(raw or "{}")

        def _read_multipart_upload(self) -> UploadedFile:
            content_type = self.headers.get("Content-Type", "")
            length = int(self.headers.get("Content-Length", "0") or "0")
            if not content_type.startswith("multipart/form-data") or length <= 0:
                raise ValueError("请上传 multipart/form-data 图片")
            raw = self.rfile.read(length)
            message = BytesParser(policy=policy.default).parsebytes(
                f"Content-Type: {content_type}\r\nMIME-Version: 1.0\r\n\r\n".encode("utf-8") + raw
            )
            filename = ""
            content = b""
            overwrite = False
            for part in message.iter_parts():
                name = part.get_param("name", header="content-disposition")
                if name == "image":
                    filename = part.get_filename() or "image"
                    content = part.get_payload(decode=True) or b""
                elif name == "overwrite":
                    overwrite = (part.get_content() or "").strip().lower() in ("1", "true", "yes", "on")
            if not filename:
                raise ValueError("缺少图片文件")
            return UploadedFile(filename=filename, content=content, overwrite=overwrite)

        def _send_static(self, name: str, content_type: str) -> None:
            data = resources.files("auto_td").joinpath("ui_static", name).read_bytes()
            self._send_bytes(data, content_type=content_type)

        def _send_image(self, name: str) -> None:
            image_name = Path(name).name
            if image_name != name:
                self._send_error(HTTPStatus.BAD_REQUEST, "图片名称不能包含目录")
                return
            path = service._storage().image_path(image_name)
            if not path.exists():
                self._send_error(HTTPStatus.NOT_FOUND, "图片不存在")
                return
            content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
            self._send_bytes(path.read_bytes(), content_type=content_type)

        def _send_json(self, payload: dict[str, Any], status: HTTPStatus = HTTPStatus.OK) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self._send_bytes(body, status=status, content_type="application/json; charset=utf-8")

        def _send_error(self, status: HTTPStatus, message: str) -> None:
            self._send_json({"error": message}, status=status)

        def _send_exception(self, exc: Exception) -> None:
            if isinstance(exc, KeyError):
                self._send_error(HTTPStatus.NOT_FOUND, str(exc).strip("'"))
            elif isinstance(exc, (FileExistsError, FileNotFoundError, ValueError)):
                self._send_error(HTTPStatus.BAD_REQUEST, str(exc))
            else:
                self._send_error(HTTPStatus.INTERNAL_SERVER_ERROR, str(exc))

        def _send_bytes(
            self,
            data: bytes,
            status: HTTPStatus = HTTPStatus.OK,
            content_type: str = "application/octet-stream",
        ) -> None:
            self.send_response(int(status))
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

    return UiRequestHandler
