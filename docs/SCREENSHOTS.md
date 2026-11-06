# Screenshot checklist

The real in-game shots the docs need. When a feature lands, the implementing PR captures its shots, saves them to `docs/images/shots/`, swaps them in where the `TODO(screenshot)` markers are, and ticks them off here.

## Ground rules

- **Muted dev client only.** Use `./gradlew runClient` with its own run directory, and set `soundCategory_master:0.0` in that run directory's `options.txt` before launching.
- **Never the owner's game.** Don't open, copy from or write to `~/Library/Application Support/minecraft*`, and don't touch the owner's `options.txt`, keybinds or worlds.
- **Fixture data, not real threads.** Run `scripts/feature-fixture.py` (protocol 2) with a throwaway config (`-Pconfig=…`). No real conversations, project names, tokens or links should appear on screen.
- **Same look every time:** a 1920 × 1080 window, GUI scale **2** unless the shot says otherwise, no shaders, the default seed `-2085440121970745551`, Creative, weather clear, and a time set per shot. Hide the HUD (<kbd>F1</kbd>) for scenery shots. No debug overlay.
- **Files:** PNG, or JPG for busy scenery. Each must stay under 600 KB (resize to 1600 px wide if needed). Use kebab-case names exactly as listed.
- A self-test (`-Pselftest=…`) that stages the scene and saves to `run/screenshots/` is ideal, because then the shot can be retaken whenever the UI changes.

## Shots

### World and office (Create T3 World, design A)

| ✓ | Shot | Scene setup | Camera | GUI |
|:-:|:--|:--|:--|:--|
| ☐ | `hero-office` | Fresh T3 World on the default seed, 2 floors, 4 working agents, `/time set 1000` | From the lake side, about 30 blocks out, slightly above the roof, office filling 60% of the frame | HUD hidden |
| ☐ | `hero-office-night` | Same as above, `/time set 14000`, lamps on | Same position as `hero-office` | HUD hidden |
| ☐ | `office-exterior-day` | Same world | Front three-quarter view from the plaza, eye height | HUD hidden |
| ☐ | `office-interior-desks` | 8 agents: 6 working, 1 needs you, 1 done | Standing behind the bench, looking across desks to the glass | 2, HUD hidden |
| ☐ | `office-roof` | Afternoon, `/time set 9000` | On the roof terrace, looking across the pergola to the lake | HUD hidden |
| ☑ | `world-lakeside-title` | Title screen with the mod loaded | Full screen | **5** |
| ☑ | `world-lakeside-new-world` | *T3 World* → New T3 World page, defaults untouched | Full screen | **5** |
| ☑ | `world-lakeside-arrival` | The first frame after loading the new world, with the welcome toast visible | Spawn view, facing the door, as loaded | **5** |
| ☑ | `world-lakeside-hub` | A T3 world exists. Title → *T3 World* | Full screen | **5** |
| ☑ | `world-lakeside-plaza`, `world-lakeside-lake`, `world-lakeside-desks` | Lakeside, office fixture paired | Plaza side, lake side, ground-floor desks with agents seated | HUD hidden, **5** |
| ☑ | `world-river-bend-plaza`, `world-river-bend-desks` | River Bend (office turned 180°) | Same | HUD hidden, **5** |

### Characters and laptop

| ✓ | Shot | Scene setup | Camera | GUI |
|:-:|:--|:--|:--|:--|
| ☐ | `agents-at-desks` | 4 working agents with mixed providers (Claude, Codex, Muse, default) | Close, at desk height, 3 seated agents typing | HUD hidden |
| ☐ | `agent-needs-you` | 1 agent waiting on an approval | At the whiteboard, agent with hand raised and **?** bubble, name tag readable | 2 |
| ☐ | `agents-lounge` | 2 done agents | Lounge corner looking at the sofa, evening light | HUD hidden |
| ☐ | `laptop-held` | Laptop in the main hand | First person, laptop visible in hand | 2 |
| ☐ | `laptop-desk` | Crosshair on a desk laptop | Close on the desk laptop, with the agent seated behind it | 2 |
| ☐ | `laptop-item` | Inventory open with the laptop in the hotbar, hovered | Inventory, cropped | 3 |

