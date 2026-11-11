# Contributing to T3 Craft

Thanks for helping. T3 Craft follows one rule above all: **simple things, done very clearly.** We cut features before we add settings or screens. If a change needs a new menu, open an issue first and we'll look for a simpler way.

**New here?** The short path to a first merged PR is [below](#your-first-pr). Working with a coding agent (Claude Code, Codex, Cursor)? It reads [AGENTS.md](AGENTS.md), and so can you: it is the five-minute brief.

## Ways to help

- **Report a bug** with the [bug template](https://github.com/alexandros-lekkas/t3craft/issues/new?template=bug_report.yml). Logs plus a screenshot or clip help the most.
- **Suggest something** with the [feature template](https://github.com/alexandros-lekkas/t3craft/issues/new?template=feature_request.yml).
- **Make art:** agent skins (`64×64`, wide or slim), office modules and provider icons. Read `docs/design/` first.
- **Write code:** pick an issue labelled [`good first issue`](https://github.com/alexandros-lekkas/t3craft/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) or [`help wanted`](https://github.com/alexandros-lekkas/t3craft/issues?q=is%3Aissue+is%3Aopen+label%3A%22help+wanted%22), and say you're on it.

## Your first PR

1. **Pick a starter issue and comment that you're taking it.** Small and self-contained: [#26 move the T3 World screen text into `en_us.json`](https://github.com/alexandros-lekkas/t3craft/issues/26), [#25 more agent skins](https://github.com/alexandros-lekkas/t3craft/issues/25), [#28 capture the remaining screenshots](https://github.com/alexandros-lekkas/t3craft/issues/28). Larger: [#23 the held T3 laptop](https://github.com/alexandros-lekkas/t3craft/issues/23), [#27 agent talks in the shared server office](https://github.com/alexandros-lekkas/t3craft/issues/27). No code needed: [#24 confirm auto-connect on Windows and Linux](https://github.com/alexandros-lekkas/t3craft/issues/24).
2. **Set up** (JDK 25, then one command): `git clone https://github.com/alexandros-lekkas/t3craft && cd t3craft && scripts/mc doctor && ./gradlew build`.
3. **See the game** without it taking over your screen: `scripts/mc world` starts the real client hidden, creates a T3 World and prints the screenshots it took ([how](docs/AGENTS-GUIDE.md)). Add `--show` to watch it in a window.
4. **Make the change** on a branch from `main`. One idea per PR.
5. **Verify:** `bash scripts/verify-ci.sh` (what CI runs), plus `scripts/mc world` or the closest `scripts/mc test <scenario>` if a player can see the change.
6. **Open the PR** with the template filled in and before/after screenshots for anything visible. Small PRs are the quickest to review.

## Dev setup

You need:

| Tool | Version | Notes |
|---|---|---|
| JDK | **25** | Minecraft 26.x targets Java 25 and the build uses a Java 25 toolchain. CI also runs Gradle on JDK 27. |
| Git | any | |
| Python | 3.10+ | Only for the test fixtures in `scripts/`. |
| T3 Code | latest | Only for testing against a real instance. Fixtures cover the rest. |

```bash
# macOS (Homebrew)
brew install openjdk@25
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"

git clone https://github.com/alexandros-lekkas/t3craft && cd t3craft
./gradlew build          # → build/libs/t3craft-<version>.jar (also runs uiCheck and worldCheck)
./gradlew runClient      # dev client, offline, no Minecraft account needed
./gradlew runServer --args=nogui   # local test server in run-server/
```

On Windows or Linux, point `JAVA_HOME` at any JDK 25 (Temurin works well).

### Tests

Most checks run against **isolated fixtures**. They never touch a real T3 pairing or dispatch to a real agent.

```bash
bash scripts/verify-ci.sh   # everything CI runs, in one go, on freshly allocated fixture ports

# By hand: let the fixture pick free ports (--port 0) and pass them to Gradle, so parallel checkouts never collide.
python3 scripts/feature-fixture.py --port 0 --port-file build/fixture-logs/legacy.port --config "$PWD/run/feature-fixture.json" &
./gradlew officeCheck connectionCheck featureCheck reliabilityCheck -PfixturePort=$(cat build/fixture-logs/legacy.port)
# protocol 2: the same with --protocol2, then mcpCheck too
```

In-game tests run the real client **without a window**: `scripts/mc world`, `scripts/mc test <scenario>`, `scripts/mc gametest` ([guide](docs/AGENTS-GUIDE.md)). The full list of self-tests (`-Pselftest=look|office|watch|…`) is in [docs/REFERENCE.md](docs/REFERENCE.md#self-tests). New in-game tests go in `src/gametest/` as Fabric client game tests.

### House rules for test runs

`scripts/mc` follows all of these for you.

- **Never pair a test client with a real T3 Code.** Use the fixtures (`--fixture office|feature`). A bare `./gradlew runClient` pairs with the T3 on your computer by design, so keep it for playing.
- **Mute test clients** (master volume 0) and use a **separate game directory**. Never write to a real `options.txt` or a real world.
- **Never take a real game's MCP port.** Give a dev client `-PmcpPort=<n>` (or `mcpPort` in its `-Pconfig` file); it also moves to the next free port by itself.
- Use staged demo threads in screenshots. Real thread titles never go in the repo.

## Pull requests

1. Branch from `main`. Keep each PR to one idea.
2. Run `bash scripts/verify-ci.sh` (or at least `./gradlew build` plus the checks your change touches).
3. Fill in the PR template. **UI changes need a before/after screenshot.**
4. If the change makes a product decision, add a dated entry to `docs/DECISIONS.md` with the reason.
5. If it changes something a player sees, update `CHANGELOG.md` under *Unreleased*.

Commit messages: short imperative subject (`Fix approval card overflow at GUI scale 3`), with the why in the body.

## Working with coding agents

Agents are welcome here, and the repo is set up for them: [AGENTS.md](AGENTS.md) is the brief every tool reads (`CLAUDE.md` imports it), `.claude/skills/` has *run-game* and *verify-pr*, and [docs/AGENTS-GUIDE.md](docs/AGENTS-GUIDE.md) explains how an agent launches the game hidden, drives it and gets screenshots back. The same rules apply to an agent's PR as to yours: it says what was run and what it printed, UI changes come with real frames, and a person has read the diff before asking for review.

## Code style

- Java 25, 4-space indent, no wildcard imports.
- Text a player sees goes in `assets/t3craft/lang/en_us.json`.
- Never log tokens, pairing links or prompt contents.

## License

By contributing, you agree your work is released under the [MIT License](LICENSE).
