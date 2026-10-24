from __future__ import annotations

import hashlib
import json
import os
import tempfile
import sys
import threading
from pathlib import Path
from typing import Optional

from .paths import get_app_home


SERVICE_NAME = "BUCC Health Cloud"
_VAULT_LOCK = threading.Lock()


class CredentialStore:
    """Store Health Cloud passwords without using macOS system credentials."""

    def __init__(self, path: Optional[Path] = None, keychain=None):
        # `path` is only consulted by Windows' legacy JSON migration.
        self.path = Path(path) if path is not None else get_app_home() / "credentials.json"
        self._keychain = keychain

    def get(self, student_id: str) -> Optional[str]:
        if sys.platform == "darwin":
            return _private_vault_get(student_id)
        keychain = self._get_keychain()
        self._migrate_legacy(keychain)
        return keychain.get_password(SERVICE_NAME, str(student_id))

    def set(self, student_id: str, password: str) -> None:
        if sys.platform == "darwin":
            _private_vault_set(student_id, password)
            return
        keychain = self._get_keychain()
        self._migrate_legacy(keychain)
        keychain.set_password(SERVICE_NAME, str(student_id), str(password))

    def delete(self, student_id: str) -> None:
        if sys.platform == "darwin":
            _private_vault_delete(student_id)
            return
        keychain = self._get_keychain()
        self._migrate_legacy(keychain)
        username = str(student_id)
        if keychain.get_password(SERVICE_NAME, username) is not None:
            keychain.delete_password(SERVICE_NAME, username)

    def _get_keychain(self):
        if self._keychain is not None:
            return self._keychain
        if sys.platform == "win32":
            try:
                from keyring.backends.Windows import WinVaultKeyring

                self._keychain = WinVaultKeyring()
                # Select Windows Vault directly; never consult global keyring config.
                _ = WinVaultKeyring.priority
            except Exception as error:
                raise RuntimeError("无法访问 Windows 凭据库，健康云密码未保存") from error
            return self._keychain
        raise RuntimeError("健康云密码仅支持 macOS 应用私有存储或 Windows 凭据库")

    def _migrate_legacy(self, keychain) -> None:
        if not self.path.exists():
            return
        payload = json.loads(self.path.read_text(encoding="utf-8"))
        if not isinstance(payload, dict):
            raise ValueError("旧版健康云凭据文件格式无效")
        for username, password in payload.items():
            if password:
                account = str(username)
                if keychain.get_password(SERVICE_NAME, account) is None:
                    keychain.set_password(SERVICE_NAME, account, str(password))
        self.path.unlink()


def _vault_paths() -> tuple[Path, Path]:
    root = get_app_home() / "health-cloud-vault"
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(root, 0o700)
    return root, root / "key-v1"


def _vault_key(key_path: Path) -> bytes:
    try:
        key = key_path.read_bytes()
        if len(key) != 32:
            raise RuntimeError("健康云本地凭据密钥无效，请在设置中重新输入密码")
        os.chmod(key_path, 0o600)
        return key
    except FileNotFoundError:
        key = os.urandom(32)
        fd, temporary = tempfile.mkstemp(prefix=".key-", dir=key_path.parent)
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "wb") as output:
                output.write(key)
                output.flush()
                os.fsync(output.fileno())
            try:
                os.link(temporary, key_path)
                return key
            except FileExistsError:
                existing = key_path.read_bytes()
                if len(existing) != 32:
                    raise RuntimeError("健康云本地凭据密钥无效，请在设置中重新输入密码")
                os.chmod(key_path, 0o600)
                return existing
        finally:
            Path(temporary).unlink(missing_ok=True)


def _credential_path(root: Path, username: str) -> Path:
    name = hashlib.sha256(str(username).encode("utf-8")).hexdigest()
    return root / f"{name}.bin"


def _private_vault_get(student_id: str) -> Optional[str]:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    with _VAULT_LOCK:
        root, key_path = _vault_paths()
        path = _credential_path(root, str(student_id))
        if not path.exists():
            return None
        blob = path.read_bytes()
        if len(blob) < 13:
            raise RuntimeError("健康云本地凭据文件无效，请重新输入密码")
        try:
            plaintext = AESGCM(_vault_key(key_path)).decrypt(
                blob[:12], blob[12:], f"{SERVICE_NAME}:{student_id}".encode("utf-8")
            )
            return plaintext.decode("utf-8")
        except Exception as error:
            raise RuntimeError("无法解密健康云本地凭据，请重新输入密码") from error


def _private_vault_set(student_id: str, password: str) -> None:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    with _VAULT_LOCK:
        root, key_path = _vault_paths()
        key = _vault_key(key_path)
        nonce = os.urandom(12)
        encrypted = nonce + AESGCM(key).encrypt(
            nonce, str(password).encode("utf-8"), f"{SERVICE_NAME}:{student_id}".encode("utf-8")
        )
        path = _credential_path(root, str(student_id))
        fd, temporary = tempfile.mkstemp(prefix=".credential-", dir=root)
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "wb") as output:
                output.write(encrypted)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, path)
        except Exception:
            Path(temporary).unlink(missing_ok=True)
            raise


def _private_vault_delete(student_id: str) -> None:
    with _VAULT_LOCK:
        root, _ = _vault_paths()
        _credential_path(root, str(student_id)).unlink(missing_ok=True)
