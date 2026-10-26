import json
import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from create_release_manifest import ASSET_FILES, create
from release_gate import GateError, validate


class CreateReleaseManifestTests(unittest.TestCase):
    def fixture(self, directory):
        version = "2.0.1"
        for template in ASSET_FILES.values():
            (directory / template.format(version=version)).write_bytes(template.encode())
        return version, "a" * 40

    def test_records_six_platforms_architecture_hash_and_unsigned_draft_status(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            with patch("create_release_manifest.android_fingerprint", return_value="1" * 64):
                manifest = create(directory, version, source_sha, allow_unsigned_harmony=True)
            self.assertEqual({"android", "ios", "macos-x86_64", "macos-arm64", "windows", "harmony"}, {item["platform"] for item in manifest["assets"]})
            mac = {item["platform"]: item for item in manifest["assets"] if item["platform"].startswith("macos-")}
            self.assertEqual("x86_64", mac["macos-x86_64"]["arch"])
            self.assertEqual("arm64", mac["macos-arm64"]["arch"])
            self.assertIsNone(next(item for item in manifest["assets"] if item["platform"] == "harmony")["signing_fingerprint"])
            self.assertEqual(source_sha, json.loads((directory / "manifest.json").read_text())["source_sha"])

    def test_signed_manifest_extracts_both_real_fingerprints_and_missing_asset_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            with patch("create_release_manifest.android_fingerprint", return_value="1" * 64), patch("create_release_manifest.harmony_fingerprint", return_value="2" * 64):
                manifest = create(directory, version, source_sha)
            self.assertEqual("2" * 64, next(item for item in manifest["assets"] if item["platform"] == "harmony")["signing_fingerprint"])
            (directory / ASSET_FILES["macos-arm64"].format(version=version)).unlink()
            with patch("create_release_manifest.android_fingerprint", return_value="1" * 64):
                with self.assertRaisesRegex(GateError, "缺少平台资产"):
                    create(directory, version, source_sha)

    def test_generated_signed_manifest_is_consumable_by_final_release_gate(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            with patch("create_release_manifest.android_fingerprint", return_value="1" * 64), patch("create_release_manifest.harmony_fingerprint", return_value="2" * 64):
                create(directory, version, source_sha)
            evidence = directory / "library-cancel-evidence.json"
            evidence.write_text(json.dumps({
                "schema": 1,
                "source_sha": source_sha,
                "created_and_cancelled_same_test_record": True,
                "official_cancel_confirmed": True,
                "timestamp": "2026-10-26T12:00:00Z",
            }))
            proof = directory / "acceptance-proof.json"
            proof.write_text(json.dumps({
                "schema": 1,
                "source_sha": source_sha,
                "manifest_sha256": hashlib.sha256((directory / "manifest.json").read_bytes()).hexdigest(),
                "library_cancel": {
                    "status": "passed",
                    "evidence_file": evidence.name,
                    "evidence_sha256": hashlib.sha256(evidence.read_bytes()).hexdigest(),
                },
            }))
            with patch("release_gate.signature_fingerprints", return_value=("1" * 64, "2" * 64)):
                with patch("release_gate.validate_package_contents"):
                    assets = validate(directory, version, source_sha, "1" * 64, "2" * 64, proof)
            self.assertEqual(6, len(assets))


if __name__ == "__main__":
    unittest.main()
