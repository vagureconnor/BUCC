#!/usr/bin/env python3
"""Package a saved iOS device .app as an unsigned IPA.

This creates a sideloading/test archive only. Removing signatures does not make
the application installable through normal iOS distribution channels.
"""

from __future__ import annotations

import argparse
import os
import plistlib
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


MACHO_MAGICS = {
    b"\xfe\xed\xfa\xce", b"\xce\xfa\xed\xfe",
    b"\xfe\xed\xfa\xcf", b"\xcf\xfa\xed\xfe",
    b"\xca\xfe\xba\xbe", b"\xbe\xba\xfe\xca",
    b"\xca\xfe\xba\xbf", b"\xbf\xba\xfe\xca",
}
IOS_DEVICE_PLATFORM = 2
IOS_SIMULATOR_PLATFORM = 7


class PackageError(Exception):
    pass


def _run(command: list[str]) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(command, text=True, capture_output=True, check=False)
    except OSError as exc:
        raise PackageError(f"Required Apple tool unavailable: {Path(command[0]).name}") from exc
    if result.returncode:
        raise PackageError(f"Apple tool failed: {Path(command[0]).name}")
    return result


def _is_macho(path: Path) -> bool:
    try:
        with path.open("rb") as stream:
            magic = stream.read(4)
    except OSError:
        return False
    return magic in MACHO_MAGICS


def _validate_device_app(app: Path) -> Path:
    info_path = app / "Info.plist"
    try:
        with info_path.open("rb") as stream:
            info = plistlib.load(stream)
    except (OSError, plistlib.InvalidFileException) as exc:
        raise PackageError("Input is missing a readable app Info.plist") from exc

    executable_name = info.get("CFBundleExecutable")
    if not isinstance(executable_name, str) or not executable_name or Path(executable_name).name != executable_name:
        raise PackageError("Info.plist has no valid CFBundleExecutable")
    executable = app / executable_name
    if not executable.is_file() or not _is_macho(executable):
        raise PackageError("App executable is missing or is not a Mach-O binary")

    archs = _run(["lipo", "-archs", str(executable)]).stdout.split()
    if "arm64" not in archs:
        raise PackageError("App executable does not contain an arm64 slice")

    load_commands = _run(["otool", "-arch", "arm64", "-l", str(executable)]).stdout
    platforms: list[int] = []
    lines = load_commands.splitlines()
    for index, line in enumerate(lines):
        if line.strip() == "cmd LC_BUILD_VERSION":
            for detail in lines[index + 1:index + 6]:
                if detail.strip().startswith("platform "):
                    try:
                        platforms.append(int(detail.strip().split()[1]))
                    except (IndexError, ValueError):
                        pass
                    break
    if IOS_SIMULATOR_PLATFORM in platforms:
        raise PackageError("Simulator builds cannot be packaged as an iPhone IPA")
    if not platforms or any(platform != IOS_DEVICE_PLATFORM for platform in platforms):
        raise PackageError("App executable is not confirmed as an iOS device build")
    return executable


def _copy_without_profiles(source: Path, destination: Path) -> None:
    def ignore(_directory: str, names: list[str]) -> set[str]:
        return {
            name for name in names
            if name == "_CodeSignature" or name == "embedded.mobileprovision"
        }

    shutil.copytree(source, destination, symlinks=True, ignore=ignore)


def _remove_embedded_signatures(app: Path) -> None:
    for path in app.rglob("*"):
        if path.is_file() and not path.is_symlink() and _is_macho(path):
            inspected = subprocess.run(
                ["codesign", "-d", "--verbose=2", str(path)],
                text=True, capture_output=True, check=False,
            )
            if inspected.returncode == 0:
                result = subprocess.run(
                    ["codesign", "--remove-signature", str(path)],
                    text=True, capture_output=True, check=False,
                )
                if result.returncode:
                    raise PackageError("Could not remove a nested code signature")


def _zip_tree(root: Path, archive_path: Path) -> None:
    with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(root.rglob("*")):
            if path.resolve() == archive_path.resolve():
                continue
            relative = path.relative_to(root).as_posix()
            info = zipfile.ZipInfo(relative + ("/" if path.is_dir() else ""))
            info.create_system = 3
            mode = path.lstat().st_mode
            info.external_attr = (mode & 0xFFFF) << 16
            if stat.S_ISLNK(mode):
                archive.writestr(info, os.readlink(path))
            elif path.is_dir():
                archive.writestr(info, b"")
            else:
                info.compress_type = zipfile.ZIP_DEFLATED
                with path.open("rb") as stream:
                    archive.writestr(info, stream.read())


def _verify_ipa(path: Path) -> None:
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            if not names or any(not name.startswith("Payload/") for name in names):
                raise PackageError("Archive contains entries outside Payload/")
            if not any(name.startswith("Payload/") and name.endswith(".app/") for name in names):
                raise PackageError("Archive does not contain Payload/<app>.app")
            if any(name.endswith("/embedded.mobileprovision") for name in names):
                raise PackageError("Archive unexpectedly contains an embedded provisioning profile")
            if any("/_CodeSignature/" in name for name in names):
                raise PackageError("Archive unexpectedly contains _CodeSignature metadata")
    except (OSError, zipfile.BadZipFile) as exc:
        raise PackageError("Could not verify generated IPA archive") from exc


def package(app_path: Path, output_path: Path) -> None:
    app = app_path.expanduser().resolve()
    output = output_path.expanduser().absolute()
    if app.suffix != ".app" or not app.is_dir():
        raise PackageError("Input must be a saved .app directory")
    if output.suffix.lower() != ".ipa":
        raise PackageError("Output filename must end in .ipa")
    if not output.parent.is_dir():
        raise PackageError("Output directory must already exist")
    if output.exists():
        raise PackageError("Output already exists; refusing to overwrite it")
    if output.resolve(strict=False).is_relative_to(app):
        raise PackageError("Output must be outside the source .app directory")

    _validate_device_app(app)
    with tempfile.TemporaryDirectory(prefix="bucc-unsigned-ipa-") as temporary:
        temp_root = Path(temporary)
        payload = temp_root / "Payload"
        payload.mkdir()
        staged_app = payload / app.name
        _copy_without_profiles(app, staged_app)
        _remove_embedded_signatures(staged_app)
        staged_ipa = temp_root / "package.ipa"
        _zip_tree(temp_root, staged_ipa)
        _verify_ipa(staged_ipa)
        # O_EXCL ensures a concurrent creator's artifact is never overwritten.
        try:
            fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
        except FileExistsError as exc:
            raise PackageError("Output already exists; refusing to overwrite it") from exc
        try:
            with os.fdopen(fd, "wb") as destination, staged_ipa.open("rb") as source:
                shutil.copyfileobj(source, destination)
        except Exception:
            output.unlink(missing_ok=True)
            raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path, help="saved physical-device .app directory")
    parser.add_argument("output", type=Path, help="new .ipa output path; existing files are never overwritten")
    args = parser.parse_args(argv)
    try:
        package(args.app, args.output)
    except PackageError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(f"Unsigned IPA created: {args.output}")
    print("This archive is unsigned and is not installable through normal iOS distribution channels.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
