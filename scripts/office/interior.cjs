// Interior pass over the expanded 30 x 24 plan (see docs/design/office.md, "Interior").
// generate.cjs stretches design A by inserting floor, which left islands of furniture in a sea of planks and gave
// every desk the same one-block column of props. This pass works in the final frame: it fills the open floor with
// a team table, turns the whiteboard into a meeting corner, squares up the lounge, slims the fridge, joins the
// desk light bars, and deals each of the eight seats its own desk kit.
//
// Seats, desk laptops, whiteboard signs, the lectern, lounge seats and the entry do not move. Everything added on
// the floor is either carpet (walkable) or leaves every anchor reachable; OfficeDesign.blocked() reads the result.
//
// Every block this pass removes or swaps for another is recorded in `legacy`, so OfficeSite still recognises an
// office built from the previous design as the office's own blocks and rebuilds over it.
const FLOOR_HEIGHT = 6;
const key = (x, y, z) => x + ',' + y + ',' + z;
const id = s => s.split('[')[0];

const GREY = 'light_gray_carpet', WHITE = 'white_carpet';
const TABLE = 'birch_slab[type=top]', BENCH = 'birch_slab[type=bottom]';
const seat = f => `polished_andesite_stairs[facing=${f},half=bottom,shape=straight]`;

function apply(blocks, floors, lamps, legacy) {
  const put = (x, y, z, s) => {
    const k = key(x, y, z), old = blocks.get(k);
    if (old && (!s || id(old) !== id(s))) legacy.push([x, y, z, id(old)]);
    if (s) blocks.set(k, s); else blocks.delete(k);
  };
  const free = (x, y, z) => !blocks.has(key(x, y, z));
  const rug = (x0, z0, x1, z1, y, colour) => {
    for (let x = x0; x <= x1; x++) for (let z = z0; z <= z1; z++) {
      const s = blocks.get(key(x, y, z));
      if (!s || /carpet/.test(s)) put(x, y, z, colour(x, z));
    }
  };
  const moveLamp = (floor, from, to) => {
    const lamp = lamps.find(l => l.floor === floor && l.x === from[0] && l.y === from[1] && l.z === from[2]);
    if (!lamp) throw new Error('no lamp at ' + from);
    const state = blocks.get(key(...from));
    put(...from, null);
    if (state) put(...to, state);
    [lamp.x, lamp.y, lamp.z] = to;
  };

  for (let n = 0; n < floors; n++) {
    const b = FLOOR_HEIGHT * n;

    // ---- desk bench: one continuous light bar per row instead of a chain link over each seat ----
    for (const z of [3, 4]) for (const x of [4, 6, 8, 10]) {
      put(x, b + 3, z, 'iron_chain[axis=x]');
      lamps.push({ floor: n, x, y: b + 3, z, day: 'iron_chain[axis=x]', night: 'end_rod[facing=east]', kind: 'desk bar' });
    }

    // ---- west credenza: the stretched run was bare past the printer ----
    put(0, b + 1, 9, 'potted_azalea_bush');
    put(0, b + 1, 11, 'potted_fern');

    // ---- team table on the open floor between the bench and the lounge, under the downlight row ----
    rug(13, 8, 19, 11, b, () => GREY);
    for (let x = 14; x <= 18; x++) for (const z of [9, 10]) put(x, b, z, TABLE);

    // ---- meeting corner: a rug under the whiteboard, a perch facing it, and a frame as wide as the board ----
    rug(1, 13, 6, 17, b, (x, z) => x === 1 || x === 6 || z === 13 || z === 17 ? GREY : WHITE);
    for (let z = 14; z <= 16; z++) put(7, b, z, BENCH);
    for (const z of [18, 19]) {
      for (let y = 0; y <= 3; y++) put(0, b + y, z, null);
      for (let y = 0; y <= 1; y++) put(0, b + y, z, 'bookshelf');
    }
    put(0, b + 2, 18, 'potted_fern');

    // ---- lounge: an even rug, the table centred on it and near the sofa, chairs in the sofa's own stone ----
    rug(12, 17, 19, 22, b, (x, z) => x === 12 || x === 19 || z === 17 || z === 22 ? GREY : WHITE);
    moveLamp(n, [13, b + 1, 20], [14, b + 1, 20]);
    put(13, b, 20, WHITE);
    for (let x = 14; x <= 17; x++) put(x, b, 19, TABLE);
    put(20, b, 17, seat('east')); put(20, b, 21, seat('east'));
    put(20, b, 20, null); put(20, b + 1, 20, null);
    moveLamp(n, [20, b + 2, 20], [20, b + 2, 19]);
    put(20, b, 19, 'birch_fence'); put(20, b + 1, 19, 'birch_fence');
    // review nook chairs match
    put(29, b, 17, seat('east')); put(27, b, 22, seat('north'));

    // ---- reading pair against the credenza's books, on the way from the desks to the whiteboard ----
    put(2, b, 8, seat('west')); put(2, b, 10, seat('west')); put(2, b, 9, TABLE);
    put(2, b + 1, 9, 'potted_flowering_azalea_bush');

    // ---- front desk: the first thing past the door, beside the monstera ----
    for (let x = 22; x <= 24; x++) put(x, b, 6, 'smooth_quartz_slab[type=top]');

    // ---- kitchen: a one-door fridge, with the counter carried on to the entry ----
    for (const x of [23, 24]) { put(x, b + 1, 0, null); put(x, b, 0, 'pale_oak_planks'); }
    put(24, b + 1, 0, 'potted_azalea_bush');

    // ---- entry: a runner in from the door and coat hooks on the wall beside it ----
    if (n === 0) {
      rug(25, 1, 26, 3, b, () => GREY);
      for (const x of [28, 29]) if (free(x, b + 1, 0)) put(x, b + 1, 0, 'tripwire_hook[attached=false,facing=south,powered=false]');
    }
  }
}

