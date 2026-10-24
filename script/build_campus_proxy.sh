#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
core="$root/third_party/bucc-physical-proxy"
go_bin="${BUCC_GO:-}"
if [[ -z "$go_bin" || ! -x "$go_bin" ]]; then go_bin="$(command -v go || true)"; fi
[[ -x "$go_bin" ]] || { echo "Go 1.25.6+ required; set BUCC_GO" >&2; exit 1; }
mode="${1:-desktop}"
output="$root/build/campus-proxy"
cd "$core"
case "$mode" in
 test) "$go_bin" test -race ./... ;;
 desktop)
  mkdir -p "$output/desktop"
  name=bucc-campus-proxy; [[ "${GOOS:-$(uname -s | tr '[:upper:]' '[:lower:]')}" == windows* ]] && name=bucc-campus-proxy.exe
  "$go_bin" build -trimpath -ldflags '-s -w' -o "$output/desktop/$name" ./cmd/bucc-campus-proxy ;;
 *) echo 'Usage: build_campus_proxy.sh test|desktop' >&2; exit 2 ;;
esac
