#!/usr/bin/env python3
"""Fail-closed verification for BUCC multi-platform release staging."""
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
import tempfile
from pathlib import Path

from package_validation import PackageError, validate_ios_ipa, validate_macos_dmg, validate_windows_exe
from public_certificate import CertificateError, leaf_sha256

REQUIRED = {"android", "ios", "macos-x86_64", "macos-arm64", "windows", "harmony"}


class GateError(ValueError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()



def normalize_fingerprint(value: str) -> str:
    return re.sub(r"[^0-9a-f]", "", value.lower())


def android_fingerprint(android_apk: Path) -> str:
    apksigner = shutil.which("apksigner")
    if not apksigner:
        raise GateError("找不到 apksigner，不能核验 Android APK 证书")
    android_result = subprocess.run(
        [apksigner, "verify", "--verbose", "--print-certs", str(android_apk)],
        check=False, capture_output=True, text=True
    )
    if android_result.returncode:
        raise GateError("apksigner 无法验证 Android APK")
    match = re.search(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", android_result.stdout)
    if not match:
        raise GateError("apksigner 未输出 Android SHA-256 证书指纹")
    return normalize_fingerprint(match.group(1))


def harmony_fingerprint(harmony_hap: Path) -> str:
    tool = Path(os.environ.get("HAP_SIGN_TOOL_JAR", ""))
    java = shutil.which("java")
    if not tool.is_file() or not java:
        raise GateError("需要官方 hap-sign-tool.jar、Java 和 OpenSSL 才能核验 Harmony 签名")
    with tempfile.TemporaryDirectory(prefix="bucc-release-signature-") as temporary:
        cert = Path(temporary) / "certificate.cer"
        profile = Path(temporary) / "profile.p7b"
        verify = subprocess.run(
            [java, "-jar", str(tool), "verify-app", "-inFile", str(harmony_hap),
             "-outCertChain", str(cert), "-outProfile", str(profile)],
            check=False, capture_output=True, text=True
        )
        if verify.returncode or not cert.is_file():
            raise GateError("hap-sign-tool 无法验证 Harmony HAP")
        try:
            return leaf_sha256(cert)
        except CertificateError as error:
            raise GateError(f"Harmony HAP 签名证书链无效：{error}") from None


def signature_fingerprints(android_apk: Path, harmony_hap: Path) -> tuple[str, str]:
    return android_fingerprint(android_apk), harmony_fingerprint(harmony_hap)


def validate_acceptance_proof(directory: Path, proof: Path, source_sha: str) -> None:
    if proof.name != "acceptance-proof.json" or proof.parent.resolve() != directory.resolve():
        raise GateError("正式发布验收证明必须是输入目录中的 acceptance-proof.json")
    try:
        proof_data = json.loads(proof.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise GateError("验收证明 JSON 格式错误") from error
    expected_proof_fields = {"schema", "source_sha", "manifest_sha256", "library_cancel"}
    if not isinstance(proof_data, dict) or set(proof_data) != expected_proof_fields:
        raise GateError("验收证明字段不符合固定 schema")
    if type(proof_data.get("schema")) is not int or proof_data["schema"] != 1 or proof_data.get("source_sha") != source_sha:
        raise GateError("验收证明未批准该源码 SHA")
    manifest = directory / "manifest.json"
    if not manifest.is_file() or proof_data.get("manifest_sha256") != sha256(manifest):
        raise GateError("验收证明 manifest SHA-256 不匹配")
    cancellation = proof_data.get("library_cancel")
    if not isinstance(cancellation, dict) or set(cancellation) != {"status", "evidence_file", "evidence_sha256"}:
        raise GateError("图书馆取消验收证明字段不符合固定 schema")
    if cancellation.get("status") != "passed" or cancellation.get("evidence_file") != "library-cancel-evidence.json":
        raise GateError("图书馆取消验收未通过或证据文件名无效")
    evidence_path = directory / "library-cancel-evidence.json"
    if not evidence_path.is_file() or cancellation.get("evidence_sha256") != sha256(evidence_path):
        raise GateError("图书馆取消验收证据缺失或 SHA-256 不匹配")
    try:
        evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise GateError("图书馆取消验收证据 JSON 格式错误") from error
    expected_evidence_fields = {
        "schema", "source_sha", "created_and_cancelled_same_test_record",
        "official_cancel_confirmed", "timestamp",
    }
    if not isinstance(evidence, dict) or set(evidence) != expected_evidence_fields:
        raise GateError("图书馆取消验收证据必须仅包含固定脱敏字段")
    if (
        type(evidence.get("schema")) is not int
        or evidence["schema"] != 1
        or evidence.get("source_sha") != source_sha
        or evidence.get("created_and_cancelled_same_test_record") is not True
        or evidence.get("official_cancel_confirmed") is not True
    ):
        raise GateError("图书馆取消验收证据不符合 schema 或未确认官方取消")
    timestamp = evidence.get("timestamp")
    if not isinstance(timestamp, str):
        raise GateError("图书馆取消验收证据缺少时间戳")
    try:
        parsed_timestamp = dt.datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    except ValueError:
        raise GateError("图书馆取消验收证据时间戳格式无效") from None
    if parsed_timestamp.tzinfo is None:
        raise GateError("图书馆取消验收证据时间戳必须包含时区")


def validate_package_contents(platform: str, artifact: Path, version: str, item: dict) -> None:
    try:
        if platform == "ios":
            if item.get("signing") != "unsigned":
                raise PackageError("iOS manifest 必须如实标记为 unsigned")
            validate_ios_ipa(artifact, version, 201)
        elif platform in {"macos-x86_64", "macos-arm64"}:
            arch = "x86_64" if platform == "macos-x86_64" else "arm64"
            validate_macos_dmg(artifact, version, 201, arch)
        elif platform == "windows":
            validate_windows_exe(artifact, item.get("signing"))
    except PackageError as error:
        raise GateError(f"{platform} 实际包验证失败：{error}") from error


def validate(
    directory: Path,
    version: str,
    source_sha: str,
    expected_android: str,
    expected_harmony: str,
    proof: Path | None,
    *,
    require_acceptance: bool = True,
) -> list[Path]:
    if require_acceptance:
        if proof is None or not proof.is_file():
            raise GateError("真实验收证明缺失；不得公开发布")
        validate_acceptance_proof(directory, proof, source_sha)
    manifest_path = directory / "manifest.json"
    if not manifest_path.is_file():
        raise GateError("缺少 manifest.json")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("version") != version or manifest.get("source_sha") != source_sha:
        raise GateError("manifest 版本或源码 SHA 不匹配")
    entries = manifest.get("assets")
    if not isinstance(entries, list):
        raise GateError("manifest assets 格式错误")
    platforms = [item.get("platform") for item in entries]
    if len(platforms) != len(set(platforms)):
        raise GateError("重复资产平台")
    missing = REQUIRED - set(platforms)
    if missing:
        raise GateError("缺少平台资产：" + ", ".join(sorted(missing)))
    if len(platforms) != len(REQUIRED) or set(platforms) != REQUIRED:
        raise GateError("正式发布必须恰好包含六个平台资产")
    by_platform = {}
    resolved = []
    for item in entries:
        platform = item.get("platform")
        by_platform[platform] = item
        expected_arch = {"macos-x86_64": "x86_64", "macos-arm64": "arm64"}.get(platform)
        if expected_arch and item.get("arch") != expected_arch:
            raise GateError(f"{platform} 架构错误：expected {expected_arch}")
        if item.get("version") != version or item.get("source_sha") != source_sha:
            raise GateError(f"{platform} 资产版本或源码 SHA 不匹配")
        relative = Path(item.get("file", ""))
        if not relative.name or relative.is_absolute() or ".." in relative.parts:
            raise GateError(f"{platform} 文件路径无效")
        if (
            len(relative.parts) != 1
            or relative.name in {"manifest.json", "acceptance-proof.json"}
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", relative.name)
        ):
            raise GateError(f"{platform} 资产必须使用独立的普通文件名")
        artifact = directory / relative
        if not artifact.is_file():
            raise GateError(f"{platform} 缺少资产文件：{relative}")
        if not re.fullmatch(r"[0-9a-f]{64}", str(item.get("sha256", ""))) or sha256(artifact) != item["sha256"]:
            raise GateError(f"{platform} 资产 SHA-256 校验失败")
        validate_package_contents(platform, artifact, version, item)
        resolved.append(artifact)
    missing = REQUIRED - by_platform.keys()
    if missing:
        raise GateError("缺少平台资产：" + ", ".join(sorted(missing)))
    android = by_platform["android"]
    harmony = by_platform["harmony"]
    actual_android, actual_harmony = signature_fingerprints(
        directory / android["file"], directory / harmony["file"]
    )
    if actual_android != normalize_fingerprint(expected_android) or not expected_android:
        raise GateError("Android 实际公开签名指纹不匹配或未配置")
    if actual_harmony != normalize_fingerprint(expected_harmony) or not expected_harmony:
        raise GateError("Harmony 实际公开签名指纹不匹配或未配置")
    if android.get("signing_fingerprint") != actual_android or harmony.get("signing_fingerprint") != actual_harmony:
        raise GateError("manifest 中的公开签名指纹与资产证书不匹配")
    return resolved


def publish_release(
    directory: Path,
    version: str,
    source_sha: str,
    expected_android: str,
    expected_harmony: str,
    proof: Path,
) -> list[Path]:
    if proof.name != "acceptance-proof.json":
        raise GateError("正式发布验收证明文件名必须为 acceptance-proof.json")
    assets = validate(directory, version, source_sha, expected_android, expected_harmony, proof)
    filenames = [asset.name for asset in assets]
    if len(filenames) != len(set(filenames)):
        raise GateError("平台资产文件名重复")
    manifest = directory / "manifest.json"
    evidence = directory / "library-cancel-evidence.json"
    tag = f"v{version}"
    gh = shutil.which("gh")
    if not gh:
        raise GateError("gh CLI is required to publish")

    def run_gh(*arguments: str) -> str:
        result = subprocess.run([gh, *arguments], check=False, capture_output=True, text=True)
        if result.returncode:
            operation = " ".join(arguments[:2])
            raise GateError(f"GitHub CLI {operation} 操作失败；上传或核验未完成，draft 保持未发布")
        return result.stdout

    def assert_main_sha() -> str:
        repository = run_gh("repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner").strip()
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise GateError("无法确定 GitHub 仓库；main SHA 未核验，draft 保持未发布")
        main_sha = run_gh("api", f"repos/{repository}/commits/main", "--jq", ".sha").strip()
        if main_sha != source_sha:
            raise GateError("GitHub main SHA 与待发布源码 SHA 不匹配；draft 保持未发布")
        return main_sha

    try:
        assert_main_sha()
        draft = json.loads(run_gh("release", "view", tag, "--json", "isDraft,targetCommitish,assets"))
    except json.JSONDecodeError as error:
        raise GateError("无法读取 GitHub draft 信息；draft 保持未发布") from error
    if draft.get("isDraft") is not True or draft.get("targetCommitish") != source_sha:
        raise GateError("GitHub draft 必须是同一待验收源码 SHA 的未发布草稿")
    allowed_names = set(filenames) | {manifest.name, proof.name, evidence.name}
    existing_assets = draft.get("assets", [])
    if not isinstance(existing_assets, list) or any(
        not isinstance(asset, dict) or asset.get("name") not in allowed_names for asset in existing_assets
    ):
        raise GateError("GitHub draft 含有未声明资产；draft 保持未发布")

    run_gh("release", "upload", tag, *(str(path) for path in [*assets, manifest, proof, evidence]), "--clobber")
    with tempfile.TemporaryDirectory(prefix="bucc-release-remote-verify-") as temporary:
        download = ["release", "download", tag, "--dir", temporary]
        expected_remote_files = set(filenames) | {manifest.name, proof.name, evidence.name}
        for filename in sorted(expected_remote_files):
            download.extend(["--pattern", filename])
        run_gh(*download)
        downloaded = Path(temporary)
        if {path.name for path in downloaded.iterdir()} != expected_remote_files:
            raise GateError("GitHub draft 下载资产集合不完整或多出文件；draft 保持未发布")
        validate(
            downloaded,
            version,
            source_sha,
            expected_android,
            expected_harmony,
            downloaded / proof.name,
        )

    assert_main_sha()
    try:
        latest_draft = json.loads(run_gh("release", "view", tag, "--json", "isDraft,targetCommitish"))
    except json.JSONDecodeError as error:
        raise GateError("公开前无法复核 GitHub draft；draft 保持未发布") from error
    if latest_draft.get("isDraft") is not True or latest_draft.get("targetCommitish") != source_sha:
        raise GateError("公开前 GitHub draft SHA 不匹配；draft 保持未发布")
    run_gh("release", "edit", tag, "--draft=false", "--latest")
    return assets


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--expected-android-fingerprint", default="")
    parser.add_argument("--expected-harmony-fingerprint", default="")
    parser.add_argument("--acceptance-proof", type=Path)
    parser.add_argument("--publish", action="store_true", help="replace and re-verify the matching draft assets before publication")
    args = parser.parse_args()
    try:
        if args.publish:
            if args.acceptance_proof is None:
                raise GateError("真实验收证明缺失；不得公开发布")
            assets = publish_release(
                args.directory,
                args.version,
                args.source_sha,
                args.expected_android_fingerprint,
                args.expected_harmony_fingerprint,
                args.acceptance_proof,
            )
        else:
            assets = validate(args.directory, args.version, args.source_sha, args.expected_android_fingerprint, args.expected_harmony_fingerprint, args.acceptance_proof)
    except (GateError, OSError, json.JSONDecodeError) as error:
        print(f"Release blocked: {error}", file=sys.stderr)
        return 1
    if not args.publish:
        print(f"Verified {len(assets)} release assets. Draft remains unpublished; pass --publish after review to publish.")
        return 0
    print(f"Published v{args.version} from {args.source_sha}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
