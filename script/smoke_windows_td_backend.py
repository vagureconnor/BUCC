#!/usr/bin/env python3
"""Smoke-test the packaged AutoTD backend and its Windows Credential Vault path."""

from __future__ import annotations

import argparse
import json
import os
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Any


ALLOWED_GET_PATHS = {"/api/state"}
ALLOWED_POST_PATHS = {
    "/api/settings/account",
    "/api/settings/healthcloud",
}
DEFAULT_IMAGES = {"image1.jpg", "image2.jpg", "image3.jpg"}
VAULT_SERVICE = "BUCC Health Cloud"


class SmokeFailure(RuntimeError):
    pass


def _assert(condition: bool, label: str) -> None:
    if not condition:
        raise SmokeFailure(label)
    print(f"PASS: {label}")


def _free_loopback_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def _request_json(
    opener: urllib.request.OpenerDirector,
    base_url: str,
    method: str,
    path: str,
    payload: dict[str, Any] | None = None,
) -> dict[str, Any]:
    if method == "GET":
        if path not in ALLOWED_GET_PATHS:
            raise SmokeFailure("GET path is not allowlisted")
    elif method == "POST":
        if path not in ALLOWED_POST_PATHS:
            raise SmokeFailure("POST path is not allowlisted")
    else:
        raise SmokeFailure("request method is not allowlisted")

    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        base_url + path,
        data=data,
        headers={"Content-Type": "application/json"} if data is not None else {},
        method=method,
    )
    try:
        with opener.open(request, timeout=5) as response:
            body = response.read()
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise SmokeFailure("local backend request failed") from error
    try:
        decoded = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SmokeFailure("local backend returned invalid JSON") from error
    if not isinstance(decoded, dict):
        raise SmokeFailure("local backend returned an unexpected response")
    return decoded


def _wait_for_backend(
    opener: urllib.request.OpenerDirector,
    base_url: str,
    process: subprocess.Popen[bytes],
) -> dict[str, Any]:
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise SmokeFailure("packaged backend exited before becoming ready")
        try:
            return _request_json(opener, base_url, "GET", "/api/state")
        except SmokeFailure:
            time.sleep(0.25)
    raise SmokeFailure("packaged backend did not become ready")


def _json_files_do_not_contain(home: Path, secret: str) -> bool:
    secret_bytes = secret.encode("utf-8")
    return all(secret_bytes not in path.read_bytes() for path in home.rglob("*.json"))


def run_smoke(backend: Path) -> None:
    from keyring.backends.Windows import WinVaultKeyring

    keyring = WinVaultKeyring()
    fixture_id = "9" + str(uuid.uuid4().int)
    # A collision is vanishingly unlikely; verify absence before claiming this Vault key.
    while keyring.get_password(VAULT_SERVICE, fixture_id) is not None:
        fixture_id = "9" + str(uuid.uuid4().int)
    owns_fixture_key = True
    fixture_password = secrets.token_urlsafe(32)
    process: subprocess.Popen[bytes] | None = None

    try:
        with tempfile.TemporaryDirectory(prefix="bucc-autotd-smoke-") as temp_home:
            home = Path(temp_home)
            port = _free_loopback_port()
            env = os.environ.copy()
            env["AUTOTD_HOME"] = str(home)
            process = subprocess.Popen(
                [str(backend), "--port", str(port)],
                env=env,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            base_url = f"http://127.0.0.1:{port}"
            try:
                initial = _wait_for_backend(opener, base_url, process)
                _assert(
                    initial.get("account_configured") is False
                    and initial.get("users") == [],
                    "fresh temporary home has no configured account",
                )

                image_names = {
                    image.get("name")
                    for image in initial.get("images", [])
                    if isinstance(image, dict)
                }
                _assert(
                    image_names == DEFAULT_IMAGES
                    and all((home / "images" / name).is_file() for name in DEFAULT_IMAGES),
                    "all three bundled default images were seeded",
                )

                account_response = _request_json(
                    opener,
                    base_url,
                    "POST",
                    "/api/settings/account",
                    {"student_id": fixture_id, "campus": "学院路"},
                )
                account = account_response.get("account", {})
                _assert(
                    account.get("student_id") == fixture_id
                    and account.get("campus") == "学院路",
                    "synthetic local TD account settings were saved",
                )

                healthcloud_response = _request_json(
                    opener,
                    base_url,
                    "POST",
                    "/api/settings/healthcloud",
                    {
                        "student_id": fixture_id,
                        "username": fixture_id,
                        "password": fixture_password,
                    },
                )
                healthcloud_account = healthcloud_response.get("account", {})
                _assert(
                    healthcloud_account.get("healthcloud_password_configured") is True,
                    "Windows Credential Vault accepted the synthetic password",
                )

                final_state = _request_json(opener, base_url, "GET", "/api/state")
                users = final_state.get("users", [])
                _assert(
                    final_state.get("account_configured") is True
                    and len(users) == 1
                    and users[0].get("student_id") == fixture_id
                    and users[0].get("campus") == "学院路"
                    and users[0].get("healthcloud_password_configured") is True,
                    "saved account and Vault password are reflected in local state",
                )
                _assert(
                    _json_files_do_not_contain(home, fixture_password),
                    "temporary home JSON files contain no test password",
                )
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
    finally:
        if owns_fixture_key:
            if keyring.get_password(VAULT_SERVICE, fixture_id) is not None:
                keyring.delete_password(VAULT_SERVICE, fixture_id)


def main() -> int:
    if sys.platform != "win32":
        print("FAIL: this smoke test can run only on native Windows")
        return 2

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", required=True, type=Path, help="Path to AutoTDBackend.exe")
    args = parser.parse_args()
    backend = args.backend.expanduser().resolve()
    if not backend.is_file() or backend.name.lower() != "autotdbackend.exe":
        print("FAIL: --backend must point to AutoTDBackend.exe")
        return 2

    try:
        run_smoke(backend)
    except SmokeFailure as error:
        print(f"FAIL: {error}")
        return 1
    except Exception:
        # Do not print exception text: it could contain the synthetic credential.
        print("FAIL: unexpected smoke-test error")
        return 1
    print("PASS: Windows TD backend smoke test complete")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
