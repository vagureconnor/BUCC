import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from release_gate import GateError, validate


class ReleaseGateTests(unittest.TestCase):
    def fixture(self, directory: Path):
        source_sha, version = "a" * 40, "2.0.1"
        (directory / "proof.json").write_text(json.dumps({"accepted": True, "source_sha": source_sha}))
        assets = []
        names = ["android", "ios", "macos-x86_64", "macos-arm64", "windows", "harmony"]
        for name in names:
            file = directory / f"{name}.pkg"
            file.write_bytes(name.encode())
            item = {"platform": name, "file": file.name, "version": version, "source_sha": source_sha, "sha256": hashlib.sha256(file.read_bytes()).hexdigest()}
            if name == "macos-x86_64":
                item["arch"] = "x86_64"
            elif name == "macos-arm64":
                item["arch"] = "arm64"
            if name == "android":
                item["signing_fingerprint"] = "a" * 64
            if name == "harmony":
                item["signing_fingerprint"] = "b" * 64
            assets.append(item)
        (directory / "manifest.json").write_text(json.dumps({"version": version, "source_sha": source_sha, "assets": assets}))
        return version, source_sha

    def run_gate(self, directory, version, source_sha, proof=None):
        with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)):
            return validate(directory, version, source_sha, "a" * 64, "b" * 64, proof)

    def test_requires_proof_and_complete_valid_bundle(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            with self.assertRaisesRegex(GateError, "验收证明"):
                self.run_gate(directory, version, source_sha)
            self.assertEqual(6, len(self.run_gate(directory, version, source_sha, directory / "proof.json")))

    def test_rejects_missing_asset_and_bad_hash(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            manifest = json.loads((directory / "manifest.json").read_text())
            manifest["assets"].pop()
            (directory / "manifest.json").write_text(json.dumps(manifest))
            with self.assertRaisesRegex(GateError, "缺少平台资产"):
                self.run_gate(directory, version, source_sha, directory / "proof.json")
            version, source_sha = self.fixture(directory)
            manifest = json.loads((directory / "manifest.json").read_text())
            manifest["assets"][0]["sha256"] = "0" * 64
            (directory / "manifest.json").write_text(json.dumps(manifest))
            with self.assertRaisesRegex(GateError, "SHA-256"):
                self.run_gate(directory, version, source_sha, directory / "proof.json")

    def test_rejects_bad_hash_fingerprint_and_source_sha(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text())
            manifest["assets"][0]["sha256"] = "0" * 64
            manifest_path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(GateError, "SHA-256"):
                self.run_gate(directory, version, source_sha, directory / "proof.json")
            version, source_sha = self.fixture(directory)
            with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)):
                with self.assertRaisesRegex(GateError, "指纹"):
                    validate(directory, version, source_sha, "c" * 64, "b" * 64, directory / "proof.json")
            with self.assertRaisesRegex(GateError, "源码 SHA"):
                self.run_gate(directory, version, "b" * 40, directory / "proof.json")

    def test_rejects_wrong_architecture(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            path = directory / "manifest.json"
            manifest = json.loads(path.read_text())
            next(item for item in manifest["assets"] if item["platform"] == "macos-arm64")["arch"] = "x86_64"
            path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(GateError, "架构错误"):
                self.run_gate(directory, version, source_sha, directory / "proof.json")


if __name__ == "__main__":
    unittest.main()
