import contextlib
import hashlib
import io
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from public_certificate import CertificateError

from script.sign_harmony_release import (
    APP_CERTIFICATE,
    APP_PROFILE,
    COMPATIBLE_VERSION,
    EXPECTED_KEYSTORE,
    EXPECTED_SIGNING_FINGERPRINT,
    SIGN_ALIAS,
    SIGN_ALGORITHM,
    SIGNATURE_ENTRY_RE,
    SigningError,
    build_sign_command,
    hash_file,
    payload_hashes,
    read_project_bundle_name,
    run_interactive_signer,
    validate_release_certificate_fingerprint,
    validate_verified_profile,
    validate_input,
    verify_payload_unchanged,
    sign,
)


SOURCE_SHA = "a" * 40
VERSION = "2.0.1"
CANARY = "CANARY-DO-NOT-LEAK-68e51c"


def write_hap(path: Path, files: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in files.items():
            archive.writestr(name, data)


def write_metadata(path: Path, hap: Path, source_sha: str = SOURCE_SHA) -> None:
    metadata = {
        "platform": "harmony",
        "version": VERSION,
        "version_code": 201,
        "source_sha": source_sha,
        "source_tree": "b" * 40,
        "file": hap.name,
        "sha256": hash_file(hap),
        "signing": "unsigned",
        "toolchain": {"harmony_sdk": "26.0.0.105"},
    }
    path.write_text(json.dumps(metadata), encoding="utf-8")


class SigningInputTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.hap = self.root / "BUCC-Harmony-v2.0.1-unsigned.hap"
        self.metadata = self.root / "manifest.json"
        self.output = self.root / "BUCC-Harmony-v2.0.1.hap"
        write_hap(self.hap, {"module.json": b"module", "ets/pages/Index.abc": b"bytecode"})
        write_metadata(self.metadata, self.hap)
        self.expected_keystore = self.root / "approved.p12"
        self.expected_keystore.write_bytes(b"not read by unit tests")

    def tearDown(self):
        self.temp.cleanup()

    def validate(self, *, source_sha=SOURCE_SHA, keystore=None):
        return validate_input(
            self.hap,
            self.metadata,
            source_sha,
            self.output,
            keystore or self.expected_keystore,
            expected_keystore=self.expected_keystore,
        )

    def test_validates_hash_source_sha_platform_version_and_unsigned_state(self):
        self.assertEqual(SOURCE_SHA, self.validate()["metadata"]["source_sha"])
        with self.assertRaisesRegex(SigningError, "source SHA"):
            self.validate(source_sha="b" * 40)

        metadata = json.loads(self.metadata.read_text())
        metadata["sha256"] = "0" * 64
        self.metadata.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(SigningError, "hash"):
            self.validate()

    def test_rejects_wrong_p12_path_and_existing_outputs(self):
        wrong_keystore = self.root / "Release.p12"
        wrong_keystore.write_bytes(b"not read by unit tests")
        with self.assertRaisesRegex(SigningError, "approved DevEco keystore"):
            self.validate(keystore=wrong_keystore)

        self.output.write_bytes(b"keep")
        with self.assertRaisesRegex(SigningError, "output already exists"):
            self.validate()
        self.assertEqual(b"keep", self.output.read_bytes())

    def test_rejects_metadata_filename_and_hash_mismatch(self):
        metadata = json.loads(self.metadata.read_text())
        metadata["file"] = "different.hap"
        self.metadata.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(SigningError, "filename"):
            self.validate()

        write_metadata(self.metadata, self.hap)
        self.hap.write_bytes(self.hap.read_bytes() + b"tamper")
        with self.assertRaisesRegex(SigningError, "hash"):
            self.validate()

    def test_preserves_all_non_signature_payload_entries(self):
        signed = self.root / "signed.hap"
        write_hap(
            signed,
            {
                "module.json": b"module",
                "ets/pages/Index.abc": b"bytecode",
                "META-INF/MANIFEST.MF": b"signed manifest",
                "META-INF/CERT.SF": b"signature",
                "META-INF/CERT.RSA": b"certificate",
                "META-INF/profile.p7b": b"profile",
            },
        )
        verify_payload_unchanged(self.hap, signed)
        changed = self.root / "changed.hap"
        write_hap(changed, {"module.json": b"changed", "ets/pages/Index.abc": b"bytecode", "META-INF/CERT.SF": b"signature"})
        with self.assertRaisesRegex(SigningError, "payload differs"):
            verify_payload_unchanged(self.hap, changed)
        self.assertNotRegex("META-INF/resources.dat", SIGNATURE_ENTRY_RE)

    def test_signing_argv_contains_no_password_and_uses_requested_profile(self):
        command = build_sign_command(
            "/path/to/java",
            Path("/sdk/hap-sign-tool.jar"),
            EXPECTED_KEYSTORE,
            APP_CERTIFICATE,
            APP_PROFILE,
            self.hap,
            self.output,
        )
        self.assertNotIn(CANARY, command)
        self.assertEqual(SIGN_ALIAS, command[command.index("-keyAlias") + 1])
        self.assertEqual(SIGN_ALGORITHM, command[command.index("-signAlg") + 1])
        self.assertEqual(COMPATIBLE_VERSION, command[command.index("-compatibleVersion") + 1])
        self.assertEqual("1", command[command.index("-pwdInputMode") + 1])

    def test_requires_source_tree_metadata(self):
        metadata = json.loads(self.metadata.read_text())
        metadata.pop("source_tree")
        self.metadata.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(SigningError, "source tree"):
            self.validate()

    def test_profile_bundle_name_matches_project_configuration(self):
        self.assertEqual("com.bucc.client", read_project_bundle_name())
        with patch_profile_content({"bundle-info": {"bundle-name": "com.bucc.client"}}) as profile:
            validate_verified_profile(profile, "com.bucc.client")
        with patch_profile_content({"bundle-info": {"bundle-name": "com.attacker.other"}}) as profile:
            with self.assertRaisesRegex(SigningError, "bundle name"):
                validate_verified_profile(profile, "com.bucc.client")
        with patch_profile_content({"bundle-info": {"bundle-name": "other", "application-id": "com.bucc.client"}}) as profile:
            with self.assertRaisesRegex(SigningError, "bundle name"):
                validate_verified_profile(profile, "com.bucc.client")

    def test_certificate_fingerprint_must_match_approved_release_identity(self):
        self.assertEqual(len(EXPECTED_SIGNING_FINGERPRINT), 64)
        self.assertEqual("7d8fabaaff15ea84e5343d94b352ee97452e4314bfb90bbdcc8569d57659f2f7", EXPECTED_SIGNING_FINGERPRINT)
        with patch("script.sign_harmony_release.validate_leaf_sha256", return_value=EXPECTED_SIGNING_FINGERPRINT):
            self.assertEqual(EXPECTED_SIGNING_FINGERPRINT, validate_release_certificate_fingerprint(self.root / "cert.cer"))
        with patch("script.sign_harmony_release.validate_leaf_sha256", side_effect=CertificateError("certificate chain leaf does not match the approved release certificate")):
            with self.assertRaisesRegex(SigningError, "approved release certificate"):
                validate_release_certificate_fingerprint(self.root / "wrong.cer")

    def test_release_certificate_chain_is_checked_before_reading_signing_password(self):
        tool = self.root / "hap-sign-tool.jar"
        certificate = self.root / "Release.cer"
        profile = self.root / "Release.p7b"
        tool.write_bytes(b"tool fixture")
        certificate.write_bytes(b"chain fixture")
        profile.write_bytes(b"profile fixture")
        validated = {
            "hap": self.hap,
            "metadata": json.loads(self.metadata.read_text()),
            "output": self.output,
            "manifest_output": self.root / "manifest.json",
            "keystore": self.expected_keystore,
        }
        with patch("script.sign_harmony_release.validate_input", return_value=validated), patch(
            "script.sign_harmony_release.APP_CERTIFICATE", certificate
        ), patch("script.sign_harmony_release.APP_PROFILE", profile), patch(
            "script.sign_harmony_release.validate_release_certificate_fingerprint",
            side_effect=SigningError("certificate chain leaf does not match the approved release certificate"),
        ), patch("script.sign_harmony_release.read_keychain_password") as read_password:
            with self.assertRaisesRegex(SigningError, "certificate chain leaf"):
                sign(self.hap, self.metadata, SOURCE_SHA, self.output, hap_sign_tool=tool, java="java")
        read_password.assert_not_called()


@contextlib.contextmanager
def patch_profile_content(content):
    from unittest.mock import patch

    payload = json.dumps(content).encode()

    def fake_openssl(argv, **kwargs):
        output = Path(argv[argv.index("-out") + 1])
        output.write_bytes(payload)
        return type("Result", (), {"returncode": 0})()

    with patch("script.sign_harmony_release.shutil.which", return_value="openssl"), patch(
        "script.sign_harmony_release.subprocess.run", side_effect=fake_openssl
    ):
        with tempfile.TemporaryDirectory() as temporary:
            profile = Path(temporary) / "verified-profile.p7b"
            profile.write_bytes(b"signed profile fixture")
            yield profile


class InteractivePasswordTests(unittest.TestCase):
    def test_password_is_sent_only_after_echo_off_and_not_returned_or_logged(self):
        with tempfile.TemporaryDirectory() as temporary:
            fake_tool = Path(temporary) / "fake_signer.py"
            fake_tool.write_text(
                "import sys, termios\n"
                "attrs = termios.tcgetattr(0)\n"
                "attrs[3] |= termios.ECHO\n"
                "termios.tcsetattr(0, termios.TCSANOW, attrs)\n"
                "print('Enter keystore password:', flush=True)\n"
                "store = sys.stdin.readline().rstrip('\\r\\n')\n"
                "print('debug ' + store, flush=True)\n"
                "print('Enter key password:', flush=True)\n"
                "key = sys.stdin.readline().rstrip('\\r\\n')\n"
                "print('debug ' + key, flush=True)\n"
                "sys.exit(0 if store == key else 9)\n",
                encoding="utf-8",
            )
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                code = run_interactive_signer([sys.executable, str(fake_tool)], CANARY, timeout_seconds=5)
            self.assertEqual(0, code)
            self.assertNotIn(CANARY, output.getvalue())

    def test_tool_failure_does_not_expose_password_or_child_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            fake_tool = Path(temporary) / "fake_signer.py"
            fake_tool.write_text(
                "import sys\n"
                "print('keystore password:', flush=True)\n"
                "secret = sys.stdin.readline().rstrip()\n"
                "print(secret, flush=True)\n"
                "sys.exit(7)\n",
                encoding="utf-8",
            )
            output = io.StringIO()
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                with self.assertRaisesRegex(SigningError, "status 7") as raised:
                    run_interactive_signer([sys.executable, str(fake_tool)], CANARY, timeout_seconds=5)
            self.assertNotIn(CANARY, output.getvalue())
            self.assertNotIn(CANARY, str(raised.exception))


if __name__ == "__main__":
    unittest.main()
