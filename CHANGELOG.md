# Changelog

All notable changes to T3 Craft. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [SemVer](https://semver.org/).

## [Unreleased]

### Coming next
- **The held T3 laptop:** a craftable item that opens the laptop screen from anywhere.
- **The T3 Craft pack** for the official Minecraft Launcher, with a light Vanilla+ set and no shaders.

### Added
- **Agents talk in person.** When a thread spawns a subagent, sends it more work, or gets its result back, the parent walks over to a *Helper* visitor who comes in from the entry; they face each other, say two short lines in speech bubbles, and go back. At most two conversations at once, one per parent, with a per-pair cooldown; routes stay on open office cells and turn with the office. The office fixture gains `/_qa/delegate` and `/_qa/finish`, `worldtest` gains `-Pworldtalk`, and `talkCheck` runs on every build. Agents near you now only stop to look at you when they're at their spot, not mid-walk.
- **Simple native Minecraft UI.** The laptop and the agent card are plain Minecraft screens again: the default font, a vanilla container-style window, vanilla buttons and edit boxes, sized to read at GUI scale 5. The laptop shows active threads (status dot, provider icon, title; snoozed folded, settled hidden) and the selected thread as wrapped text with one input box and Send, plus a big Approve / Deny row or the answer options when the agent needs you. Clicking an agent opens a small card beside it over the lightly dimmed world, with the last message, an input box, Approve / Deny and *Open on laptop*. The streamed web UI stays behind `webUi: true` in the config file and Chrome never starts while it is off. Adds the `nativeui` dev capture. ([#14](https://github.com/alexandros-lekkas/t3craft/pull/14), [design](docs/design/native-ui.md))
- **Zero setup.** T3 Craft finds the T3 Code running on your computer and pairs with it by itself through T3's bundled CLI, retrying until T3 is up. No command ever asks you to pick a thread first: with none chosen it talks to the thread that needs you, else the most recent one, and with no threads it opens T3. The mod says one line in chat per game, when T3 connects; office bounds and reconnects go to the log. `/t3` is two lines of help (open T3, office); debug and admin subcommands are hidden unless `"debug": true` in `config/t3craft.json`. The office builds without a confirmation step (terrain is replaced; your own chests and builds make it refuse, naming the spot). Agent building is on by default, also for configs from earlier versions. A local pairing T3 no longer accepts is replaced by itself. Self-tests and dev captures never pair with your real T3. Adds `firstRunCheck`. ([#11](https://github.com/alexandros-lekkas/t3craft/pull/11))
- **Office A with player agents** ([#8](https://github.com/alexandros-lekkas/t3craft/pull/8)). A two-floor Scandinavian studio built by `/t3 office`, Mannequin agents with per-provider skins, desk laptops that open their agent's thread, props, and `/t3 office go` from anywhere.
- **Configurable MCP port.** `mcpPort` in `config/t3craft.json` (or `-Dt3craft.mcpPort` / `-PmcpPort` for dev runs). When the port is taken, the server moves to the next free one and logs it, so a second game still gets its MCP server. It binds the documented `127.0.0.1`, and the address it bound is written to `config/t3craft-mcp.json` (`url`, `port`, `pid`) for tools, removed when that game quits.
- **JSON-RPC errors from the MCP server.** Malformed requests get `-32700`, `-32600`, `-32601` and `-32602` errors with the caller's id instead of bare HTTP errors or silence; the body limit is 1 MiB. `reliabilityCheck` covers it without Minecraft.
- **Modifier keys reach the streamed UI.** Shift, Ctrl, Alt and ⌘ are sent to T3's page as their own keydown/keyup transitions, so held-Shift selection and chords work.
- **Collision-free fixture ports.** `scripts/feature-fixture.py --port 0` picks a free pair and `--port-file` reports it; `scripts/verify-ci.sh` uses that, so concurrent checks no longer fight over 25680–25683.
- **Create T3 World.** A *T3 World* button beside Singleplayer: name, place (Lakeside, Cherry Plains, River Bend, Surprise me or a custom seed), Creative or Survival, Create. The integrated server builds design A at the seed's recorded site (or a flat, dry spot it finds near spawn), turned so the glazing faces the view, and you arrive on the plaza facing the door with the welcome toast. Agents, laptops, props, whiteboard and lectern follow the turn, and `/t3 office` takes you to that office instead of building another. Adds `worldCheck` and the `worldtest` dev run. ([#9](https://github.com/alexandros-lekkas/t3craft/pull/9))
- **The real T3 UI in game** (now opt-in with `webUi: true`). <kbd>`</kbd> and the laptop open T3 Code's own web app full screen, and right-clicking an agent opens its thread in a floating card over the blurred world. Streamed from a hidden Chrome, Edge, Brave or Chromium over CDP, pixel-exact at every GUI scale, with one-time pairing minted through T3's bundled CLI. The native panel opens when no browser is found or T3 is not running. Adds `webProbe` and the `webShowcase` dev run. ([#7](https://github.com/alexandros-lekkas/t3craft/pull/7))
- **A lit office.** A hidden light grid, ceiling downlights, a stair sconce, parapet and path lanterns keep every walkable cell at block light 11 or more day and night, with no dark corners on the roof or the path. The pendant shades glow cream at night. `officeCheck` now computes block light over the template and fails if any cell goes dim; rebuilding an office clears the old hidden lights first.
- Public T3 MCP becomes the main connection, with browser OAuth and a legacy fallback. A T3 restart re-establishes the session by itself. ([#1](https://github.com/alexandros-lekkas/t3craft/pull/1), merged)
- Thread visibility mirrors T3 Code's sidebar. Settled threads are hidden (except pins), snoozed threads are grouped and collapsed, and subagent and side-chat rows are hidden while their approvals still reach you. ([#1](https://github.com/alexandros-lekkas/t3craft/pull/1), merged)
- `uiCheck` regression checks for diff headers, table cells and ellipsizing, run on every build. ([#3](https://github.com/alexandros-lekkas/t3craft/pull/3))

### Fixed
- **First in-game QA pass.** A new Lakeside T3 World no longer drops you out of the world at Y −64: the arrival spot's chunks are generated before its height is read, and the arrival is always within 4 blocks of the office floor. The office stairs reach the upper floor and the roof: the landing at the top of each flight was a 4×2 hole. Typing "yes…" after clicking Approve or Deny no longer answers the next approval; the keyboard goes back to the composer. The laptop has a **+ New thread** row (it starts in your most recently used project) and never opens on, or sends into, a thread its list no longer shows. Office agents **walk** (legs and arms swing, bodies turn into corners, a little faster), turn their whole body to face you and back, sit down at their seat when you stand close, are back at their desks after a relog instead of walking in, and avoid wearing the same skin. Name tags no longer show through floors, the lounge sofa shows a name only when you look at it, the Done toast stays off the open laptop, and `/t3 office` on a slope grasses the lawn instead of leaving bare dirt.
- **Review follow-ups.** A T3 World deleted and created again under the same name gets its new office: the agents, laptops and `/t3 office` no longer target the old one (adoption is now remembered in the save). Rebuilding over a turned office sees its own blocks instead of refusing them as "your Planks", and still finds a player's build there. The arrival spot moves toward the door when it would land in a pond or on a tree, and lawn above plaza level gets its grass back. On the agent card and the laptop, Approve / Deny stay inert while an answer is on its way (no double answers, no unseen answer to the next request), a failed send puts its text back in the open box, Enter on a focused button presses that button, a letter-bound panel key no longer closes the card on the first letter of a reply, the card stops re-wrapping its message every frame and fits short windows, and many answer options use two columns at GUI scale 5–6. A refused fresh pairing backs off (15 s up to five minutes), warns once, and keeps your thread choice. With two games open, the one still running takes `config/t3craft-mcp.json` back when the other quits. The unreachable Activity screen and its dev flows are removed.
- **Office furniture without a pairing** ([#15](https://github.com/alexandros-lekkas/t3craft/pull/15)). A built office gets its props and day/night lamps whether or not a machine is paired; only the agents and their desk laptops wait for the pairing. Props are placed from one plan per office, wait for their chunk on a server, and a rebuild replaces them instead of duplicating.
- **Connection:** a legacy socket that drops while its close is still being processed no longer reconnects at once with the cached protocol version. The reconnect now always waits for the backoff and re-checks the machine's protocol, which also makes the `mcpCheck` disconnect scenarios deterministic.
- **UI** ([#3](https://github.com/alexandros-lekkas/t3craft/pull/3)): drafts are no longer lost when switching between New, a thread and the picker. Approval details wrap. Answered requests reveal the next one. Answered question sets are no longer re-asked. Markdown links keep their URL. Review Refresh keeps your file and scroll position. Trackpad scrolling works. Scroll position holds while a reply streams in. The model chip is ellipsized. Key hints follow your key bindings.
- **Build and release** ([#2](https://github.com/alexandros-lekkas/t3craft/pull/2)): tag releases run the full fixture-backed checks before publishing the jar and `SHA256SUMS.txt`. Builds are locked and reproducible on Gradle 9.8.2 with a Java 25 toolchain, and CI runs on JDK 25 and 27. The macOS installer and launcher are fixed.
- **World** ([#2](https://github.com/alexandros-lekkas/t3craft/pull/2)): office placement reports its bounds and asks before replacing existing blocks. Residents and queued work stay tied to their world. Whiteboard and lamp updates retry in loaded chunks. Report-book delivery re-checks the player and permissions.

### Changed
- New maintainer: **Alexandros Lekkas**. The project continues [maxwellyoung/t3craft](https://github.com/maxwellyoung/t3craft) with thanks to Maxwell Young.
- A dedicated server pairs with `/t3office pair <link>` and reports with `/t3office status` (moved from the removed `/t3village`).
- Pre-0.6 configs migrate on load: each world's office keeps its place under `offices`; the old `village`, `villages` and `officeWorlds` keys are dropped.
- Docs describe what is on `main`: T3 World, Office A, the native laptop and agent card (the streamed web UI opt-in), zero setup and the actual `/t3` command tree.

### Removed
- **The village.** `/t3 village`, the villager residents and the server's shared village (`/t3village here|off`). The office is the only place agents live. A dedicated server discards the shared village's leftover agents as their chunks load.
- **The Connections screen** and `/t3 connections` and `/t3 pair`. Pairing is automatic; `config/t3craft.json` is the only override (`/t3 unpair`, debug only, forgets every machine and the local T3 pairs again).

## [0.5.0] (2026-10-04)
### Added
- Backend activity view and numbered checkpoint changes in review.
- Modrinth listing and a tag-build workflow. (Re-released by the new maintainer on 2026-11-06 with checksums.)

## [0.4.0] (2026-10-04)
### Added
- Guided private pairing. Healthy machines are preserved during connection recovery.

## [0.3.0] (2026-10-04)
### Added
- Pinned office residents stay put. Projects can be filtered by machine.

## [0.2.1] (2026-10-04)
### Fixed
- Physical lectern targeting, verified in a packaged walkthrough.

## [0.2.0] (2026-10-04)
### Added
- The decision desk, checkpoint review, and compatibility with the current T3 nightly.
- Hardened MCP access, a scoped launcher shutdown and connection-recovery hints.

## [0.1.0] – 0.1.4 (2026-09-23)
### Added
- **First release:** T3 Code inside Minecraft. An in-game panel, threads from every machine, approvals and questions, and agents that build.
- A shared server village, report books on completion, and one notification per event.
- A Mac launcher (`T3 Craft.app`) that starts the world, joins it and stops it on quit, with optional SSH tunnels.

[Unreleased]: https://github.com/alexandros-lekkas/t3craft/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/alexandros-lekkas/t3craft/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/alexandros-lekkas/t3craft/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/alexandros-lekkas/t3craft/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/alexandros-lekkas/t3craft/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/alexandros-lekkas/t3craft/compare/v0.1.4...v0.2.0
[0.1.0]: https://github.com/alexandros-lekkas/t3craft/releases/tag/v0.1.0
