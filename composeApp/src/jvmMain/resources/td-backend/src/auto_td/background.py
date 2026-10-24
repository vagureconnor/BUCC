from __future__ import annotations

import json
import os
import shlex
import signal
import subprocess
import sys
import time
import uuid
from contextlib import contextmanager
from csv import reader as csv_reader
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Iterator, Optional, Sequence

from .storage import AppStorage


@dataclass(frozen=True)
class BackgroundStartResult:
    pid: int
    pid_path: Path
    already_running: bool


@dataclass(frozen=True)
class BackgroundStopResult:
    pid: Optional[int]
    stopped: bool
    message: str


@dataclass(frozen=True)
class BackgroundStatusResult:
    running: bool
    pid: Optional[int]
    stale: bool
    message: str


@dataclass(frozen=True)
class SchedulerProcessRecord:
    pid: int
    token: str
    started_at: str


def start_scheduler_process(
    storage: AppStorage,
    worker_command: Optional[Sequence[str]] = None,
    startup_timeout: float = 5.0,
) -> BackgroundStartResult:
    storage.home.mkdir(parents=True, exist_ok=True)
    with _exclusive_lock(_start_lock_path(storage)):
        status = get_scheduler_status(storage)
        if status.running and status.pid is not None:
            return BackgroundStartResult(status.pid, storage.pid_path, already_running=True)

        clear_stop_request(storage)
        token = uuid.uuid4().hex
        command = list(worker_command or [sys.executable, "-m", "auto_td.cli", "--daemon-worker"])
        command.extend(["--daemon-token", token])
        process = subprocess.Popen(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
            close_fds=True,
        )

        deadline = time.monotonic() + max(0.1, startup_timeout)
        while time.monotonic() < deadline:
            record = read_pid_record(storage.pid_path)
            if record is not None and record.token == token and _record_is_owned(storage, record):
                return BackgroundStartResult(record.pid, storage.pid_path, already_running=False)
            if process.poll() is not None:
                existing = get_scheduler_status(storage)
                if existing.running and existing.pid is not None:
                    return BackgroundStartResult(existing.pid, storage.pid_path, already_running=True)
                raise RuntimeError("后台定时检测进程启动失败，未取得进程锁")
            time.sleep(0.02)

        process.terminate()
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            process.kill()
        clear_pid_file(storage.pid_path, expected_pid=process.pid, expected_token=token)
        raise RuntimeError("后台定时检测进程启动超时")


def stop_scheduler_process(storage: AppStorage, timeout: float = 5.0) -> BackgroundStopResult:
    record = read_pid_record(storage.pid_path)
    if record is None:
        clear_pid_file(storage.pid_path)
        clear_stop_request(storage)
        return BackgroundStopResult(None, False, "后台定时检测已停止")

    if not _record_is_owned(storage, record):
        clear_pid_file(storage.pid_path, expected_pid=record.pid, expected_token=record.token)
        return BackgroundStopResult(
            record.pid,
            False,
            f"后台进程身份不匹配，未发送停止信号 PID={record.pid}",
        )

    request_scheduler_stop(storage, record.token)
    deadline = time.monotonic() + max(0.0, timeout)
    while time.monotonic() < deadline:
        if not _record_is_owned(storage, record):
            clear_pid_file(storage.pid_path, expected_pid=record.pid, expected_token=record.token)
            clear_stop_request(storage, expected_token=record.token)
            return BackgroundStopResult(record.pid, True, f"已停止后台定时检测进程 PID={record.pid}")
        time.sleep(0.05)

    if not _record_is_owned(storage, record):
        clear_pid_file(storage.pid_path, expected_pid=record.pid, expected_token=record.token)
        clear_stop_request(storage, expected_token=record.token)
        return BackgroundStopResult(record.pid, True, f"已停止后台定时检测进程 PID={record.pid}")
    return BackgroundStopResult(record.pid, False, f"后台定时检测进程未在超时前退出 PID={record.pid}")


