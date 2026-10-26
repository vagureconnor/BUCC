import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from release_gate import GateError, publish_release, validate


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

    def test_build_only_validation_skips_acceptance_but_publication_default_still_requires_it(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            (directory / "proof.json").unlink()
            with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)):
                self.assertEqual(6, len(validate(directory, version, source_sha, "a" * 64, "b" * 64, None, require_acceptance=False)))
                with self.assertRaisesRegex(GateError, "验收证明"):
                    validate(directory, version, source_sha, "a" * 64, "b" * 64, None)

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

    def proof_for_publish(self, directory, source_sha):
        proof = directory / "acceptance-proof.json"
        proof.write_text(json.dumps({"accepted": True, "source_sha": source_sha}), encoding="utf-8")
        return proof

    def test_publication_uploads_and_revalidates_only_verified_assets_before_unpublishing(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            proof = self.proof_for_publish(directory, source_sha)
            commands = []

            def fake_gh(args, **kwargs):
                commands.append(args[1:])
                if args[1:3] == ["release", "view"]:
                    return SimpleNamespace(returncode=0, stdout=json.dumps({"isDraft": True, "targetCommitish": source_sha}))
                if args[1:3] == ["release", "download"]:
                    target = Path(args[args.index("--dir") + 1])
                    for index, argument in enumerate(args):
                        if argument == "--pattern":
                            filename = args[index + 1]
                            (target / filename).write_bytes((directory / filename).read_bytes())
                    return SimpleNamespace(returncode=0, stdout="")
                return SimpleNamespace(returncode=0, stdout="")

            with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)), patch("release_gate.shutil.which", return_value="gh"), patch("release_gate.subprocess.run", side_effect=fake_gh):
                published = publish_release(directory, version, source_sha, "a" * 64, "b" * 64, proof)
            self.assertEqual(6, len(published))
            self.assertEqual(["release", "view"], commands[0][:2])
            self.assertEqual(["release", "upload"], commands[1][:2])
            self.assertIn("--clobber", commands[1])
            self.assertEqual(8, len([part for part in commands[1][3:] if part != "--clobber"]))
            self.assertEqual(["release", "download"], commands[2][:2])
            self.assertEqual(["release", "edit"], commands[3][:2])

    def test_wrong_draft_target_fails_before_upload_or_publish(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            proof = self.proof_for_publish(directory, source_sha)
            with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)), patch("release_gate.shutil.which", return_value="gh"), patch(
                "release_gate.subprocess.run",
                return_value=SimpleNamespace(returncode=0, stdout=json.dumps({"isDraft": True, "targetCommitish": "b" * 40})),
            ) as run:
                with self.assertRaisesRegex(GateError, "待验收源码 SHA"):
                    publish_release(directory, version, source_sha, "a" * 64, "b" * 64, proof)
            self.assertEqual(1, run.call_count)

    def test_unexpected_existing_draft_asset_fails_before_upload_or_publish(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            proof = self.proof_for_publish(directory, source_sha)
            draft = {"isDraft": True, "targetCommitish": source_sha, "assets": [{"name": "unrelated.zip"}]}
            with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)), patch("release_gate.shutil.which", return_value="gh"), patch(
                "release_gate.subprocess.run",
                return_value=SimpleNamespace(returncode=0, stdout=json.dumps(draft)),
            ) as run:
                with self.assertRaisesRegex(GateError, "未声明资产"):
                    publish_release(directory, version, source_sha, "a" * 64, "b" * 64, proof)
            self.assertEqual(1, run.call_count)

    def test_upload_failure_keeps_draft_unpublished(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            proof = self.proof_for_publish(directory, source_sha)
            calls = []

            def fake_gh(args, **kwargs):
                calls.append(args[1:3])
                if args[1:3] == ["release", "view"]:
                    return SimpleNamespace(returncode=0, stdout=json.dumps({"isDraft": True, "targetCommitish": source_sha}))
                return SimpleNamespace(returncode=1, stdout="")

            with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)), patch("release_gate.shutil.which", return_value="gh"), patch("release_gate.subprocess.run", side_effect=fake_gh):
                with self.assertRaisesRegex(GateError, "上传"):
                    publish_release(directory, version, source_sha, "a" * 64, "b" * 64, proof)
            self.assertEqual([["release", "view"], ["release", "upload"]], calls)

    def test_remote_old_or_changed_harmony_asset_blocks_unpublishing(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            proof = self.proof_for_publish(directory, source_sha)
            calls = []

            def fake_gh(args, **kwargs):
                calls.append(args[1:3])
                if args[1:3] == ["release", "view"]:
                    return SimpleNamespace(returncode=0, stdout=json.dumps({"isDraft": True, "targetCommitish": source_sha}))
                if args[1:3] == ["release", "download"]:
                    target = Path(args[args.index("--dir") + 1])
                    for index, argument in enumerate(args):
                        if argument == "--pattern":
                            filename = args[index + 1]
                            content = b"old unsigned HAP" if filename == "harmony.pkg" else (directory / filename).read_bytes()
                            (target / filename).write_bytes(content)
                    return SimpleNamespace(returncode=0, stdout="")
                return SimpleNamespace(returncode=0, stdout="")

            with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)), patch("release_gate.shutil.which", return_value="gh"), patch("release_gate.subprocess.run", side_effect=fake_gh):
                with self.assertRaisesRegex(GateError, "SHA-256"):
                    publish_release(directory, version, source_sha, "a" * 64, "b" * 64, proof)
            self.assertNotIn(["release", "edit"], calls)

    def test_publish_requires_canonical_proof_filename(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            proof = directory / "proof.json"
            with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)), patch("release_gate.shutil.which") as which:
                with self.assertRaisesRegex(GateError, "acceptance-proof.json"):
                    publish_release(directory, version, source_sha, "a" * 64, "b" * 64, proof)
            which.assert_not_called()


if __name__ == "__main__":
    unittest.main()
