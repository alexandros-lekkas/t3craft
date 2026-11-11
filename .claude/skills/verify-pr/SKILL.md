---
name: verify-pr
description: Run the checks T3 Craft expects before a pull request and report what passed. Use before opening or updating a PR, or when asked whether a change is ready, verified or done.
---

# Verify before a PR

The definition of done is in `AGENTS.md`. Work through it in order and stop at the first failure.

1. **Build and unit-level checks:** `./gradlew build` (JDK 25; `scripts/mc doctor` shows which one is found). It runs `uiCheck`, `worldCheck` and `talkCheck`.
2. **Everything CI runs:** `bash scripts/verify-ci.sh`. It starts both fixture protocols on free ports, runs every fixture-backed check and the launcher, installer, jar and doc-link checks. On failure the fixture logs are in `build/fixture-logs/`.
3. **In the real client,** when the change touches anything a player sees or the office:
   - `scripts/mc world Lakeside` for T3 World and the office (add `--fixture office` for agents).
   - `scripts/mc test <scenario>` for the scenario closest to the change (`docs/REFERENCE.md#self-tests`).
   - `scripts/mc gametest` for the Fabric client game tests.
   Open the screenshots it lists and look at them. Keep before/after frames for the PR.
4. **Paperwork:** `CHANGELOG.md` under *Unreleased* for anything a player sees; a dated entry at the top of `docs/DECISIONS.md` for a product decision; `docs/REFERENCE.md` when behaviour, options or checks changed; new player text in `assets/t3craft/lang/en_us.json`.
5. **Safety sweep of the diff:** no tokens, pairing links or prompt contents in logs, fixtures or screenshots; no real thread titles; nothing under `run*/` committed.
6. **Report** exactly what ran and what it printed (PASS lines, failing output verbatim, anything skipped and why). If a check was not run, say so.

When pushing: `origin` is `alexandros-lekkas/t3craft`. Use `gh … -R alexandros-lekkas/t3craft`; never push or open a PR against an `upstream` remote.
