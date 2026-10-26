#!/usr/bin/env python3
"""Fail-closed verification for BUCC multi-platform release staging."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

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


def signature_fingerprints(android_apk: Path, harmony_hap: Path) -> tuple[str, str]:
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
    android_fp = normalize_fingerprint(match.group(1))

    tool = Path(os.environ.get("HAP_SIGN_TOOL_JAR", ""))
    java = shutil.which("java")
    openssl = shutil.which("openssl")
    if not tool.is_file() or not java or not openssl:
        raise GateError("需要官方 hap-sign-tool.jar、Java 和 OpenSSL 才能核验 Harmony 签名")
    with tempfile.TemporaryDirectory(prefix="bucc-release-signature-") as temporary:
        cert = Path(temporary) / "certificate.cer"
        profile = Path(temporary) / "profile.p7b"
        verify = subprocess.run(
            [java, "-jar", str(tool), "verify-app", "-inFile", str(harmony_hap),
             "-outCertchain", str(cert), "-outProfile", str(profile)],
            check=False, capture_output=True, text=True
        )
        if verify.returncode or not cert.is_file():
            raise GateError("hap-sign-tool 无法验证 Harmony HAP")
        certificate = subprocess.run(
            [openssl, "x509", "-in", str(cert), "-noout", "-fingerprint", "-sha256"],
            check=False, capture_output=True, text=True
        )
        if certificate.returncode:
            raise GateError("无法读取 Harmony HAP 签名证书指纹")
        match = re.search(r"SHA256 Fingerprint=([0-9a-fA-F:]+)", certificate.stdout)
        if not match:
            raise GateError("OpenSSL 未输出 Harmony SHA-256 证书指纹")
        harmony_fp = normalize_fingerprint(match.group(1))
    return android_fp, harmony_fp


def validate(directory: Path, version: str, source_sha: str, expected_android: str, expected_harmony: str, proof: Path | None) -> list[Path]:
    if proof is None or not proof.is_file():
        raise GateError("真实验收证明缺失；不得公开发布")
    proof_data = json.loads(proof.read_text(encoding="utf-8"))
    if proof_data.get("accepted") is not True or proof_data.get("source_sha") != source_sha:
        raise GateError("验收证明未批准该源码 SHA")
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
        artifact = directory / relative
        if not artifact.is_file():
            raise GateError(f"{platform} 缺少资产文件：{relative}")
        if not re.fullmatch(r"[0-9a-f]{64}", str(item.get("sha256", ""))) or sha256(artifact) != item["sha256"]:
            raise GateError(f"{platform} 资产 SHA-256 校验失败")
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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--expected-android-fingerprint", default="")
    parser.add_argument("--expected-harmony-fingerprint", default="")
    parser.add_argument("--acceptance-proof", type=Path)
    parser.add_argument("--publish", action="store_true", help="publish the matching GitHub draft after every check succeeds")
    args = parser.parse_args()
    try:
        assets = validate(args.directory, args.version, args.source_sha, args.expected_android_fingerprint, args.expected_harmony_fingerprint, args.acceptance_proof)
    except (GateError, OSError, json.JSONDecodeError) as error:
        print(f"Release blocked: {error}", file=sys.stderr)
        return 1
    if not args.publish:
        print(f"Verified {len(assets)} release assets. Draft remains unpublished; pass --publish after review to publish.")
        return 0
    gh = shutil.which("gh")
    if not gh:
        print("Release blocked: gh CLI is required to publish.", file=sys.stderr)
        return 1
    tag = f"v{args.version}"
    try:
        status = subprocess.run([gh, "release", "view", tag, "--json", "isDraft,targetCommitish"], check=False, capture_output=True, text=True)
        if status.returncode:
            raise GateError("找不到对应 GitHub draft release")
        draft = json.loads(status.stdout)
        if draft.get("isDraft") is not True or draft.get("targetCommitish") != args.source_sha:
            raise GateError("GitHub draft 不是待验收源码 SHA 对应的草稿")
        publish = subprocess.run([gh, "release", "edit", tag, "--draft=false", "--latest"], check=False)
        if publish.returncode:
            raise GateError("GitHub 未能公开 draft release")
    except (OSError, json.JSONDecodeError, GateError) as error:
        print(f"Release blocked: {error}", file=sys.stderr)
        return 1
    print(f"Published {tag} from {args.source_sha}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
