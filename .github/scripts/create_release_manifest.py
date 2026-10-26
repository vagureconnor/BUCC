#!/usr/bin/env python3
"""Create a six-artifact release manifest, verifying package signing certificates."""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

from release_gate import GateError, android_fingerprint, harmony_fingerprint

ASSET_FILES = {
    "android": "BUCC-Android-v{version}.apk",
    "ios": "BUCC-iOS-v{version}-unsigned.ipa",
    "macos-x86_64": "BUCC-macOS-x86_64-v{version}.dmg",
    "macos-arm64": "BUCC-macOS-arm64-v{version}.dmg",
    "windows": "BUCC-Windows-v{version}.exe",
    "harmony": "BUCC-Harmony-v{version}.hap",
}
ARCH = {"android": "universal", "ios": "arm64", "macos-x86_64": "x86_64", "macos-arm64": "arm64", "windows": "x86_64", "harmony": "arm64-v8a"}


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def create(directory: Path, version: str, source_sha: str, allow_unsigned_harmony: bool = False) -> dict:
    assets = []
    for platform, template in ASSET_FILES.items():
        filename = template.format(version=version)
        file = directory / filename
        if platform == "harmony" and not file.is_file():
            filename = f"BUCC-Harmony-v{version}-unsigned.hap"
            file = directory / filename
        if not file.is_file():
            raise GateError(f"缺少平台资产：{filename}")
        item = {
            "platform": platform,
            "arch": ARCH[platform],
            "file": filename,
            "version": version,
            "source_sha": source_sha,
            "sha256": file_sha256(file),
        }
        if platform == "android":
            item["signing_fingerprint"] = android_fingerprint(file)
        elif platform == "harmony":
            try:
                item["signing_fingerprint"] = harmony_fingerprint(file)
            except GateError:
                if not allow_unsigned_harmony:
                    raise
                item["signing_fingerprint"] = None
                item["signing"] = "unsigned draft; must be signed and manifest regenerated before publication"
        elif platform == "ios":
            item["signing"] = "unsigned"
        assets.append(item)
    manifest = {"version": version, "source_sha": source_sha, "assets": assets}
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--allow-unsigned-harmony", action="store_true", help="draft only; final publish gate will reject the unsigned HAP")
    args = parser.parse_args()
    try:
        manifest = create(args.directory, args.version, args.source_sha, args.allow_unsigned_harmony)
    except (GateError, OSError) as error:
        print(f"Manifest blocked: {error}", file=sys.stderr)
        return 1
    print(f"Wrote manifest for {len(manifest['assets'])} assets ({args.source_sha}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
