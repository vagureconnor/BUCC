#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
harmony_root="$repo_root/harmonyApp"
output_dir="${1:-$repo_root/build/harmony-release}"
deveco_home="${DEVECO_HOME:-/Applications/DevEco-Studio.app/Contents}"
if [[ -d "$deveco_home/sdk/default/openharmony" ]]; then
  OHOS_SDK_HOME="${OHOS_SDK_HOME:-$deveco_home/sdk/default/openharmony}"
  export PATH="$deveco_home/tools/ohpm/bin:$deveco_home/tools/hvigor/bin:$PATH"
fi
export OHOS_SDK_HOME
version="$(sed -n 's/^project.version=//p' "$repo_root/gradle.properties")"
code="$(sed -n 's/^project.version.code=//p' "$repo_root/gradle.properties")"
source_sha="$(git -C "$repo_root" rev-parse HEAD)"

if [[ -z "${OHOS_SDK_HOME:-}" || ! -d "$OHOS_SDK_HOME" ]]; then
  echo 'Harmony build blocked: OHOS_SDK_HOME must point to the pinned HarmonyOS 26 SDK.' >&2
  exit 2
fi
ohpm_command="$(command -v ohpm || true)"
hvigor_command="$(command -v hvigorw || true)"
if [[ -z "$ohpm_command" ]]; then
  echo 'Harmony build blocked: DevEco OHPM is not installed or not on PATH.' >&2
  exit 2
fi
if [[ -z "$hvigor_command" ]]; then
  echo 'Harmony build blocked: DevEco Hvigor is not installed or not on PATH.' >&2
  exit 2
fi
sdk_version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("version", ""))' "$OHOS_SDK_HOME/toolchains/oh-uni-package.json")"
if [[ "$sdk_version" != 26.0.0.105 ]]; then
  echo "Harmony build blocked: expected SDK 26.0.0.105, found $sdk_version." >&2
  exit 2
fi
hvigor_version="$("$hvigor_command" --version | tail -1 | tr -d '\r')"
ohpm_version="$("$ohpm_command" --version | tail -1 | tr -d '\r')"
node_command="$(command -v node || true)"
if [[ -z "$node_command" ]]; then
  echo 'Harmony build blocked: Node.js is not installed or not on PATH.' >&2
  exit 2
fi
node_version="$("$node_command" --version | tr -d '\r')"
studio_version="$(python3 -c 'import json,pathlib,sys; p=pathlib.Path(sys.argv[1]); print(json.loads(p.read_text()).get("version", "unknown") if p.is_file() else "not-detected")' "$deveco_home/Resources/product-info.json")"

python3 "$repo_root/.github/scripts/version_sync.py" --check
"$repo_root/gradlew" -p "$repo_root/harmonyBuild" :composeApp:prepareHarmonyHost -Pbucc.harmony.ui=true
(
  cd "$harmony_root"
  "$ohpm_command" install --all
  "$hvigor_command" assembleHap --mode module -p product=default -p module=entry@default -p buildMode=release
)

hap="$(find "$harmony_root/entry/build" -type f -name '*.hap' -print -quit)"
if [[ -z "$hap" ]]; then
  echo 'Harmony build failed: Hvigor produced no HAP.' >&2
  exit 1
fi
mkdir -p "$output_dir"
cp "$hap" "$output_dir/BUCC-Harmony-v${version}-unsigned.hap"
python3 - "$output_dir" "$version" "$code" "$source_sha" "$sdk_version" "$hvigor_version" "$ohpm_version" "$deveco_home" "$studio_version" "$node_version" <<'PY'
import hashlib, json, pathlib, sys
out = pathlib.Path(sys.argv[1])
version, code, source_sha, sdk_version, hvigor_version, ohpm_version, deveco_home, studio_version, node_version = sys.argv[2:]
hap = next(out.glob('*.hap'))
manifest = {
  'platform': 'harmony', 'version': str(version), 'version_code': int(str(code)),
  'source_sha': str(source_sha), 'file': hap.name,
  'sha256': hashlib.sha256(hap.read_bytes()).hexdigest(),
  'toolchain': {'deveco_studio': studio_version, 'hvigor': hvigor_version, 'ohpm': ohpm_version, 'node': node_version, 'harmony_sdk': sdk_version},
  'signing': 'unsigned',
}
(out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
PY
