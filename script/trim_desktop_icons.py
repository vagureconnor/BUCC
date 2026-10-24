#!/usr/bin/env python3
"""Remove unreferenced extended Material Icons from a desktop app bundle."""

from __future__ import annotations

import re
import sys
import tempfile
import zipfile
from pathlib import Path


ICON_PREFIX = "androidx/compose/material/icons/"
ICON_REFERENCE = re.compile(
    rb"androidx/compose/material/icons/[A-Za-z0-9_$]+(?:/[A-Za-z0-9_$]+)*"
)


def trim(app_directory: Path) -> tuple[int, int]:
    icon_jars = list(app_directory.rglob("material-icons-extended-desktop-*.jar"))
    if len(icon_jars) != 1:
        raise RuntimeError(f"Expected one desktop Material Icons jar, found {len(icon_jars)}")
    icon_jar = icon_jars[0]

    references: set[str] = set()
    for candidate in app_directory.rglob("*.jar"):
        if candidate == icon_jar:
            continue
        with zipfile.ZipFile(candidate) as archive:
            for entry in archive.infolist():
                if entry.filename.endswith(".class"):
                    references.update(
                        f"{match.group().decode()}.class"
                        for match in ICON_REFERENCE.finditer(archive.read(entry.filename))
                    )

    with zipfile.ZipFile(icon_jar) as source:
        entries = source.infolist()
        icon_classes = {
            entry.filename
            for entry in entries
            if entry.filename.startswith(ICON_PREFIX) and entry.filename.endswith(".class")
        }
        keep = icon_classes & references
        if not keep or len(keep) == len(icon_classes):
            raise RuntimeError("Material Icons trimming did not identify a useful safe subset")

        with tempfile.NamedTemporaryFile(dir=icon_jar.parent, suffix=".jar", delete=False) as temp:
            temporary = Path(temp.name)
        try:
            with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as target:
                for entry in entries:
                    if entry.filename in icon_classes and entry.filename not in keep:
                        continue
                    target.writestr(entry, source.read(entry.filename), compresslevel=9)
        finally:
            source.close()
        try:
            icon_jar.replace(temporary)
        finally:
            temporary.unlink(missing_ok=True)
    return len(keep), len(icon_classes) - len(keep)


if __name__ == "__main__":
    try:
        kept, removed = trim(Path(sys.argv[1]))
    except (IndexError, OSError, RuntimeError, zipfile.BadZipFile) as error:
        print(f"Desktop icon trimming failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
    print(f"Desktop icons: kept {kept} referenced classes, removed {removed} unused classes")
