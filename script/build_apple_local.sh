#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
platform="${1:?Usage: build_apple_local.sh ios|macos-arm64|macos-x86_64 NEW_OUTPUT_DIRECTORY}"
output_dir="${2:?A new, empty output directory is required}"
[[ "$(uname -s)" == Darwin ]] || { echo 'Apple builds require the local Mac.' >&2; exit 2; }
cd "$repo_root"
[[ -z "$(git status --porcelain)" ]] || { echo 'Commit source changes before building an identified artifact.' >&2; exit 2; }
python3 .github/scripts/version_sync.py --check
source_sha="$(git rev-parse HEAD)"
source_tree="$(git rev-parse 'HEAD^{tree}')"
version="$(sed -n 's/^project.version=//p' gradle.properties)"
version_code="$(sed -n 's/^project.version.code=//p' gradle.properties)"
[[ "$version" == 2.0.1 && "$version_code" == 201 ]] || { echo 'Expected BUCC 2.0.1 version code 201.' >&2; exit 2; }
[[ ! -e "$output_dir" ]] || { echo 'Refusing to overwrite an existing output directory.' >&2; exit 2; }
mkdir -p "$(dirname "$output_dir")"
mkdir "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"

verify_source() {
  [[ "$(git rev-parse HEAD)" == "$source_sha" && "$(git rev-parse 'HEAD^{tree}')" == "$source_tree" && -z "$(git status --porcelain)" ]] || {
    echo 'Source changed during build; artifact is not accepted.' >&2; exit 2;
  }
}
write_metadata() {
  python3 - "$artifact" "$platform" "$arch" "$signing" "$source_sha" "$source_tree" "$version" "$version_code" <<'PY'
import hashlib,json,sys
from pathlib import Path
artifact=Path(sys.argv[1]);platform,arch,signing,sha,tree,version,code=sys.argv[2:]
metadata={'platform':'macos-'+arch if platform.startswith('macos-') else platform,'file':artifact.name,'version':version,'version_code':int(code),'source_sha':sha,'source_tree':tree,'sha256':hashlib.sha256(artifact.read_bytes()).hexdigest(),'architecture':arch,'signing':signing}
(artifact.parent/('metadata-'+metadata['platform']+'.json')).write_text(json.dumps(metadata,indent=2)+'\n')
PY
}

if [[ "$platform" == ios ]]; then
  artifact="$output_dir/BUCC-iOS-v${version}-unsigned.ipa"
  archive="$output_dir/BUCC.xcarchive"
  xcodebuild archive -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release \
    -destination 'generic/platform=iOS' -archivePath "$archive" \
    -derivedDataPath "$repo_root/build/ios-local-derived" \
    CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=
  python3 script/package_unsigned_ipa.py "$archive/Products/Applications/BUCC.app" "$artifact"
  python3 - "$repo_root/.github/scripts" "$artifact" "$version" "$version_code" <<'PY'
import sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
from package_validation import validate_ios_ipa
validate_ios_ipa(Path(sys.argv[2]), sys.argv[3], int(sys.argv[4]))
PY
  arch=arm64; signing=unsigned
