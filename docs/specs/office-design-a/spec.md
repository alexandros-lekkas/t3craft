> **Status:** chosen design (2026-11-05). Summary with pictures: [docs/design/office.md](../../design/office.md). Preview renders: [contact sheet](../../images/office-contact-sheet.png). Open `preview.html` locally for the interactive 3D view.

# T3 Craft agent office — Design A: Scandi Studio

A warm, modern Scandinavian studio: a white-concrete box with floor-to-ceiling south glazing, birch mullions and floors, a slatted birch brise-soleil on the east face, white floor bands with a quartz sun lip, and a planted rooftop terrace with a pergola and a birch tree. Inside: one open studio per floor with an 8-desk white bench under linear pendants, a kitchenette and island, a framed whiteboard on a wood-framed wall, a sunken-feel lounge (L-sofa, rug, candles, sage armchairs) facing the glass, and a library review nook.

Files next to this spec:

| File | What it is |
|---|---|
| `preview.html` | Self-contained three.js (cdnjs `three.js/0.170.0`, pinned) voxel preview. Its voxels come from the same generator as this spec. Views: Exterior / Ground floor / Floor 2 cutaways, day/night lamps, name tags, anchor markers. URL hash: `#view=ext\|f0\|f1&night=1&anchors=1&labels=0`. |
| `preview.png` | 2×2 contact sheet (exterior day, ground cutaway, floor 2 at night, exterior night). Single views: `preview-*.png`. |
| `blocks-2floors-day.mcfunction` | Exact setblock list for a 2-floor build in day state (3,305 blocks, relative `~x ~y ~z` from the office origin). Pane and fence connections are already resolved. |
| `anchors.json` | Every anchor and lamp from the tables below, as JSON. |

The generator (`OfficeGen.build(floors, {night})`, inline in `preview.html`) is the source of truth. If this document and the generator disagree, the generator wins. It can be ported to Java line by line.

---

## 1. Coordinates and dimensions

The origin is the same as in today's `T3Office`: **(0,0,0) is the inside floor corner of the ground floor, and y=0 is the first air layer.**

| | |
|---|---|
| Interior | x 0..21 (22), z 0..17 (18) |
| Walls | west x=-1, east x=22, north z=-1 (entry side), south z=18 (glass side) |
| Footprint | 24 × 20 walls, plus the fins at x=23 and the sun lip at x=-2 / z=19, so 26 × 21 overall |
| `FLOOR_HEIGHT` | **6**. Floor *n* has slab y = 6n-1 and air layers y = 6n … 6n+4 (5 clear blocks) |
| Floors | 1–4, one per paired machine, all identical (floor 0 adds the entry door, the doormat and the exterior pieces) |
| Roof deck | slab y = 6F-1; terrace items from y = 6F |
| Facing / yaw | MC yaw: 0 = +z (south), 90 = -x (west), 180 = -z (north), -90 = +x (east) |

All local coordinates below are **floor-relative** (add `6·n` to y). Exterior items are absolute for floor 0.

---

## 2. Palette (vanilla 26.3 block IDs)

