#!/usr/bin/env python3
"""Validate locally built release assets and optionally report a GitHub status."""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import hashlib
import datetime as dt
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / ".github" / "scripts"))

from release_gate import GateError, REQUIRED, validate as validate_release_assets  # noqa: E402

STATUS_CONTEXT = "Local five platform gate"
REQUIRED_TESTS = ("shared", "compose", "python")


def validate_test_evidence(path: Path, source_sha: str, expected_tree: str | None = None) -> None:
    if not re.fullmatch(r"[0-9a-f]{40,64}", source_sha):
        raise GateError("源码 SHA 格式无效")
    try:
        evidence = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise GateError(f"测试证据缺失或 JSON 无效：{error}") from error
    if not isinstance(evidence, dict) or evidence.get("source_sha") != source_sha:
        raise GateError("测试证据源码 SHA 不匹配")
    source_tree = evidence.get("source_tree")
    if not isinstance(source_tree, str) or not re.fullmatch(r"[0-9a-f]{40}", source_tree):
        raise GateError("测试证据 source_tree 格式无效")
    if expected_tree is not None and source_tree != expected_tree:
        raise GateError("测试证据 source_tree 与当前源码不匹配")
    if type(evidence.get("schema")) is not int or evidence["schema"] != 1 or evidence.get("status") != "success" or evidence.get("clean_checkout") is not True:
        raise GateError("测试证据必须来自 clean checkout 且全部成功")
    if evidence.get("source_sha_end") != source_sha or evidence.get("source_tree_end") != source_tree:
        raise GateError("测试运行期间源码 SHA/tree 发生变化")
    _validate_timestamp(evidence.get("started_at"))
    _validate_timestamp(evidence.get("finished_at"))
    suites = evidence.get("tests")
    if not isinstance(suites, dict) or set(suites) != set(REQUIRED_TESTS):
        raise GateError("测试证据必须包含 shared、compose、python 三个套件")
    for name in REQUIRED_TESTS:
        result = suites.get(name)
        if not isinstance(result, dict):
            raise GateError(f"测试证据缺少 {name} 结果")
        if result.get("source_sha") != source_sha or result.get("source_tree") != source_tree:
            raise GateError(f"{name} 测试证据源码 SHA 不匹配")
        if result.get("status") != "success":
            raise GateError(f"{name} 测试未成功")
        if type(result.get("exit_code")) is not int or result["exit_code"] != 0:
            raise GateError(f"{name} 测试命令未成功退出")
        for count in ("tests", "failures", "errors", "skipped"):
            if type(result.get(count)) is not int or result[count] < 0:
                raise GateError(f"{name} 测试 {count} 数量无效")
        if result["tests"] == 0 or result["failures"] or result["errors"]:
            raise GateError(f"{name} 测试零用例、存在 failure 或 error")
        command = result.get("command")
        if not isinstance(command, list) or not command or not all(isinstance(part, str) for part in command):
            raise GateError(f"{name} 缺少真实测试命令")
        if name in {"shared", "compose"}:
            expected_command = [
                "./gradlew", "--no-daemon", "--max-workers=2", "--rerun-tasks",
                "--no-configuration-cache", "spotlessCheck", ":shared:jvmTest", ":composeApp:jvmTest",
            ]
            if command != expected_command:
                raise GateError(f"{name} 证据没有记录约定的 Gradle 测试命令")
        elif len(command) < 5 or command[1:5] != ["-m", "unittest", "discover", "-s"] or command[5:] != [".github/scripts"]:
            raise GateError("python 证据没有记录 unittest discover 命令")
        log_name = result.get("log_file")
        if not isinstance(log_name, str):
            raise GateError(f"{name} 测试日志路径无效")
        log_relative = Path(log_name)
        if log_relative.is_absolute() or ".." in log_relative.parts or len(log_relative.parts) != 1:
            raise GateError(f"{name} 测试日志路径无效")
        log = path.parent / log_relative
        if not log.is_file() or not re.fullmatch(r"[0-9a-f]{64}", str(result.get("log_sha256", ""))):
            raise GateError(f"{name} 测试日志缺失或哈希格式无效")
        digest = hashlib.sha256(log.read_bytes()).hexdigest()
        if digest != result["log_sha256"]:
            raise GateError(f"{name} 测试日志 SHA-256 不匹配")
        _validate_timestamp(result.get("started_at"))
        _validate_timestamp(result.get("finished_at"))


def _validate_timestamp(value: object) -> None:
    if not isinstance(value, str):
        raise GateError("测试证据缺少时间戳")
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise GateError("测试证据时间戳格式无效") from None
    if parsed.tzinfo is None:
        raise GateError("测试证据时间戳必须包含时区")


