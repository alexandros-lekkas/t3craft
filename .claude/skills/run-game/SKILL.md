---
name: run-game
description: Launch the T3 Craft dev client without a window, run a scenario and look at the screenshots. Use when asked to run, start, test or screenshot the game or mod, to check a UI or office change in the real client, or to capture shots for a PR or the docs.
---

# Run the game and screenshot a scenario

Everything goes through `scripts/mc` (details: `docs/AGENTS-GUIDE.md`). It starts the real client hidden, muted, unpaired, in `run-harness/`, and prints `RESULT`, the new screenshot paths and the log path. Exit code 0 PASS, 1 FAIL, 2 no verdict.

1. `scripts/mc doctor` if this is the first run in the checkout (JDK 25, leftovers).
2. Pick the run:
   - The T3 World flow and the office, outside and in: `scripts/mc world Lakeside` (also `"River Bend"`, `"Cherry Plains"`).
   - A self-test scenario from `docs/REFERENCE.md#self-tests`: `scripts/mc test <scenario>`. It opens the newest save in `run-harness/saves`; run `scripts/mc world` once if there is none.
   - Staged threads and agents: add `--fixture office` (twelve threads, agents at desks) or `--fixture feature` (approvals, questions, review). Never a real T3.
   - Documentation-size frames: add `--width 1000 --height 625` (2000 × 1250 on a Retina Mac).
   - The Fabric client game tests: `scripts/mc gametest`.
   - Something no scenario covers: `scripts/mc run --world "T3 World" [--fixture office]`, then `scripts/mc cmd "<command>"`, `scripts/mc status`, `scripts/mc shot <name>`, and always `scripts/mc stop` at the end.
3. Read the `RESULT` line. On FAIL or no verdict: `scripts/mc logs --grep <TEXT>` (or `--gradle` for build errors) before changing anything.
4. Open the PNG paths it printed with the Read tool and check them against what was asked. A PASS with a wrong-looking frame is still a failure.
5. Shots for the docs go in `docs/images/shots/` under the names in `docs/SCREENSHOTS.md`, each under 600 KB.

Rules that are not negotiable: never pass `--show` unless the user asked to watch; never touch a real Minecraft directory; never pair with a real T3 Code; no OS input automation or screen capture; one client at a time, stopped when you finish; staged thread titles only in anything you save.