| Role | Block state |
|---|---|
| Floor / ceiling field | `birch_planks` |
| Slab ring (floor bands) | `white_concrete` |
| Corner columns, south mullions (x=7, x=14), north column (x=5, x=11, x=16), east fins | `stripped_birch_log[axis=y]` |
| Solid walls (west, north kitchen/core, east) | `white_concrete` |
| Glazing | `glass_pane[...]` (connections precomputed) |
| Sun lip (south z=19, west x=-2 at every upper slab level) | `smooth_quartz_slab[type=top]` |
| Entry door (floor 0, x17–18, z=-1) | `birch_door[facing=south,half=lower/upper,hinge=right/left,open=false]` |
| Entry canopy | `smooth_quartz` (x16–19, y4, z-3..-2) |
| Path | `smooth_stone` (x16–19, y-1, z-6..-2) |
| Desk top | `smooth_quartz_slab[type=top]` |
| Monitor | `dark_oak_trapdoor[facing=north\|south,half=bottom,open=true]` (the open trapdoor stands on the far edge of the desk; it faces the person sitting) |
| Desk chair | `pale_oak_stairs[facing=north\|south,half=bottom,shape=straight]` (facing = the side the chair back is on) |
| Bench rug, lounge rug border, nook rug, roof rug | `light_gray_carpet` |
| Lounge rug field | `white_carpet` |
| Sofa | `polished_andesite_stairs[facing=north]` ×5, corner `[facing=north,shape=inner_left]`, return `[facing=west]` ×3 |
| Armchairs (accent) | `waxed_oxidized_cut_copper_stairs[facing=east\|north]` |
| Tables, shelves, tray | `birch_slab[type=top]`; window bench `birch_slab[type=bottom]` |
| Shelving | `bookshelf`, `chiseled_bookshelf[facing=west,slot_*_occupied=…]` |
| Whiteboard | `white_concrete` board, `stripped_birch_wood[axis=y]` frame, `birch_wall_sign[facing=east]` ×6 |
| Floor sign | `birch_wall_sign[facing=west]` |
| Kitchen | `pale_oak_planks` cabinets, `water_cauldron[level=3]` sink, `brewing_stand` coffee machine, `iron_block` ×2 fridge, `decorated_pot` |
| Island | `stripped_birch_wood[axis=x]` |
| Stairs | `birch_stairs[facing=south,half=bottom,shape=straight]` |
| Balustrade / guards | `glass_pane` |
| Lectern | `lectern[facing=west,has_book=false,powered=false]` |
| Pendant hangers | `iron_chain[axis=y]` (renamed from `chain` in 1.21.9) |
| Lamps (day ↔ night) | see §6: `iron_chain[axis=x]` ↔ `end_rod[facing=east]`; `white_wool` ↔ `ochre_froglight`; `iron_chain[axis=y]`/`air` ↔ `lantern[hanging=…]`; `*_candle[lit=false]` ↔ `[lit=true]` |
| Always-on light | `light[level=15]` grid at y2, `end_rod` downlights and sconces, parapet and bollard `lantern`s (see §6) |
| Plants | `decorated_pot` + `azalea_leaves[persistent=true]` (+ `flowering_azalea_leaves[persistent=true]`); `potted_fern`, `potted_azalea_bush`, `potted_flowering_azalea_bush`, `potted_cactus` |
| Roof planting | `moss_block` + `azalea`, `flowering_azalea`, `fern`, `short_grass`, `firefly_bush`; tree `birch_log[axis=y]` + `birch_leaves[persistent=true]` |

Block counts for 2 floors: birch_planks 1172, white_concrete 602, glass_pane 491, stripped_birch_log 263, everything else under 110 each.

---

## 3. Floor plans

Legend: `#` white concrete · `I` birch column/fin · `=` glass pane · `D` door · `d` desk (quartz slab, monitor above) · `v`/`^` desk chair (back to the north / south) · `s` sofa · `a` armchair · `t` top-slab table/shelf/tray · `b` window bench · `L` lectern · `B` bookshelf · `P` potted tree · `k` cabinet · `K` sink · `F` fridge · `w` birch wood (whiteboard frame, island) · `o` floor-lamp post · `:` light-grey carpet · `'` white carpet · `>` birch stair (rises south) · `m` moss planter · `p` plant · `.` birch floor · blank = void (stair well).

The east fins (`I` at x=23, z = 0,2,4…16, full height) are outside the drawn grid.

```
GROUND FLOOR (floor 0) — layer y=0 (furniture), walls drawn at y=1
        x→ 901234567890123456789012
  z= -1 I=====I=====I####IDD###I
  z=  0 #P.........PkkKkkF::...#
  z=  1 =B.::::::..............#
  z=  2 =B.:vvvv:...........=>>#
  z=  3 =B.:dddd:....wwww...=..#
  z=  4 IB.:dddd:...........=..=
  z=  5 =B.:^^^^:...........=..=
  z=  6 =B.::::::...........=..#
  z=  7 =B.............P....=..#
  z=  8 #BBBB.............P....#
  z=  9 #w.......BBBBB.........#
  z= 10 #t....................B#
  z= 11 #t................:::.B#
  z= 12 #t......sssssst...:L:.t=
  z= 13 #w......s::::::a..:::.a=
  z= 14 =P......s:ttt':o......t=
  z= 15 =.......s:'''':a......B#
  z= 16 =.......t::::::.....a.B#
  z= 17 =bbbbbP..........P....P#
  z= 18 I=======I======I=======I
```

