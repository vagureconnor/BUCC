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
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / ".github" / "scripts"))

from release_gate import GateError, REQUIRED, validate as validate_release_assets  # noqa: E402

STATUS_CONTEXT = "Local five platform gate"
REQUIRED_TESTS = ("shared", "compose", "python")


def validate_test_evidence(path: Path, source_sha: str) -> None:
    if not re.fullmatch(r"[0-9a-f]{40,64}", source_sha):
        raise GateError("源码 SHA 格式无效")
    try:
        evidence = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise GateError(f"测试证据缺失或 JSON 无效：{error}") from error
    if not isinstance(evidence, dict) or evidence.get("source_sha") != source_sha:
        raise GateError("测试证据源码 SHA 不匹配")
    suites = evidence.get("tests")
    if not isinstance(suites, dict):
        raise GateError("测试证据缺少 tests 对象")
    for name in REQUIRED_TESTS:
        result = suites.get(name)
        if not isinstance(result, dict):
            raise GateError(f"测试证据缺少 {name} 结果")
        if result.get("source_sha") != source_sha:
            raise GateError(f"{name} 测试证据源码 SHA 不匹配")
        if result.get("status") != "success" or type(result.get("failures")) is not int or result["failures"] != 0:
            raise GateError(f"{name} 测试未成功或 failures 不为 0")


def validate_local_gate(
    directory: Path,
    version: str,
    source_sha: str,
    expected_android: str,
    expected_harmony: str,
    test_report: Path,
) -> list[Path]:
    validate_test_evidence(test_report, source_sha)
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
) -> list[Path]:
    assets = validate_local_gate(
        directory,
        version,
        source_sha,
        expected_android,
        expected_harmony,
        test_report,
    )
    if report_status:
        if not repository:
            raise GateError("报告状态时必须提供仓库 owner/repository")
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
        head = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, check=False, capture_output=True, text=True
        )
        if head.returncode or head.stdout.strip() != args.source_sha:
            raise GateError("指定源码 SHA 与本机当前 HEAD 不完全一致")
        assets = validate_local_gate(
            args.directory,
            read_version(ROOT),
            args.source_sha,
            args.expected_android_fingerprint,
            args.expected_harmony_fingerprint,
            args.tests_report,
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
