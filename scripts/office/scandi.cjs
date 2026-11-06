(function (root) {
  const W = 22, L = 18, FLOOR_HEIGHT = 6;

  function parse(s) {
    const i = s.indexOf('[');
    const name = i < 0 ? s : s.slice(0, i);
    const props = {};
    if (i >= 0) s.slice(i + 1, -1).split(',').forEach(p => { const [k, v] = p.split('='); props[k] = v; });
    return { name, props };
  }

  const NON_FULL = /(_slab|_stairs|_carpet|_trapdoor|_pane|_fence|iron_chain|end_rod|lantern|flower_pot|potted_|decorated_pot|candle|_sign|_door|^light$|lectern|cauldron|brewing_stand|^azalea$|flowering_azalea$|fern|short_grass|firefly_bush|iron_bars)/;
  const isFull = n => n && !NON_FULL.test(n);

  const T = {
    floor: 'birch_planks', band: 'white_concrete', wall: 'white_concrete', col: 'stripped_birch_log[axis=y]',
    clad: 'stripped_birch_log[axis=y]', pane: 'glass_pane', lip: 'smooth_quartz_slab[type=top]',
    desk: 'smooth_quartz_slab[type=top]', chairN: 'pale_oak_stairs[facing=north,half=bottom,shape=straight]',
    chairS: 'pale_oak_stairs[facing=south,half=bottom,shape=straight]',
    monN: 'dark_oak_trapdoor[facing=north,half=bottom,open=true]', monS: 'dark_oak_trapdoor[facing=south,half=bottom,open=true]',
    chainY: 'iron_chain[axis=y]', chainX: 'iron_chain[axis=x]', rod: 'end_rod[facing=east]',
    shadeDay: 'white_wool', shadeNight: 'ochre_froglight',
    sofa: f => `polished_andesite_stairs[facing=${f},half=bottom,shape=straight]`,
    arm: f => `waxed_oxidized_cut_copper_stairs[facing=${f},half=bottom,shape=straight]`,
    table: 'birch_slab[type=top]', bench: 'birch_slab[type=bottom]',
    shelf: 'bookshelf', cabinet: 'pale_oak_planks', step: 'birch_stairs[facing=south,half=bottom,shape=straight]',
    frame: 'stripped_birch_wood[axis=y]', board: 'white_concrete', sign: f => `birch_wall_sign[facing=${f}]`,
    rugEdge: 'light_gray_carpet', rug: 'white_carpet',
  };

  function build(floors, opts) {
    opts = opts || {};
    const night = !!opts.night;
    floors = Math.max(1, Math.min(4, floors | 0));
    const B = new Map();
    const key = (x, y, z) => x + ',' + y + ',' + z;
    const set = (x, y, z, s) => { if (s == null || s === 'air') B.delete(key(x, y, z)); else B.set(key(x, y, z), s); };
    const fill = (x0, y0, z0, x1, y1, z1, s) => {
      for (let x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
        for (let y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
          for (let z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) set(x, y, z, s);
    };
    const lamps = [];
    const anchors = { floors: [], roof: {}, exterior: {} };
    const zones = []; // for spec only: [floor, kind, x0,z0,x1,z1]

    // ---- slabs: floor n (n=0..F-1) and roof (n=F) ----
    for (let n = 0; n <= floors; n++) {
      const y = FLOOR_HEIGHT * n - 1;
      for (let x = -1; x <= W; x++) for (let z = -1; z <= L; z++) {
        const edgeX = x === -1 || x === W, edgeZ = z === -1 || z === L;
        set(x, y, z, edgeX && edgeZ ? T.col : edgeX || edgeZ ? T.band : T.floor);
      }
      if (n > 0) {
        fill(20, y, 4, 21, y, 6, 'air'); // stair well
        // sun lip on the south and west faces
        fill(-2, y, L + 1, W, y, L + 1, T.lip);
        fill(-2, y, -1, -2, y, L, T.lip);
      }
    }

    // ---- tall potted plant helper ----
    const plant = (x, y, z, tall, flowering) => {
      set(x, y, z, 'decorated_pot[facing=north]');
      set(x, y + 1, z, `azalea_leaves[persistent=true]`);
      if (tall) set(x, y + 2, z, flowering ? 'flowering_azalea_leaves[persistent=true]' : 'azalea_leaves[persistent=true]');
    };

    for (let n = 0; n < floors; n++) {
      const b = FLOOR_HEIGHT * n;
      const S = (x, y, z, s) => set(x, b + y, z, s);
      const F = (x0, y0, z0, x1, y1, z1, s) => fill(x0, b + y0, z0, x1, b + y1, z1, s);
      const P = (x, y, z, t, f) => plant(x, b + y, z, t, f);
      const lamp = (x, y, z, day, nite, kind) => { lamps.push({ floor: n, x, y: b + y, z, day, night: nite, kind }); S(x, y, z, night ? nite : day); };

      // ---- corners ----
      for (const [x, z] of [[-1, -1], [W, -1], [-1, L], [W, L]]) F(x, 0, z, x, 4, z, T.col);
      // ---- south facade (z=18): floor-to-ceiling glass, two birch mullions ----
      for (let x = 0; x < W; x++) F(x, 0, L, x, 4, L, x === 7 || x === 14 ? T.col : T.pane);
      // ---- west facade (x=-1) ----
      for (let z = 0; z < L; z++) {
        if (z >= 1 && z <= 7) { S(-1, 0, z, T.wall); F(-1, 1, z, -1, 3, z, z === 4 ? T.col : T.pane); S(-1, 4, z, T.wall); }
        else if (z >= 14) F(-1, 0, z, -1, 4, z, T.pane);
        else F(-1, 0, z, -1, 4, z, T.wall);
      }
      // ---- north facade (z=-1): desk ribbon, kitchen wall, entry, stair core ----
      for (let x = 0; x < W; x++) {
        if (x <= 10) { S(x, 0, -1, T.wall); F(x, 1, -1, x, 3, -1, x === 5 ? T.col : T.pane); S(x, 4, -1, T.wall); }
        else if (x === 11 || x === 16) F(x, 0, -1, x, 4, -1, T.col);
        else if (x <= 15) { F(x, 0, -1, x, 4, -1, T.wall); S(x, 3, -1, T.pane); }
        else if (x <= 18) {
          if (n === 0) {
            const hinge = x === 17 ? 'right' : 'left';
            S(x, 0, -1, `birch_door[facing=south,half=lower,hinge=${hinge},open=false]`);
            S(x, 1, -1, `birch_door[facing=south,half=upper,hinge=${hinge},open=false]`);
            F(x, 2, -1, x, 3, -1, T.pane);
          } else F(x, 0, -1, x, 3, -1, T.pane);
          S(x, 4, -1, T.wall);
        } else F(x, 0, -1, x, 4, -1, T.wall);
      }
      // ---- east facade (x=22): white wall, stair slot + nook window; birch fins at x=23 added below ----
      for (let z = 0; z < L; z++) {
        F(W, 0, z, W, 4, z, T.wall);
        if (z === 4 || z === 5) F(W, 0, z, W, 4, z, T.pane);
        if (z >= 12 && z <= 14) F(W, 1, z, W, 3, z, T.pane);
      }

      // ---- stair: straight cantilevered birch run, x 20..21, rising south ----
      for (let k = 0; k <= 5; k++) F(20, k, 2 + k, 21, k, 2 + k, T.step); // k=5 sits in the slab above
      F(19, 0, 2, 19, 0, 7, T.pane); // glass balustrade

      // ---- work bench: 4x2 back-to-back desks ----
      for (let x = 3; x <= 6; x++) {
        S(x, 0, 3, T.desk); S(x, 0, 4, T.desk);
        S(x, 1, 3, T.monN); S(x, 1, 4, T.monS);
        S(x, 0, 2, T.chairN); S(x, 0, 5, T.chairS);
        for (const z of [3, 4]) lamp(x, 3, z, T.chainX, T.rod, 'desk bar');
      }
      for (const x of [3, 6]) for (const z of [3, 4]) S(x, 4, z, T.chainY);
      // grey felt rug around the bench (only where nothing stands)
      for (let x = 2; x <= 7; x++) for (let z = 1; z <= 6; z++) if (!B.has(key(x, b, z))) S(x, 0, z, T.rugEdge);
      // credenza under the west ribbon, with pots
      F(0, 0, 1, 0, 0, 7, T.shelf);
      S(0, 1, 2, 'potted_fern'); S(0, 1, 4, 'potted_flowering_azalea_bush'); S(0, 1, 6, 'potted_cactus');
      P(0, 0, 0, true, false);
      // low bookshelf divider between desks and the whiteboard
      F(0, 0, 8, 3, 0, 8, T.shelf);
      S(1, 1, 8, 'potted_azalea_bush'); S(3, 1, 8, 'potted_fern');

      // ---- kitchenette on the north wall ----
      P(10, 0, 0, true, true);
      F(11, 0, 0, 15, 0, 0, T.cabinet);
      S(13, 0, 0, 'water_cauldron[level=3]');
      S(12, 1, 0, 'brewing_stand');
      S(14, 1, 0, 'decorated_pot[facing=south]');
      F(16, 0, 0, 16, 1, 0, 'iron_block');
      F(11, 2, 0, 15, 2, 0, T.table); // floating shelf
      S(11, 3, 0, 'potted_fern'); S(15, 3, 0, 'potted_cactus');
      // island with two hanging lanterns
      F(12, 0, 3, 15, 0, 3, 'stripped_birch_wood[axis=x]');
      for (const x of [13, 14]) { S(x, 4, 3, T.chainY); lamp(x, 3, 3, T.chainY, 'lantern[hanging=true]', 'island lantern'); }

      // ---- whiteboard on the west wall ----
      F(0, 0, 9, 0, 2, 9, T.frame); F(0, 0, 13, 0, 2, 13, T.frame); F(0, 3, 9, 0, 3, 13, T.frame);
      F(0, 1, 10, 0, 2, 12, T.board);
      F(0, 0, 10, 0, 0, 12, T.table); // marker tray
      for (let z = 10; z <= 12; z++) { S(1, 2, z, T.sign('east')); S(1, 1, z, T.sign('east')); }
      lamp(1, 3, 11, 'air', 'end_rod[facing=east]', 'whiteboard picture light');

      // low bookshelf divider between the open floor and the lounge
      F(8, 0, 9, 12, 0, 9, T.shelf);
      S(8, 1, 9, 'potted_fern'); S(10, 1, 9, 'potted_flowering_azalea_bush'); S(12, 1, 9, 'potted_cactus');
      P(14, 0, 7, true, true);
      // ---- lounge ----
      F(8, 0, 12, 12, 0, 12, T.sofa('north'));
      S(7, 0, 12, 'polished_andesite_stairs[facing=north,half=bottom,shape=inner_left]');
      F(7, 0, 13, 7, 0, 15, T.sofa('west'));
      S(7, 0, 16, T.table); S(7, 1, 16, 'potted_flowering_azalea_bush');
      S(13, 0, 12, T.table); S(13, 1, 12, 'potted_azalea_bush');
      for (let x = 8; x <= 13; x++) for (let z = 13; z <= 16; z++)
        S(x, 0, z, x === 8 || x === 13 || z === 13 || z === 16 ? T.rugEdge : T.rug);
      F(9, 0, 14, 11, 0, 14, T.table);
      lamp(9, 1, 14, 'white_candle[candles=3,lit=false]', 'white_candle[candles=3,lit=true]', 'candles');
      S(10, 1, 14, 'potted_fern');
      lamp(11, 1, 14, 'yellow_candle[candles=2,lit=false]', 'yellow_candle[candles=2,lit=true]', 'candles');
      S(10, 4, 14, T.chainY); lamp(10, 3, 14, T.shadeDay, T.shadeNight, 'lounge pendant');
      S(14, 0, 13, T.arm('east')); S(14, 0, 15, T.arm('east'));
      F(14, 0, 14, 14, 1, 14, 'birch_fence'); lamp(14, 2, 14, T.shadeDay, T.shadeNight, 'floor lamp');
      // window bench + plants in the glass corner
      F(0, 0, 17, 4, 0, 17, T.bench);
      P(0, 0, 14, true, true); P(5, 0, 17, true, false);

      // ---- review nook ----
      S(18, 0, 12, 'lectern[facing=west,has_book=false,powered=false]');
      for (let x = 17; x <= 19; x++) for (let z = 11; z <= 13; z++) if (!(x === 18 && z === 12)) S(x, 0, z, T.rugEdge);
      S(18, 4, 12, T.chainY); lamp(18, 3, 12, T.shadeDay, T.shadeNight, 'lectern pendant');
      for (const z of [10, 11, 15, 16]) for (let y = 0; y <= 2; y++)
        S(21, y, z, (y + z) % 2 ? 'chiseled_bookshelf[facing=west,slot_0_occupied=true,slot_1_occupied=true,slot_2_occupied=false,slot_3_occupied=true,slot_4_occupied=false,slot_5_occupied=true]' : T.shelf);
      S(21, 0, 13, T.arm('east'));
      S(21, 0, 12, T.table); S(21, 1, 12, 'potted_fern');
      S(21, 0, 14, T.table); S(21, 1, 14, 'potted_azalea_bush');
      S(19, 0, 16, T.arm('north'));
      P(16, 0, 17, true, true); P(21, 0, 17, true, false);

      // ---- floor label sign at the stair arrival ----
      S(21, 2, 9, T.sign('west'));
      // entry mat / plant
      if (n === 0) F(17, 0, 0, 18, 0, 0, T.rugEdge);
      P(17, 0, 8, false, false);

      // Fill light, downlights and terrace lanterns are added over the expanded plan by lighting.cjs.

      // ---- anchors ----
      const seat = 0.3, sofa = 0.3;
      const fl = { floor: n, y: b };
      fl.work = [
        ...[3, 4, 5, 6].map((x, i) => ({ slot: i, x, z: 2, sink: seat, yaw: 0, desk: [x, 3], monitor: [x, 1, 3] })),
        ...[3, 4, 5, 6].map((x, i) => ({ slot: 4 + i, x, z: 5, sink: seat, yaw: 180, desk: [x, 4], monitor: [x, 1, 4] })),
      ];
      fl.needsYou = [[2, 11], [2, 12], [2, 10], [3, 11], [3, 12], [3, 10], [4, 11], [4, 12]].map(([x, z]) => ({ x, z, sink: 0, yaw: 90 }));
      fl.lounge = [
        { x: 10, z: 12, sink: sofa, yaw: 0 }, { x: 9, z: 12, sink: sofa, yaw: 0 }, { x: 11, z: 12, sink: sofa, yaw: 0 },
        { x: 8, z: 12, sink: sofa, yaw: 0 }, { x: 12, z: 12, sink: sofa, yaw: 0 },
        { x: 7, z: 14, sink: sofa, yaw: -90 }, { x: 14, z: 13, sink: sofa, yaw: 90 }, { x: 7, z: 13, sink: sofa, yaw: -90 },
      ];
      fl.loungeOverflow = [
        { x: 7, z: 15, sink: sofa, yaw: -90 }, { x: 14, z: 15, sink: sofa, yaw: 90 }, { x: 21, z: 13, sink: sofa, yaw: 90 },
        { x: 19, z: 16, sink: sofa, yaw: 0 }, { x: 13, z: 4, sink: 0, yaw: 180 }, { x: 14, z: 4, sink: 0, yaw: 180 },
        { x: 1, z: 17, sink: 0.45, yaw: 180 }, { x: 3, z: 17, sink: 0.45, yaw: 180 },
      ];
      fl.fridge = { x: 16, z: 1, sink: 0, yaw: 180 };
      fl.whiteboard = { board: [0, 1, 10, 0, 2, 12], signs: { left: [1, 2, 12], middle: [1, 2, 11], right: [1, 2, 10], row2: [[1, 1, 12], [1, 1, 11], [1, 1, 10]] }, facing: 'east' };
      fl.lectern = { block: [18, 0, 12], reader: { x: 17, z: 12, sink: 0, yaw: -90 } };
      fl.sign = { block: [21, 2, 9], facing: 'west' };
      fl.stairUp = { bottom: { x: 20, z: 1 }, firstStep: [20, 0, 2], top: [20, 5, 7] };
      fl.arrival = { x: 20, z: 8, sink: 0, yaw: 0 };
      if (n === 0) fl.door = { x: 17, z: 0, outside: [17, -2] };
      anchors.floors.push(fl);
      if (n === 0) {
        zones.push(['work', 2, 1, 7, 6], ['kitchen', 10, 0, 16, 4], ['whiteboard', 0, 9, 4, 13], ['lounge', 7, 12, 14, 16], ['review nook', 16, 10, 21, 17], ['stair', 19, 1, 21, 8]);
      }
    }

    // ---- roof terrace ----
    const rb = FLOOR_HEIGHT * floors;
    const R = (x, y, z, s) => set(x, rb + y, z, s);
    const RF = (x0, y0, z0, x1, y1, z1, s) => fill(x0, rb + y0, z0, x1, rb + y1, z1, s);
    const rlamp = (x, y, z, day, nite, kind) => { lamps.push({ floor: 'roof', x, y: rb + y, z, day, night: nite, kind }); R(x, y, z, night ? nite : day); };
    for (let x = -1; x <= W; x++) for (let z = -1; z <= L; z++) {
      const edgeX = x === -1 || x === W, edgeZ = z === -1 || z === L;
      if (!edgeX && !edgeZ) continue;
      R(x, 0, z, (edgeX && edgeZ) || (z === L && (x === 7 || x === 14)) ? T.col : T.pane);
    }
    RF(19, 0, 3, 19, 0, 7, T.pane); RF(20, 0, 3, 21, 0, 3, T.pane); // stair head guard
    // pergola x 3..11, z 9..15
    for (const [x, z] of [[3, 9], [11, 9], [3, 15], [11, 15]]) RF(x, 0, z, x, 2, z, T.col);
    RF(3, 3, 9, 11, 3, 9, 'stripped_birch_log[axis=x]'); RF(3, 3, 15, 11, 3, 15, 'stripped_birch_log[axis=x]');
    for (const x of [5, 7, 9]) RF(x, 3, 10, x, 3, 14, 'stripped_birch_log[axis=z]');
    RF(5, 0, 10, 9, 0, 10, 'birch_stairs[facing=north,half=bottom,shape=straight]');
    RF(5, 0, 14, 9, 0, 14, 'birch_stairs[facing=south,half=bottom,shape=straight]');
    RF(5, 0, 11, 9, 0, 13, T.rugEdge);
    RF(6, 0, 12, 8, 0, 12, T.table);
    for (const x of [5, 7, 9]) rlamp(x, 2, 12, 'air', 'lantern[hanging=true]', 'pergola lantern');
    // planters along south + north parapets
    const flora = ['azalea', 'fern', 'flowering_azalea', 'short_grass', 'firefly_bush', 'fern'];
    for (let x = 0; x < W; x++) { R(x, 0, L - 1, 'moss_block'); R(x, 1, L - 1, flora[x % flora.length]); }
    for (let x = 0; x <= 10; x++) { R(x, 0, 0, 'moss_block'); R(x, 1, 0, flora[(x + 3) % flora.length]); }
    // birch tree in a 3x3 moss bed
    RF(15, 0, 12, 17, 0, 14, 'moss_block');
    const leaf = 'birch_leaves[persistent=true]';
    for (let dx = -2; dx <= 2; dx++) for (let dz = -2; dz <= 2; dz++) {
      if (Math.abs(dx) + Math.abs(dz) <= 3) R(16 + dx, 3, 13 + dz, leaf);
      if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) R(16 + dx, 4, 13 + dz, leaf);
      if (Math.abs(dx) + Math.abs(dz) <= 1) R(16 + dx, 5, 13 + dz, leaf);
    }
    RF(16, 1, 13, 16, 4, 13, 'birch_log[axis=y]');
    // outdoor dining table
    RF(13, 0, 5, 16, 0, 5, T.table);
    RF(13, 0, 4, 16, 0, 4, 'birch_stairs[facing=north,half=bottom,shape=straight]');
    RF(13, 0, 6, 16, 0, 6, 'birch_stairs[facing=south,half=bottom,shape=straight]');
    rlamp(14, 1, 5, 'air', 'lantern[hanging=false]', 'table lantern');
    R(15, 1, 5, 'potted_flowering_azalea_bush');
    // deck loungers facing the view
    R(1, 0, 13, 'birch_stairs[facing=north,half=bottom,shape=straight]'); R(1, 0, 15, 'birch_stairs[facing=north,half=bottom,shape=straight]');
    R(1, 0, 14, T.table); R(1, 1, 14, 'potted_flowering_azalea_bush');
    anchors.roof = { y: rb, arrival: { x: 20, z: 8 }, benchSouth: [5, 6, 7, 8, 9].map(x => ({ x, z: 10, sink: 0.3, yaw: 0 })), benchNorth: [5, 6, 7, 8, 9].map(x => ({ x, z: 14, sink: 0.3, yaw: 180 })) };

    // ---- east brise-soleil: full-height stripped birch fins, one block proud of the wall ----
    for (let z = 0; z < L; z += 2) fill(W + 1, 0, z, W + 1, FLOOR_HEIGHT * floors - 1, z, T.clad);
    // ---- exterior: entry canopy, path, planters, building sign ----
    fill(16, 4, -3, 19, 4, -2, 'smooth_quartz');
    for (const x of [17, 18]) { lamps.push({ floor: 0, x, y: 3, z: -2, day: 'air', night: 'lantern[hanging=true]', kind: 'entry lantern' }); set(x, 3, -2, night ? 'lantern[hanging=true]' : 'air'); }
    fill(16, -1, -6, 19, -1, -2, 'smooth_stone');
    for (const x of [15, 20]) { set(x, 0, -2, 'moss_block'); set(x, 1, -2, 'flowering_azalea'); }
    set(19, 2, -2, T.sign('north'));
    anchors.exterior = { sign: [19, 2, -2], door: [17, 0, -1], path: [16, -1, -6, 19, -1, -2] };

    // ---- connections for panes / fences (so setblock output is exact) ----
    const dirs = { north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0] };
    for (const [k, s] of B) {
      const { name } = parse(s);
      const pane = /_pane$|iron_bars/.test(name), fence = /_fence$/.test(name);
      if (!pane && !fence) continue;
      const [x, y, z] = k.split(',').map(Number);
      const p = [];
      for (const d in dirs) {
        const ns = B.get(key(x + dirs[d][0], y, z + dirs[d][1]));
        const nn = ns && parse(ns).name;
        const ok = nn && (isFull(nn) || (pane ? /_pane$|iron_bars/.test(nn) : /_fence$/.test(nn)));
        p.push(`${d}=${ok ? 'true' : 'false'}`);
      }
      B.set(k, `${name}[${p.join(',')}]`);
    }
    return { blocks: B, anchors, lamps, zones, floors, dims: { W, L, FLOOR_HEIGHT } };
  }

  const api = { build, parse, isFull, W, L, FLOOR_HEIGHT };
  if (typeof module !== 'undefined') module.exports = api; else root.OfficeGen = api;
})(typeof window !== 'undefined' ? window : globalThis);