```
UPPER FLOOR (floor 1, y=6) — identical except glazed entry bay and the stair-well hole at x20..21 z4..6
        x→ 901234567890123456789012
  z= -1 I=====I=====I####I==###I
  z=  0 #P.........PkkKkkF.....#
  z=  1 =B.::::::..............#
  z=  2 =B.:vvvv:...........=>>#
  z=  3 =B.:dddd:....wwww...=..#
  z=  4 IB.:dddd:...........=  =
  z=  5 =B.:^^^^:...........=  =
  z=  6 =B.::::::...........=  #
  z=  7 =B.............P....=>>#
  z=  8 #BBBB.............P....#
  z=  9 #w.......BBBBB.........#
  z= 10 #t....................B#
  z= 11 #t................:::.B#
  z= 12 #t......sssssst...:L:.t=
  z= 13 #w......s::::::a..:::.a=
  z= 14 =P......s:ttt':o......t=
  z= 15 =.......s:'''':a......B#
  z= 16 =.......t::::::.....a.B#
  z= 17 =bbbbbP..........P....P#
  z= 18 I=======I======I=======I
```

```
ANCHOR OVERLAY (floor 0): 1–8 = desk chairs (slot order), N/n = whiteboard waiting spots
        x→ 901234567890123456789012
  z= -1 I=====I=====I####IDD###I
  z=  0 #P.........PkkKkkF::...#
  z=  1 =B.::::::..............#
  z=  2 =B.:1234:...........=>>#
  z=  3 =B.:dddd:....wwww...=..#
  z=  4 IB.:dddd:...........=..=
  z=  5 =B.:5678:...........=..=
  z=  6 =B.::::::...........=..#
  z=  7 =B.............P....=..#
  z=  8 #BBBB.............P....#
  z=  9 #w.......BBBBB.........#
  z= 10 #t.nn.................B#
  z= 11 #t.Nnn............:::.B#
  z= 12 #t.nnn..sssssst...:L:.t=
  z= 13 #w......s::::::a..:::.a=
  z= 14 =P......s:ttt':o......t=
  z= 15 =.......s:'''':a......B#
  z= 16 =.......t::::::.....a.B#
  z= 17 =bbbbbP..........P....P#
  z= 18 I=======I======I=======I
```

Zones (floor-relative x/z): **work** x2–7 z1–6 · **kitchen** x10–16 z0–4 · **whiteboard** x0–4 z9–13 · **lounge** x7–14 z12–16 · **review nook** x16–21 z10–17 · **stair core** x19–21 z1–8.

### Walls (per floor, y 0..4)

| Facade | Layout |
|---|---|
| South z=18 | x0–21 glass y0–4 (floor to ceiling); birch mullions at x=7, 14; corners birch logs |
| West x=-1 | z0 concrete · z1–7 ribbon: y0 concrete, y1–3 glass (birch mullion at z=4), y4 concrete · z8–13 concrete (behind the whiteboard) · z14–17 glass y0–4 (wraps the lounge corner) |
| North z=-1 | x0–10 ribbon (y1–3 glass, mullion x=5) · x11, x16 birch columns · x12–15 concrete with clerestory glass at y3 · x17–18 entry: floor 0 has the double birch door y0–1 and glass y2–3; upper floors have glass y0–3 · x19–21 concrete (stair core) |
| East x=22 | concrete; full-height glass slot z4–5 (lights the stair); glass z12–14 y1–3 (nook window seat). Fins at x=23 every 2nd z, y 0 … 6F-1 |
| Slab ring | `white_concrete`, corners `stripped_birch_log`; on every upper slab there is a `smooth_quartz_slab[type=top]` lip at z=19 (x-2..22) and x=-2 (z-1..18) |

---

## 4. Elevation and section

South elevation (2 floors, looking north; `-` = quartz lip, `?`/`p` = planters):

