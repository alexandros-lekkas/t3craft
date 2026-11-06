> **Status:** design spec, 2026-11-05; implemented in [PR #9](https://github.com/alexandros-lekkas/t3craft/pull/9). What shipped is described in [docs/design/world.md](../design/world.md), which wins where the two differ. Two later owner decisions supersede parts of it:
> - **Office design A (Scandi Studio)** replaces the Glasshouse design assumed below. Same 24×20 footprint and 6-block floor pitch. See [office-design-a/spec.md](office-design-a/spec.md).
> - **Zero setup:** the mod auto-connects to the local T3, so page 2 ("Connect your agents") and the pairing steps are dropped. The default seed list is in [seeds-report.md](seeds-report.md).
> - **No villagers:** residents are player-like characters. Wherever this spec says "villagers", read "agent characters".
> - **Since removed:** `T3Village` and `ServerVillage` are gone ([PR #13](https://github.com/alexandros-lekkas/t3craft/pull/13)); the office residents are `OfficeResidents` on the client and `ServerOffice` on a shared server.
>
> Summary with pictures: [docs/design/world.md](../design/world.md).

# T3 World: "Create T3 Craft World" + office at spawn

Status: design spec, ready to implement · 2026-11-05 · target: T3 Craft 0.6.0, Minecraft Java 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3
Mockup: `t3-world-mockup.html` (same folder)

Owner's rule for every decision below: **one seamless experience of you in your world with your agents living and working in it.** Minimal setup. No permission prompts, confirmations or "are you sure" dialogs.

---

## 0. Summary and recommendation

- **Feature 1: a custom `T3WorldScreen`, not a new tab inside vanilla `CreateWorldScreen`.** It is one vanilla-styled page with a world name, a curated place (seed), Creative or Survival, and two checkboxes. It creates the world directly through `WorldOpenFlows.createFreshLevel(...)`, so no mixin is needed. A second page appears only when no T3 machine is paired: paste a link, or skip. Entry points: a **T3 World** button that splits the title screen's Singleplayer row, and a **New T3 World** button on the world list. **More options…** opens the vanilla Create World screen prefilled, with a T3 banner, for anyone who wants to adjust things.
- **No WorldPreset, no worldgen datapack.** A preset can only define dimensions. It cannot set the seed, gamerules or game mode, and it cannot place one structure at spawn. Everything T3-specific happens either through `LevelSettings`/`WorldOptions` at creation or in a one-time setup pass on first server start.
- **Feature 2: approach (b) plus (c).** One `OfficePlacer` places the modular `.nbt` templates (lobby, N floors, roof) server-side through `StructureTemplate`. It uses the curated seed's recorded origin and facing, or a flat-spot search for any other seed, which reads the noise heightmap without generating chunks. It runs in `SERVER_STARTED`, before the player joins, so the player **loads in standing on the office plaza, facing the door**. `/t3 office` stays for existing worlds. On a vanilla server, where only the client has the mod, the same templates are converted into `fill`/`setblock` commands.
- **Reject (a), a worldgen structure.** Worldgen places structures relative to the chunk grid, not to spawn. It runs in every world that has the pack, cannot know the floor count, cannot grow later, and makes the world's registries depend on the mod.
- **Grow, don't rebuild.** Pairing a new machine appends one floor module and moves the roof up. Nothing else changes. Removing a machine leaves its floor as "Vacant".
- **Needs a common (`main`) entrypoint.** Today the mod has only `client` and dedicated `server` entrypoints, so the integrated (singleplayer) server runs no T3 code. That is the one structural change everything else depends on.

---

## 1. What exists today (main @ 0.5.0)

| Area | Today | Consequence |
| --- | --- | --- |
| Entrypoints | `client: T3CraftClient`, `server: T3CraftServer` (dedicated only) | The integrated server has no hooks. Add `main: T3CraftCommon`. |
| Client office | `T3CraftClient.buildOffice()` puts the origin at the player's feet `(-10, 0, +5)` and queues `T3Office.blueprint()` as chat commands through `OfficeQueue`, 6 per tick | Needs op/cheats. Fixed orientation (door faces north/-z). Builds the whole building every time. |
| Layout | `T3Office`: one 14×30 loft, `FLOOR_HEIGHT = 9`, hard-coded `WORK`/`NEEDS_YOU`/`LOUNGE` spots, `blocked()` grid | Will be replaced by a manifest-driven `OfficeLayout`. |
| Brain | `OfficeBrain` mirrors `OfficeRoster.sync` (8 desks per machine) to agents with floor/x/z/yaw | Keep. Feed it `OfficeLayout` spots instead of `T3Office` statics. |
| Server office | `ServerOffice`: Overworld only, origin in `config.serverOffice`, real tagged villagers, whiteboard/lamps via commands | Keep the residents. Swap the build to `OfficePlacer`. |
| Per-world state | `T3Config.villages[worldKey]`, `officeWorlds`, `officePreferences[worldKey]`; `worldKey = "save:<folder>|<dimension>"` | Still the client's source for resident placement. Mirror it from the new world `SavedData`. |
| Agent build | `config.agentCommands` (global, not per world), toggled by `/t3 agent-build on|off` | The "My agents can build for me" checkbox sets this. |
| Pairing UI | `T3ConnectionsScreen` with the private `PrivateLink` field; `mod.pairEnvironment(link, label, expectedOwner, callback)` | Reuse it as the embedded pairing step. |

Office design input: only **C / Glasshouse** exists so far (`office-designs/C-glass-hq/`). It is 24 × 20 blocks with a 6-block floor pitch, 8 desks per floor, waiting spots at the decision board, a lounge and a roof garden, and it is already modular in its generator (`N` floors × `H=6`, roof on top). This spec assumes Glasshouse's module split. Any other design has to provide the same three modules and a manifest (§7).

---

## 2. Feature 1: player flow

```
Title screen ──[T3 World]──► (has T3 world?) ─yes─► T3 World hub: [Continue <name>] [New T3 World]
                                  │no
                                  ▼
World list ──[New T3 World]──► Page 1 "New T3 World" ──[Create T3 World]──► (paired?) ─yes─► create
                                  │ [More options…] → vanilla CreateWorldScreen, prefilled + T3 banner │no
                                  ▼                                                               ▼
                                                                         Page 2 "Connect your agents"
                                                                         [Pair & create world] / [Skip for now]
                                                                                       ▼
                         vanilla progress screens ("Preparing…") ── setup pass runs here (server side)
                                                                                       ▼
                         player spawns on the plaza facing the office door → welcome toast
```

Clicks to a playing world: **2** when already paired (T3 World → Create T3 World), **4** when unpaired (T3 World → Create → Paste link → Pair & create world). All defaults are good enough to press Enter straight through.

### 2.1 Title screen

`ScreenEvents.AFTER_INIT` for `TitleScreen`. Find the Singleplayer button with `Screens.getButtons(screen)`, matching on `Component.translatable("menu.singleplayer")` contents. Shrink it to 98 px and add a 98 px **T3 World** button 4 px to its right in the same row. Do not move any other button. (Mod Menu changes the Realms/Mods row, so the two don't collide.) If the Singleplayer button isn't found, because another mod rearranged the screen, fall back to a 20×20 T3 icon button left of the Singleplayer row's y, with a tooltip.

| Element | Copy |
| --- | --- |
| Button | `T3 World` |
| Tooltip | `Your world with your agents in it. Office at spawn, ready to go.` |

Click behaviour:
- If `config.t3Worlds` has an entry whose save folder still exists: open the **T3 World hub**. Its default focus is Continue, so Enter loads the world.
- Otherwise: open **Page 1** directly.

### 2.2 T3 World hub (only when a T3 world exists)

A small vanilla screen: title, two 200 px buttons, Back.

| Element | Copy |
| --- | --- |
| Title | `T3 World` |
| Primary | `Continue: <world name>` (subtitle line under it in grey: `Last played <relative time> · <n> machine(s) · <mode>`) |
| Secondary | `New T3 World` |
| Footer | `Back` |

Continue calls `minecraft.createWorldOpenFlows().openWorld(levelId, () -> setScreen(this))`. In 1.21.x this is `openWorld(String, Runnable)`; check the 26.3 signature with `genSources`. Show at most the single most recent T3 world. Everything else is on the vanilla world list.

### 2.3 World list (SelectWorldScreen)

`ScreenEvents.AFTER_INIT` on `SelectWorldScreen`. Add a 150 px `New T3 World` button. Place it left of the vanilla `Create New World` button, or on the bottom row if the layout leaves no room. Clicking it opens Page 1 with this screen as parent.

### 2.4 Page 1 "New T3 World" (`T3WorldScreen`)

A vanilla `Screen` that uses a `HeaderAndFooterLayout` and a centred 310 px column (the same proportions as the Create World Game tab). Widgets: `EditBox`, `CycleButton`, `Checkbox`, `Button`, and grey `StringWidget`/`MultiLineTextWidget` descriptions. No custom rendering beyond the vanilla menu background.

| # | Widget | Label / copy | Default |
| --- | --- | --- | --- |
| H | Title | `New T3 World` | |
| 1 | EditBox + label | Label `World Name`. Value `T3 World` (if taken, vanilla-style `T3 World (2)` through `FileUtil.findAvailableName`) | `T3 World` |
| 2 | CycleButton | `Place: <seed name>`. Values = curated seeds (§4), then `Surprise me`, then `Custom seed` | first curated seed |
| 2a | grey text under 2 | Seed's `blurb`, one line. For Surprise me: `A random world. Your office finds a flat, dry spot near spawn.` For Custom seed: an `EditBox` appears, hint `Seed (number or text)`, plus the same line as Surprise me | |
| 3 | CycleButton | `Game Mode: Creative` / `Game Mode: Survival` | Creative |
| 3a | grey text | Creative: `Fly and build freely. Peaceful, so nothing wanders into the office.` Survival: `Day and night, mobs, hunger. You keep your inventory when you die.` | |
| 4 | Checkbox | `Build my office at spawn` | on |
| 4a | grey text | `Your agents' office is waiting when you arrive. One floor per paired machine; new machines add a floor.` | |
| 5 | Checkbox | `My agents can build for me` | on |
| 5a | grey text | `Agents on this computer can run commands in your worlds through T3 Craft. Every command shows in chat. /t3 agent-build off to stop.` | |
| 6 | status line | Paired: `§a●§r T3: MacBook Pro, home-server` (labels via `config.labelFor`, live/polling dot from `state.machines()`). Unpaired: `§7○ No T3 machine paired yet. You can pair on the next step.` | |
| F1 | Button (primary) | Paired: `Create T3 World`. Unpaired: `Next: Connect agents` | focused |
| F2 | Button | `Cancel` | |
| F3 | small text button, footer left | `More options…` | |

Never shown, always applied: cheats **on** and structures **on**. Cheats are needed for agent build, report books and the command fallback, and the owner wants no toggles for this. A one-line grey footnote under the column says: `Cheats are on so your agents can build. Change anything later in More options or with /gamerule.`

Keyboard: Enter = primary. Esc = Cancel, back to the parent screen.

### 2.5 Page 2 "Connect your agents" (only if `!config.paired()`)

Reuse the pairing form from `T3ConnectionsScreen`. Refactor its form into a `PairingForm` helper (the name `EditBox` plus the `PrivateLink`, the `Paste link` button and the `submit()` logic) that both screens use. Credentials stay out of chat and narration exactly as they do today.

| Element | Copy |
| --- | --- |
| Title | `Connect your agents` |
| Body (MultiLineText, 310 px) | `Your agents live on a T3 machine. In T3 Code, open Settings → Connections and create a pairing link, then paste it here. The link stays private: it is hidden on screen and never goes to chat.` |
| Field label | `Pairing link` (hidden field, hint `Paste your T3 pairing link`) |
| Button | `Paste link` |
| Field label | `Machine name (optional)` (hint `e.g. MacBook Pro`) |
| Primary | `Pair & create world` (enabled when the link is non-blank) |
| Secondary | `Skip for now` |
| Footer left | `Back` |
| MCP helper row | grey: `Agents reach this world at 127.0.0.1:25590/mcp` + small button `Copy Claude Code setup` → clipboard `claude mcp add --transport http -s user minecraft http://127.0.0.1:25590/mcp` |
| Status while pairing | `Pairing…` (primary disabled) |
| Success | `Paired with <label>. Creating your world…` and world creation starts immediately |
| Error | the exact string from `pairEnvironment`'s callback (it never contains the credential), in red, under the buttons. Stay on the page. |

Skip creates the world straight away. The office is still built, with one floor labelled `Vacant` (§8.4).

### 2.6 "More options…" (vanilla Create World, prefilled)

1. Register the pending setup (§3.3) under the name currently in the name box.
2. `CreateWorldScreen.openFresh(minecraft, parent)`. Check the 26.3 overload. 1.21.x has `openFresh(Minecraft, @Nullable Screen)`, and newer builds add a `Runnable` or callback variant.
3. On that screen's first `AFTER_INIT` only (track it with a `WeakHashMap<Screen, Boolean>`), mutate `screen.getUiState()`: `setName`, `setSeed(seedString)`, `setGameMode(CREATIVE|SURVIVAL)`, `setAllowCommands(true)` (called `setAllowCheats` in older mappings), `setDifficulty`, `setGenerateStructures(true)`, and the gamerules in §3.2 through `getGameRules()`. The player then sees and can edit the T3 defaults in the vanilla tabs, including the vanilla Game Rules editor.
4. Add a non-interactive banner widget under the tab bar, plus one small button:
   - Banner: `T3 Craft: your office and agents will be set up in this world.`
   - Button: `✕ Plain world` removes the pending setup and the banner. One click, no confirmation.
5. The pending setup is matched by level id at first server start (§3.3). A changed name still matches, because the pending entry records the *screen* rather than the name: on `CreateWorldScreen#onCreate`, read `uiState.getTargetFolder()` and rewrite the pending key. If that method isn't exposed in 26.3, use one small `@Inject` at the head of `CreateWorldScreen#onCreate`. That inject is the only mixin in this feature, and it is optional: without it, renaming in More options drops the T3 setup and the banner says so.

Optional, P2: on a CreateWorldScreen opened normally, add a 20×20 icon button at bottom-left with the tooltip `Make this a T3 World: office at spawn, agents ready, cheats on`. It turns on the same banner state.

---

## 3. Feature 1: what is set when

### 3.1 At creation: `WorldOpenFlows.createFreshLevel`

Page 1 does not drive `CreateWorldScreen`. It builds the level itself:

```java
String levelId = FileUtil.findAvailableName(minecraft.getLevelSource().getBaseDir(), name, "");
GameRules rules = new GameRules(enabledFeatures);          // 26.3: check constructor (registry-backed rules since 1.21.11)
T3WorldDefaults.apply(rules, mode);                        // §3.2
LevelSettings settings = new LevelSettings(name, mode == CREATIVE ? GameType.CREATIVE : GameType.SURVIVAL,
    /*hardcore*/ false, mode == CREATIVE ? Difficulty.PEACEFUL : Difficulty.NORMAL, /*allowCommands*/ true,
    rules, WorldDataConfiguration.DEFAULT);
WorldOptions options = new WorldOptions(seed, /*generateStructures*/ true, /*bonusChest*/ false);
PendingWorlds.put(levelId, setup);                         // §3.3, before starting the server
minecraft.createWorldOpenFlows().createFreshLevel(levelId, settings, options,
    access -> access.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(),
    parentScreen);
```

These are the 1.21.x Mojang names. 26.x ships unobfuscated with Mojang names, so check each against `./gradlew genSources`. Keep the creation code in one class, `T3WorldCreator`, so any API drift stays in one file.

| Set at creation | How |
| --- | --- |
| Seed | `WorldOptions.seed`. Curated: the entry's long. Surprise me: `WorldOptions.randomSeed()`. Custom: `WorldOptions.parseSeed(text)`, which falls back to `text.hashCode()` like vanilla |
| Game mode, difficulty, cheats, hardcore off | `LevelSettings` |
| Gamerules | `LevelSettings.gameRules` (§3.2) |
| World type | Normal (the vanilla preset). No custom preset |
| Structures on, bonus chest off | `WorldOptions` |
| Data packs | none extra. The mod's `data/t3craft/**` ships in Fabric's always-on mod resources, which is where the office templates come from |

| Set on first server start (setup pass, §3.3) | Why it can't be set at creation |
| --- | --- |
| Office placement and its world `SavedData` | needs the generated terrain |
| World spawn on the office plaza, spawn radius 0 | depends on where the office went |
| Time of day 1000 (morning) and clear weather for 6000 ticks | cosmetic: arrive in daylight |
| First-join teleport + welcome toast | needs the player |

| Set in client config (global, not per world) | |
| --- | --- |
| `agentCommands = true` if checkbox 5 is on | same field as `/t3 agent-build on`. Saved when Create is pressed |
| Pairing | Page 2, via `pairEnvironment` |
| `t3Worlds += {levelId, name, createdAt}` | for the title-screen button and hub |

### 3.2 Gamerules

Since 1.21.11, gamerules are registry entries with snake_case ids. The 26.3 ids below are expected but **must be checked with `/gamerule` tab-completion** before shipping. Apply them through the typed `GameRules` API, never through command strings.

| Rule (legacy name) | 26.3 id (verify) | Creative | Survival | Why |
| --- | --- | --- | --- | --- |
| `spawnRadius` | `respawn_radius` | 0 | 0 | arrive exactly on the plaza |
| `doInsomnia` | `spawn_phantoms` | false | false | long coding sessions without sleeping |
| `mobGriefing` | `mob_griefing` | false | false | creepers don't hole the office |
| `keepInventory` | `keep_inventory` | true | true | dying while reading a diff shouldn't cost your stuff |
| `doWeatherCycle` | `advance_weather` | false | true | Creative stays sunny |
| `doTraderSpawning` | `spawn_wandering_traders` | false | true | wandering traders would be mistaken for residents |
| `sendCommandFeedback` | `send_command_feedback` | **true (leave)** | **true** | agent-build's "every command shows in chat" depends on it |
| `doDaylightCycle` | `advance_time` | true (leave) | true | office lamps already follow real NYC sun; in-game time is free |

Difficulty: Creative → Peaceful, Survival → Normal.

### 3.3 Pending handoff and the setup pass

The client (screen) and the integrated server run in the same JVM, but the server has no client classes. Hand off through common code:

```java
// common (no client imports)
record T3WorldSetup(String levelId, String seedId /*null = random/custom*/, boolean creative,
                    boolean office, long createdAtMillis) {}
final class PendingWorlds {                // static map + persisted copy in config/t3craft.json "pendingWorlds"
  static void put(String levelId, T3WorldSetup s);
  static Optional<T3WorldSetup> take(String levelId);   // removes
}
```

Persisting it covers a crash between creation and the first start. Entries older than 24 h are dropped on load.

`T3CraftCommon.onInitialize()` registers:

- `ServerLifecycleEvents.SERVER_STARTED`:
  `if (server.isDedicatedServer()) return;` (dedicated servers use `/t3office`).
  `levelId = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString()`. This is the same derivation as `T3CraftClient.worldKey()`.
  `setup = PendingWorlds.take(levelId)`. If it is absent, or `T3WorldData.get(server)` already has `setupDone`, return.
  Fresh-world guard: `server.overworld().getGameTime() < 200`.
  Run `T3WorldSetupPass.run(server, setup)` on the server thread. The vanilla progress screen is still showing, so the player never sees a half-built office:
  1. `OfficeSite site = setup.seedId != null ? Seeds.byId(id).site(server) : FlatSpotSearch.find(server.overworld(), spawn)` (§6).
  2. If `setup.office` and the site is not null: `OfficePlacer.place(level, site, design, floors = max(1, pairedCount))`.
  3. `level.setRespawnData(...)` / `setDefaultSpawnPos(site.plaza(), site.plazaYaw())` (check the 26.3 name). If there is no site, keep vanilla spawn.
  4. Time 1000; `setWeatherParameters(6000, 0, false, false)`.
  5. Write `T3WorldData { setupDone = true, seedId, site, design, floors, version }` and mark it dirty.
  6. `T3WorldEvents.OFFICE_PLACED.invoker().placed(levelId, site)`. This is a common-code Fabric `Event`. The client listens and mirrors the result into `config.villages[worldKey]` and `config.officeWorlds`, so the existing client resident path just works (§8.1).
- `ServerPlayConnectionEvents.JOIN`: if `T3WorldData.setupDone && !welcomed(player)`, teleport to the plaza with the yaw facing the door (belt and braces, since the spawn is already there), mark the player welcomed in `T3WorldData`, and send the welcome over the client hook below.

Welcome toast (client, `SystemToast`, one only):

| Case | Title | Body |
| --- | --- | --- |
| paired, office placed | `Welcome to <world name>` | `Your office is right ahead. Press \` to talk to your agents.` |
| unpaired, office placed | `Welcome to <world name>` | `Your office is ready. Press \` to pair a T3 machine and your agents move in.` |
| no flat spot found | `Welcome to <world name>` | `No flat spot near spawn. Stand where you want your office and run /t3 office.` |
| office unchecked | `Welcome to <world name>` | `Press \` to talk to your agents. /t3 office builds them an office.` |

---

## 4. Seed list slot

A separate agent is choosing seeds. The format is fixed here so their result drops straight in. Bundle it as a classpath resource, `src/main/resources/t3craft/seeds.json`. It is read on both sides through `T3CraftCommon.class.getResourceAsStream`, and it is not a datapack, so packs can't change it.

```json
{
  "schema": 1,
  "minecraft": "26.3",
  "seeds": [
    {
      "id": "harbor-hill",                 // stable, used in T3WorldData.seedId
      "name": "Harbor Hill",               // shown as "Place: Harbor Hill" (≤ 18 chars to fit the button)
      "seed": "-4172144997902289642",      // string, parsed to long (JSON can't hold all longs)
      "blurb": "Grassy rise over a quiet bay. Office faces the sunset.",  // ≤ 60 chars, one line at 310 px
      "office": {
        "x": 112, "z": -48,                // door-centre block column, absolute
        "y": null,                         // null = take it from the heightmap at placement; a number overrides it
        "facing": "west",                  // direction the building's view side faces (see §7 "view")
        "dimension": "minecraft:overworld"
      },
      "verified": "2026-11-05 26.3 server: flat ±1, dry, no trees in footprint"
    }
  ]
}
```

Rules:
- Order = cycle order. The first entry is the default.
- `facing` is the direction the office's **view side** looks out to. The door is on the opposite side, and spawn is placed in front of the door. The seed agent picks it by looking at the vista.
- At placement, the recorded origin still goes through a validation pass, the same scoring as §6 restricted to that one spot. If the spot fails, for example because a newer worldgen changed it, it falls back to `FlatSpotSearch` around it. A seed therefore never produces a floating or flooded office.
- Placeholder until the seed agent delivers: ship with `"seeds": []`. The cycle then has only `Surprise me` and `Custom seed`, and the default becomes `Surprise me`. The mockup uses placeholder names.

---

## 5. Feature 2: approach comparison

| | (a) Worldgen structure in a bundled datapack | (b) `StructureTemplate` placement on first start + flat-spot search | (c) `/t3 office` |
| --- | --- | --- | --- |
| "Exactly once, near spawn" | ✗ Structure sets are `random_spread` or `concentric_rings`, both anchored to the chunk grid around (0, 0), not to spawn. Getting exactly one needs a custom `StructurePlacement` type, which means registry code | ✓ placed at an exact spot, once, recorded in `SavedData` | ✓ wherever the player stands |
| Flat, dry spot, best view | ~ Biome filters and `terrain_adaptation: beard_*` only. No view logic | ✓ heightmap scoring, water check, view scoring (§6) | player's choice |
| Floor count = paired machines | ✗ fixed at generation, and worldgen knows nothing about pairing | ✓ placed from the live pairing count | ✓ |
| Grow a floor later | ✗ jigsaw is generation-time only | ✓ same placer appends a module | ✓ |
| Affects other worlds | ✗ every world with the pack generates it in new chunks, including existing worlds | ✓ only worlds with a `T3WorldSetup` | only on command |
| World works without the mod | ✗ structure ids become registry references (warnings or broken structure starts) | ✓ just blocks + one ignorable `.dat` | ✓ |
| Spawn on the plaza | ✗ structure starts are generated after spawn is chosen | ✓ spawn set after placement | n/a |
| Works with a client-only mod on a vanilla server | ✗ | ✗ server side; the command fallback in (c) covers it | ✓ (op needed) |
| Effort | high (custom placement type, jigsaw pools, processors) | medium | low (exists; port to templates) |

**Recommendation: (b) as the engine, with (c) as the manual trigger using the same engine. Do not build (a).** The only thing (a) offers is the office "existing" before the player arrives, and (b) already gives that, because setup runs during the loading screen.

---

## 6. Flat-spot search (any seed without a recorded origin, and seed validation)

Server side, on the server thread. It reads **noise heightmaps without generating chunks**:

```java
ChunkGenerator gen = level.getChunkSource().getGenerator();
RandomState rs   = level.getChunkSource().randomState();
int surface = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
int floor   = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG,  level, rs);
// water depth = surface - floor  (> 0 means water at this column)
Holder<Biome> biome = gen.getBiomeSource().getNoiseBiome(x >> 2, surface >> 2, z >> 2, rs.sampler());
```

Algorithm:
1. Footprint = design footprint rotated, plus the plaza margin (Glasshouse: 24 × 20 + 3 on every side → 30 × 26).
2. Candidates: rings around the vanilla spawn, radius 0 → 160 blocks, step 8. 4 rotations each.
3. Sample the footprint on a 4-block grid (≈ 56 samples). **Reject** if any sample has water depth > 0, any biome is in `#minecraft:is_ocean`, `#is_river`, `#is_beach` or `#is_mountain`, or `max - min` surface height > 4.
4. **Score** (lower is better): `3·(max-min) + 0.02·distanceFromSpawn + 6·treeBiome` (`#is_forest`/`#is_jungle`/`#is_taiga`, since trees need clearing) `- viewScore`.
   `viewScore` for a rotation: sample 9 points 16–48 blocks out from the **view side**, in a 60° cone. Score `= mean(clamp(originY − h, 0, 24)) + 8·(fraction of samples over water)`. Open, downhill or water views win.
5. Take the best candidate. `originY = median(surface samples)`, rounded. The first candidate within 32 blocks with `max-min ≤ 1` and `viewScore > 6` ends the search early.
6. Return `null` if nothing qualifies within 160 blocks. The setup pass then skips placement and the toast says so.

Cost: ≤ 21 rings × ~8–60 candidates × 56 noise samples. These are noise reads, not chunk generation, so expect tens of ms. Benchmark it in a smoke check and cap it at 250 ms. The best candidate so far wins at the cap.

After choosing the site, `OfficePlacer` force-loads the 3×3 to 4×4 chunks it touches with `level.getChunk(cx, cz)`, which generates them synchronously behind the loading screen. Then:
- **clear** the envelope from the plaza level up to the roof top + 2 (removes trees and overhangs),
- **foundation**: for every plaza and footprint column, fill down from `originY − 1` to the first solid non-leaf block with the plaza material (`smooth_stone`) on the perimeter and `dirt` inside, at most 12 blocks. The building never floats on a slope,
- then place the modules.

---

## 7. Modular office templates and layout manifest

### 7.1 Files

```
src/main/resources/data/t3craft/structure/office/glasshouse/lobby.nbt   # ground floor: entrance, reception, decision board, stairs up
src/main/resources/data/t3craft/structure/office/glasshouse/floor.nbt   # one machine: 8 desks, waiting spots, lounge, lectern, stairwell
src/main/resources/data/t3craft/structure/office/glasshouse/roof.nbt    # roof garden, parapet, logo
src/main/resources/data/t3craft/office/glasshouse.json                  # layout manifest (below)
```

Templates are saved from an authoring world with structure blocks, **with air included** so placement clears the interior. Use `structure_void` only where terrain should show through (the plaza edge). They are authored facing **south** (view side at +z, door at −z) with the origin at the inner north-west floor corner, which matches the convention `T3Office` already uses.

### 7.2 Manifest (`office/<id>.json`)

```json
{
  "schema": 1, "id": "glasshouse", "version": 1, "name": "Glasshouse",
  "footprint": { "x": 24, "z": 20 },
  "plaza": { "margin": 3, "block": "minecraft:smooth_stone" },
  "view": "south",                                   // authored direction of the view side
  "door": { "x": 11, "z": -1 },                      // outside the door, ground floor
  "spawn": { "x": 11, "z": -4, "yawToDoor": true },
  "modules": {
    "lobby": { "template": "t3craft:office/glasshouse/lobby", "height": 6 },
    "floor": { "template": "t3craft:office/glasshouse/floor", "height": 6, "max": 8 },
    "roof":  { "template": "t3craft:office/glasshouse/roof",  "height": 4 }
  },
  "floorLayout": {                                   // per floor module, relative to that module's origin
    "desks":   [ {"x":3,"z":5,"yaw":0,"sink":0.3}, {"x":3,"z":9,"yaw":180,"sink":0.3}, "… 8 total" ],
    "waiting": [ {"x":2,"z":12,"yaw":90}, "… 8 total" ],
    "lounge":  [ {"x":16,"z":13,"sink":0.45,"yaw":0}, "… 8 total" ],
    "fridge":  {"x":21,"z":2,"yaw":180},
    "arrival": {"x":12,"z":3},                       // where residents appear (top of the stairwell)
    "machineSign": {"x":11,"y":2,"z":0,"facing":"south"},
    "whiteboard": {"signs":[{"x":2,"y":3,"z":13},{"x":2,"y":3,"z":12}],"facing":"east"},
    "lectern": {"x":19,"z":15},
    "lamps": { "night": [{"x":4,"y":4,"z":7,"block":"minecraft:sea_lantern"}], "day": "minecraft:white_concrete" },
    "walkable": "derive"                             // or an explicit RLE grid
  },
  "lobbyLayout": { "arrival": {"x":11,"z":1}, "reception": {"x":11,"z":5} }
}
```

`"walkable": "derive"`: at load, read the floor template's palette, which works on both client and server. A cell `(x, z)` is walkable if its block at y = 0 is air, carpet or a pressure plate and the block at y = −1 is solid. Desk, lounge and waiting spots are always destinations. This replaces the hand-written `T3Office.blocked()`.

### 7.3 Code

- `OfficeDesign` (common): parsed manifest + module ids. `Designs.get("glasshouse")`.
- `OfficeLayout` (common, pure): built from the design + a `Rotation`. It exposes `desk(floor, i)`, `waiting(...)`, `lounge(...)`, `path(...)` and so on as **world-relative** coordinates. Cells go through `StructureTemplate.transform(pos, Mirror.NONE, rotation, BlockPos.ZERO)`, and yaw gets `rotation × 90°` added. `OfficeBrain` takes an `OfficeLayout` instead of the `T3Office` statics. Keep `T3Office` as the "legacy loft" design for already-built offices until they are rebuilt.
- `OfficePlacer` (common, server):
  `place(ServerLevel, OfficeSite, OfficeDesign, int floors)` · `addFloor(ServerLevel, T3WorldData)` · `relabel(ServerLevel, T3WorldData, int floor, String label)`.
  Placement uses `level.getStructureManager().get(id).orElseThrow().placeInWorld(level, pos, pos, new StructurePlaceSettings().setRotation(r).setIgnoreEntities(true), level.random, Block.UPDATE_CLIENTS)`.
  Module y: lobby at `originY`, floor *i* at `originY + lobby.height + i·floor.height`, roof above the last floor.
- `OfficeCommands` (client): the fallback for a vanilla server. Reads the same `.nbt` from the jar (`NbtIo.readCompressed` on the classpath resource) and the palette, applies the rotation, and turns it into run-length `fill` commands along x, plus `setblock` with NBT for signs and the lectern. These go through the existing `OfficeQueue`, so feedback stays hidden. Expect ~1–2k commands for lobby + floor + roof, about 15 s at 6 per tick, with a progress line in the HUD (`Building office… 42%`).

### 7.4 World state

`T3WorldData extends SavedData` (`data/t3craft_office.dat`, `SavedDataType` with a `Codec`):

```
schema: 1
setupDone: bool          seedId: string?         welcomed: [uuid]
office: { design: "glasshouse", designVersion: 1, dimension, origin: BlockPos, rotation,
          floors: [ { owner: "https://…", label: "MacBook Pro" } | { owner: null, label: "Vacant" } ] }
```

This is the authority for singleplayer worlds. The client mirrors it into `config.villages`/`officeWorlds` via `OFFICE_PLACED` and on join, so existing client code keeps working unchanged. A dedicated server keeps using `config.serverOffice`, but stores the same `office` object next to it (`config.serverOfficeLayout`).

---

## 8. Residents, growth and constraints

### 8.1 How residents appear

- **Singleplayer:** residents stay **client-side**, using the existing `T3Village` office mode. The pairing tokens live in client config, and only you are in the world. The client gets the origin and rotation from `OFFICE_PLACED`, or on join reads `T3WorldData` through `minecraft.getSingleplayerServer()` (same JVM, accessed on the server thread with `server.submit(...).join()`).
- First arrival: once a machine is paired and threads sync, each resident spawns at its floor's `arrival` cell (the top of the stairwell; lobby `arrival` for floor 0's first walk). It then walks to its zone, as the existing `OfficeBrain` door/ladder entry does. In an unpaired office, the lobby reception sign reads `Press \` to pair a T3 machine`. The setup pass writes it, and `relabel` clears it once paired.
- **Dedicated server with the mod:** `ServerOffice` real villagers, unchanged except that positions come from `OfficeLayout`.
- **LAN:** guests see no residents, because they are client-side on the host. This is a known limit. Later work could spawn real villagers on the integrated server, but that is out of scope.

### 8.2 Adding a machine later (grow, don't rebuild)

Trigger: `pairEnvironment` succeeds with a new owner while in a world whose `T3WorldData.office` exists.

1. If a `Vacant` floor exists, assign the machine to it: `relabel` only, with no block changes beyond the sign.
2. Otherwise `OfficePlacer.addFloor`:
   - clear the current roof module's volume (air),
   - place `floor.nbt` at the next floor y,
   - place `roof.nbt` above it,
   - set the floor's machine sign,
   - append to `floors` and save.
   Lower floors and everything the player built around the office are untouched. **Roof edits are lost when the roof moves up.** Document it in the README, and in `/t3 office` output if the roof differs from its template. Optional later: capture the roof region into a temp template and re-place it.
3. Cap: `floor.max` (8). Past the cap, the machine's residents use the lobby lounge and the decision board, with no desks, and chat says `Office is at 8 floors. This machine's agents share the lobby.`
4. Singleplayer: the client runs this as `server.execute(() -> OfficePlacer.addFloor(...))`. Vanilla server: `OfficeCommands` sends only the new floor + roof commands. Dedicated with the mod: `ServerOffice` calls `OfficePlacer.addFloor` when `/t3office pair` adds an environment.
5. Removing a machine: its floor becomes `Vacant` (sign updated, residents leave). It never shrinks automatically. Order is stable: floors are keyed by owner and never re-sorted, which keeps the README promise that "re-pairing or renaming keeps floor order".

### 8.3 Client-only vs server

| Where the mod runs | Create T3 World | Office placement | Grow | Residents |
| --- | --- | --- | --- | --- |
| Singleplayer (client jar; the integrated server runs the `main` entrypoint) | ✓ | ✓ templates + search, during the loading screen | ✓ | client-side villagers |
| Client only, on a vanilla/Paper server | n/a | `/t3 office` → `OfficeCommands` fallback (needs op) | ✓ via commands (op) | client-side villagers |
| Dedicated server with the mod | n/a (server.properties). Operator may set `level-seed` from `seeds.json` and run `/t3office auto` (P2: places with seed origin or search) | ✓ `/t3office build` = templates at the player's spot | ✓ | real server villagers |
| Server with the mod, client without | n/a | ✓ | ✓ | real villagers, visible to everyone |

`fabric.mod.json` change: `"entrypoints": { "main": ["dev.maxwellyoung.t3craft.T3CraftCommon"], "client": [...], "server": [...] }`. `T3CraftCommon` must import no `net.minecraft.client.*` class. Keep it to server and common code (`T3WorldData`, `PendingWorlds`, `OfficePlacer`, `FlatSpotSearch`, `Seeds`, `OfficeDesign`, `T3WorldEvents`).

### 8.4 Unpaired at creation

The office is placed with lobby + **1** floor labelled `Vacant` + roof, so the building looks complete. The first machine paired takes that floor with `relabel`, without placing a new one.

---

## 9. `/t3 office` for existing worlds (c)

- **Singleplayer:** builds at the player's spot, which keeps today's muscle memory: door ~5 blocks ahead, view side away from the player. Uses `OfficePlacer` on the integrated server, with no op check because singleplayer cheats are implied. Writes `T3WorldData`. If an office already exists, it **moves** it: clear the old envelope to air, then place at the new spot. No confirmation. Chat says `Office moved here. /t3 office back to undo` (P2: `back` re-places at the previous origin).
- **`/t3 office auto`** (new): runs `FlatSpotSearch` around the player instead of using their exact spot. Chat: `Office built 38 blocks north-east, facing the valley. /tp'd you to the door.`
- **Vanilla server:** `OfficeCommands`. Same copy as today: `Building your office in front of you. Your agents move in once it's done… Needs command permission (op).`
- **Legacy offices** (built by 0.5 `T3Office.blueprint`): keep working with the `T3Office` layout until `/t3 office` is run again.

---

## 10. Implementation plan (ordered)

1. **Common entrypoint.** `T3CraftCommon` + `fabric.mod.json` `main`. Move `Seeds`, `PendingWorlds`, `T3WorldEvents` into it. Smoke: the integrated server logs `T3 Craft common ready`.
2. **Office data model.** `OfficeDesign` manifest parsing, `OfficeLayout` with rotation, and `walkable: derive`. Port `OfficeBrain` to it, keeping a `T3Office` legacy adapter. Smoke: extend `OfficeChecks` with rotated paths for all 4 rotations, every desk reachable from `arrival`.
3. **Templates.** Export Glasshouse lobby, floor and roof `.nbt` from the design's `blocks.json`. Either write a small generator in `scripts/` that emits `.nbt` with the NBT structure format (palette + blocks + size), or build it in a dev world and save it with structure blocks. Commit the manifest.
4. **`OfficePlacer`** (place / addFloor / relabel) + **`T3WorldData`**. Smoke: place in a superflat test world, assert blocks at the manifest's sign/lectern positions for every rotation.
5. **`FlatSpotSearch`** with timing. Smoke: against 3 fixed seeds, assert non-null, no water under the footprint, < 250 ms.
6. **Setup pass** (`SERVER_STARTED` + `JOIN`), spawn, weather, time, welcome toast hook.
7. **`T3WorldScreen`** pages 1–2, `T3WorldHub`, title and world-list buttons, `T3WorldCreator`. Refactor `PairingForm` out of `T3ConnectionsScreen`.
8. **"More options…"** prefill + banner (+ the optional `onCreate` inject).
9. **Growth** on pairing; Vacant handling; cap.
10. **`/t3 office`** port to `OfficePlacer`; `OfficeCommands` fallback for vanilla servers; `/t3 office auto`.
11. **README + FIRST_SESSION:** the new first-run path ("Title screen → T3 World → Create"), the roof-edit note, the LAN note.

Acceptance (manual, in the real client):
- A fresh profile, paired: Title → T3 World → Create T3 World. You spawn on the plaza facing the door within the normal world-load time, and agents walk to their desks within ~10 s of loading.
- Unpaired → Skip: the office has one Vacant floor and the welcome toast says press `. Pair via ` and the residents arrive on that floor, with no blocks rebuilt.
- Pair a second machine in that world: one floor is added and the roof moves up. A block you placed by the lobby door is still there.
- Surprise me ×5: the office is never in water, never floating more than 1 block before the foundation fill, and never more than 160 blocks from vanilla spawn.
- Open the world without the mod: it loads with no registry errors, and the office is just blocks.
- No new confirmation dialog anywhere in the flow.

---

## 11. Open items

- **Seeds:** waiting on the seed agent. `seeds.json` ships empty and the UI defaults to Surprise me (§4).
- **Office design:** only Glasshouse exists so far. The other two concepts must provide lobby/floor/roof + manifest to be selectable. A design picker is out of scope; ship one design.
- **26.3 API names to verify with `genSources`:** `WorldOpenFlows.createFreshLevel` / `openWorld`, `CreateWorldScreen.openFresh`, `WorldCreationUiState#setAllowCommands` / `getTargetFolder`, `GameRules` construction and ids, `ServerLevel#setRespawnData` vs `setDefaultSpawnPos`, `SavedDataType`. All exist in some form in 1.21.x. Isolate them in `T3WorldCreator` / `T3WorldSetupPass`.
- **`agentCommands` is global**, so ticking it on affects all worlds. That matches today's `/t3 agent-build`. Making it per world is a separate change, and the owner's no-gates stance argues against adding one.
