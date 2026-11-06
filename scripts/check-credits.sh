#!/usr/bin/env bash
# Fails if any file under assets/ or data/ has no credit (or original-work rule),
# if a bundled credit lacks a redistributable license, or if the generated credits
# files (CREDITS.md, THIRD_PARTY_LICENSES.md, modpack/CREDITS.md, the in-game
# credits.json, ATTRIBUTION.md files) are out of date with credits/credits.json.
#
# Fix: edit credits/credits.json, then run  python3 -I credits/generate.py
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 -I credits/generate.py --check "$@"