elif [[ "$platform" == macos-arm64 || "$platform" == macos-x86_64 ]]; then
  target_arch="${platform#macos-}"
  host_arch="$(uname -m)"
  java_home="${BUCC_JAVA_HOME:-${JAVA_HOME:-}}"
  td_python="${BUCC_TD_PACKAGING_PYTHON:-}"
  [[ -n "$java_home" && -x "$java_home/bin/java" ]] || { echo 'Set BUCC_JAVA_HOME to the installed JDK 21 for the target architecture.' >&2; exit 2; }
  [[ -n "$td_python" && -x "$td_python" ]] || { echo 'Set BUCC_TD_PACKAGING_PYTHON to an existing target-architecture Python environment with packaging dependencies.' >&2; exit 2; }
  if [[ "$target_arch" == arm64 ]]; then
    [[ "$host_arch" == arm64 ]] || { echo 'ARM64 Mac packaging requires an ARM64 host runtime.' >&2; exit 2; }
    run=(env JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH")
    run_python=("$td_python")
  else
    [[ "$host_arch" == arm64 || "$host_arch" == x86_64 ]] || { echo 'Unsupported Intel target host.' >&2; exit 2; }
    run=(arch -x86_64 /usr/bin/env JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH")
    run_python=(arch -x86_64 "$td_python")
  fi
  runtime_arch="$("${run[@]}" java -XshowSettings:properties -version 2>&1 | sed -n 's/.*os.arch = //p' | head -1)"
  [[ ("$target_arch" == arm64 && "$runtime_arch" == aarch64) || ("$target_arch" == x86_64 && ("$runtime_arch" == x86_64 || "$runtime_arch" == amd64)) ]] || {
    echo "Target JDK architecture mismatch: expected $target_arch." >&2; exit 2;
  }
  python_arch="$("${run_python[@]}" -c 'import platform; print(platform.machine())')"
  [[ ("$target_arch" == arm64 && ("$python_arch" == arm64 || "$python_arch" == aarch64)) || ("$target_arch" == x86_64 && ("$python_arch" == x86_64 || "$python_arch" == amd64)) ]] || {
    echo "Target Python architecture mismatch: expected $target_arch." >&2; exit 2;
  }
  artifact="$output_dir/BUCC-macOS-${target_arch}-v${version}.dmg"
  target_output="compose/binaries/$target_arch"
  build_output="$repo_root/composeApp/build/$target_output/main-release/dmg/BUCC-${version}.dmg"
  [[ ! -e "$build_output" ]] || { echo 'Remove the previous Gradle DMG output before building a new architecture.' >&2; exit 2; }
  env BUCC_TD_ARCH="$target_arch" BUCC_TD_PACKAGING_PYTHON="$td_python" \
    "${run[@]}" ./gradlew :composeApp:packageReleaseDmg -Pbucc.packageOutput="$target_output" \
      --no-configuration-cache --no-daemon --console=plain --max-workers=2
  app="$repo_root/composeApp/build/$target_output/main-release/app/BUCC.app"
  codesign --verify --deep --strict "$app"
  sig="$(codesign -dv --verbose=4 "$app" 2>&1 || true)"
  [[ "$sig" == *"Signature=adhoc"* ]] || { echo 'Mac application is not ad-hoc signed.' >&2; exit 2; }
  info="$app/Contents/Info.plist"
  [[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$info")" == "$version" && \
     "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$info")" == "$version_code" ]] || { echo 'Mac bundle version metadata mismatch.' >&2; exit 2; }
  exe="$app/Contents/MacOS/BUCC"
  archs="$(lipo -archs "$exe")"
  [[ " $archs " == *" $target_arch "* ]] || { echo 'Mac application executable architecture mismatch.' >&2; exit 2; }
  hdiutil verify "$build_output"
  mountpoint="$(mktemp -d "$output_dir/.dmg-mount.XXXXXX")"
  mounted=0
  cleanup_mount() { if [[ "$mounted" == 1 ]]; then hdiutil detach "$mountpoint" -quiet; fi; rm -rf "$mountpoint"; }
  trap cleanup_mount EXIT
  hdiutil attach "$build_output" -readonly -nobrowse -mountpoint "$mountpoint" -quiet
  mounted=1
  packaged_app="$mountpoint/BUCC.app"
  [[ -d "$packaged_app" ]] || { echo 'DMG is missing BUCC.app.' >&2; exit 2; }
  codesign --verify --deep --strict "$packaged_app"
  dmg_info="$packaged_app/Contents/Info.plist"
  [[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$dmg_info")" == "$version" && \
     "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$dmg_info")" == "$version_code" ]] || { echo 'DMG app version metadata mismatch.' >&2; exit 2; }
  dmg_archs="$(lipo -archs "$packaged_app/Contents/MacOS/BUCC")"
  [[ " $dmg_archs " == *" $target_arch "* ]] || { echo 'DMG app architecture mismatch.' >&2; exit 2; }
  dmg_signature="$(codesign -dv --verbose=4 "$packaged_app" 2>&1 || true)"
  [[ "$dmg_signature" == *"Signature=adhoc"* ]] || { echo 'DMG app is not ad-hoc signed.' >&2; exit 2; }
  cleanup_mount; mounted=0; trap - EXIT
  cp "$build_output" "$artifact"
  arch="$target_arch"; signing=ad-hoc
else
  echo 'Expected ios, macos-arm64, or macos-x86_64.' >&2; exit 2
fi
verify_source
write_metadata