```
        x→ 89012345678901234567890123
  y= 18
  y= 17                  ***
  y= 16                  ***
  y= 15      IIIIIIIII  *****
  y= 14      I       I    I
  y= 13   pppppppppppppppppppppp
  y= 12  I=======I======I=======I
  y= 11 -------------------------I
  y= 10  I=======I======I=======II
  y=  9  I=======I======I=======II
  y=  8  I=======I======I=======II
  y=  7  I=======I======I=======II
  y=  6  I=======I======I=======II
  y=  5 -------------------------I
  y=  4  I=======I======I=======II
  y=  3  I=======I======I=======II
  y=  2  I=======I======I=======II
  y=  1  I=======I======I=======II
  y=  0  I=======I======I=======II
  y= -1  I######################I
```

Section through the stair at x=20 (looking west). Each floor's straight run of six `birch_stairs[facing=south]` goes from (x20–21, y0, z2) to (x20–21, y5, z7). The 6th step **sits inside the slab above** and is flush with the next floor. The slab above is open at x20–21, z4–6 (well). Runs stack identically, so a run floats above the one below (cantilevered look). The top floor's run comes out on the roof.

```
        z→ 901234567890123456789
  y= 18
  y= 17
  y= 16
  y= 15
  y= 14
  y= 13                   p
  y= 12 =   =             m=
  y= 11 #....   >..........#-
  y= 10 #      >           =
  y=  9 #     >            =
  y=  8 #    >             =
  y=  7 #   >              =
  y=  6 #  >               =
  y=  5 #....   >..........#-
  y=  4 #      >           =
  y=  3 #     >            =
  y=  2 #    >             =
  y=  1 #   >              =
  y=  0 #  >               =
  y= -1 #..................#
```

Clearance check: on step *k* (z=2+k) the player occupies y k+1..k+2. Steps k=2..4 need the well (z4–6) open, and the run above only occupies y ≥ 6+k at that z, so there is always ≥2 clear blocks.

---

## 5. Interactive anchors (floor-relative; add y = 6·floor)

### WORK — 8 desks (slot order = today's stable desk slots)

| Slot | Chair (x,z) | Yaw | Sink | Desk (x,z) | Monitor (x,y,z) |
|---|---|---|---|---|---|
| 1 | 3,2 | 0 | 0.3 | 3,3 | 3,1,3 `facing=north` |
| 2 | 4,2 | 0 | 0.3 | 4,3 | 4,1,3 |
| 3 | 5,2 | 0 | 0.3 | 5,3 | 5,1,3 |
| 4 | 6,2 | 0 | 0.3 | 6,3 | 6,1,3 |
| 5 | 3,5 | 180 | 0.3 | 3,4 | 3,1,4 `facing=south` |
| 6 | 4,5 | 180 | 0.3 | 4,4 | 4,1,4 |
| 7 | 5,5 | 180 | 0.3 | 5,4 | 5,1,4 |
| 8 | 6,5 | 180 | 0.3 | 6,4 | 6,1,4 |

Chairs are stairs (seat top at y+0.5). `sink` uses the current `Spot` semantics, and 0.3 matches today's office chairs. Typing particles/sounds should come from the monitor block.

### NEEDS_YOU — whiteboard (west wall)

- Board: `white_concrete` x0, y1–2, z10–12. Frame: `stripped_birch_wood` x0 z9 & z13 y0–2, and y3 z9–13. Marker tray: `birch_slab[type=top]` x0 y0 z10–12.
- Signs (`birch_wall_sign[facing=east]`, attached to the board): **row 1 y=2**: left z=12 (title "NEEDS YOU (n)" + thread), middle z=11, right z=10 (detail). **Row 2 y=1**: z=12, 11, 10 (overflow). Viewers face west, so +z is on their left.
- Picture light above the board: (1,3,11), see lamps.
- Waiting spots, all yaw 90 (facing the board), sink 0, in fill order: (2,11) (2,12) (2,10) (3,11) (3,12) (3,10) (4,11) (4,12).

### LOUNGE — finished agents (8 primary + overflow)

