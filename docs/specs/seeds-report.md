> **Status:** verified 2026-11-05. Default and alternates adopted (see [Decisions](../DECISIONS.md#2026-11-05--plains-seed-by-default)). Pictures: [docs/design/world.md](../design/world.md). The coordinates the mod actually uses are in [Recorded office sites](#recorded-office-sites) below; the per-seed "office origin" lines are the survey's 24 × 20 boxes.

# Verified Java 26.3 seed shortlist

Generated and measured using the official vanilla 26.3 server, JDK 25. Fifteen distinct seeds evaluated: ten published leads and five pseudorandom seeds. All 256×256 maps contain 65,536 measured columns, with no missing data.

Recommended default: `-2085440121970745551`.

## Recorded office sites

The survey below measured axis-aligned 24 × 20 boxes (origin = minimum-X, minimum-Z corner) to rank seeds. The mod records a different thing in `src/main/resources/t3craft/seeds.json`: the office **origin** in design A's own convention (the inside floor corner of the ground floor, see [office-design-a/spec.md](office-design-a/spec.md)) and the direction its glazing **faces**; the building, plaza included (34 × 32 blocks), is turned about that cell. These sites were verified in-game with the full footprint (`./gradlew runClient -Pworldtest=<place>`, pictures in [world.md](../design/world.md)) and are the numbers to use. Placement re-checks them against the generator's heightmaps and searches nearby if worldgen has drifted.

| Place | Seed | Recorded origin | Glazing faces | Survey box (this report) |
| --- | --- | --- | --- | --- |
| Lakeside | `-2085440121970745551` | (8, 65, -2) | east | (-5, 65, -21), face east |
| Cherry Plains | `-4474845086078965121` | (44, 63, 7) | west | (24, 64, 10), face west |
| River Bend | `3478701220631481329` | (7, 64, -69) | north | (6, 65, 9), face north |

Office origin is the northwest/min-X/min-Z floor corner; footprint is 24 along +X by 20 along +Z. Y is one above the highest existing surface block. All 480 columns are dry natural ground and clear of trees/buildings; ordinary grass/flowers need clearing. Fill lower ground to Y−1. Every footprint corner lies within 64 horizontal blocks of saved world spawn. Facing identifies the front/view, and does not redefine the footprint. Spawn coordinates are the saved world spawn, not a promise of an exact joining-player location.

## 1. -2085440121970745551 — Lakeside plains and forest edge — recommended default

An open green start with a broad river-water basin east of the office and a wooded southern edge. The best balance of a comfortable spawn, scenery, and easy automatic construction. The office is essentially beside spawn, with no trees or water in its footprint.

- Spawn: `(0, 65, 0)`.
- Survey box origin: `(-5, 65, -21)`; face **east**; center 13.2 blocks from spawn. Recorded site: origin `(8, 65, -2)` facing east (see above).
- Flatness: ground Y 63–64; range 1 blocks; standard deviation 0.431; 118 foundation-fill blocks; 480/480 dry columns.
- Nearby features: Broad water basin east (river located at 64,65,0; 64 blocks from spawn); forest edge south, about 76 blocks from office center; windswept hills within the mapped area. Village at -176,~, -480 (511 blocks from spawn). Cherry grove 1,229 blocks away and jagged peaks 830 blocks away: neither is advertised as an immediate spawn view. A ruined portal/lava area lies farther southeast in the forest, outside the office.
- PNG: see the map in [docs/design/world.md](../design/world.md).

## 2. -4474845086078965121 — Open plains with cherry trees and a nearby village

Broad, mostly treeless plains with cherry trees on the western side, forest to the northeast and village buildings to the southeast. The office patch is especially uniform. There are cave openings elsewhere near spawn; keep paths away from those holes. Water is farther away than in the first and third choices.

- Spawn: `(0, 66, 0)`.
- Survey box origin: `(24, 64, 10)`; face **west**; center 40.5 blocks from spawn. Recorded site: origin `(44, 63, 7)` facing west, where the placement search put design A (the survey box is too small for it).
- Flatness: ground Y 62–63; range 1 blocks; standard deviation 0.162; 467 foundation-fill blocks; 480/480 dry columns.
- Nearby features: Cherry grove located at -128,66,0 (128 blocks from spawn), west-facing pink-tree view; village at 144,~,64 (157 blocks); river at 64,66,-128 (143 blocks), with mapped water about 100 blocks north of office center. No nearby peak biome: frozen peaks 2,056 blocks away.
- PNG: see the map in [docs/design/world.md](../design/world.md).

## 3. 3478701220631481329 — Riverside plains below a cherry hill

Open plains beside a broad river bend, with a cherry-covered hill south of the office. Face the front north across the water and use south-facing upper-floor windows for the cherry hill. The clear patch needs modest two-block grading; the spawn and office are on the low plains, not atop the hill.

- Spawn: `(-16, 68, 16)`.
- Survey box origin: `(6, 65, 9)`; face **north**; center 33.6 blocks from spawn. Recorded site: origin `(7, 64, -69)` facing north on the bend, where the placement search put design A.
- Flatness: ground Y 62–64; range 2 blocks; standard deviation 0.608; 620 foundation-fill blocks; 480/480 dry columns.
- Nearby features: Mapped water 13.5 blocks from office center; river located at -48,68,-16 (45 blocks from spawn). Cherry grove located at 16,68,80 (71 blocks from spawn), with its mapped edge about 37 blocks from office center. Village at -176,~,176 (226 blocks). No nearby peak biome: frozen peaks 2,404 blocks away.
- PNG: see the map in [docs/design/world.md](../design/world.md).

## Scope and evidence

Vanilla verification only. No T3 Craft repository or Minecraft installation was inspected or changed; world-generation mods/datapacks may change these results. No in-game perspective renders were made, so view-facing recommendations are inferred from measured maps and biome locations. The scripts inspect actual block-state palettes and biome palettes from saved Anvil regions. Server `/locate` outputs are retained verbatim in each analysis JSON and server log.

26.1 has technical world-generation/provider changes; 26.2 adds sulfur caves and surface springs; 26.3 adds dappled forest and abandoned camps. Older seed-list claims were therefore treated only as leads. Sources and method are below.

All servers stopped and generated world directories deleted. The measurements and maps were kept for auditing; the maps are in `docs/images/seed-*.png`.

## Other evaluated seeds

- `2048005618087379093`: beautiful cherry-ring meadow, office `(-10,104,-21)`, one-block range and only 25 fill blocks. Excluded from the main shortlist because nearby water is at Y62 while the patch ground is Y102–103; steep banks conflict with the comfortable-terrain brief.
- `-4995054936707021313`: two-block patch, but office floor Y93 versus spawn Y105; hillier and less immediate water access.
- `3569855217892442351`: seven-block office height range.
- `5323346537427257204`: three-block range and substantial surrounding relief.
- `-2950580325944898288`: fourteen-block patch range; too steep.
- `301057310337194311`: icy shore/taiga, eleven-block patch range.
- `2898023729702160184`: one-block patch, but desert/badlands coast rather than the requested green landscape.
- `-4518760718010806170`: four-block patch; forest, hills and swamp nearby.
- `-4404213973234970907`: no unobstructed dry 24×20 patch satisfying the distance constraint.
- `2465799522528499105`: three-block patch, office floor Y93 versus spawn Y116.
- `6844742820798046092`: attractive green biomes, but three-block patch range.
- `1159732188867385867`: three-block patch, office floor Y66 versus spawn Y100.

---

## Sources and method

Official version metadata: https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
26.3 metadata: https://piston-meta.mojang.com/v1/packages/80608ff6753705b6a82e2cb86a5937d4d233abd4/26.3.json
Server SHA1 verified: 33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c
Official release notes: https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3
26.3 adds dappled forest and abandoned camps, so prior-version seed descriptions are not treated as verification. All evaluated worlds use the exact official 26.3 server.

Published candidate leads (claims independently tested in generated worlds):
- -4474845086078965121: https://ezseed.net/popular/seed/26.3-Java/-4474845086078965121/
- 2048005618087379093 and -4995054936707021313: https://gamingroute.com/best-minecraft-seeds/
- 3569855217892442351: https://god-seed.com/popular/seed/3569855217892442351/
- 5323346537427257204: https://www.reddit.com/r/minecraftseeds/comments/1x8qwnj/looking_for_this_seed_plains_village_next_to_a/
- -2950580325944898288: https://www.techradar.com/pro/the-best-minecraft-ideas-for-spring-break-in-2026
- Five independent pseudorandom seeds: Python random.Random(2631105), signed 64-bit range. Values in candidates.json.

Maps: north up, 3 pixels/block, 256x256 blocks centered on saved world spawn; red dot = saved spawn, white rectangle = 24 blocks along X by 20 along Z. Stylized block colors, with slope shading, not screenshots. Trees are rendered at canopy height. Noncolliding common flowers, grass and thin snow are ignored. Bushes and all other non-ground blocks conservatively exclude an office patch. Each office footprint corner is <=64 horizontal blocks from saved spawn.

Office origin means minimum X, minimum Z corner, Y one above the highest surface block. Place foundation/support beneath the floor to fill low spots; fill_blocks counts blocks required to bring the ground up to the highest column, excluding the office floor itself. Rotation must preserve the stated 24X by20Z world-axis footprint. Height standard deviation is population standard deviation across all 480 columns. Search minimizes height range, then standard deviation with a small distance penalty. These are vanilla terrain checks, not a T3 Craft modpack integration test. Actual joining-player spawn may vary within the vanilla spawn radius.

Each world is stopped cleanly, parsed from saved Anvil region files, then deleted. Per-seed server logs, spawn JSON, measured analysis, compressed surface arrays, and maps are retained to audit results without retaining worlds.

Additional 26.x changelog review:
- https://www.minecraft.net/en-us/article/minecraft-java-edition-26-1 — technical feature/provider changes; not a basis for claiming byte-identical generation.
- https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2 — adds sulfur caves and surface sulfur springs.
- 26.3 then adds dappled forest and abandoned camps. No cross-version equivalence is assumed in this report.

Final four candidate leads:
- 2465799522528499105, 3478701220631481329, 6844742820798046092: https://ezseed.net/popular/best/cherry-grove-java-26-3/
- 1159732188867385867: https://chunkscan.com/seeds/cherry-grove-village
