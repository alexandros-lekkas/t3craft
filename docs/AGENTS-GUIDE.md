# Running the game without a window

> How people and coding agents (Claude Code, Codex, Cursor and the rest) launch the T3 Craft dev client, drive it and see what happened, without a Minecraft window ever appearing. The short version is in [AGENTS.md](../AGENTS.md); the full list of self-test scenarios and checks is in the [reference](REFERENCE.md#build-and-develop).

## The one entry point: `scripts/mc`

```sh
scripts/mc doctor                       # JDK 25, Python, leftovers, remotes
scripts/mc world [Lakeside]             # title → New T3 World → Create → office, by day and night
scripts/mc test <scenario> [--world N]  # one in-game self-test
scripts/mc gametest                     # Fabric client game tests (src/gametest)
scripts/mc run [--world "T3 World"]     # a client in the background, for driving by hand
scripts/mc cmd "tp @s 0 80 0"           #   … run a command in it
scripts/mc status                       #   … where the player is, what they look at
scripts/mc shot <name>                  #   … save a screenshot, print its path
scripts/mc stop                         #   … save and quit
scripts/mc logs [-n 80] [--grep TEXT]   # the game log of the last run
scripts/mc shots                        # newest screenshots
```

It needs Python 3.10+ and a JDK 25 somewhere it can find (`JAVA_HOME`, Homebrew's `openjdk@25`, `/usr/libexec/java_home`, `/usr/lib/jvm`, the usual Windows folders). On Windows run `python scripts\mc …`.

Every client it starts is:

| | |
|:--|:--|
| **Hidden** | No window, no Dock icon or taskbar focus, nothing brought to the front. `--show` opens a normal window when you want to watch. |
| **Muted** | `run-harness/options.txt` gets `soundCategory_master:0.0` before every start. |
| **Isolated** | Its game directory is `run-harness/` in this checkout (ignored by git): its own options, saves, logs and screenshots. Never a real Minecraft folder. |
| **Unpaired** | It never looks for the T3 Code on this computer. `--fixture office` (twelve staged threads, agents at desks) or `--fixture feature` (approvals, questions, review) starts a loopback fake T3 on free ports and pairs with that. |
| **Off the real game's ports** | Its Minecraft MCP server listens on 25690 (`--mcp-port`), never 25590. |
| **Bounded** | `--timeout` seconds (default 900) and the client is stopped. |

Every run ends the same way, so a script or an agent can act on it:

```text
RESULT   PASS T3 World 'T3 World' place Lakeside … at desks 0 (unpaired)
SHOTS    8 in /…/t3craft/run-harness/screenshots
  /…/t3craft/run-harness/screenshots/t3world-lakeside-title.png
  …
LOG      /…/t3craft/run-harness/harness/gradle.log
TIME     59 s
```

Exit code **0** is PASS, **1** is FAIL, **2** means no verdict (the build failed, the run timed out or the game crashed; read the log). Open the listed PNGs to see the frames: they are the game's own framebuffer at the window size you asked for (`--width 1000 --height 625` gives 2000 × 1250 on a Retina Mac).

### Recipes

```sh
# Does the T3 World flow still work, and what does the office look like?
scripts/mc world Lakeside

# The laptop against twelve staged threads, at the size the docs use.
scripts/mc world Lakeside                      # once, to have a world with an office
scripts/mc test look --fixture office --width 1000 --height 625

# Look at something specific by hand.
scripts/mc run --world "T3 World" --fixture office
scripts/mc cmd "time set 13000"
scripts/mc cmd "tp @s 8 66 -10 180 10"
scripts/mc shot office-dusk
scripts/mc stop

# Anything Gradle accepts goes after a bare `--`.
scripts/mc world Lakeside --fixture office -- -Pworldtalk=stage
```

A fresh `run-harness/` has no worlds; `scripts/mc test` opens the newest save there, so run `scripts/mc world` once first. Delete `run-harness/` whenever you want a clean slate.

Some older scenarios expect a particular world. `nativeui` places the camera for an office that is not turned, so it misses in a Lakeside T3 World (that office is turned a quarter); give it an office save built with `/t3 office` (`--world <save>`). A scenario that fails this way ends with `RESULT FAIL the game crashed: …` and the frame it took shows where the camera was.

### Driving a background client

`scripts/mc run` returns once the client's MCP server answers. A `--world` save may still be loading for a few seconds; `scripts/mc status` says *the player is not in a world* until it is in. From there:

- **Commands and state** go through the mod's own [Minecraft MCP server](REFERENCE.md#let-agents-use-minecraft): `scripts/mc cmd` and `scripts/mc status` call `minecraft_run_command` and `minecraft_status` over HTTP, so no MCP client setup is needed. To use the tools natively, point your agent at the URL `scripts/mc run` printed, for example `claude mcp add --transport http -s local minecraft-dev http://127.0.0.1:25690/mcp`. The repo ships no `.mcp.json`: the server only exists while a dev client runs, and a checked-in entry would show every Claude Code user an approval prompt and a failed connection the rest of the time.
- **Screenshots and quitting** use two files the dev client watches when started with `-Dt3craft.harness=<dir>` (`DevHarness.java`): `shot-request` (a name) and `quit-request`. `scripts/mc shot` and `scripts/mc stop` write them for you.

## Plain Gradle

`scripts/mc` only assembles Gradle properties. Used directly:

```sh
./gradlew runClient -Pworldtest=Lakeside                 # hidden by default
./gradlew runClient -Pselftest=look -Pworld="T3 World"   # hidden by default
./gradlew runClient -Pworldtest=Lakeside -Pshow=true     # in a window
./gradlew runClient -Phidden=true                        # hide an ordinary dev client
./gradlew runClientGameTest                              # hidden; -Pshow=true to watch
```

| Property | Effect |
|:--|:--|
| `-Pshow=true` | Show the window for a run that would be hidden. |
| `-Phidden=true` | Hide a run that would be shown (a plain `runClient`). |
| `-PclientDir=<dir>` | Game directory for the client run (default `run`). |
| `-PnoPair=true` | Never auto-connect to the T3 Code on this computer. |
| `-Pharness=<dir>` | Watch `<dir>` for `shot-request` / `quit-request`. |
| `-Pworld=<save>` | Open a singleplayer save straight away (also without `-Pselftest`). |
| `-Pwidth=<w> -Pheight=<h>` | Window size in points, for any client run. |
| `-PmcpPort=<n>` | Port for the in-game MCP server. |

Self-test, world-test, capture and demo-recording (`-Pdemo`, `scripts/demo/record.sh`) runs are hidden unless you pass `-Pshow=true`; a hidden client says so once in its log. Mind that a bare `./gradlew runClient` uses `run/` and pairs with your real T3, which is what you want when playing and not what you want in automation.

## Fabric client game tests

New in-game tests should be [Fabric client game tests](https://docs.fabricmc.net/develop/automatic-testing) rather than more scenarios in `SelfTest.java`. They live in `src/gametest/` (a separate dev-only mod, `t3craft-gametest`, never packaged into the jar), run with `./gradlew runClientGameTest` or `scripts/mc gametest`, and save numbered screenshots to `build/run/clientGameTest/screenshots/`. The game directory `build/run/clientGameTest/` is wiped before every run.

`T3WorldGameTest` is the example: it checks that the title screen offers *T3 World*, creates one through the real screens, asserts that the office was built where the save says, and takes three screenshots.

```java
public class MyGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		context.waitForScreen(TitleScreen.class);
		context.clickScreenButton("T3 World");          // matches the button's label
		context.takeScreenshot("my-shot");
		int value = context.computeOnClient(mc -> /* read game state on the render thread */ 1);
	}
}
```

Things to know:

- Register the class under `fabric-client-gametest` in `src/gametest/resources/fabric.mod.json`.
- The test runs on its own thread while the client and server advance in lockstep. Touch the game only through `context` (`runOnClient`, `computeOnClient`, `waitFor`, `waitTicks`); the test otherwise runs faster than real time, so always wait on a condition, never on wall-clock time.
- Put the class in package `dev.maxwellyoung.t3craft` to reach the mod's package-private state, as the checks in `src/smoke` do.
- A test must end on the title screen with no world open, or the runner fails it. Worlds made with `context.worldBuilder().create()` close themselves in a try-with-resources; a world entered through the mod's own screens has to be left by hand (see the end of `T3WorldGameTest`).
- The run never auto-connects to a real T3 (`fabric.client.gametest` counts as a dev run). To test against staged threads, start a fixture and point `-Dt3craft.config` at its pairing file.
- Screenshots here are the window's logical size (854 × 480), not 2× Retina frames. For documentation shots use the self-tests.
- `assertScreenshotEquals` compares against a template image; rendering differs a little between GPUs, so prefer state assertions and keep screenshots as evidence.

The existing self-tests (`-Pselftest`, `-Pworldtest`) stay as they are; they are not being migrated.

## How the hidden window works

Minecraft 26.3 creates its window with **SDL3** (older versions used GLFW, and most guides still say so). With `-Dt3craft.hidden=true`, two small mixins in `devmixin/` apply; without the property the mixin config's plugin (`HiddenWindow`) rejects them all, so the shipped mod is unchanged in normal play.

1. `Window.createWindow` passes `SDL_WINDOW_HIDDEN` along with its other flags, so the window is created but never mapped. The GPU context and the game's own render target exist as usual, which is why screenshots work.
2. `Window.onFocus` and `Window.onIconified` are ignored, so the game keeps believing it is focused: it does not open the pause menu in singleplayer, and the frame limiter does not drop to its minimised rate.
3. Before `SDL_Init`, the hint `SDL_MAC_BACKGROUND_APP=1` is set. On macOS, SDL otherwise turns the process into a regular foreground app when video starts, which means a Dock icon, a menu bar and stolen focus even with no window.

Checked on macOS 26 (Apple silicon, OpenGL backend): a 59-second world test ran at full tick rate, the process had no on-screen window and was registered as `BackgroundOnly`, and every frame was correct. App Nap did not throttle it.

### Other platforms and CI

| Where | Status |
|:--|:--|
| macOS, Apple silicon | Verified: `world`, `test look`, `gametest`, `run` / `cmd` / `status` / `shot` / `stop`, with and without `--fixture office`, and `scripts/demo/record.sh`. `-PwebShowcase` hidden and `--show` are not yet run. |
| Linux desktop (X11 or Wayland) | Not yet run. The same SDL flag applies; expected to work. |
| Linux without a display (CI, containers) | Not yet run. Needs a virtual display for the GPU context: `xvfb-run -a scripts/mc gametest` with Mesa's software renderer. |
| Windows | Not yet run. `python scripts\mc …`; the SDL flag is the same, the background-app hint does nothing there. |

A CI job for the client game tests is the next step. This is the shape to try, on `ubuntu-24.04` next to the existing build job:

```yaml
  client-gametest:
    runs-on: ubuntu-24.04
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 25 }
      - uses: gradle/actions/setup-gradle@v4
      - run: sudo apt-get update && sudo apt-get install -y xvfb libgl1-mesa-dri
      - run: xvfb-run -a ./gradlew runClientGameTest
      - uses: actions/upload-artifact@v4
        if: always()
        with: { name: client-gametest-screenshots, path: build/run/clientGameTest/screenshots }
```

Fabric documents one known issue on GitHub's runners: the lockstep network synchronizer can fail there. If it does, the Fabric docs describe the `fabric.client.gametest.disableNetworkSynchronizer` switch.

## What else exists, and why it is not used here

Looked at in November 2026 for Minecraft 26.3:

| Tool | What it is | 26.3 | Verdict |
|:--|:--|:--|:--|
| [Fabric client game test API](https://docs.fabricmc.net/develop/automatic-testing) (`fabric-client-gametest-api-v1`) | The closest thing to Playwright for a Fabric client mod: test code drives a real client (screens, input, world creation, waits, screenshots, screenshot comparison). Part of Fabric API. | Yes: ships in Fabric API `0.161.0+26.3`, and Fabric API's own [26.3 CI](https://github.com/FabricMC/fabric-api/blob/26.3/.github/workflows/build.yml) runs it. | **Adopted** for new tests. It still opens a window by itself, so it runs with the hidden mode above. |
| Fabric server game tests (`fabric-gametest-api-v1`, vanilla GameTest) | Structure-based tests on a headless server. | Yes | Not adopted: this mod's behaviour is almost all client side. Worth adding if the shared server office grows. |
| [HeadlessMC](https://github.com/headlesshq/headlessmc) and [mc-runtime-test](https://github.com/headlesshq/mc-runtime-test) | A command-line launcher that can run the packaged client headlessly on CI runners, and a GitHub Action built on it that boots the client, joins a world, optionally runs registered game tests, and quits. | Listed for Fabric 26.1–26.3. | Not adopted. It launches a production install (with a validated account, or offline only on CI runners) rather than the dev environment, the action is a boot smoke test rather than a driver, and its display handling is Xvfb, which is Linux only. A good later addition as a "does the released jar boot" check in CI. |
| Hiding the window through the windowing library | What the hidden mode does. Guides describe GLFW's `GLFW_VISIBLE`; 26.3 needs the SDL3 equivalent. | Yes | **Adopted** (see above). |
| `xvfb` (virtual X display) | Runs any Linux GUI program without a screen. | n/a | Linux only, so it cannot help on a Mac. The planned CI job uses it. |
| [Mineflayer](https://github.com/PrismarineJS/mineflayer) / prismarine | Protocol-level bots in JavaScript: a fake player that talks to a server. | Not confirmed: its documented range ends at 1.21.11, with 26.x work in progress. | Not useful here: it never runs the client, so none of this mod's screens, rendering or client-side agents exist for it. |
