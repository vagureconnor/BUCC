import hashlib
import json
import tempfile
import unittest
import sys
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "script"))
from local_platform_gate import GateError, run_local_gate


class LocalPlatformGateTests(unittest.TestCase):
    def fixture(self, directory: Path):
        source_sha, version = "a" * 40, "2.0.1"
        assets = []
        for platform in ("android", "ios", "macos-x86_64", "macos-arm64", "windows", "harmony"):
            artifact = directory / f"{platform}.pkg"
            artifact.write_bytes(platform.encode())
            item = {
                "platform": platform,
                "file": artifact.name,
                "version": version,
                "source_sha": source_sha,
                "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
            }
            if platform == "macos-x86_64":
                item["arch"] = "x86_64"
            elif platform == "macos-arm64":
                item["arch"] = "arm64"
            elif platform == "android":
                item["signing_fingerprint"] = "a" * 64
            elif platform == "harmony":
                item["signing_fingerprint"] = "b" * 64
            assets.append(item)
        (directory / "manifest.json").write_text(
            json.dumps({"version": version, "source_sha": source_sha, "assets": assets}),
            encoding="utf-8",
        )
        evidence = {
            "source_sha": source_sha,
            "tests": {
                name: {"source_sha": source_sha, "status": "success", "failures": 0}
                for name in ("shared", "compose", "python")
            },
        }
        report = directory / "tests.json"
        report.write_text(json.dumps(evidence), encoding="utf-8")
        return version, source_sha, report

    def run_gate(self, directory, version, source_sha, report, report_status=False):
        with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)):
            return run_local_gate(
                directory,
                version,
                source_sha,
                "a" * 64,
                "b" * 64,
                report,
                report_status=report_status,
                repository="example/BUCC",
            )

    def test_local_gate_accepts_complete_signed_assets_and_current_test_evidence_without_cancel_proof(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha, report = self.fixture(directory)
            with patch("local_platform_gate.publish_status") as publish:
                assets = self.run_gate(directory, version, source_sha, report, report_status=True)
            self.assertEqual(6, len(assets))
            publish.assert_called_once_with("example/BUCC", source_sha)

    def test_missing_platform_assets_block_success_status(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha, report = self.fixture(directory)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text())
            manifest["assets"].pop()
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with patch("local_platform_gate.publish_status") as publish:
                with self.assertRaisesRegex(GateError, "缺少平台资产"):
                    self.run_gate(directory, version, source_sha, report, report_status=True)
            publish.assert_not_called()

    def test_extra_assets_are_not_treated_as_the_six_platform_gate(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha, report = self.fixture(directory)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text())
            manifest["assets"].append({"platform": "linux", "file": "linux.pkg"})
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(GateError, "恰好六个平台"):
                self.run_gate(directory, version, source_sha, report)

    def test_wrong_source_sha_blocks_success_status(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha, report = self.fixture(directory)
            with patch("local_platform_gate.publish_status") as publish:
                with self.assertRaisesRegex(GateError, "源码 SHA"):
                    self.run_gate(directory, version, "b" * 40, report, report_status=True)
            publish.assert_not_called()

    def test_failed_or_incomplete_test_evidence_blocks_success_status(self):
        for suite, values in (
            ("shared", {"status": "failure", "failures": 0}),
            ("compose", {"status": "success", "failures": 1}),
            ("python", {"status": "success", "failures": 0, "source_sha": "b" * 40}),
        ):
            with self.subTest(suite=suite), tempfile.TemporaryDirectory() as temp:
                directory = Path(temp)
                version, source_sha, report = self.fixture(directory)
                evidence = json.loads(report.read_text())
                evidence["tests"][suite].update(values)
                report.write_text(json.dumps(evidence), encoding="utf-8")
                with patch("local_platform_gate.publish_status") as publish:
                    with self.assertRaises(GateError):
                        self.run_gate(directory, version, source_sha, report, report_status=True)
                publish.assert_not_called()

    def test_reporting_status_is_local_only(self):
        with patch.dict("os.environ", {"GITHUB_ACTIONS": "true"}):
            with patch("local_platform_gate.subprocess.run") as run:
                from local_platform_gate import publish_status

                with self.assertRaisesRegex(GateError, "本机"):
                    publish_status("example/BUCC", "a" * 40)
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
