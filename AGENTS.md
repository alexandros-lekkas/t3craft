# Working on T3 Craft (for coding agents and new contributors)

T3 Craft is a Fabric mod for **Minecraft Java 26.3** that shows [T3 Code](https://t3.codes) coding agents as characters in an in-game office. Java 25, Fabric Loader 0.19.5, Fabric API, Loom 1.17, Gradle 9.8 (wrapper checked in). The one product rule: **simple things, done very clearly** — cut features before adding settings, commands or screens.

This file is the five-minute brief. Details live in [CONTRIBUTING.md](CONTRIBUTING.md), [docs/AGENTS-GUIDE.md](docs/AGENTS-GUIDE.md) (running the game without a window), [docs/REFERENCE.md](docs/REFERENCE.md) (every option and check) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Commands

```sh
scripts/mc doctor                 # JDK 25 found? leftovers? which remotes?
./gradlew build                   # compile + uiCheck, worldCheck, talkCheck → build/libs/t3craft-<version>.jar
bash scripts/verify-ci.sh         # exactly what CI runs: build + every fixture-backed check, on free ports

scripts/mc world                  # real client, hidden: title → New T3 World → office; screenshots; PASS/FAIL
scripts/mc test <scenario>        # one in-game self-test (list: docs/REFERENCE.md#self-tests)
scripts/mc gametest               # Fabric client game tests in src/gametest
scripts/mc run --world "T3 World" # background client; then: scripts/mc cmd "time set day" · status · shot <name> · stop
scripts/mc logs --grep WORLDTEST  # the game log of the last run
```

Gradle needs **JDK 25** as `JAVA_HOME` (`scripts/mc` finds one by itself; by hand on macOS: `export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"`). Python 3.10+ runs the fixtures and `scripts/mc`.

## Seeing the game without a window

`scripts/mc` starts the real dev client **hidden**: no window, no Dock icon, no stolen focus, muted, in its own game directory (`run-harness/`), unpaired from any real T3, on its own MCP port (25690). The game still renders, so screenshots are real frames. Each run ends with:

```text
RESULT   PASS …            ← exit code 0 PASS, 1 FAIL, 2 no verdict (build error, timeout, crash)
SHOTS    8 in <repo>/run-harness/screenshots
  <absolute path to each new .png>     ← open these to see what happened
LOG      <repo>/run-harness/harness/gradle.log
```

Add `--show` to watch a run in a window, `--fixture office` (twelve staged threads) or `--fixture feature` (approvals, review) to pair with a loopback T3 fixture on free ports. Raw Gradle works too: `-Pselftest`, `-Pworldtest`, `-PwebShowcase` and `-Pdemo` runs are hidden by default (`-Pshow=true` to watch). More in [docs/AGENTS-GUIDE.md](docs/AGENTS-GUIDE.md).

## Project map

| Where | What |
|:--|:--|
| `src/main/java/dev/maxwellyoung/t3craft/` | The mod. One flat package; classes are package-private unless Fabric needs them public. |
| `…/mixin/` | Mixins for agent rendering, walking and collision (`t3craft.mixins.json`). |
| `…/devmixin/`, `HiddenWindow.java`, `DevHarness.java` | Dev-only hidden window and harness hooks; applied only with `-Dt3craft.hidden` / `-Dt3craft.harness`. |
| `src/main/resources/` | `fabric.mod.json`, mixin configs, `assets/t3craft/` (lang, textures, models), `data/t3craft/office/` (the office design). |
| `src/smoke/java/` | Plain-JVM checks (`*Checks.java`) run by Gradle tasks without Minecraft. Same package, so they reach package-private code. |
| `src/gametest/` | Fabric client game tests: a real client driven by test code (`runClientGameTest`). |
| `scripts/` | `mc` (run the game), `verify-ci.sh`, `feature-fixture.py` and `office/fixture.py` (fake T3 servers), `check-*.py`, Mac launcher. |
| `docs/` | `REFERENCE.md`, `ARCHITECTURE.md`, `DECISIONS.md`, `ROADMAP.md`, `SCREENSHOTS.md`, `design/` (one page per feature). |
| `modpack/`, `credits/` | The launcher pack and third-party attributions (`scripts/check-credits.sh` guards them). |

Main classes and what they own:

- **Entry points:** `T3CraftClient` (client: keys, `/t3` commands, config, per-world office, wiring), `T3CraftCommon`, `T3CraftServer` + `ServerOffice` (dedicated server: the shared office, `/t3office`).
- **Talking to T3:** `T3State` (the thread model every screen reads), `T3Backend` (the interface), `T3McpBackend` + `T3McpAuth` (T3's public MCP, OAuth), `T3Api` + `T3Socket` (legacy client protocol: HTTP and the live WebSocket), `T3Config` (`config/t3craft.json`), `T3AutoConnect` + `T3Web` (zero-setup pairing with the local T3).
- **Native UI:** `T3Screen` (the laptop), `T3AgentCardScreen` (the card beside an agent), `T3ReviewScreen`, `T3PinsScreen`, `T3DeskScreen`, `T3Hud`, `T3Ui` (shared look), `T3Markdown`, `T3Diff`, `T3Decisions`, `T3Books`.
- **Office:** `OfficeDesign` (the building data), `OfficeFrame` (origin + rotation → world coordinates), `OfficePlacement` (puts blocks in a world), `T3Office` (layout: desks, zones, walkable cells), `OfficeBrain` (where each agent is and goes; no Minecraft classes), `OfficeResidents` (mirrors the brain onto client Mannequins), `OfficeRoster` (desk assignment and pins), `AgentTalk` / `OfficeTalks` / `AgentWalk` (agents meeting in person), `OfficeProps`, `DeskLaptops`, `UsageBoard*`.
- **T3 World:** `T3WorldScreen`, `T3WorldHubScreen`, `T3WorldCreator`, `T3WorldData` (the save marker), `T3WorldSetup` (first start on the integrated server), `T3Seeds`.
- **Agents → Minecraft:** `T3Mcp` (the in-game MCP server, `127.0.0.1:25590/mcp`), `McpJsonRpc`, `McpRequestPolicy`, `ClientCommands`.
- **Streamed web UI (opt-in):** `T3WebScreen`, `ChromeHost`, `Cdp`, `WebPage`, `WebViewport`.
- **In-game self-tests:** `SelfTest` (`-Pselftest=<scenario>`), `WorldSelfTest` (`-Pworldtest=<place>`), `WebShowcase`.

## Hard rules

These protect the owner's machine, game and T3 account. They are not preferences.

1. **Never touch a real Minecraft directory.** Not `~/Library/Application Support/minecraft*`, `%APPDATA%\.minecraft` or `~/.minecraft`; not its `options.txt`, worlds or mods. Dev clients use this checkout's `run-harness/` (or `run/`).
2. **Never pair with a real T3 Code in tests.** Use the fixtures (`--fixture office|feature`, or `scripts/feature-fixture.py --port 0 --port-file …`). `scripts/mc` and every self-test run pass `-Dt3craft.noAutoConnect`; a bare `./gradlew runClient` **does** pair with the T3 on this computer, by design, so don't use it for automation.
3. **Muted, separate game directory, own MCP port.** Never take 25590 from a real game (`-PmcpPort=<n>`). `scripts/mc` does all three.
4. **No OS input automation, no screen capture, nothing brought to the front.** Screenshots come from the game's framebuffer.
5. **Staged thread titles only** in screenshots, fixtures and docs. Never real thread titles, project names, tokens or pairing links.
6. **Never log tokens, pairing links or prompt contents.**
7. **One Minecraft client at a time** per checkout; stop yours when done (`scripts/mc stop`).

## Definition of done

- `./gradlew build` passes, and `bash scripts/verify-ci.sh` passes (or the PR says which checks ran).
- A change a player can see: before/after screenshots from a hidden run (`scripts/mc …`) in the PR, and a line in `CHANGELOG.md` under *Unreleased*.
- A product decision (what the mod does or refuses to do): a dated entry at the top of `docs/DECISIONS.md`.
- New player-facing text goes in `assets/t3craft/lang/en_us.json`. No new setting, command or screen without an issue first.
- Docs that describe the changed behaviour are updated (`docs/REFERENCE.md` is the source of truth; `python3 -I scripts/check-doc-links.py` checks links).
- One idea per PR, branched from `main`, PR template filled in. Java 25, the surrounding file's indentation, no wildcard imports.

## Pitfalls

- **JDK:** a newer default JDK fails to compile `build.gradle`. Use 25. `scripts/mc doctor` tells you what it found.
- **Two remotes:** `origin` is `alexandros-lekkas/t3craft`; some checkouts also have `upstream` (`maxwellyoung/t3craft`) and `gh` may default to it. Always `gh … -R alexandros-lekkas/t3craft`; never push or open PRs against upstream.
- **Ports:** fixtures default to 25680–25683 and collide between checkouts. Use `--port 0 --port-file` (what `verify-ci.sh` and `scripts/mc` do) and pass `-PfixturePort`.
- **Minecraft 26.3 is not the Minecraft you remember:** windowing is SDL3 (not GLFW), names are Mojang's (unobfuscated), rendering goes through `com.mojang.blaze3d` / `renderpearl`. Check the real classes (`javap` on `~/.gradle/caches/fabric-loom/26.3/minecraft-merged.jar`) before assuming an API.
- **Threads:** `SavedData` and world edits belong to the server thread, screens and entities to the render thread. `WorldSelfTest.readMarker` shows the pattern.
- **Dependency lock:** `gradle.lockfile` pins every dependency; a version bump needs `./gradlew dependencies --write-locks`.
- **Shared files:** `SelfTest.java`, `T3CraftClient.java`, `build.gradle` and `CHANGELOG.md` are edited by most PRs. Keep edits there small and put new code in new files.

Starter issues: [good first issue](https://github.com/alexandros-lekkas/t3craft/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) · [help wanted](https://github.com/alexandros-lekkas/t3craft/issues?q=is%3Aissue+is%3Aopen+label%3A%22help+wanted%22).
