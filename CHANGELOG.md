# Changelog

All notable changes to T3 Craft. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [SemVer](https://semver.org/).

## [Unreleased]

### Coming next (the zero-setup release)
- **Auto-connect:** T3 Craft finds the T3 Code on your computer and connects by itself. No pairing links and no Connections screen.
- **Create T3 World:** a one-click world on a chosen plains seed, with the office at spawn.
- **Agents as people:** player-like characters with per-provider skins replace villagers.
- **Click an agent to talk:** a small native card beside it. The **T3 laptop** shows your threads in a simple, plain Minecraft screen.
- **The T3 Craft pack** for the official Minecraft Launcher, with a light Vanilla+ set and no shaders.

### Added
- **Simple native Minecraft UI.** The laptop and the agent card are plain Minecraft screens again: the default font, a vanilla container-style window, vanilla buttons and edit boxes, sized to read at GUI scale 5. The laptop shows active threads (status dot, provider icon, title; snoozed folded, settled hidden) and the selected thread as wrapped text with one input box and Send, plus a big Approve / Deny row or the answer options when the agent needs you. Clicking an agent opens a small card beside it over the lightly dimmed world, with the last message, an input box, Approve / Deny and *Open on laptop*. The streamed web UI stays behind `webUi: true` in the config file and Chrome never starts while it is off. Adds the `nativeui` dev capture. ([design](docs/design/native-ui.md))
- **Create T3 World.** A *T3 World* button beside Singleplayer: name, place (Lakeside, Cherry Plains, River Bend, Surprise me or a custom seed), Creative or Survival, Create. The integrated server builds design A at the seed's recorded site (or a flat, dry spot it finds near spawn), turned so the glazing faces the view, and you arrive on the plaza facing the door with the welcome toast. Agents, laptops, props, whiteboard and lectern follow the turn, and `/t3 office` takes you to that office instead of building another. Adds `worldCheck` and the `worldtest` dev run. ([#9](https://github.com/alexandros-lekkas/t3craft/pull/9))
- **The real T3 UI in game** (now opt-in with `webUi: true`). <kbd>`</kbd> and the laptop open T3 Code's own web app full screen, and right-clicking an agent opens its thread in a floating card over the blurred world. Streamed from a hidden Chrome, Edge, Brave or Chromium over CDP, pixel-exact at every GUI scale, with one-time pairing minted through T3's bundled CLI. The native panel opens when no browser is found or T3 is not running. Adds `webProbe` and the `webShowcase` dev run. ([#7](https://github.com/alexandros-lekkas/t3craft/pull/7))
- **A lit office.** A hidden light grid, ceiling downlights, a stair sconce, parapet and path lanterns keep every walkable cell at block light 11 or more day and night, with no dark corners on the roof or the path. The pendant shades glow cream at night. `officeCheck` now computes block light over the template and fails if any cell goes dim; rebuilding an office clears the old hidden lights first.
- Public T3 MCP becomes the main connection, with browser OAuth and a legacy fallback. A T3 restart re-establishes the session by itself. ([#1](https://github.com/alexandros-lekkas/t3craft/pull/1), merged)
- Thread visibility mirrors T3 Code's sidebar. Settled threads are hidden (except pins), snoozed threads are grouped and collapsed, and subagent and side-chat rows are hidden while their approvals still reach you. ([#1](https://github.com/alexandros-lekkas/t3craft/pull/1), merged)
- `uiCheck` regression checks for diff headers, table cells and ellipsizing, run on every build. ([#3](https://github.com/alexandros-lekkas/t3craft/pull/3))

### Fixed
- **Connection:** a legacy socket that drops while its close is still being processed no longer reconnects at once with the cached protocol version. The reconnect now always waits for the backoff and re-checks the machine's protocol, which also makes the `mcpCheck` disconnect scenarios deterministic.
- **UI** ([#3](https://github.com/alexandros-lekkas/t3craft/pull/3)): drafts are no longer lost when switching between New, a thread and the picker. Approval details wrap. Answered requests reveal the next one. Answered question sets are no longer re-asked. Markdown links keep their URL. Review Refresh keeps your file and scroll position. Trackpad scrolling works. Scroll position holds while a reply streams in. The model chip is ellipsized. Key hints follow your key bindings.
- **Build and release** ([#2](https://github.com/alexandros-lekkas/t3craft/pull/2)): tag releases run the full fixture-backed checks before publishing the jar and `SHA256SUMS.txt`. Builds are locked and reproducible on Gradle 9.8.2 with a Java 25 toolchain, and CI runs on JDK 25 and 27. The macOS installer and launcher are fixed.
- **World** ([#2](https://github.com/alexandros-lekkas/t3craft/pull/2)): office placement reports its bounds and asks before replacing existing blocks. Residents and queued work stay tied to their world. Whiteboard and lamp updates retry in loaded chunks. Report-book delivery re-checks the player and permissions.

### Changed
- New maintainer: **Alexandros Lekkas**. The project continues [maxwellyoung/t3craft](https://github.com/maxwellyoung/t3craft) with thanks to Maxwell Young.

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