// ---- desk kits: one per seat, so a row reads as eight people rather than one desk eight times ----
// Each desk is two blocks wide: the seat's cell (laptop in front of the sitter) and a free cell beside it.
// s runs along the desk from the sitter's centre toward the free cell, d from the near edge (0) to the back (1).
const KITS = [
  { name: 'two screens', chair: 'grey', props: [
    ['monitor', 'monitor', 'idle', 0, .84, 0, .6], ['monitor2', 'monitor', 'idle', .93, .78, -16, .6],
    ['mug', 'coffee_mug', 'sage', -.36, .22, 25], ['notebook', 'notebook', 'open', .7, .28, -12]] },
  { name: 'plants', chair: 'oak', props: [
    ['plant', 'desk_plant', 'white', .78, .8, 0], ['plant2', 'desk_plant', 'sage', 1.22, .66, 40], ['plant3', 'desk_plant', 'sage', -.36, .82, 0],
    ['mug', 'coffee_mug', 'white', .62, .24, -30]] },
  { name: 'paperwork', chair: 'grey', props: [
    ['lamp', 'desk_lamp', 'off', 1.2, .8, 0], ['notebook', 'notebook', 'closed', .9, .36, 8], ['notebook2', 'notebook', 'closed', .92, .36, -14, .35, .066],
    ['notebook3', 'notebook', 'closed', .9, .37, 22, .35, .132], ['mug', 'coffee_mug', 'charcoal', -.36, .22, 140], ['nameplate', 'nameplate', null, -.37, .82, 180]] },
  { name: 'bare', chair: 'oak', props: [
    ['mug', 'coffee_mug', 'white', -.36, .22, 60], ['nameplate', 'nameplate', null, 1.0, .8, 180], ['headphones', 'headphones_stand', null, 1.25, .4, 160]] },
  { name: 'side screen', chair: 'oak', props: [
    ['monitor', 'monitor', 'idle', 1.0, .8, -12, .6], ['plant', 'desk_plant', 'sage', -.36, .8, 0], ['mug', 'coffee_mug', 'charcoal', .6, .22, 70]] },
  { name: 'coffee', chair: 'grey', props: [
    ['monitor', 'monitor', 'idle', 0, .84, 0, .6], ['mug', 'coffee_mug', 'sage', -.36, .22, -165], ['mug2', 'coffee_mug', 'white', .72, .3, 40],
    ['mug3', 'coffee_mug', 'charcoal', 1.1, .62, 100], ['notebook', 'notebook', 'open', 1.15, .26, 10]] },
  { name: 'reading lamp', chair: 'oak', props: [
    ['lamp', 'desk_lamp', 'off', .74, .8, 0], ['plant', 'desk_plant', 'white', 1.24, .8, 0], ['mug', 'coffee_mug', 'white', -.36, .22, -85],
    ['notebook', 'notebook', 'closed', 1.0, .3, -6]] },
  { name: 'headphones', chair: 'grey', props: [
    ['headphones', 'headphones_stand', null, -.36, .8, 20], ['monitor', 'monitor', 'idle', .5, .84, 0, .6], ['notebook', 'notebook', 'closed', 1.15, .3, 174],
    ['mug', 'coffee_mug', 'sage', 1.2, .74, 135], ['nameplate', 'nameplate', null, .62, .18, 0]] },
];