def get_scheduler_status(storage: AppStorage) -> BackgroundStatusResult:
    record = read_pid_record(storage.pid_path)
    if record is not None and _record_is_owned(storage, record):
        return BackgroundStatusResult(True, record.pid, False, f"后台定时检测运行中 PID={record.pid}")

    lock_held = _scheduler_lock_is_held(storage)
    if record is None:
        clear_pid_file(storage.pid_path)
        return BackgroundStatusResult(
            False,
            None,
            lock_held,
            "后台进程锁已占用但身份尚未确认" if lock_held else "后台定时检测未运行",
        )

    clear_pid_file(storage.pid_path, expected_pid=record.pid, expected_token=record.token)
    return BackgroundStatusResult(
        False,
        record.pid,
        True,
        f"后台进程身份已失效，未操作该进程 PID={record.pid}",
    )


def acquire_scheduler_worker(storage: AppStorage, token: str) -> Optional[BinaryIO]:
    normalized = str(token).strip()
    if not normalized:
        raise ValueError("后台进程 token 不能为空")
    storage.home.mkdir(parents=True, exist_ok=True)
    owner = _try_lock(_worker_lock_path(storage), blocking=False)
    if owner is None:
        return None
    try:
        clear_stop_request(storage)
        write_pid_file(
            storage.pid_path,
            pid=os.getpid(),
            token=normalized,
            started_at=process_start_identity(os.getpid()) or "unknown",
        )
        return owner
    except BaseException:
        _unlock_and_close(owner)
        raise


def release_scheduler_worker(storage: AppStorage, token: str, owner: BinaryIO) -> None:
    try:
        clear_pid_file(storage.pid_path, expected_pid=os.getpid(), expected_token=token)
        clear_stop_request(storage, expected_token=token)
    finally:
        _unlock_and_close(owner)


def scheduler_stop_requested(storage: AppStorage, token: str) -> bool:
    try:
        payload = json.loads(_stop_path(storage).read_text(encoding="utf-8"))
    except (FileNotFoundError, TypeError, ValueError, json.JSONDecodeError):
        return False
    return str(payload.get("token", "")) == str(token)


def request_scheduler_stop(storage: AppStorage, token: str) -> None:
    _write_json_atomic(_stop_path(storage), {"token": str(token)})


def clear_stop_request(storage: AppStorage, expected_token: Optional[str] = None) -> None:
    path = _stop_path(storage)
    if expected_token is not None:
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (FileNotFoundError, TypeError, ValueError, json.JSONDecodeError):
            return
        if str(payload.get("token", "")) != str(expected_token):
            return
    try:
        path.unlink()
    except FileNotFoundError:
        pass


def read_pid_record(pid_path: Path) -> Optional[SchedulerProcessRecord]:
    try:
        payload = json.loads(pid_path.read_text(encoding="utf-8"))
        pid = int(payload["pid"])
        token = str(payload["token"]).strip()
        started_at = str(payload["started_at"]).strip()
    except (FileNotFoundError, KeyError, TypeError, ValueError, json.JSONDecodeError):
        return None
    if pid <= 0 or not token or not started_at:
        return None
    return SchedulerProcessRecord(pid=pid, token=token, started_at=started_at)


def read_pid_file(pid_path: Path) -> Optional[int]:
    record = read_pid_record(pid_path)
    return record.pid if record is not None else None


def write_pid_file(pid_path: Path, pid: int, token: str, started_at: str) -> None:
    _write_json_atomic(
        pid_path,
        {"pid": int(pid), "token": str(token), "started_at": str(started_at)},
    )


def clear_pid_file(
    pid_path: Path,
    expected_pid: Optional[int] = None,
    expected_token: Optional[str] = None,
) -> None:
    if expected_pid is not None or expected_token is not None:
        record = read_pid_record(pid_path)
        if record is None:
            return
        if expected_pid is not None and record.pid != expected_pid:
            return
        if expected_token is not None and record.token != expected_token:
            return
    try:
        pid_path.unlink()
    except FileNotFoundError:
        pass


def process_identity_matches(record: SchedulerProcessRecord) -> bool:
    if not record.token or not record.started_at:
        return False
    started_at = process_start_identity(record.pid)
    command = _process_command(record.pid)
    if started_at != record.started_at or not command:
        return False
    try:
        arguments = shlex.split(command)
    except ValueError:
        arguments = command.split()
    return record.token in arguments