def current_checkout() -> tuple[str, str, bool]:
    sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, check=False, capture_output=True, text=True)
    tree = subprocess.run(["git", "rev-parse", "HEAD^{tree}"], cwd=ROOT, check=False, capture_output=True, text=True)
    status = subprocess.run(
        ["git", "status", "--porcelain", "--untracked-files=all"],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    if sha.returncode or tree.returncode or status.returncode:
        raise GateError("无法读取本机 clean checkout 状态")
    return sha.stdout.strip(), tree.stdout.strip(), not status.stdout.strip()


def validate_local_gate(
    directory: Path,
    version: str,
    source_sha: str,
    expected_android: str,
    expected_harmony: str,
    test_report: Path,
    source_tree: str | None = None,
) -> list[Path]:
    validate_test_evidence(test_report, source_sha, source_tree)
    try:
        manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise GateError(f"发布 manifest 缺失或 JSON 无效：{error}") from error
    platforms = [asset.get("platform") for asset in manifest.get("assets", []) if isinstance(asset, dict)]
    missing = REQUIRED - set(platforms)
    if missing:
        raise GateError("缺少平台资产：" + ", ".join(sorted(missing)))
    if len(platforms) != len(REQUIRED) or set(platforms) != REQUIRED:
        raise GateError("本机平台 gate 要求恰好六个平台资产，包括 macOS 双架构")
    return validate_release_assets(
        directory,
        version,
        source_sha,
        expected_android,
        expected_harmony,
        None,
        require_acceptance=False,
    )


def run_local_gate(
    directory: Path,
    version: str,
    source_sha: str,
    expected_android: str,
    expected_harmony: str,
    test_report: Path,
    *,
    report_status: bool = False,
    repository: str | None = None,
    source_tree: str | None = None,
) -> list[Path]:
    current_sha, current_tree, clean = current_checkout()
    if current_sha != source_sha or not clean:
        raise GateError("本机平台 gate 要求当前 checkout SHA 匹配且工作树 clean")
    if source_tree is not None and source_tree != current_tree:
        raise GateError("本机当前 source_tree 与预期不匹配")
    assets = validate_local_gate(
        directory,
        version,
        source_sha,
        expected_android,
        expected_harmony,
        test_report,
        current_tree,
    )
    if report_status:
        if not repository:
            raise GateError("报告状态时必须提供仓库 owner/repository")
        current_sha, current_tree, clean = current_checkout()
        if current_sha != source_sha or not clean:
            raise GateError("报告成功状态要求当前 checkout SHA 匹配且工作树 clean")
        validate_test_evidence(test_report, source_sha, current_tree)
        publish_status(repository, source_sha)
    return assets


def publish_status(repository: str, source_sha: str) -> None:
    if os.environ.get("GITHUB_ACTIONS", "").lower() == "true":
        raise GateError("此状态只能从本机报告，不能在 GitHub Actions 中报告")
    gh = shutil.which("gh")
    if not gh:
        raise GateError("找不到 gh CLI；请先用现有 gh 登录")
    result = subprocess.run(
        [
            gh,
            "api",
            f"repos/{repository}/statuses/{source_sha}",
            "-f",
            "state=success",
            "-f",
            f"context={STATUS_CONTEXT}",
            "-f",
            "description=Local five-platform assets and test evidence verified",
        ],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode:
        raise GateError("gh 无法为该 SHA 写入成功状态；请检查仓库与 statuses:write 权限")


def read_version(root: Path) -> str:
    properties = root / "gradle.properties"
    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith("project.version="):
            return line.partition("=")[2].strip()
    raise GateError("gradle.properties 缺少 project.version")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True, help="包含 manifest.json 和六个平台资产的目录")
    parser.add_argument("--source-sha", required=True, help="应与当前本机 HEAD 完全一致的源码 SHA")
    parser.add_argument("--tests-report", type=Path, required=True, help="shared/compose/python 测试证据 JSON")
    parser.add_argument("--expected-android-fingerprint", required=True)
    parser.add_argument("--expected-harmony-fingerprint", required=True)
    parser.add_argument("--report-status", action="store_true", help=f"验证通过后写入 {STATUS_CONTEXT} 状态")
    parser.add_argument("--repository", help="--report-status 时必填，例如 owner/repository")
    args = parser.parse_args()
    try:
        if args.report_status and not args.repository:
            raise GateError("--report-status 必须同时提供 --repository owner/repository")
        current_sha, current_tree, clean = current_checkout()
        if current_sha != args.source_sha:
            raise GateError("指定源码 SHA 与本机当前 HEAD 不完全一致")
        if not clean:
            raise GateError("写入 Local five platform gate 状态要求 clean checkout")
        assets = validate_local_gate(
            args.directory,
            read_version(ROOT),
            args.source_sha,
            args.expected_android_fingerprint,
            args.expected_harmony_fingerprint,
            args.tests_report,
            current_tree,
        )
        if args.report_status:
            publish_status(args.repository, args.source_sha)
    except (GateError, OSError, json.JSONDecodeError) as error:
        print(f"Local platform gate failed: {error}", file=sys.stderr)
        return 1
    print(f"Verified {len(assets)} signed release assets and all required tests for {args.source_sha}.")
    if args.report_status:
        print(f"Reported success status: {STATUS_CONTEXT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
