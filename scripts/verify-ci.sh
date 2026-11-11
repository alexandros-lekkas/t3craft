#!/usr/bin/env bash
# The same fixture-backed verification runs for pull requests and releases.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/fixture-logs
rm -f build/fixture-logs/legacy.port build/fixture-logs/v2.port
# Each run gets its own free port pairs unless T3CRAFT_FIXTURE_PORT / T3CRAFT_FIXTURE_V2_PORT pin them, so several
# checkouts (or agents) can verify at the same time without colliding on 25680–25683.
python3 scripts/feature-fixture.py --port "${T3CRAFT_FIXTURE_PORT:-0}" --port-file build/fixture-logs/legacy.port >build/fixture-logs/legacy.log 2>&1 &
legacy=$!
python3 scripts/feature-fixture.py --protocol2 --port "${T3CRAFT_FIXTURE_V2_PORT:-0}" --port-file build/fixture-logs/v2.port >build/fixture-logs/v2.log 2>&1 &
v2=$!
cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then cat build/fixture-logs/*.log; fi
  kill "$legacy" "$v2" 2>/dev/null || true
  wait "$legacy" "$v2" 2>/dev/null || true
  exit "$status"
}
trap cleanup EXIT
wait_port_file() {
  for attempt in {1..120}; do
    if ! kill -0 "$legacy" "$v2" 2>/dev/null; then echo "A fixture process exited early" >&2; exit 1; fi
    if [ -s "$1" ]; then cat "$1"; return; fi
    sleep 0.25
  done
  echo "Fixture $1 did not report its port" >&2; exit 1
}
legacy_port="$(wait_port_file build/fixture-logs/legacy.port)"
v2_port="$(wait_port_file build/fixture-logs/v2.port)"
echo "Fixtures: legacy on $legacy_port/$((legacy_port + 1)), protocol 2 on $v2_port/$((v2_port + 1))"
for port in "$legacy_port" "$((legacy_port + 1))" "$v2_port" "$((v2_port + 1))"; do
  ready=false
  for attempt in {1..60}; do
    if ! kill -0 "$legacy" "$v2" 2>/dev/null; then echo "A fixture process exited early" >&2; exit 1; fi
    if curl --fail --silent "http://127.0.0.1:$port/.well-known/t3/environment" >/dev/null; then ready=true; break; fi
    sleep 0.25
  done
  if [ "$ready" != true ]; then echo "Fixture $port did not become ready" >&2; exit 1; fi
done
./gradlew verifyAll "$@" -PfixturePort="$legacy_port"
./gradlew connectionCheck featureCheck reliabilityCheck firstRunCheck "$@" -PfixturePort="$v2_port"
# MCP scenarios mutate fixture state; keep each leg in a separate invocation.
./gradlew mcpCheck "$@" -PfixturePort="$legacy_port"
./gradlew mcpCheck "$@" -PfixturePort="$v2_port"
python3 scripts/check-launcher.py
python3 scripts/check-installer.py
python3 scripts/check-jar.py
# Everything a release publishes (jar, .mrpack, installers, checksums) is built and checked here too.
python3 scripts/build-dist.py
python3 scripts/check-dist.py
python3 -I scripts/check-doc-links.py
