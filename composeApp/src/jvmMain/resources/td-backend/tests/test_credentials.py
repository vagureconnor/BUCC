from __future__ import annotations

import json
import hashlib
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from auto_td.credentials import CredentialStore, SERVICE_NAME


class MemoryKeychain:
    def __init__(self):
        self.values: dict[tuple[str, str], str] = {}

    def get_password(self, service: str, username: str):
        return self.values.get((service, username))

    def set_password(self, service: str, username: str, password: str):
        self.values[(service, username)] = password

    def delete_password(self, service: str, username: str):
        del self.values[(service, username)]


class CredentialStoreTest(unittest.TestCase):
    def test_macos_uses_private_encrypted_vault_without_touching_keychain_or_legacy_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            legacy = root / "credentials.json"
            legacy.write_text(json.dumps({"20370001": "legacy-secret"}), encoding="utf-8")
            store = CredentialStore(path=legacy)

            with patch.object(sys, "platform", "darwin"), patch.object(
                store, "_get_keychain", side_effect=AssertionError("macOS must not access Keychain")
            ), patch("auto_td.credentials.get_app_home", return_value=root):
                self.assertIsNone(store.get("20370001"))
                store.set("20370001", "health-secret")
                self.assertEqual("health-secret", store.get("20370001"))
                self.assertIsNone(store.get("20370002"))
                self.assertEqual(json.dumps({"20370001": "legacy-secret"}), legacy.read_text(encoding="utf-8"))
                vault = root / "health-cloud-vault"
                self.assertEqual(0o700, vault.stat().st_mode & 0o777)
                self.assertEqual(0o600, (vault / "key-v1").stat().st_mode & 0o777)
                credential_files = list(vault.glob("*.bin"))
                self.assertEqual(1, len(credential_files))
                self.assertNotIn(b"health-secret", credential_files[0].read_bytes())
                self.assertEqual(0o600, credential_files[0].stat().st_mode & 0o777)
                store.delete("20370001")
                self.assertIsNone(store.get("20370001"))

    def test_macos_rejects_tampered_or_wrong_account_ciphertext(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            store = CredentialStore(path=root / "credentials.json")
            with patch.object(sys, "platform", "darwin"), patch.object(
                store, "_get_keychain", side_effect=AssertionError("macOS must not access Keychain")
            ), patch("auto_td.credentials.get_app_home", return_value=root):
                store.set("20370001", "health-secret")
                vault = root / "health-cloud-vault"
                credential_path = next(vault.glob("*.bin"))
                blob = credential_path.read_bytes()

                tampered = bytearray(blob)
                tampered[-1] ^= 0x01
                credential_path.write_bytes(tampered)
                with self.assertRaisesRegex(RuntimeError, "无法解密"):
                    store.get("20370001")

                credential_path.write_bytes(blob)
                other_account_path = vault / f"{hashlib.sha256(b'20370002').hexdigest()}.bin"
                other_account_path.write_bytes(blob)
                with self.assertRaisesRegex(RuntimeError, "无法解密"):
                    store.get("20370002")

    def test_windows_uses_explicit_win_vault_backend(self) -> None:
        backend_module = types.ModuleType("keyring.backends.Windows")

        class FakeWinVaultKeyring:
            priority = 1

            def __init__(self):
                self.marker = "windows-vault"

        backend_module.WinVaultKeyring = FakeWinVaultKeyring
        keyring_module = types.ModuleType("keyring")
        keyring_module.__path__ = []
        backends_module = types.ModuleType("keyring.backends")
        backends_module.__path__ = []
        keyring_module.get_keyring = lambda: self.fail("global keyring configuration must not be used")

        with patch.object(sys, "platform", "win32"), patch.dict(
            sys.modules,
            {
                "keyring": keyring_module,
                "keyring.backends": backends_module,
                "keyring.backends.Windows": backend_module,
            },
        ):
            backend = CredentialStore()._get_keychain()

        self.assertIsInstance(backend, FakeWinVaultKeyring)
        self.assertEqual("windows-vault", backend.marker)

    def test_windows_backend_import_failure_has_safe_error(self) -> None:
        with patch.object(sys, "platform", "win32"), patch.dict(
            sys.modules,
            {"keyring": None, "keyring.backends": None, "keyring.backends.Windows": None},
        ):
            with self.assertRaisesRegex(RuntimeError, "无法访问 Windows 凭据库") as error:
                CredentialStore()._get_keychain()

        self.assertNotIn("password", str(error.exception).lower())

    def test_set_get_and_delete_use_keychain_backend(self) -> None:
        keychain = MemoryKeychain()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "credentials.json"
            store = CredentialStore(path=path, keychain=keychain)

            with patch.object(sys, "platform", "win32"):
                store.set("20370001", "secret")

                self.assertEqual("secret", store.get("20370001"))
            self.assertEqual({(SERVICE_NAME, "20370001"): "secret"}, keychain.values)
            self.assertFalse(path.exists())
            with patch.object(sys, "platform", "win32"):
                store.delete("20370001")
                self.assertIsNone(store.get("20370001"))

    def test_migrates_legacy_json_into_keychain_then_removes_plaintext_file(self) -> None:
        keychain = MemoryKeychain()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "credentials.json"
            path.write_text(json.dumps({"20370001": "old-secret"}), encoding="utf-8")
            store = CredentialStore(path=path, keychain=keychain)

            with patch.object(sys, "platform", "win32"):
                self.assertEqual("old-secret", store.get("20370001"))

            self.assertEqual("old-secret", keychain.get_password(SERVICE_NAME, "20370001"))
            self.assertFalse(path.exists())


if __name__ == "__main__":
    unittest.main()