### Streamed T3 UI

Capture the real full app from the laptop and a real thread floating beside an agent. Native-panel captures below document the fallback, not the primary launch UI.

These were taken against the real local T3 in the dev client with `./gradlew runClient -PwebShowcase=<threadId>` (read-only), at a 2000 × 1250 window. They are kept at full size, unedited, because the [real T3 UI page](design/real-t3-ui.md) uses them as evidence that text is pixel-exact.

| ✓ | Shot | Scene setup | Camera | GUI |
|:-:|:--|:--|:--|:--|
| ☑ | `ui-real-laptop-scale5` | Laptop open on the focused thread | Full screen | **5** |
| ☑ | `ui-real-laptop-input-scale5` | Same, after typing `minecraft` into T3's sidebar search (input focused via the DOM; see the known gap) | Full screen | **5** |
| ☑ | `ui-real-ping-toast-scale5` | A *Done* ping toast in the HUD, laptop closed | Full screen | **5** |
| ☑ | `ui-real-ping-jump-scale5` | Laptop after answering that ping with `: the pinged thread is open | Full screen | **5** |
| ☑ | `ui-real-agent-card-scale5` | Agent card open on one thread, world blurred behind | Full screen | **5** |

### Native panel fallback

| ✓ | Shot | Scene setup | Camera | GUI |
|:-:|:--|:--|:--|:--|
| ☐ | `ui-panel` | Fixture with 5 active threads over 2 projects, 6 snoozed. Focused thread working, with a collapsed *Ran 3 commands* row | Full screen panel over the office | 2 |
| ☐ | `ui-panel-laptop` | Same, opened from a desk laptop (laptop framing) | Full screen | 2 |
| ☐ | `ui-panel-scale3` | Same as `ui-panel` | Full screen | **3** |
| ☐ | `ui-approval` | Fixture approval: a long wrapped command | Panel, approval card in view | 2 |
| ☐ | `ui-question` | Fixture question with 3 options | Panel, question card in view | 2 |
| ☐ | `ui-snoozed` | Snoozed section expanded | Sidebar crop | 2 |
| ☐ | `ui-decisions` | 3 pending requests across 2 fixture machines | Decisions screen (<kbd>J</kbd>) | 2 |
| ☐ | `ui-review` | Fixture checkpoint with 2 files, multi-hunk | Review screen, patch with line numbers | 2 |
| ☐ | `ui-error-strip` | Stop fixture A only | Panel with the one-line error strip and its single button | 2 |

### Moments

| ✓ | Shot | Scene setup | Camera | GUI |
|:-:|:--|:--|:--|:--|
| ☐ | `done-toast` | Fixture thread completes | In-world view with the single Done toast | 2 |
| ☐ | `report-book` | Open the report book from that completion | Book screen | 2 |
| ☐ | `agent-build` | `minecraft_run_command` builds something small near the office | Over-the-shoulder view of the build, toast visible | 2 |

### Later: images and browser

| ✓ | Shot | Scene setup | Camera | GUI |
|:-:|:--|:--|:--|:--|
| ☐ | `thread-image-inline` | Fixture thread with one screenshot attachment | Panel, inline thumbnail | 2 |
| ☐ | `thread-image-viewer` | Click the thumbnail | Full-size viewer | 2 |
| ☐ | `agent-browser-live` | Fixture or demo page in a streamable tab | Browser viewer, observe mode | 2 |

## Where each shot goes

| Shot(s) | Replaces |
|:--|:--|
| `hero-office`, `hero-office-night` | The `<picture>` hero in the [README](../README.md) |
| `office-*`, `agents-*` | Renders in [office.md](design/office.md) and the README's "What it feels like" |
| `world-*` | Real shots from `-Pworldtest` in [world.md](design/world.md); the `docs/images/world-*.png` files are the design mockups |
| `ui-real-*` | [real-t3-ui.md](design/real-t3-ui.md) and the README feature table |
| `ui-*` | Mockups in [ui.md](design/ui.md) |
| `laptop-*` | The texture in [characters-and-laptop.md](design/characters-and-laptop.md) |
| `thread-image-*`, `agent-browser-live` | [browser-and-images.md](design/browser-and-images.md) |
