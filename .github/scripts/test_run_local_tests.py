import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "script"))
from run_local_tests import GateError, run_tests, safe_test_environment


class LocalTestEvidenceRunnerTests(unittest.TestCase):
    def test_strips_signing_secrets_from_test_process_environment(self):
        with patch.dict("os.environ", {
            "BUCC_HARMONY_SIGN_PASSWORD": "must-not-pass",
            "APPLE_CERTIFICATE_P12": "/private/key.p12",
            "OPENAI_API_KEY": "not-a-signing-credential",
        }, clear=True):
            environment = safe_test_environment()
        self.assertNotIn("BUCC_HARMONY_SIGN_PASSWORD", environment)
        self.assertNotIn("APPLE_CERTIFICATE_P12", environment)
        self.assertIn("OPENAI_API_KEY", environment)

    def setup_workspace(self, root: Path):
        (root / "shared/build/test-results/jvmTest").mkdir(parents=True)
        (root / "composeApp/build/test-results/jvmTest").mkdir(parents=True)

    def fake_git(self, *, dirty=False, tree_changes=False):
        snapshots = {"tree": 0}

        def run(command, **kwargs):
            if command[:2] == ["git", "rev-parse"]:
                if command[-1] == "HEAD":
                    return SimpleNamespace(returncode=0, stdout="a" * 40 + "\n", stderr="")
                return SimpleNamespace(returncode=0, stdout=("b" if snapshots["tree"] == 0 else "c") * 40 + "\n", stderr="")
            if command[:3] == ["git", "status", "--porcelain"]:
                return SimpleNamespace(returncode=0, stdout="M tracked.txt\n" if dirty else "", stderr="")
            if command[0] == "./gradlew":
                root = Path(kwargs["cwd"])
                if tree_changes:
                    snapshots["tree"] = 1
                xml = '<testsuite><testcase/><testcase><skipped/></testcase></testsuite>'
                for module in ("shared", "composeApp"):
                    folder = root / module / "build/test-results/jvmTest"
                    folder.mkdir(parents=True, exist_ok=True)
                    (folder / "TEST-suite.xml").write_text(xml)
                kwargs["stdout"].write(b"Gradle test output\n")
                return SimpleNamespace(returncode=0, stdout="", stderr="")
            if command[1:4] == ["-m", "unittest", "discover"]:
                kwargs["stdout"].write(b"Ran 2 tests in 0.01s\nOK (skipped=1)\n")
                return SimpleNamespace(returncode=0, stdout="", stderr="")
            raise AssertionError(command)

        return run, snapshots

    def test_collects_counts_commands_and_log_hashes_for_all_suites(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            root.mkdir()
            self.setup_workspace(root)
            output = Path(temporary) / "evidence"
            fake_run, _ = self.fake_git()
            with patch("run_local_tests.subprocess.run", side_effect=fake_run):
                report = run_tests(root, output)
            data = json.loads(report.read_text())
            self.assertEqual("success", data["status"])
            self.assertTrue(data["clean_checkout"])
            self.assertEqual({"shared", "compose", "python"}, set(data["tests"]))
            self.assertEqual((2, 0, 0, 1), tuple(data["tests"]["shared"][key] for key in ("tests", "failures", "errors", "skipped")))
            for result in data["tests"].values():
                log = report.parent / result["log_file"]
                self.assertEqual(result["log_sha256"], hashlib.sha256(log.read_bytes()).hexdigest())
                self.assertTrue(result["command"])
            gradle_tasks = data["tests"]["shared"]["command"]
            self.assertLess(gradle_tasks.index("spotlessCheck"), gradle_tasks.index(":shared:jvmTest"))

    def test_dirty_source_and_existing_output_are_rejected_before_commands(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            root.mkdir()
            output = Path(temporary) / "evidence"
            fake_run, _ = self.fake_git(dirty=True)
            with patch("run_local_tests.subprocess.run", side_effect=fake_run):
                with self.assertRaisesRegex(GateError, "clean"):
                    run_tests(root, output)
            output.mkdir()
            with self.assertRaisesRegex(GateError, "已存在"):
                run_tests(root, output)

    def test_stale_xml_is_removed_and_zero_tests_cannot_be_successful(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "repo"
            root.mkdir()
            self.setup_workspace(root)
            old_xml = root / "shared/build/test-results/jvmTest/old.xml"
            old_xml.write_text('<testsuite tests="99">' + "<testcase/>" * 99 + "</testsuite>")
            output = Path(temporary) / "evidence"
            fake_run, _ = self.fake_git()

            def no_gradle_xml(command, **kwargs):
                if command[0] == "./gradlew":
                    kwargs["stdout"].write(b"Gradle ran no tests\n")
                    return SimpleNamespace(returncode=0, stdout="", stderr="")
                return fake_run(command, **kwargs)

            with patch("run_local_tests.subprocess.run", side_effect=no_gradle_xml):
                report = run_tests(root, output)
            data = json.loads(report.read_text())
            self.assertEqual("failure", data["status"])
            self.assertEqual(0, data["tests"]["shared"]["tests"])
            self.assertFalse(old_xml.exists())

    def test_failed_command_and_source_tree_change_never_report_success(self):
        for failure_mode in ("command", "python-command", "python-zero", "tree"):
            with self.subTest(failure_mode=failure_mode), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary) / "repo"
                root.mkdir()
                self.setup_workspace(root)
                output = Path(temporary) / "evidence"
                fake_run, _ = self.fake_git(tree_changes=failure_mode == "tree")

                def run(command, **kwargs):
                    if command[0] == "./gradlew" and failure_mode == "command":
                        kwargs["stdout"].write(b"tests failed\n")
                        return SimpleNamespace(returncode=1, stdout="", stderr="")
                    if command[1:4] == ["-m", "unittest", "discover"] and failure_mode == "python-command":
                        kwargs["stdout"].write(b"Ran 2 tests in 0.01s\nFAILED (errors=1)\n")
                        return SimpleNamespace(returncode=1, stdout="", stderr="")
                    if command[1:4] == ["-m", "unittest", "discover"] and failure_mode == "python-zero":
                        kwargs["stdout"].write(b"Ran 0 tests in 0.00s\nOK\n")
                        return SimpleNamespace(returncode=0, stdout="", stderr="")
                    return fake_run(command, **kwargs)

                with patch("run_local_tests.subprocess.run", side_effect=run):
                    report = run_tests(root, output)
                data = json.loads(report.read_text())
                self.assertEqual("failure", data["status"])


if __name__ == "__main__":
    unittest.main()
