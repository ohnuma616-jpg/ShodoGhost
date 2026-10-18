#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -x "${HOME}/swift/usr/bin/swift" ]]; then
  export PATH="${HOME}/swift/usr/bin:${PATH}"
fi
PORT="${SHODO_LOOPBACK_PORT:-18771}"
READY="$(mktemp)"
rm -f "$READY"
./gradlew :protocol:loopback --args="$PORT $READY" --console=plain > /tmp/shodo-loopback.log 2>&1 &
GRADLE_PID=$!
cleanup() {
  kill "$GRADLE_PID" >/dev/null 2>&1 || true
  wait "$GRADLE_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT
for _ in $(seq 1 100); do
  if [[ -s "$READY" ]]; then
    break
  fi
  sleep 0.2
done
if [[ ! -s "$READY" ]]; then
  echo "loopback host did not listen" >&2
  cat /tmp/shodo-loopback.log >&2 || true
  exit 1
fi
SHODO_LOOPBACK_PORT="$PORT" swift test --package-path ios/Protocol --filter kotlinGlassLoopback
