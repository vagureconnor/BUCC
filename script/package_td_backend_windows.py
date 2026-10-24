#!/usr/bin/env python3
"""Build the bundled Windows x64 AutoTD backend with the current Python."""

from __future__ import annotations

import os
import shutil
import struct
import subprocess
import sys
import sysconfig
from pathlib import Path


PYINSTALLER_VERSION = "6.22.3"


def run(command: list[str], *, cwd: Path | None = None) -> None:
    subprocess.run(command, cwd=cwd, check=True)


def main() -> int:
    if sys.platform != "win32":
        raise SystemExit("Windows TD backend packaging must run with native Windows Python.")
    python_platform = sysconfig.get_platform()
    pointer_bits = struct.calcsize("P") * 8
    if python_platform != "win-amd64" or pointer_bits != 64:
        raise SystemExit(
            f"Expected Windows x64 Python (win-amd64, 64-bit), found {python_platform} ({pointer_bits}-bit)."
        )

    root = Path(__file__).resolve().parent.parent
    source = root / "composeApp" / "src" / "jvmMain" / "resources" / "td-backend"
    build = Path(
        os.environ.get(
            "BUCC_TD_PACKAGING_OUTPUT",
            root / "composeApp" / "build" / "td-packaging-windows",
        )
    ).resolve()
    dist = build / "onedir-dist"
    work = build / "pyinstaller-work"
    destination = build / "resources" / "windows-x64" / "td-backend"
    common = build / "resources" / "common"

    if not (source / "BackendMain.py").is_file():
        raise SystemExit(f"AutoTD source not found: {source}")

    run(
        [
            sys.executable,
            "-m",
            "pip",
            "install",
            "--disable-pip-version-check",
            f"pyinstaller=={PYINSTALLER_VERSION}",
            "keyring==25.7.0",
        ]
    )

    separator = os.pathsep
    run(
        [
            sys.executable,
            "-m",
            "PyInstaller",
            "--noconfirm",
            "--clean",
            "--onedir",
            "--contents-directory",
            "runtime",
            "--name",
            "AutoTDBackend",
            "--paths",
            str(source / "src"),
            "--add-data",
            f"{source / 'src' / 'auto_td' / 'templates'}{separator}auto_td/templates",
            "--add-data",
            f"{source / 'src' / 'auto_td' / 'ui_static'}{separator}auto_td/ui_static",
            "--add-data",
            f"{source / 'default_images'}{separator}default_images",
            "--hidden-import",
            "keyring.backends.Windows",
            "--collect-all",
            "keyring",
            "--distpath",
            str(dist),
            "--workpath",
            str(work),
            "--specpath",
            str(build),
            str(source / "BackendMain.py"),
        ],
        cwd=root,
    )

    built = dist / "AutoTDBackend" / "AutoTDBackend.exe"
    if not built.is_file():
        raise SystemExit(f"PyInstaller did not produce the expected executable: {built}")

    # Replace only this script's generated backend directory after a successful
    # build. A failed PyInstaller run leaves the previous package untouched.
    staged = destination.with_name(destination.name + ".staging")
    if staged.exists():
        shutil.rmtree(staged)
    shutil.copytree(built.parent, staged)
    if destination.exists():
        shutil.rmtree(destination)
    staged.rename(destination)

    common.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source / "VENDOR_NOTICE.md", common / "VENDOR_NOTICE.md")
    shutil.copy2(root / "LICENSE", common / "LICENSE")
    print(f"Windows x64 AutoTD backend packaged at: {destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
