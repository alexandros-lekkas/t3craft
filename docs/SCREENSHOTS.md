# Screenshot checklist

The real in-game shots the docs need. When a feature lands, the implementing PR captures its shots, saves them to `docs/images/shots/`, swaps them in where the `TODO(screenshot)` markers are, and ticks them off here.

## Ground rules

- **Muted dev client only.** `scripts/mc world`, `scripts/mc test <scenario>` and `scripts/mc run` + `scripts/mc shot <name>` do this for you, without showing a window ([guide](AGENTS-GUIDE.md)). By hand: `./gradlew runClient` with its own run directory, and `soundCategory_master:0.0` in that directory's `options.txt` before launching.
- **Never the owner's game.** Don't open, copy from or write to `~/Library/Application Support/minecraft*`, and don't touch the owner's `options.txt`, keybinds or worlds.
- **Fixture data, not real threads.** Run `scripts/feature-fixture.py` (protocol 2) with a throwaway config (`-Pconfig=…`). No real conversations, project names, tokens or links should appear on screen.
- **Same look every time:** a 1920 × 1080 window, GUI scale **2** unless the shot says otherwise, no shaders, the default seed `-2085440121970745551`, Creative, weather clear, and a time set per shot. Hide the HUD (<kbd>F1</kbd>) for scenery shots. No debug overlay.
- **Files:** PNG, or JPG for busy scenery. Each must stay under 600 KB (resize to 1600 px wide if needed). Use kebab-case names exactly as listed.
- A self-test (`-Pselftest=…`) that stages the scene and saves to `run/screenshots/` is ideal, because then the shot can be retaken whenever the UI changes.

## Shots

### World and office (T3 World, design A)

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
| ☑ | `t3world-<place>-tour-*` (kept in `docs/design/office/interior*.{png,jpg}`) | Same run: `-Pworldtest` walks a fixed interior tour after the desks shot | Overview, bench, desks, entry, whiteboard, lounge, kitchen, nook, team table, upper floor, three at night | HUD hidden |
| ☑ | `world-river-bend-plaza`, `world-river-bend-desks` | River Bend (office turned 180°) | Same | HUD hidden, **5** |
| ☑ | `office-talk-ask`, `office-talk-report`, `office-talk-thanks` | Lakeside, office fixture paired, `curl /_qa/delegate?parent=office-0&title=Take%20the%20tests` then `/_qa/finish?id=…` | `-Pworldtalk=300:Build the studio` frames the pair side-on | HUD shown, **5** |

### Characters and laptop

| ✓ | Shot | Scene setup | Camera | GUI |
|:-:|:--|:--|:--|:--|
| ☐ | `agents-at-desks` | 4 working agents with mixed providers (Claude, Codex, Muse, default) | Close, at desk height, 3 seated agents typing | HUD hidden |
| ☐ | `agent-needs-you` | 1 agent waiting on an approval | At the whiteboard, agent with hand raised and **?** bubble, name tag readable | 2 |
| ☐ | `agents-lounge` | 2 done agents | Lounge corner looking at the sofa, evening light | HUD hidden |
| ☐ | `laptop-held` | Laptop in the main hand | First person, laptop visible in hand | 2 |
| ☐ | `laptop-desk` | Crosshair on a desk laptop | Close on the desk laptop, with the agent seated behind it | 2 |
| ☐ | `laptop-item` | Inventory open with the laptop in the hotbar, hovered | Inventory, cropped | 3 |

### The simple native UI

The in-game UI. Taken in the dev client at **GUI scale 5** in a 2000 × 1250 window against the office fixture, with `./gradlew runClient -Pselftest=nativeui -Pworld=<office save> -Pconfig=config/office-fixture.json -Pwidth=1000 -Pheight=625 -PmcpPort=25591` (and `scripts/office/fixture.py` running). Resized to 1600 px; the office scenes are JPG.

| ✓ | Shot | Scene setup | Camera | GUI |
|:-:|:--|:--|:--|:--|
| ☑ | `native-laptop` | Laptop open on a finished thread | Full screen | **5** |
| ☑ | `native-laptop-needs-you` | Laptop on the thread waiting for an approval: Approve / Deny row | Full screen | **5** |
| ☑ | `native-laptop-typing` | A message typed into the input box | Full screen | **5** |
| ☑ | `native-laptop-sent` | After sending: the message under *You*, status Working, Stop | Full screen | **5** |
| ☑ | `native-agent-card` | The card beside the waiting agent, world dimmed | First person at the whiteboard | **5** |
| ☑ | `native-agent-card-typing` | Same, with a reply typed | First person | **5** |
| ☑ | `native-agent-aim` | Crosshair on the waiting agent, before the click | First person | **5** |
| ☑ | `native-laptop-scale2`, `native-laptop-scale3` | The laptop at the other scales | Full screen | 2, 3 |

### Streamed T3 UI (opt-in, `webUi: true`)

These were taken against the real local T3 in the dev client with `./gradlew runClient -PwebShowcase=<threadId>` (read-only), at a 2000 × 1250 window. They are kept at full size, unedited, because the [real T3 UI page](design/real-t3-ui.md) uses them as evidence that text is pixel-exact.

| ✓ | Shot | Scene setup | Camera | GUI |
|:-:|:--|:--|:--|:--|
| ☑ | `ui-real-laptop-scale5` | Laptop open on the focused thread | Full screen | **5** |
| ☑ | `ui-real-laptop-input-scale5` | Same, after typing `minecraft` into T3's sidebar search (input focused via the DOM; see the known gap) | Full screen | **5** |
| ☑ | `ui-real-ping-toast-scale5` | A *Done* ping toast in the HUD, laptop closed | Full screen | **5** |
| ☑ | `ui-real-ping-jump-scale5` | Laptop after answering that ping with `: the pinged thread is open | Full screen | **5** |
| ☑ | `ui-real-agent-card-scale5` | Agent card open on one thread, world blurred behind | Full screen | **5** |

## Where each shot goes

| Shot(s) | Replaces |
|:--|:--|
| `hero-office`, `hero-office-night` | The `<picture>` hero in the [README](../README.md) |
| `office-*`, `agents-*` | Renders in [office.md](design/office.md) and the README's "What it feels like" |
| `world-*` | Real shots from `-Pworldtest` in [world.md](design/world.md); the `docs/images/world-*.png` files are the design mockups |
| `native-*` | [native-ui.md](design/native-ui.md) and the README feature table |
| `ui-real-*` | [real-t3-ui.md](design/real-t3-ui.md) |
| `ui-*` | Mockups in [ui.md](design/ui.md) |
| `laptop-*` | The texture in [characters-and-laptop.md](design/characters-and-laptop.md) |
| `thread-image-*`, `agent-browser-live` | [browser-and-images.md](design/browser-and-images.md) |
