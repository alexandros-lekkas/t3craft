#!/usr/bin/env bash
# The same fixture-backed verification runs for pull requests and releases.
set -euo pipefail
# Override both port pairs when another checkout is running fixtures locally.
legacy_port="${T3CRAFT_FIXTURE_PORT:-25680}"
v2_port="${T3CRAFT_FIXTURE_V2_PORT:-25682}"
mkdir -p build/fixture-logs
python3 scripts/feature-fixture.py --port "$legacy_port" >build/fixture-logs/legacy.log 2>&1 &
legacy=$!
python3 scripts/feature-fixture.py --protocol2 --port "$v2_port" >build/fixture-logs/v2.log 2>&1 &
v2=$!
cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then cat build/fixture-logs/*.log; fi
  kill "$legacy" "$v2" 2>/dev/null || true
  wait "$legacy" "$v2" 2>/dev/null || true
  exit "$status"
}
trap cleanup EXIT
for port in "$legacy_port" "$((legacy_port + 1))" "$v2_port" "$((v2_port + 1))"; do
  ready=false
  for attempt in {1..60}; do
    kill -0 "$legacy" "$v2"
    if curl --fail --silent "http://127.0.0.1:$port/.well-known/t3/environment" >/dev/null; then ready=true; break; fi
    sleep 0.25
  done
  if [ "$ready" != true ]; then echo "Fixture $port did not become ready" >&2; exit 1; fi
done
./gradlew verifyAll "$@" -PfixturePort="$legacy_port"
./gradlew connectionCheck featureCheck reliabilityCheck "$@" -PfixturePort="$v2_port"
# MCP scenarios mutate fixture state; keep each leg in a separate invocation.
./gradlew mcpCheck "$@" -PfixturePort="$legacy_port"
./gradlew mcpCheck "$@" -PfixturePort="$v2_port"
python3 scripts/check-launcher.py
python3 scripts/check-installer.py
python3 scripts/check-jar.py