/** The office's props in the final frame: the mapped room props (some moved with their furniture) plus the desk kits and the new zones. */
function props(source, X, Z) {
  const out = [];
  const add = (pid, item, state, pos, yaw, viewRange, desk) => {
    const p = { id: pid, item, pos: pos.map(v => Math.round(v * 1000) / 1000), yaw, viewRange: viewRange || .35 };
    if (state) p.state = state;
    if (desk !== undefined) p.desk = desk;
    out.push(p);
  };
  for (let slot = 0; slot < 8; slot++) {
    const kit = KITS[slot], south = slot >= 4, x0 = 3 + 2 * (slot % 4) + .5;
    add(`desk${slot}.chair`, 'desk_chair', kit.chair, [x0, .0625, south ? 5.5 : 2.5], south ? 180 : 0, .6, slot);
    for (const [name, item, state, s, d, yaw, range, lift] of kit.props)
      add(`desk${slot}.${name}`, item, state, [x0 + s, 1 + (lift || 0), south ? 5 - d : 3 + d], south ? 180 - yaw : yaw, range, slot);
  }
  const moved = { 'lounge.mug.table1': [15.3, 1, 19.4], 'lounge.mug.table2': [16.75, 1, 19.6] };
  for (const p of source.room || []) {
    const q = { ...p, pos: moved[p.id] || [X(p.pos[0]), p.pos[1], Z(p.pos[2])] };
    delete q.world; delete q.replaces; delete q.shares; delete q.zone;
    out.push(q);
  }
  // Team table: six chairs, two closed laptops and the clutter of a table people actually use.
  [14.5, 16.5, 18.5].forEach((x, i) => {
    add(`team.chair.n${i}`, 'desk_chair', i % 2 ? 'oak' : 'grey', [x, .0625, 8.5], 0, .6);
    add(`team.chair.s${i}`, 'desk_chair', i % 2 ? 'grey' : 'oak', [x, .0625, 11.5], 180, .6);
  });
  add('team.laptop.n', 'laptop', 'closed', [14.6, 1, 9.45], 8);
  add('team.laptop.s', 'laptop', 'closed', [18.4, 1, 10.5], 172);
  add('team.notebook', 'notebook', 'open', [16.45, 1, 10.55], 185);
  add('team.mug.1', 'coffee_mug', 'white', [15.4, 1, 9.3], 50);
  add('team.mug.2', 'coffee_mug', 'charcoal', [17.3, 1, 10.6], -140);
  add('team.plant', 'desk_plant', 'white', [16.5, 1, 9.75], 0);
  // Front desk: someone sits here facing the door.
  add('front.chair', 'desk_chair', 'grey', [23.5, .0625, 7.5], 180, .6);
  add('front.monitor', 'monitor', 'off', [23.1, 1, 6.2], 180, .6);
  add('front.nameplate', 'nameplate', null, [24.3, 1, 6.2], 0);
  add('front.plant', 'desk_plant', 'sage', [22.35, 1, 6.45], 0);
  add('front.mug', 'coffee_mug', 'white', [24.2, 1, 6.72], -40);
  add('front.notebook', 'notebook', 'open', [23.85, 1, 6.62], 172);
  add('reading.mug', 'coffee_mug', 'sage', [2.2, 1, 9.2], 60);
  // Credenza and kitchen counter.
  add('work.credenza.notebook', 'notebook', 'closed', [.5, 1, 10.35], 80);
  add('work.credenza.notebook2', 'notebook', 'closed', [.52, 1.066, 10.38], 62);
  add('work.credenza.lamp', 'desk_lamp', 'off', [.5, 1, 8.5], 90);
  add('kitchen.mug.counter2', 'coffee_mug', 'sage', [23.3, 1, .5], 200);
  add('kitchen.mug.counter3', 'coffee_mug', 'white', [23.68, 1, .42], 150);
  add('meeting.notebook', 'notebook', 'closed', [7.5, .5, 14.4], 100);
  return out;
}

module.exports = { apply, props, KITS };