| # | Spot (x,z) | Yaw | Sink | Seat |
|---|---|---|---|---|
| 1 | 10,12 | 0 | 0.3 | sofa centre |
| 2 | 9,12 | 0 | 0.3 | sofa |
| 3 | 11,12 | 0 | 0.3 | sofa |
| 4 | 8,12 | 0 | 0.3 | sofa |
| 5 | 12,12 | 0 | 0.3 | sofa |
| 6 | 7,14 | -90 | 0.3 | sofa return |
| 7 | 14,13 | 90 | 0.3 | sage armchair |
| 8 | 7,13 | -90 | 0.3 | sofa return |

Overflow, in order: (7,15) yaw -90 sofa · (14,15) yaw 90 armchair · (21,13) yaw 90 nook window seat · (19,16) yaw 0 nook armchair facing the glass · (13,4) and (14,4) yaw 180 standing at the island · (1,17) and (3,17) yaw 180 window bench (sink 0.45 onto a bottom slab).
FRIDGE spot (grab a drink on the way to the sofa): (16,1) yaw 180.

### Other anchors

| Anchor | Position | Notes |
|---|---|---|
| Review lectern | block (18,0,12) `facing=west` | reader stands at (17,12) yaw -90. Light-grey rug x17–19 z11–13. Pendant overhead |
| Floor label sign | (21,2,9) `birch_wall_sign[facing=west]` | on the east wall at the stair arrival. Line 1 `FLOOR n`, line 2 machine name |
| Stair up | bottom landing (20,1) → first step (20–21, 0, 2) → top step (20–21, 5, 7) | replaces `LADDER` |
| Arrival (from the floor below) | (20,8) yaw 0 | replaces "come up the ladder" |
| Entry door (floor 0) | inside (17,0); outside (17,-2) | replaces `DOOR = {10,0}` |
| Exterior sign | (19,2,-2) `birch_wall_sign[facing=north]` | under the canopy: "T3 OFFICE" |
| Roof arrival | (20, 6F, 8) | benches under the pergola: x5–9 z10 (yaw 0) and z14 (yaw 180), sink 0.3 |

### Blocked cells for pathing

Mark every y=0 cell holding something other than carpet, sign or light as blocked: desks, chairs (as destinations only), credenza x0 z1–7, dividers x0–3 z8 and x8–12 z9, island x12–15 z3, kitchen x10–16 z0, whiteboard x0 z9–13, sofa, tables, armchairs, lamp post (14,14), lectern, nook shelves x21, bench x0–4 z17, the plants listed in §3, stair x20–21 z2–7, balustrade x19 z2–7. Upper floors also block x20–21 z4–6 (well). The roster can compute this from the block list (`anchors.json` holds the anchors; derive the blocked cells from `blocks-2floors-day.mcfunction` or the generator).

---

## 6. Lamps (day ↔ night)

| Lamp | Positions (floor-relative) | Day | Night |
|---|---|---|---|
| Desk linear pendants | x3–6, y3, z3 and z4 (two bars); hangers `iron_chain[axis=y]` at (3,4,3) (6,4,3) (3,4,4) (6,4,4) | `iron_chain[axis=x]` | `end_rod[facing=east]` |
| Island lanterns | (13,3,3) (14,3,3), chains at y4 | `iron_chain[axis=y]` | `lantern[hanging=true]` |
| Lounge pendant | (10,3,14), chain (10,4,14) | `white_wool` | `ochre_froglight` |
| Floor lamp | shade (14,2,14) on `birch_fence` (14,0–1,14) | `white_wool` | `ochre_froglight` |
| Lectern pendant | (18,3,12), chain (18,4,12) | `white_wool` | `ochre_froglight` |
| Whiteboard picture light | (1,3,11) | `air` | `end_rod[facing=east]` |
| Coffee-table candles | (9,1,14) `white_candle[candles=3]`, (11,1,14) `yellow_candle[candles=2]` | `lit=false` | `lit=true` |
| Entry lanterns (floor 0) | (17,3,-2) (18,3,-2), hanging under the canopy | `air` | `lantern[hanging=true]` |
| Pergola lanterns (roof) | (5|7|9, 6F+2, 12), hanging from the slats | `air` | `lantern[hanging=true]` |
| Dining-table lantern (roof) | (14, 6F+1, 5) | `air` | `lantern[hanging=false]` |
| Hidden fill light | superseded: see the always-on layer below | | |

