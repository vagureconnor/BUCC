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
            if name == "ios":
                item["signing"] = "unsigned"
            assets.append(item)
        (directory / "manifest.json").write_text(json.dumps({"version": version, "source_sha": source_sha, "assets": assets}))
        self.proof_for_publish(directory, source_sha)
        return version, source_sha

    def run_gate(self, directory, version, source_sha, proof=None, *, require_acceptance=True):
        with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)), patch("release_gate.validate_package_contents"):
            return validate(directory, version, source_sha, "a" * 64, "b" * 64, proof, require_acceptance=require_acceptance)

    def test_requires_proof_and_complete_valid_bundle(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            proof = directory / "acceptance-proof.json"
            proof.unlink()
            with self.assertRaisesRegex(GateError, "验收证明"):
                self.run_gate(directory, version, source_sha, proof)
            self.proof_for_publish(directory, source_sha)
            self.assertEqual(6, len(self.run_gate(directory, version, source_sha, directory / "acceptance-proof.json")))

    def test_build_only_validation_skips_acceptance_but_publication_default_still_requires_it(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)):
                with patch("release_gate.validate_package_contents"):
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
                self.run_gate(directory, version, source_sha, None, require_acceptance=False)
            version, source_sha = self.fixture(directory)
            manifest = json.loads((directory / "manifest.json").read_text())
            manifest["assets"][0]["sha256"] = "0" * 64
            (directory / "manifest.json").write_text(json.dumps(manifest))
            with self.assertRaisesRegex(GateError, "SHA-256"):
                self.run_gate(directory, version, source_sha, None, require_acceptance=False)

    def test_rejects_bad_hash_fingerprint_and_source_sha(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            manifest_path = directory / "manifest.json"
            manifest = json.loads(manifest_path.read_text())
            manifest["assets"][0]["sha256"] = "0" * 64
            manifest_path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(GateError, "SHA-256"):
                self.run_gate(directory, version, source_sha, None, require_acceptance=False)
            version, source_sha = self.fixture(directory)
            with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)):
                with self.assertRaisesRegex(GateError, "指纹"):
                    with patch("release_gate.validate_package_contents"):
                        validate(directory, version, source_sha, "c" * 64, "b" * 64, directory / "acceptance-proof.json")
            with self.assertRaisesRegex(GateError, "源码 SHA"):
                self.run_gate(directory, version, "b" * 40, None, require_acceptance=False)

    def test_rejects_wrong_architecture(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            path = directory / "manifest.json"
            manifest = json.loads(path.read_text())
            next(item for item in manifest["assets"] if item["platform"] == "macos-arm64")["arch"] = "x86_64"
            path.write_text(json.dumps(manifest))
            self.proof_for_publish(directory, source_sha)
            with self.assertRaisesRegex(GateError, "架构错误"):
                self.run_gate(directory, version, source_sha, directory / "acceptance-proof.json")

    def test_acceptance_proof_and_evidence_require_exact_redacted_schema_and_matching_hashes(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            proof = directory / "acceptance-proof.json"
            original = json.loads(proof.read_text())
            malformed_proofs = []
            missing_schema = dict(original)
            missing_schema.pop("schema")
            malformed_proofs.append(missing_schema)
            extra_field = dict(original, account="must-not-be-present")
            malformed_proofs.append(extra_field)
            bad_manifest = dict(original, manifest_sha256="0" * 64)
            malformed_proofs.append(bad_manifest)
            for value in malformed_proofs:
                proof.write_text(json.dumps(value), encoding="utf-8")
                with self.subTest(proof=value), self.assertRaises(GateError):
                    validate(directory, version, source_sha, "a" * 64, "b" * 64, proof)
            proof.write_text(json.dumps(original), encoding="utf-8")
            evidence = directory / "library-cancel-evidence.json"
            evidence.write_text(json.dumps({"source_sha": source_sha, "account": "real account"}), encoding="utf-8")
            original["library_cancel"]["evidence_sha256"] = hashlib.sha256(evidence.read_bytes()).hexdigest()
            proof.write_text(json.dumps(original), encoding="utf-8")
            with self.assertRaisesRegex(GateError, "脱敏"):
                validate(directory, version, source_sha, "a" * 64, "b" * 64, proof)

    def test_main_sha_mismatch_blocks_upload(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            calls = []

            def fake_gh(args, **kwargs):
                calls.append(args[1:])
                if args[1:3] == ["repo", "view"]:
                    return SimpleNamespace(returncode=0, stdout="example/BUCC\n")
                if args[1:3] == ["api", "repos/example/BUCC/commits/main"]:
                    return SimpleNamespace(returncode=0, stdout="b" * 40)
                return SimpleNamespace(returncode=0, stdout="{}")

            with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)), patch("release_gate.validate_package_contents"), patch("release_gate.shutil.which", return_value="gh"), patch("release_gate.subprocess.run", side_effect=fake_gh):
                with self.assertRaisesRegex(GateError, "main SHA"):
                    publish_release(directory, version, source_sha, "a" * 64, "b" * 64, directory / "acceptance-proof.json")
            self.assertFalse(any(call[:2] == ["release", "upload"] for call in calls))

    def test_main_sha_change_before_unpublish_keeps_release_draft(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            proof = directory / "acceptance-proof.json"
            main_queries = 0
            calls = []

            def fake_gh(args, **kwargs):
                nonlocal main_queries
                calls.append(args[1:])
                if args[1:3] == ["repo", "view"]:
                    return SimpleNamespace(returncode=0, stdout="example/BUCC\n")
                if args[1:3] == ["api", "repos/example/BUCC/commits/main"]:
                    main_queries += 1
                    return SimpleNamespace(returncode=0, stdout=(source_sha if main_queries == 1 else "b" * 40))
                if args[1:3] == ["release", "view"]:
                    return SimpleNamespace(returncode=0, stdout=json.dumps({"isDraft": True, "targetCommitish": source_sha}))
                if args[1:3] == ["release", "download"]:
                    target = Path(args[args.index("--dir") + 1])
                    for index, argument in enumerate(args):
                        if argument == "--pattern":
                            name = args[index + 1]
                            (target / name).write_bytes((directory / name).read_bytes())
                    return SimpleNamespace(returncode=0, stdout="")
                return SimpleNamespace(returncode=0, stdout="")

            with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)), patch("release_gate.validate_package_contents"), patch("release_gate.shutil.which", return_value="gh"), patch("release_gate.subprocess.run", side_effect=fake_gh):
                with self.assertRaisesRegex(GateError, "main SHA"):
                    publish_release(directory, version, source_sha, "a" * 64, "b" * 64, proof)
            self.assertNotIn(["release", "edit", "v2.0.1", "--draft=false", "--latest"], calls)

    def proof_for_publish(self, directory, source_sha):
        proof = directory / "acceptance-proof.json"
        evidence = directory / "library-cancel-evidence.json"
        evidence.write_text(json.dumps({
            "schema": 1,
            "source_sha": source_sha,
            "created_and_cancelled_same_test_record": True,
            "official_cancel_confirmed": True,
            "timestamp": "2026-10-26T12:00:00Z",
        }), encoding="utf-8")
        manifest_sha = hashlib.sha256((directory / "manifest.json").read_bytes()).hexdigest()
        evidence_sha = hashlib.sha256(evidence.read_bytes()).hexdigest()
        proof.write_text(json.dumps({
            "schema": 1,
            "source_sha": source_sha,
            "manifest_sha256": manifest_sha,
            "library_cancel": {
                "status": "passed",
                "evidence_file": evidence.name,
                "evidence_sha256": evidence_sha,
            },
        }), encoding="utf-8")
        return proof

    def test_publication_uploads_and_revalidates_only_verified_assets_before_unpublishing(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            proof = self.proof_for_publish(directory, source_sha)
            commands = []

            def fake_gh(args, **kwargs):
                commands.append(args[1:])
                if args[1:3] == ["repo", "view"]:
                    return SimpleNamespace(returncode=0, stdout="example/BUCC\n")
                if args[1:3] == ["api", "repos/example/BUCC/commits/main"]:
                    return SimpleNamespace(returncode=0, stdout=source_sha + "\n")
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

            with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)), patch("release_gate.validate_package_contents"), patch("release_gate.shutil.which", return_value="gh"), patch("release_gate.subprocess.run", side_effect=fake_gh):
                published = publish_release(directory, version, source_sha, "a" * 64, "b" * 64, proof)
            self.assertEqual(6, len(published))
            upload_index = next(index for index, call in enumerate(commands) if call[:2] == ["release", "upload"])
            self.assertIn("--clobber", commands[upload_index])
            self.assertEqual(9, len([part for part in commands[upload_index][3:] if part != "--clobber"]))
            self.assertEqual(["release", "download"], commands[upload_index + 1][:2])
            self.assertEqual(["release", "edit"], commands[-1][:2])

    def test_wrong_draft_target_fails_before_upload_or_publish(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            proof = self.proof_for_publish(directory, source_sha)
            calls = []
            def fake_gh(args, **kwargs):
                calls.append(args[1:])
                if args[1:3] == ["repo", "view"]:
                    return SimpleNamespace(returncode=0, stdout="example/BUCC\n")
                if args[1:3] == ["api", "repos/example/BUCC/commits/main"]:
                    return SimpleNamespace(returncode=0, stdout=source_sha + "\n")
                return SimpleNamespace(returncode=0, stdout=json.dumps({"isDraft": True, "targetCommitish": "b" * 40}))
            with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)), patch("release_gate.validate_package_contents"), patch("release_gate.shutil.which", return_value="gh"), patch(
                "release_gate.subprocess.run", side_effect=fake_gh,
            ) as run:
                with self.assertRaisesRegex(GateError, "待验收源码 SHA"):
                    publish_release(directory, version, source_sha, "a" * 64, "b" * 64, proof)
            self.assertEqual(3, run.call_count)

    def test_unexpected_existing_draft_asset_fails_before_upload_or_publish(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            proof = self.proof_for_publish(directory, source_sha)
            draft = {"isDraft": True, "targetCommitish": source_sha, "assets": [{"name": "unrelated.zip"}]}
            calls = []
            def fake_gh(args, **kwargs):
                calls.append(args[1:])
                if args[1:3] == ["repo", "view"]:
                    return SimpleNamespace(returncode=0, stdout="example/BUCC\n")
                if args[1:3] == ["api", "repos/example/BUCC/commits/main"]:
                    return SimpleNamespace(returncode=0, stdout=source_sha + "\n")
                return SimpleNamespace(returncode=0, stdout=json.dumps(draft))
            with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)), patch("release_gate.validate_package_contents"), patch("release_gate.shutil.which", return_value="gh"), patch(
                "release_gate.subprocess.run", side_effect=fake_gh,
            ) as run:
                with self.assertRaisesRegex(GateError, "未声明资产"):
                    publish_release(directory, version, source_sha, "a" * 64, "b" * 64, proof)
            self.assertEqual(3, run.call_count)

    def test_upload_failure_keeps_draft_unpublished(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            proof = self.proof_for_publish(directory, source_sha)
            calls = []

            def fake_gh(args, **kwargs):
                calls.append(args[1:3])
                if args[1:3] == ["repo", "view"]:
                    return SimpleNamespace(returncode=0, stdout="example/BUCC\n")
                if args[1:3] == ["api", "repos/example/BUCC/commits/main"]:
                    return SimpleNamespace(returncode=0, stdout=source_sha + "\n")
                if args[1:3] == ["release", "view"]:
                    return SimpleNamespace(returncode=0, stdout=json.dumps({"isDraft": True, "targetCommitish": source_sha}))
                return SimpleNamespace(returncode=1, stdout="")

            with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)), patch("release_gate.validate_package_contents"), patch("release_gate.shutil.which", return_value="gh"), patch("release_gate.subprocess.run", side_effect=fake_gh):
                with self.assertRaisesRegex(GateError, "上传"):
                    publish_release(directory, version, source_sha, "a" * 64, "b" * 64, proof)
            self.assertIn(["release", "upload"], calls)
            self.assertNotIn(["release", "edit"], calls)

    def test_remote_old_or_changed_harmony_asset_blocks_unpublishing(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            version, source_sha = self.fixture(directory)
            proof = self.proof_for_publish(directory, source_sha)
            calls = []

            def fake_gh(args, **kwargs):
                calls.append(args[1:3])
                if args[1:3] == ["repo", "view"]:
                    return SimpleNamespace(returncode=0, stdout="example/BUCC\n")
                if args[1:3] == ["api", "repos/example/BUCC/commits/main"]:
                    return SimpleNamespace(returncode=0, stdout=source_sha + "\n")
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

            with patch("release_gate.signature_fingerprints", return_value=("a" * 64, "b" * 64)), patch("release_gate.validate_package_contents"), patch("release_gate.shutil.which", return_value="gh"), patch("release_gate.subprocess.run", side_effect=fake_gh):
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