def process_start_identity(pid: int) -> Optional[str]:
    if pid <= 0 or os.name == "nt":
        return None
    completed = subprocess.run(
        ["ps", "-ww", "-p", str(pid), "-o", "lstart="],
        capture_output=True,
        text=True,
        check=False,
    )
    value = completed.stdout.strip()
    return value or None


def is_process_running(pid: int) -> bool:
    if pid <= 0:
        return False
    if os.name == "nt":
        return _is_process_running_windows(pid)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def terminate_process(pid: int) -> None:
    if os.name == "nt":
        _terminate_process_windows(pid)
        return
    os.kill(pid, signal.SIGTERM)


def _record_is_owned(storage: AppStorage, record: SchedulerProcessRecord) -> bool:
    return (
        _scheduler_lock_is_held(storage)
        and is_process_running(record.pid)
        and process_identity_matches(record)
    )


def _scheduler_lock_is_held(storage: AppStorage) -> bool:
    handle = _try_lock(_worker_lock_path(storage), blocking=False)
    if handle is None:
        return True
    _unlock_and_close(handle)
    return False


@contextmanager
def _exclusive_lock(path: Path) -> Iterator[BinaryIO]:
    handle = _try_lock(path, blocking=True)
    if handle is None:
        raise RuntimeError(f"无法取得后台进程控制锁: {path}")
    try:
        yield handle
    finally:
        _unlock_and_close(handle)


def _try_lock(path: Path, blocking: bool) -> Optional[BinaryIO]:
    path.parent.mkdir(parents=True, exist_ok=True)
    handle = path.open("a+b")
    try:
        if os.name == "nt":
            import msvcrt

            if path.stat().st_size == 0:
                handle.write(b"\0")
                handle.flush()
            handle.seek(0)
            mode = msvcrt.LK_LOCK if blocking else msvcrt.LK_NBLCK
            msvcrt.locking(handle.fileno(), mode, 1)
        else:
            import fcntl

            flags = fcntl.LOCK_EX | (0 if blocking else fcntl.LOCK_NB)
            fcntl.flock(handle.fileno(), flags)
        return handle
    except (BlockingIOError, OSError):
        handle.close()
        return None


def _unlock_and_close(handle: BinaryIO) -> None:
    try:
        if os.name == "nt":
            import msvcrt

            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl

            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
    finally:
        handle.close()


def _worker_lock_path(storage: AppStorage) -> Path:
    return storage.home / "autotd.worker.lock"


def _start_lock_path(storage: AppStorage) -> Path:
    return storage.home / "autotd.start.lock"


def _stop_path(storage: AppStorage) -> Path:
    return storage.home / "autotd.stop"


def _process_command(pid: int) -> Optional[str]:
    if pid <= 0 or os.name == "nt":
        return None
    completed = subprocess.run(
        ["ps", "-ww", "-p", str(pid), "-o", "command="],
        capture_output=True,
        text=True,
        check=False,
    )
    value = completed.stdout.strip()
    return value or None


def _write_json_atomic(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.{uuid.uuid4().hex}.tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def _is_process_running_windows(pid: int) -> bool:
    completed = subprocess.run(
        ["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"],
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        return False

    for raw_line in completed.stdout.splitlines():
        line = raw_line.strip()
        if not line or not line.startswith('"'):
            continue
        row = next(csv_reader([line]), [])
        if len(row) < 2:
            continue
        try:
            if int(row[1]) == pid:
                return True
        except ValueError:
            continue
    return False


def _terminate_process_windows(pid: int) -> None:
    completed = subprocess.run(
        ["taskkill", "/PID", str(pid), "/T"],
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode == 0:
        return

    detail = "\n".join(part.strip() for part in (completed.stdout, completed.stderr) if part and part.strip())
    lowered = detail.lower()
    if any(token in lowered for token in ("not found", "no running instance", "no tasks are running")):
        raise ProcessLookupError(pid)
    if any(token in detail for token in ("找不到", "不存在")):
        raise ProcessLookupError(pid)
    if "access is denied" in lowered or any(token in detail for token in ("拒绝访问", "存取被拒")):
        raise PermissionError(detail or f"Access denied when stopping PID={pid}")
    raise RuntimeError(detail or f"taskkill failed with exit code {completed.returncode}")