### Always-on layer (added 2026-11-06, over the expanded 30 × 24 plan)

`scripts/office/lighting.cjs` runs after the plan is expanded, so its coordinates are the built ones, not the ones in this table. Per floor: a hidden `light[level=15]` grid at y2 every third block from (1,1) (shifted one cell when something stands there), `end_rod[facing=down]` downlights at y4 over (4|10|16|22|28, 10), (25|26, 1), (16|22, 1) and (4, 15), an `end_rod[facing=west]` sconce at (29,3,1) at the foot of the stair, and hidden lights at (28,7,5) in the well and (29,2,6) under the run. Roof: `lantern[hanging=false]` on the corner posts, the south mullion posts (11|20, 6F+1, 24) and the stair-head guard (27, 6F+1, 3), with hidden lights at 6F+2 over the walks. Entry: a `birch_fence` bollard with a lantern at (22|27, 0–1, -6). Target: every walkable interior cell at block light ≥ 11 day and night, no roof or path cell at 0. `officeCheck` verifies it.

Survival notes: wool, froglight, end rods and chains have no support rules. Hanging lanterns are under a chain, a full canopy block or a log slat (all sturdy). Candles stand on top slabs (a sturdy top face). Leaves need `persistent=true`. Every pendant leaves y2 clear above the furniture.

---

## 7. Roof terrace (y = 6F)

```
ROOF TERRACE (2-floor build: deck slab y=11, items at y=12)
        x→ 901234567890123456789012
  z= -1 I======================I
  z=  0 =mmmmmmmmmmm...........=
  z=  1 =......................=
  z=  2 =......................=
  z=  3 =...................====
  z=  4 =.............>>>>..=..=
  z=  5 =.............tttt..=..=
  z=  6 =.............>>>>..=..=
  z=  7 =...................=>>=
  z=  8 =......................=
  z=  9 =...I.......I..........=
  z= 10 =.....>>>>>............=
  z= 11 =.....:::::............=
  z= 12 =.....:ttt:.....mmm....=
  z= 13 =.>...:::::.....mmm....=
  z= 14 =.t...>>>>>.....mmm....=
  z= 15 =.>.I.......I..........=
  z= 16 =......................=
  z= 17 =mmmmmmmmmmmmmmmmmmmmmm=
  z= 18 I=======I======I=======I
```

- Parapet: `glass_pane` on the slab ring, with `stripped_birch_log` posts at the corners and at x=7 and x=14 on the south side.
- Stair head guard: panes x19 z3–7 and z3 x20–21.
- Pergola x3–11 z9–15: four log posts y0–2, beams `stripped_birch_log[axis=x]` at y3 on z9 and z15, slats `[axis=z]` x5, 7, 9 over z10–14. Facing birch-stair benches, a light-grey rug and a top-slab table under it.
- Planters: moss strip along the south edge (z17, x0–21) and the north edge (z0, x0–10).
- Birch tree: a 3×3 moss bed at x15–17 z12–14, trunk (16, 1–4, 13), leaf canopy y3–5.
- Outdoor dining: table x13–16 z5 with benches on z4 and z6.
- Two deck loungers face the view at (1,13) and (1,15), with a side table between them.

---

## 8. Build notes for the mod

1. Build order: slabs for floors 0…F (the roof is slab F), cut each well, then walls, then the stair runs (the 6th step overwrites the slab), then furniture, then the roof, then the exterior. Fins go last (they run y0 … 6F-1 through the slab rings at x=23).
2. Re-applying lamps: only the positions in §6 change, so `lamps(floors, night)` stays a short list like today.
3. The whiteboard writer keeps today's two-sign layout: left (1,y+2,12) and right (1,y+2,10), with the middle (1,y+2,11) and row 2 as extra lines.
4. Every block ID is a vanilla name valid since 1.21.9 (`iron_chain`, pale oak, `firefly_bush`, `short_grass`). If 26.3 renames anything, `iron_chain` is the only likely candidate.
5. Pane and fence connection properties are written out explicitly, so `/setblock` (which does not reshape the placed block) and structure templates give the exact look.
