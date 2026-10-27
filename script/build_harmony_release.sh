#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
harmony_root="$repo_root/harmonyApp"
output_dir="${1:-$repo_root/build/harmony-release}"
DEVECO_SDK_HOME="${DEVECO_SDK_HOME:-/Applications/DevEco-Studio.app/Contents/sdk}"
OHOS_SDK_HOME="${OHOS_SDK_HOME:-$DEVECO_SDK_HOME/default/openharmony}"
deveco_contents="${DEVECO_SDK_HOME%/sdk}"
node_dir="$deveco_contents/tools/node/bin"
hvigor_command="$deveco_contents/tools/hvigor/bin/hvigorw"
ohpm_command="$deveco_contents/tools/ohpm/bin/ohpm"
node_command="$node_dir/node"
shared_lib="$harmony_root/entry/libs/arm64-v8a/libbucc_compose.so"
expected_sdk_version="26.0.0.105"

export DEVECO_SDK_HOME OHOS_SDK_HOME
export PATH="$node_dir:$deveco_contents/tools/ohpm/bin:$deveco_contents/tools/hvigor/bin:$PATH"

if [[ -n "$(git -C "$repo_root" status --porcelain --untracked-files=all)" ]]; then
  echo 'Harmony build blocked: commit source changes before producing SHA-bound output.' >&2
  exit 2
fi
if [[ -e "$output_dir" ]]; then
  echo "Harmony build blocked: output path already exists: $output_dir" >&2
  exit 2
fi
if [[ ! -d "$DEVECO_SDK_HOME" || ! -d "$OHOS_SDK_HOME" || ! -f "$OHOS_SDK_HOME/toolchains/oh-uni-package.json" ]]; then
  echo 'Harmony build blocked: set DEVECO_SDK_HOME to the installed DevEco IDE/sdk and use its installed default/openharmony SDK.' >&2
  exit 2
fi
if [[ ! -x "$node_command" || ! -x "$hvigor_command" || ! -x "$ohpm_command" ]]; then
  echo 'Harmony build blocked: installed DevEco Node, Hvigor, or OHPM executable is missing.' >&2
  exit 2
fi

sdk_version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("version", ""))' "$OHOS_SDK_HOME/toolchains/oh-uni-package.json")"
if [[ "$sdk_version" != "$expected_sdk_version" ]]; then
  echo "Harmony build blocked: expected SDK $expected_sdk_version, found $sdk_version." >&2
  exit 2
fi

version="$(sed -n 's/^project.version=//p' "$repo_root/gradle.properties")"
code="$(sed -n 's/^project.version.code=//p' "$repo_root/gradle.properties")"
source_sha="$(git -C "$repo_root" rev-parse HEAD)"
source_tree="$(git -C "$repo_root" rev-parse 'HEAD^{tree}')"
studio_version="$(python3 -c 'import json,pathlib,sys; p=pathlib.Path(sys.argv[1]); print(json.loads(p.read_text()).get("version", "not-detected") if p.is_file() else "not-detected")' "$deveco_contents/Resources/product-info.json")"
hvigor_version="$("$hvigor_command" --version | tail -1 | tr -d '\r')"
ohpm_version="$("$ohpm_command" --version | tail -1 | tr -d '\r')"
node_version="$("$node_command" --version | tr -d '\r')"

python3 "$repo_root/.github/scripts/version_sync.py" --check
rm -rf "$harmony_root/entry/build"
rm -f "$shared_lib"
"$repo_root/gradlew" -p "$repo_root/harmonyBuild" --no-daemon --max-workers=2 :composeApp:prepareHarmonyHost -Pbucc.harmony.ui=true
if [[ ! -s "$shared_lib" ]]; then
  echo 'Harmony build failed: Release prepare did not produce entry/libs/arm64-v8a/libbucc_compose.so.' >&2
  exit 1
fi
shared_lib_sha_before="$(shasum -a 256 "$shared_lib" | awk '{print $1}')"

(
  cd "$harmony_root"
  "$ohpm_command" install --all
  "$hvigor_command" --no-daemon assembleHap --mode module -p product=default -p module=entry@default -p buildMode=release
)

hap="$harmony_root/entry/build/default/outputs/default/entry-default-unsigned.hap"
if [[ ! -s "$hap" ]]; then
  echo 'Harmony build failed: Hvigor did not produce the expected entry-default-unsigned.hap.' >&2
  exit 1
fi
shared_lib_sha_after="$(shasum -a 256 "$shared_lib" | awk '{print $1}')"
if [[ "$shared_lib_sha_after" != "$shared_lib_sha_before" ]]; then
  echo 'Harmony build failed: Release shared library changed during Hvigor packaging.' >&2
  exit 1
fi
if [[ "$(git -C "$repo_root" rev-parse HEAD)" != "$source_sha" || "$(git -C "$repo_root" rev-parse 'HEAD^{tree}')" != "$source_tree" || -n "$(git -C "$repo_root" status --porcelain --untracked-files=all)" ]]; then
  echo 'Harmony build failed: source SHA/tree or clean working tree changed during build.' >&2
  exit 1
fi

mkdir -p "$(dirname "$output_dir")"
mkdir "$output_dir"
python3 - "$hap" "$output_dir" "$version" "$code" "$source_sha" "$source_tree" "$sdk_version" "$hvigor_version" "$ohpm_version" "$DEVECO_SDK_HOME" "$studio_version" "$node_version" <<'PY'
import hashlib
import json
import shutil
import sys
from pathlib import Path

source = Path(sys.argv[1])
out = Path(sys.argv[2])
version, code, source_sha, source_tree, sdk_version, hvigor_version, ohpm_version, sdk_home, studio_version, node_version = sys.argv[3:]
artifact = out / f"BUCC-Harmony-v{version}-unsigned.hap"
manifest_path = out / "manifest.json"
created_artifact = False
try:
    with artifact.open("xb") as stream:
        created_artifact = True
        with source.open("rb") as hap:
            shutil.copyfileobj(hap, stream)
    manifest = {
        "platform": "harmony",
        "version": str(version),
        "version_code": int(code),
        "source_sha": source_sha,
        "source_tree": source_tree,
        "file": artifact.name,
        "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
        "toolchain": {
            "deveco_sdk_home": sdk_home,
            "deveco_studio": studio_version,
            "hvigor": hvigor_version,
            "ohpm": ohpm_version,
            "node": node_version,
            "harmony_sdk": sdk_version,
        },
        "signing": "unsigned",
    }
    with manifest_path.open("x", encoding="utf-8") as stream:
        json.dump(manifest, stream, indent=2)
        stream.write("\n")
except Exception:
    if created_artifact:
        artifact.unlink(missing_ok=True)
    raise
PY
