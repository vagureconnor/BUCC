#!/bin/bash
set -euo pipefail
BUCC_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUCC_MODE="${1:-run}"
BUCC_APP="$BUCC_ROOT/composeApp/build/compose/binaries/main/app/BUCC.app"
case "$BUCC_MODE" in
  run|--verify|--debug|--logs) ;;
  *) echo "usage: $0 [run|--verify|--debug|--logs]" >&2; exit 2 ;;
esac
pkill -x BUCC >/dev/null 2>&1 || true
cd "$BUCC_ROOT"
./gradlew :composeApp:createDistributable --console=plain
if [[ "$BUCC_MODE" == --debug ]]; then
  exec lldb -- "$BUCC_APP/Contents/MacOS/BUCC"
fi
/usr/bin/open -n "$BUCC_APP"
if [[ "$BUCC_MODE" == --verify ]]; then
  sleep 2
  pgrep -x BUCC >/dev/null
elif [[ "$BUCC_MODE" == --logs ]]; then
  exec /usr/bin/log stream --info --style compact --predicate 'process == "BUCC"'
fi
