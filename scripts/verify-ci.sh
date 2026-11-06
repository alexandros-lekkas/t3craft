#!/usr/bin/env bash
# The same fixture-backed verification runs for pull requests and releases.
set -euo pipefail
mkdir -p build/fixture-logs
python3 scripts/feature-fixture.py >build/fixture-logs/legacy.log 2>&1 &
legacy=$!
python3 scripts/feature-fixture.py --protocol2 --port 25682 >build/fixture-logs/v2.log 2>&1 &
v2=$!
cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then cat build/fixture-logs/*.log; fi
  kill "$legacy" "$v2" 2>/dev/null || true
  wait "$legacy" "$v2" 2>/dev/null || true
  exit "$status"
}
trap cleanup EXIT
for port in 25680 25681 25682 25683; do
  ready=false
  for attempt in {1..60}; do
    kill -0 "$legacy" "$v2"
    if curl --fail --silent "http://127.0.0.1:$port/.well-known/t3/environment" >/dev/null; then ready=true; break; fi
    sleep 0.25
  done
  if [ "$ready" != true ]; then echo "Fixture $port did not become ready" >&2; exit 1; fi
done
./gradlew build officeCheck connectionCheck featureCheck reliabilityCheck "$@"
./gradlew connectionCheck featureCheck reliabilityCheck -PfixturePort=25682 "$@"
# MCP scenarios mutate fixture state; keep each leg in a separate invocation.
./gradlew mcpCheck "$@"
./gradlew mcpCheck -PfixturePort=25682 "$@"
python3 scripts/check-launcher.py
