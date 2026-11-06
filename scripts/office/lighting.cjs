// Lighting pass over the expanded 30 x 24 plan (see docs/design/office.md, "Lighting").
// Runs after generate.cjs has stretched design A, so the hidden fill grid is even across the real floor.
//
// Two layers:
//  1. An always-on layer: a hidden `light[level=15]` grid at head+1 height (y2) every three blocks, so every
//     walkable cell sits at block light 11 or more day and night and nothing can spawn inside; white end-rod
//     downlights on the ceiling over the circulation routes; a stair sconce and a hidden light over each well;
//     lanterns on the parapet posts, the stair head and the entry path bollards.
//  2. The design's own day/night lamps (desk bars, pendants, candles, canopy and pergola lanterns) stay as they
//     are and add the warm evening layer on top.
const W = 30, L = 24, FLOOR_HEIGHT = 6;
const key = (x, y, z) => x + ',' + y + ',' + z;

function apply(blocks, floors) {
  const free = (x, y, z) => !blocks.has(key(x, y, z));
  const put = (x, y, z, s) => blocks.set(key(x, y, z), s);
  const placed = { fill: 0, downlights: 0, lanterns: 0 };

  for (let n = 0; n < floors; n++) {
    const b = FLOOR_HEIGHT * n;
    // Hidden fill grid: three apart, so no floor cell is more than two steps from a column of light.
    for (let x = 1; x < W; x += 3) for (let z = 1; z < L; z += 3) {
      const spot = [[x, z], [x + 1, z], [x - 1, z], [x, z + 1], [x, z - 1]].find(([sx, sz]) => free(sx, b + 2, sz));
      if (spot) { put(spot[0], b + 2, spot[1], 'light[level=15]'); placed.fill++; }
    }
    // Ceiling downlights: a row across the open floor between the bench and the lounge, a pair inside the
    // entry, a pair over the kitchen aisle and one over the whiteboard where agents wait.
    const downlights = [[4, 10], [10, 10], [16, 10], [22, 10], [28, 10], [25, 1], [26, 1], [16, 1], [22, 1], [4, 15]];
    for (const [x, z] of downlights) if (free(x, b + 4, z)) { put(x, b + 4, z, 'end_rod[facing=down]'); placed.downlights++; }
    // Stair: a sconce on the east wall at the foot of the run and a hidden light in the well above it.
    if (free(29, b + 3, 1)) { put(29, b + 3, 1, 'end_rod[facing=west]'); placed.downlights++; }
    if (free(28, b + 7, 5)) { put(28, b + 7, 5, 'light[level=15]'); placed.fill++; }
    if (free(29, b + 2, 6)) { put(29, b + 2, 6, 'light[level=15]'); placed.fill++; } // the nook under the run
  }

  // Roof terrace: lanterns on the four corner posts, the two south mullion posts and the stair-head guard.
  const rb = FLOOR_HEIGHT * floors;
  for (const [x, z] of [[-1, -1], [W, -1], [-1, L], [W, L], [11, L], [20, L], [27, 3]]) {
    if (free(x, rb + 1, z)) { put(x, rb + 1, z, 'lantern[hanging=false]'); placed.lanterns++; }
  }
  // Open terrace: hidden lights just above head height over the walks and the west deck, so the garden
  // reads as softly lit between the lanterns and nothing can spawn on it.
  for (const [x, z] of [[4, 4], [8, 5], [26, 6], [14, 9], [2, 10], [22, 10], [16, 13], [3, 17], [9, 19], [27, 20], [17, 21]]) {
    if (free(x, rb + 2, z)) { put(x, rb + 2, z, 'light[level=15]'); placed.fill++; }
  }
  // Entry path: a birch bollard with a lantern at each far corner of the stone path.
  for (const x of [22, 27]) {
    put(x, 0, -6, 'birch_fence[east=false,north=false,south=false,waterlogged=false,west=false]');
    put(x, 1, -6, 'lantern[hanging=false]'); placed.lanterns++;
  }
  return placed;
}

module.exports = { apply };
