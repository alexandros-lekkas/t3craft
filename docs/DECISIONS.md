# Decisions

The product calls behind T3 Craft, newest first. Each one has the date, the decision and the reason in a line. Add new entries at the top, and when a decision changes, edit the old entry rather than deleting it.

**The rule behind all of them:** one seamless experience, with you in your own world and your agents living and working in it. Do a few simple things very clearly.

| Date | Decision | Why |
|:--|:--|:--|
| 2026-11-06 | [Simple native Minecraft UI](#2026-11-06--simple-native-minecraft-ui) | *"It was better when it was mc, but just very simple"* |
| 2026-11-05 | Office only; real T3 UI streamed in-game (UI part superseded) | Keep one workplace and the full T3 experience |
| 2026-11-06 | [Nothing to pick, nothing to confirm, one line in chat](#2026-11-06--nothing-to-pick-nothing-to-confirm-one-line-in-chat) | Every demand in chat is a place to get stuck |
| 2026-11-06 | [A lit office, day and night](#2026-11-06--a-lit-office-day-and-night) | No dark corners, no spawns, nothing modded-looking |
| 2026-11-05 | [Simple features, no settings or machine UI](#2026-11-05--simple-features-no-settings-or-machine-ui) | Each screen has one obvious purpose |
| 2026-11-05 | [Zero setup: auto-connect to the local T3](#2026-11-05--zero-setup-auto-connect-to-the-local-t3) | Players shouldn't have to type commands or paste links |
| 2026-11-05 | [No protections or confirmations](#2026-11-05--no-protections-or-confirmations) | The owner runs agents in full access by choice |
| 2026-11-05 | [MCP first, legacy as a fallback](#2026-11-05--mcp-first-legacy-as-a-fallback) | A public API won't break with T3 updates |
| 2026-11-05 | [Thread visibility mirrors T3 Code](#2026-11-05--thread-visibility-mirrors-t3-code) | Settled means done, and done means out of the way |
| 2026-11-05 | [The T3 laptop](#2026-11-05--the-t3-laptop) | An in-world way to open T3 |
| 2026-11-05 | [No villagers: agents are player characters](#2026-11-05--no-villagers-agents-are-player-characters) | Agents are your coworkers |
| 2026-11-05 | [Calm UI, like the T3 desktop app](#2026-11-05--calm-ui-like-the-t3-desktop-app) | The current panel is too bold and dense |
| 2026-11-05 | [Keep report books](#2026-11-05--keep-report-books) | The owner likes them |
| 2026-11-05 | [Official Minecraft Launcher only](#2026-11-05--official-minecraft-launcher-only) | No extra launcher to learn |
| 2026-11-05 | [No shaders](#2026-11-05--no-shaders) | Performance and a clean vanilla look |
| 2026-11-05 | [Curated QoL modpack](#2026-11-05--curated-qol-modpack) | Fast and pleasant, without big content changes |
| 2026-11-05 | [Plains seed by default](#2026-11-05--plains-seed-by-default) | A flat, dry spot right beside spawn |
| 2026-11-05 | [Office design A: Scandi Studio](#2026-11-05--office-design-a-scandi-studio) | Warm, readable and modular |

---

### 2026-11-06 · Simple native Minecraft UI

**Decision:** The in-game UI is native Minecraft, very simple: the default font, a vanilla container-style window, vanilla buttons and edit boxes, sized for GUI scale 5 first. The laptop shows active threads on the left and the selected thread on the right with one input box and Send, plus Approve / Deny or the answer options when the agent needs you. Clicking an agent opens a small card beside it over the lightly dimmed world. Nothing else: no tabs for activity, review, diff, pins or connections. The streamed web UI stays in the code behind `webUi: true` and is off by default. ([design](design/native-ui.md))
**Why:** After trying the streamed web app in game, the owner said *"idk why we switched to full js ui, it was better when it was mc, but just very simple."* The earlier native panel was *"way too bold and dense"*, so this one shows fewer things, bigger and clearer.
### 2026-11-06 · A lit office, day and night
**Decision:** Light the office in two layers. An always-on layer (a hidden light grid, white end-rod downlights, a stair sconce, lanterns on the parapet posts and the path bollards) keeps every walkable cell at block light 11 or more and the roof and path never dark. The design's own lamps keep switching with the clock as the warm evening layer, and the fabric shades glow cream (`ochre_froglight`) rather than orange shroomlight. ([design](design/office.md#lighting))
**Why:** The office was too dim, inside by day and everywhere by night, and an open roof at light 0 spawns mobs. Hidden light blocks and end rods are vanilla and invisible or discreet, so the ceiling stays clean.

### 2026-11-05 · Office only and streamed T3 UI

**Decision:** Focus on the office only. An arrival plaza belongs to the office; multi-building expansion is out of scope. Stream the real T3 web UI in-game: the laptop opens the full app, and clicking an agent opens its thread in a floating window. ([how it works](design/real-t3-ui.md), [#7](https://github.com/alexandros-lekkas/t3craft/pull/7)) **The UI part is superseded** by the [simple native UI](#2026-11-06--simple-native-minecraft-ui); the streamed app is opt-in.
**Why:** Preserve the actual T3 experience without rebuilding it in Minecraft widgets.

### 2026-11-06 · Nothing to pick, nothing to confirm, one line in chat

**Decision:** No command ever asks the player to pick a thread first. With nothing chosen it talks to the thread that needs them, else the most recently active one, and with no threads at all it opens T3. The office builds where you stand without a confirmation step: terrain is replaced, and only your own storage and builds make it refuse (one red line naming the spot). A local pairing T3 stops accepting is dropped and made again by itself. On its own the mod says one line in chat per game, when T3 connects (*T3 connected · press ` to open*); everything else goes to the log, or to chat only on error. `/t3` prints two lines of help (open T3, office). Debug and admin subcommands (unpair, threads, books, pins, agent-build…) exist only with `"debug": true` in `config/t3craft.json`, so tab-complete shows players only what they need. Agent building is on by default.
**Why:** The owner's first session was a wall of chat and a *"Pick a thread first"* refusal. Every demand in chat is a place to get stuck, and a feature that needs instructions in chat isn't done clearly enough.

### 2026-11-05 · Simple features, no settings or machine UI

**Decision:** Ship a small set of features and make each one obvious. The player never sees a Connections screen, machine management or a settings page. Overrides live only in `config/t3craft.json`. *Done 2026-11-06:* the Connections screen and `/t3 connections` and `/t3 pair` are removed (`/t3 unpair` stays as a debug-only recovery tool).
**Why:** The owner's words were *"we just want simple stuff but done super clearly and simply"*. When a feature needs a screen to explain it, cut the feature.

### 2026-11-05 · Zero setup: auto-connect to the local T3

**Decision:** The mod finds T3 Code on the same computer and connects silently, with no pairing link and no `/t3 pair`. The world-creation flow drops its "Connect your agents" page. *Done 2026-11-06:* `T3AutoConnect` pairs the laptop, the agent card and the office; the opt-in streamed UI pairs its own browser.
**Why:** The best setup is none. Every command the player has to type is a place they can get stuck.

### 2026-11-05 · No protections or confirmations

**Decision:** No permission gates, command allowlists or "are you sure?" dialogs anywhere, including agent building.
**Why:** The owner runs T3 in full access with no approvals by choice. Fixes go to correctness, performance and data loss instead.

### 2026-11-05 · MCP first, legacy as a fallback

**Decision:** Talk to T3 Code through its public MCP server for everything it supports. Keep T3's undocumented client protocol only for permission approvals, checkpoint diffs and live push updates. Each path has its own health, so one failing never takes down the other. ([#1](https://github.com/alexandros-lekkas/t3craft/pull/1), [architecture](ARCHITECTURE.md))
**Why:** A documented public API survives T3 updates, and the hybrid keeps features MCP doesn't have yet.

### 2026-11-05 · Thread visibility mirrors T3 Code

**Decision:** Show **active** threads. Put **snoozed** threads in a collapsed section. **Settled** threads are done and hidden everywhere: sidebar, office, HUD. Pinned threads are the exception. Subagent and side-chat children are hidden, but their pending approvals still reach Decisions.
**Why:** The game should show the same list as T3's sidebar, with nothing extra and nothing stale.

### 2026-11-05 · The T3 laptop

**Decision:** Add a craftable laptop item and block. Right-click it to open the T3 laptop screen, as an alternative to the <kbd>`</kbd> key. ([design](design/characters-and-laptop.md))
**Why:** Opening T3 becomes something you do in the world, not a hotkey you have to remember.

### 2026-11-05 · No villagers: agents are player characters

**Decision:** Agents are human-shaped characters with skins, everywhere: office residents and the shared server office. No villagers. *Done 2026-11-06:* the old village mode (`/t3 village`, `/t3village`) is removed; only the office remains.
**Why:** Agents are your coworkers. They should look like people sitting at desks, not traders.

### 2026-11-05 · Calm UI, like the T3 desktop app

**Decision (superseded twice):** Earlier native-panel styling explorations are retained for reference. The streamed web app replaced them, and the [simple native UI](#2026-11-06--simple-native-minecraft-ui) replaced that. ([native exploration](design/ui.md), [what shipped](design/native-ui.md))
**Why:** The owner found the current panel *"way too bold and dense, can't understand anything"*.

### 2026-11-05 · Keep report books

**Decision:** Keep the written book an agent leaves when it finishes, along with the one completion toast.
**Why:** They work well, and the owner likes them.

### 2026-11-05 · Official Minecraft Launcher only

**Decision:** The modpack installs as a normal installation in the official Minecraft Launcher, with its own game folder. We never require Prism, the Modrinth App or any other launcher.
**Why:** Players already have the official launcher, and a second launcher is one more thing to learn.

### 2026-11-05 · No shaders

**Decision:** Remove both Iris and the Miniature shader from the pack. No shaders ship.
**Why:** Performance comes first, and the curated vanilla look already reads well.

### 2026-11-05 · Curated QoL modpack

**Decision:** Build a small Vanilla+ pack of our own around T3 Craft: performance mods (Sodium, Lithium, FerriteCore, ImmediatelyFast, Entity Culling, Dynamic FPS), conveniences (Mod Menu, AppleSkin, Mouse Tweaks, Shulker Box Tooltip, Chat Heads) and subtle polish (Continuity, Visuality, AmbientSounds). We don't build on a large existing pack.
**Why:** Good performance, pleasant visuals and minimal setup, with every version pinned and verified for 26.3.

### 2026-11-05 · Plains seed by default

**Decision:** The default world seed is `-2085440121970745551`, lakeside plains. The office origin is (-5, 65, -21), facing east. The alternates are `-4474845086078965121` (cherry plains) and `3478701220631481329` (riverside plains). ([maps](design/world.md))
**Why:** All 480 footprint columns are dry and flat within one block, 13 blocks from spawn, with a lake view.

### 2026-11-05 · Office design A: Scandi Studio

**Decision:** Build the office from design A, a white-and-birch studio with south glazing, an 8-desk bench, a whiteboard, a lounge and a roof terrace. B (Japanese) and C (Glasshouse) were not chosen. ([design](design/office.md))
**Why:** It's warm and easy to read at a glance, and its 6-block floor module stacks cleanly, one floor per machine.
