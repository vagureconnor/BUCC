#!/bin/bash
set -euo pipefail
BUCC_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TD_SOURCE="$BUCC_ROOT/composeApp/src/jvmMain/resources/td-backend"
TD_BUILD="${BUCC_TD_PACKAGING_OUTPUT:-$BUCC_ROOT/composeApp/build/td-packaging}"
TD_PYTHON="${BUCC_TD_PACKAGING_PYTHON:-$TD_BUILD/venv/bin/python}"
TD_ARCH="${BUCC_TD_ARCH:-$(uname -m)}"
case "$TD_ARCH" in
  arm64)
    TD_RESOURCE_PLATFORM="macos-arm64"
    ;;
  x86_64)
    TD_RESOURCE_PLATFORM="macos-x64"
    ;;
  *)
    echo "Unsupported macOS TD backend architecture: $TD_ARCH" >&2
    exit 1
    ;;
esac
if [[ "$TD_ARCH" == "x86_64" && "$(uname -m)" == "arm64" ]]; then
  if [[ -z "${BUCC_TD_PACKAGING_PYTHON:-}" ]]; then
    echo "Intel TD backend packaging on Apple Silicon requires BUCC_TD_PACKAGING_PYTHON to point to an x86_64 Python environment." >&2
    exit 1
  fi
fi
run_python() {
  if [[ "$TD_ARCH" == "x86_64" && "$(uname -m)" == "arm64" ]]; then
    arch -x86_64 "$TD_PYTHON" "$@"
  else
    "$TD_PYTHON" "$@"
  fi
}
if [[ ! -x "$TD_PYTHON" ]]; then
  if [[ "$TD_ARCH" != "$(uname -m)" ]]; then
    echo "Cross-architecture TD backend packaging requires an existing target-architecture Python environment." >&2
    exit 1
  fi
  python3 -m venv "$TD_BUILD/venv"
  run_python -m pip install --disable-pip-version-check 'pyinstaller==6.22.3'
fi
PYTHON_MACHINE=$(run_python -c 'import platform; print(platform.machine())')
if [[ "$PYTHON_MACHINE" != "$TD_ARCH" ]]; then
  echo "TD backend Python architecture mismatch: expected $TD_ARCH, found $PYTHON_MACHINE." >&2
  exit 1
fi
run_python -m pip install --disable-pip-version-check 'pyinstaller==6.22.3' 'cryptography==46.0.3'
TD_DEST="$TD_BUILD/resources/$TD_RESOURCE_PLATFORM/td-backend"
mkdir -p "$TD_BUILD/resources/common"
run_python -m PyInstaller --noconfirm --onedir --contents-directory runtime --name AutoTDBackend \
  --target-architecture "$TD_ARCH" \
  --paths "$TD_SOURCE/src" \
  --add-data "$TD_SOURCE/src/auto_td/templates:auto_td/templates" \
  --add-data "$TD_SOURCE/src/auto_td/ui_static:auto_td/ui_static" \
  --add-data "$TD_SOURCE/default_images:default_images" \
  --collect-all cryptography \
  --distpath "$TD_BUILD/onedir-dist" \
  --workpath "$TD_BUILD/pyinstaller-work" --specpath "$TD_BUILD" \
  "$TD_SOURCE/BackendMain.py"
rm -rf "$TD_DEST"
mkdir -p "$TD_DEST"
cp -R "$TD_BUILD/onedir-dist/AutoTDBackend/." "$TD_DEST/"
cp "$TD_SOURCE/VENDOR_NOTICE.md" "$TD_BUILD/resources/common/"
cp "$BUCC_ROOT/LICENSE" "$TD_BUILD/resources/common/"
