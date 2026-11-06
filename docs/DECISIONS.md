# Decisions

The product calls behind T3 Craft, newest first. Each one has the date, the decision and the reason in a line. Add new entries at the top, and when a decision changes, edit the old entry rather than deleting it.

**The rule behind all of them:** one seamless experience, with you in your own world and your agents living and working in it. Do a few simple things very clearly.

| Date | Decision | Why |
|:--|:--|:--|
| 2026-11-05 | Office only; real T3 UI streamed in-game | Keep one workplace and the full T3 experience |
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

### 2026-11-05 · Office only and streamed T3 UI

**Decision:** Focus on the office only. An arrival plaza belongs to the office; multi-building expansion is out of scope. Stream the real T3 web UI in-game: the laptop opens the full app, and clicking an agent opens its thread in a floating window. The native panel is the fallback.
**Why:** Preserve the actual T3 experience without rebuilding it in Minecraft widgets.

### 2026-11-05 · Simple features, no settings or machine UI

**Decision:** Ship a small set of features and make each one obvious. The player never sees a Connections screen, machine management or a settings page. Overrides live only in `config/t3craft.json`.
**Why:** The owner's words were *"we just want simple stuff but done super clearly and simply"*. When a feature needs a screen to explain it, cut the feature.

### 2026-11-05 · Zero setup: auto-connect to the local T3

**Decision:** The mod finds T3 Code on the same computer and connects silently, with no pairing link and no `/t3 pair`. The world-creation flow drops its "Connect your agents" page.
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

**Decision:** Add a craftable laptop item and block. Right-click it to open the full streamed T3 web app, as an alternative to the <kbd>`</kbd> key. ([design](design/characters-and-laptop.md))
**Why:** Opening T3 becomes something you do in the world, not a hotkey you have to remember.

### 2026-11-05 · No villagers: agents are player characters

**Decision:** Agents are human-shaped characters with skins, everywhere: office residents, the village and shared servers. No villagers.
**Why:** Agents are your coworkers. They should look like people sitting at desks, not traders.

### 2026-11-05 · Calm UI, like the T3 desktop app

**Decision (superseded):** Earlier native-panel styling explorations are retained for reference. The primary UI is now the real T3 web app streamed in-game; the native panel is a fallback. ([design](design/ui.md))
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
