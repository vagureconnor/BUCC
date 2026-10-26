#!/usr/bin/env python3
"""Check that platform version declarations match gradle.properties."""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def properties(path: Path) -> dict[str, str]:
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def declared_versions(root: Path = ROOT) -> dict[str, str]:
    canonical = properties(root / "gradle.properties")
    version = canonical["project.version"]
    code = canonical["project.version.code"]
    harmony = properties(root / "harmonyBuild/gradle.properties")["project.version"]
    app = json.loads((root / "harmonyApp/AppScope/app.json5").read_text(encoding="utf-8")) ["app"]
    package = json.loads((root / "harmonyApp/oh-package.json5").read_text(encoding="utf-8"))
    xcconfig = (root / "iosApp/Configuration/Config.xcconfig").read_text(encoding="utf-8")
    pbx = (root / "iosApp/iosApp.xcodeproj/project.pbxproj").read_text(encoding="utf-8")
    marketing = re.findall(r"MARKETING_VERSION\s*=\s*([^;]+);", pbx)
    builds = re.findall(r"CURRENT_PROJECT_VERSION\s*=\s*([^;]+);", pbx)
    values = {
        "gradle.properties": version,
        "harmonyBuild/gradle.properties": harmony,
        "harmony versionName": str(app["versionName"]),
        "harmony versionCode": str(app["versionCode"]),
        "harmony package version": str(package["version"]),
        "iOS marketing version": re.search(r"(?m)^MARKETING_VERSION=(.+)$", xcconfig).group(1).strip(),
        "iOS build number": re.search(r"(?m)^CURRENT_PROJECT_VERSION=(.+)$", xcconfig).group(1).strip(),
        "root code": code,
    }
    values.update({f"iOS project marketing version {index}": item.strip() for index, item in enumerate(marketing)})
    values.update({f"iOS project build number {index}": item.strip() for index, item in enumerate(builds)})
    return values


def check(root: Path = ROOT) -> list[str]:
    values = declared_versions(root)
    canonical = properties(root / "gradle.properties")
    expected_version, expected_code = canonical["project.version"], canonical["project.version.code"]
    errors = []
    for key, value in values.items():
        expected = expected_code if "code" in key.lower() or "build number" in key.lower() else expected_version
        if value != expected:
            errors.append(f"{key}: expected {expected}, found {value}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="read-only consistency check")
    args = parser.parse_args()
    errors = check()
    if errors:
        print("Version declarations disagree:\n- " + "\n- ".join(errors), file=sys.stderr)
        return 1
    print("Platform versions and build codes match gradle.properties.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
