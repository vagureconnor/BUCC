#!/usr/bin/env python3
"""Run local JVM and Python suites and record SHA-bound test evidence."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


class GateError(ValueError):
    pass


def timestamp() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def run_git(root: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", *arguments], cwd=root, check=False, capture_output=True, text=True
    )
    if result.returncode:
        raise GateError("无法读取本机 Git 状态")
    return result.stdout.strip()


def source_snapshot(root: Path) -> tuple[str, str, bool]:
    source_sha = run_git(root, "rev-parse", "HEAD")
    source_tree = run_git(root, "rev-parse", "HEAD^{tree}")
    clean = not run_git(root, "status", "--porcelain", "--untracked-files=all")
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha) or not re.fullmatch(r"[0-9a-f]{40}", source_tree):
        raise GateError("本机 Git SHA/tree 格式无效")
    return source_sha, source_tree, clean


def clean_test_results(root: Path) -> None:
    for relative in (
        Path("shared/build/test-results/jvmTest"),
        Path("composeApp/build/test-results/jvmTest"),
    ):
        target = root / relative
        if target.is_dir():
            shutil.rmtree(target)
        elif target.exists():
            target.unlink()


def safe_test_environment() -> dict[str, str]:
    environment = os.environ.copy()
    sensitive = re.compile(r"(?:KEYSTORE|SIGN|PASSWORD|PASSPHRASE|P12|PRIVATE_KEY|CERTIFICATE|PROVISIONING_PROFILE)", re.I)
    for name in list(environment):
        if sensitive.search(name):
            environment.pop(name, None)
    return environment


def command_log(root: Path, output: Path, name: str, command: list[str]) -> tuple[int, str]:
    log = output / f"{name}.log"
    with log.open("wb") as stream:
        try:
            completed = subprocess.run(
                command,
                cwd=root,
                env=safe_test_environment(),
                stdout=stream,
                stderr=subprocess.STDOUT,
                check=False,
            )
            return completed.returncode, log.relative_to(output).as_posix()
        except OSError:
            stream.write(b"Unable to start test command.\n")
            return 127, log.relative_to(output).as_posix()


def junit_counts(results: Path) -> dict[str, int]:
    counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    xml_files = sorted(results.rglob("*.xml")) if results.is_dir() else []
    for file in xml_files:
        try:
            root = ET.parse(file).getroot()
        except (ET.ParseError, OSError):
            raise GateError("本次 Gradle JUnit XML 无法解析") from None
        cases = root.iter("testcase")
        for case in cases:
            counts["tests"] += 1
            counts["failures"] += sum(1 for _ in case.findall("failure"))
            counts["errors"] += sum(1 for _ in case.findall("error"))
            counts["skipped"] += sum(1 for _ in case.findall("skipped"))
    return counts


def python_counts(log: Path) -> dict[str, int]:
    output = log.read_text(encoding="utf-8", errors="replace")
    count_match = re.search(r"Ran\s+(\d+)\s+tests?\s+in\s+", output)
    tests = int(count_match.group(1)) if count_match else 0
    summary = re.search(r"FAILED\s*\(([^)]*)\)", output)
    fields = {name: int(value) for name, value in re.findall(r"(failures|errors|skipped)=(\d+)", summary.group(1))} if summary else {}
    if not summary:
        ok_summary = re.search(r"OK\s*\(([^)]*)\)", output)
        if ok_summary:
            fields = {name: int(value) for name, value in re.findall(r"(skipped)=(\d+)", ok_summary.group(1))}
    return {
        "tests": tests,
        "failures": fields.get("failures", 0),
        "errors": fields.get("errors", 0),
        "skipped": fields.get("skipped", 0),
    }


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def suite_record(
    name: str,
    source_sha: str,
    source_tree: str,
    command: list[str],
    output: Path,
    log_relative: str,
    exit_code: int,
    counts: dict[str, int],
    started_at: str,
) -> dict:
    log = output / log_relative
    status = "success" if exit_code == 0 and counts["tests"] > 0 and counts["failures"] == 0 and counts["errors"] == 0 else "failure"
    return {
        "source_sha": source_sha,
        "source_tree": source_tree,
        "status": status,
        **counts,
        "exit_code": exit_code,
        "command": command,
        "log_file": log_relative,
        "log_sha256": file_sha256(log),
        "started_at": started_at,
        "finished_at": timestamp(),
    }


def run_tests(root: Path, output_directory: Path) -> Path:
    root = root.resolve()
    output_directory = output_directory.expanduser().resolve()
    try:
        output_directory.relative_to(root)
    except ValueError:
        pass
    else:
        raise GateError("测试证据输出目录必须位于源码仓库之外")
    if output_directory.exists():
        raise GateError("测试证据输出目录已存在；拒绝覆盖")
    source_sha, source_tree, clean = source_snapshot(root)
    if not clean:
        raise GateError("测试前工作树必须 clean")

    output_directory.parent.mkdir(parents=True, exist_ok=True)
    output_directory.mkdir()
    started_at = timestamp()
    clean_test_results(root)

    gradle_command = [
        "./gradlew", "--no-daemon", "--max-workers=2", "--rerun-tasks",
        "--no-configuration-cache", "spotlessCheck", ":shared:jvmTest", ":composeApp:jvmTest",
    ]
    gradle_started = timestamp()
    gradle_exit, gradle_log = command_log(root, output_directory, "shared-compose-gradle", gradle_command)
    gradle_finished = timestamp()

    python_command = [sys.executable, "-m", "unittest", "discover", "-s", ".github/scripts"]
    python_started = timestamp()
    python_exit, python_log = command_log(root, output_directory, "python-unittest", python_command)
    python_finished = timestamp()

    tests: dict[str, dict] = {}
    for name, relative in (("shared", Path("shared/build/test-results/jvmTest")), ("compose", Path("composeApp/build/test-results/jvmTest"))):
        counts = junit_counts(root / relative)
        tests[name] = suite_record(
            name, source_sha, source_tree, gradle_command, output_directory, gradle_log,
            gradle_exit, counts, gradle_started,
        )
        tests[name]["finished_at"] = gradle_finished
    python_path = output_directory / python_log
    tests["python"] = suite_record(
        "python", source_sha, source_tree, python_command, output_directory, python_log,
        python_exit, python_counts(python_path), python_started,
    )
    tests["python"]["finished_at"] = python_finished

    end_sha, end_tree, end_clean = source_snapshot(root)
    checkout_unchanged = end_sha == source_sha and end_tree == source_tree and end_clean
    overall = checkout_unchanged and all(result["status"] == "success" for result in tests.values())
    report = {
        "schema": 1,
        "source_sha": source_sha,
        "source_tree": source_tree,
        "source_sha_end": end_sha,
        "source_tree_end": end_tree,
        "status": "success" if overall else "failure",
        "clean_checkout": checkout_unchanged,
        "started_at": started_at,
        "finished_at": timestamp(),
        "tests": tests,
    }
    report_path = output_directory / "tests.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Local test evidence: {report_path}; status={report['status']}; sha={source_sha}")
    return report_path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-directory", type=Path, required=True, help="新目录，必须位于源码仓库之外")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    try:
        report = run_tests(args.root, args.output_directory)
    except (GateError, OSError) as error:
        print(f"Local tests blocked: {error}", file=sys.stderr)
        return 1
    data = json.loads(report.read_text(encoding="utf-8"))
    return 0 if data["status"] == "success" else 1


if __name__ == "__main__":
    raise SystemExit(main())
