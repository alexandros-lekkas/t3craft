package dev.maxwellyoung.t3craft;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Spatial acceptance checks use synthetic rows and disposable owner-only configuration. */
public final class OfficeChecks {
 private static void checkScandi() {
  check(T3Office.WORK.length==8 && T3Office.WORK[0].x()==3 && T3Office.WORK[0].z()==2,"designer desk anchors parsed");
  check(OfficeSite.bounds(net.minecraft.core.BlockPos.ZERO,T3Office.blueprint(List.of("one machine"))).max().getY()>=17,"one machine always builds both floors and roof");
  check(T3Office.WIDTH==30 && T3Office.LENGTH==24,"roomier design footprint");
  check(ServerOffice.legacyVillager(java.util.Set.of("t3craft")) && !ServerOffice.legacyVillager(java.util.Set.of(ServerOffice.TAG,"t3craft_agent","t3craft_laptop")),"leftover shared-village agents are discarded; office agents and laptops are kept");
  check(T3Office.FLOOR_HEIGHT==6 && T3Office.NEEDS_YOU[0].z()==15,"floor and whiteboard anchors match design A");
  for(int floors=1;floors<=6;floors++)check(OfficeDesign.template(floors).getAsJsonObject("anchors").getAsJsonArray("floors").size()==floors,"floor template count");
  check(AgentAppearance.provider("claudeAgent").equals("claude"),"driver alias maps to bundled Claude art");
  check(AgentAppearance.provider("unknown-new-driver").equals("default"),"unknown driver uses bundled fallback");
  for(var e:AgentAppearance.SKINS.getAsJsonArray("skins")) {var skin=e.getAsJsonObject();check(OfficeChecks.class.getResource("/assets/t3craft/textures/entity/agent/"+skin.get("file").getAsString())!=null,"every manifest skin is bundled");}
  check(AgentAppearance.skin("codex","thread-a").equals(AgentAppearance.skin("codex","thread-a")),"alternate selection stable");
  check(OfficeProps.PROPS.size()==59,"complete original prop placements");
  check(OfficeProps.state("coffee_mug","sage",T3State.Status.WORKING,false).equals("sage_steam"),"working mug steam");
  check(OfficeProps.state("monitor","idle",T3State.Status.WORKING,false).equals("on")&&OfficeProps.state("monitor","idle",null,false).equals("off"),"monitor follows desk occupancy and status");
  check(OfficeProps.state("desk_lamp","off",null,true).equals("on")&&OfficeProps.state("desk_lamp","off",T3State.Status.IDLE,false).equals("off"),"night desk lamps");
  check(DeskLaptops.state(T3State.Status.NEEDS_YOU).equals("needs_you")&&DeskLaptops.state(T3State.Status.WORKING).equals("working")&&DeskLaptops.state(T3State.Status.DONE).equals("idle"),"laptop status mapping");
  check(DeskLaptops.uuid("a",true).equals(DeskLaptops.uuid("a",true))&&!DeskLaptops.uuid("a",true).equals(DeskLaptops.uuid("a",false))&&!DeskLaptops.uuid("a",true).equals(DeskLaptops.uuid("b",true)),"laptop pair identity stable and distinct");
  checkLighting();
  var brain=new OfficeBrain();var row=row("owner",0,T3State.Status.WORKING);brain.sync(List.of(row),List.of("owner"),new OfficeRoster.Preferences());brain.face(row.id());var a=brain.agents().get(row.id());double x=a.x,z=a.z;brain.tick();check(a.x==x&&a.z==z&&!a.walking,"head turn hold pauses the brain");
 }

	/** The office is lit everywhere an agent can stand, day and night, and nothing can spawn on the terrace or the path. */
	private static void checkLighting() {
		for (int floors : new int[] {2, 3}) for (boolean night : new boolean[] {false, true}) {
			var blocks = OfficeLighting.blocks(floors, night);
			var light = OfficeLighting.blockLight(blocks);
			String when = floors + " floors " + (night ? "night" : "day");
			var inside = OfficeLighting.report(light, OfficeLighting.walkable(blocks, floors));
			check(inside.cells() > 550 * floors, "walkable interior found: " + when + ": " + inside);
			check(inside.min() >= 11, "every walkable interior cell at block light 11 or more, " + when + ": " + inside);
			var terrace = OfficeLighting.report(light, OfficeLighting.terrace(blocks, floors));
			check(terrace.cells() > 500 && terrace.min() >= 3, "roof terrace lit so nothing spawns, " + when + ": " + terrace);
			List<net.minecraft.core.BlockPos> path = new ArrayList<>();
			for (int x = 22; x <= 27; x++) for (int z = -6; z <= -2; z++) if (!blocks.containsKey(new net.minecraft.core.BlockPos(x, 0, z))) path.add(new net.minecraft.core.BlockPos(x, 0, z));
			var entry = OfficeLighting.report(light, path);
			check(entry.min() >= 6, "entry path lit to the door, " + when + ": " + entry);
			long hidden = blocks.values().stream().filter(s -> s.is(net.minecraft.world.level.block.Blocks.LIGHT)).count();
			check(hidden < 120 * floors, "hidden fill stays a sparse grid, " + when + ": " + hidden);
			if (floors == 2 && night) System.out.println("Office light: inside " + inside + "; terrace " + terrace + "; entry " + entry);
		}
		var blueprint = T3Office.blueprint(List.of("one machine"));
		check(blueprint.stream().anyMatch(c -> c.contains("end_rod[facing=down]")) && blueprint.stream().anyMatch(c -> c.contains("lantern[hanging=false]")), "rebuilt offices place the downlights and lanterns");
		check(OfficeDesign.lamps(2, true).stream().anyMatch(c -> c.contains("ochre_froglight")) && OfficeDesign.lamps(2, false).stream().noneMatch(c -> c.contains("froglight")), "pendant shades glow warm at night only");
	}

	public static void main(String[] args) throws Exception {
		checkPlacement();
		checkScandi();
		checkProps();
		checkCommandDispatch();
		check(!OfficeQueue.permissionFailure(net.minecraft.network.chat.Component.literal("Unknown command: permission")), "literal chat is not a permission denial");
		for (String key : List.of("command.unknown.command", "commands.generic.permission"))
			check(OfficeQueue.permissionFailure(net.minecraft.network.chat.Component.empty().append(net.minecraft.network.chat.Component.translatable(key))), "nested translatable denial is recognized");
		CommandBudget budget = new CommandBudget();
		int spam = 0, sends = 0;
		for (int tick = 0; tick < 24000; tick++) {
			budget.tick(); spam = Math.max(0, spam - 1);
			for (int producer = 0; producer < 8; producer++) if (budget.acquire(tick * 50_000_000L)) { spam += 20; sends++; }
			check(spam < 200, "all producers stay under vanilla's command-spam limit");
		}
		check(sends == 600, "shared budget allows one command per forty ticks without bursts");
		OfficeRoster.Preferences malformed = new OfficeRoster.Preferences();
		malformed.desks.put("broken", 0); malformed.desks.put(null, 1); malformed.desks.put("owner\n", 2);
		malformed.desks.put("owner\nthread", null);
		OfficeRoster.sync(List.of(), List.of("owner"), malformed);
		check(malformed.desks.isEmpty(), "malformed saved desks are sanitized before pruning");
		String a = "http://127.0.0.1:25680", b = "http://127.0.0.1:25681";
		List<T3State.ThreadRow> rows = new ArrayList<>();
		for (String owner : List.of(a, b)) for (int i = 0; i < 12; i++) rows.add(row(owner, i, i == 10 ? T3State.Status.NEEDS_YOU : i == 9 ? T3State.Status.WORKING : T3State.Status.DONE));
		OfficeRoster.Preferences prefs = new OfficeRoster.Preferences();
		T3State.ThreadRow pin = rows.get(11);
		check(prefs.pin(pin), "old thread can be pinned");
		var selected = OfficeRoster.sync(rows, List.of(a, b), prefs);
		check(selected.size() == 24, "each machine has sixteen desk capacity despite duplicate names");
		check(selected.stream().anyMatch(r -> r.row().equals(pin)), "old pin beats recent threads");
		check(selected.stream().filter(r -> r.row().status() == T3State.Status.NEEDS_YOU).count() == 2, "old waiting work is included on both machines");
		check(selected.stream().filter(r -> r.row().status() == T3State.Status.WORKING).count() == 2, "working rows beat recent done rows");
		Map<String, Integer> before = Map.copyOf(prefs.desks);
		List<T3State.ThreadRow> reordered = new ArrayList<>(selected.stream().map(OfficeRoster.Resident::row).toList());
		Collections.reverse(reordered);
		OfficeRoster.sync(reordered, List.of(a, b), prefs);
		check(before.equals(prefs.desks), "recency reorder keeps every included desk");
		OfficeBrain brain = new OfficeBrain(); brain.sync(reordered, List.of(a, b), prefs);
		check(brain.agents().values().stream().filter(r -> r.floor == 2).count() == 8, "same display name still has a distinct second floor");
		check(brain.agents().get(pin.id()).desk == before.get(OfficeRoster.key(pin)) % 8, "brain consumes the saved desk");
		check(!OfficeRoster.projectKey(rows.get(0)).equals(OfficeRoster.projectKey(rows.get(12))), "same project title on two machines remains distinct");

		// A transient unavailable snapshot must retain unpinned desks, including owners not in the roster.
		OfficeRoster.sync(List.of(), List.of(a, b), prefs, java.util.Set.of());
		check(before.equals(prefs.desks), "unknown snapshots retain every desk, not just pins");
		OfficeRoster.sync(reordered.stream().filter(r -> r.ownerKey().equals(a)).toList(), List.of(a, b), prefs, java.util.Set.of(a));
		check(before.equals(prefs.desks), "one unavailable peer cannot lose its assignments");
		OfficeRoster.sync(List.of(), List.of(a, b), prefs, java.util.Set.of(b));
		check(prefs.desks.keySet().stream().noneMatch(key -> key.startsWith(b + "\n")), "authoritative empty snapshot prunes an available owner");
		check(prefs.desks.entrySet().containsAll(before.entrySet().stream().filter(e -> e.getKey().startsWith(a + "\n")).toList()), "pruning one owner preserves the unavailable peer");
		prefs.desks.clear(); prefs.desks.putAll(before);

		WorldBoundQueue<String> queue = new WorldBoundQueue<>();
		Object level = new Object(), connection = new Object();
		queue.add(level, connection, List.of("fill", "setblock"));
		queue.validate(level, connection);
		check("fill".equals(queue.poll()), "same session sends queued work in order");
		check(!queue.targetLoaded(false) && queue.isEmpty(), "unloaded target cancels placement instead of resuming over new work");
		queue.targetLoaded(true);
		check(queue.isEmpty(), "chunk reload cannot resurrect destructive commands");
		queue.add(level, connection, List.of("old"));
		queue.validate(new Object(), connection);
		check(queue.isEmpty(), "dimension or world change discards remaining work");
		queue.add(level, connection, List.of("old"));
		queue.validate(level, new Object());
		check(queue.isEmpty(), "reconnect to the same level discards work");
		queue.add(level, connection, List.of("old"));
		queue.validate(null, null);
		check(queue.isEmpty(), "disconnect discards work even before another world joins");
		queue.add(level, connection, List.of("old"));
		Object otherLevel = new Object();
		queue.add(otherLevel, connection, List.of("new"));
		check("new".equals(queue.poll()) && queue.isEmpty(), "enqueue after world change cannot carry old commands");

		var directory = Files.createTempDirectory("t3craft-office-check-");
		var file = directory.resolve("config.json");
		try {
			T3Config config = new T3Config();
			config.environments.add(new T3Config.Environment("Same name", a, "fixture-only"));
			config.environments.add(new T3Config.Environment("Same name", b, "fixture-only"));
			prefs.floorOwners = List.of(a, b);
			config.officePreferences.put("world-a", prefs);
			config.officePreferences.put("world-b", new OfficeRoster.Preferences());
			config.save(file);
			T3Config restored = T3Config.load(file);
			check(restored.officePreferences.get("world-a").floorOwners.equals(List.of(a, b)), "built floor owners survive save/load");
			var builtPrefs = restored.officePreferences.get("world-a");
			restored.environments.removeFirst();
			brain.sync(reordered.stream().filter(r -> r.ownerKey().equals(b)).toList(), builtPrefs.floorOwners, builtPrefs, java.util.Set.of(b));
			check(brain.agents().values().stream().allMatch(agent -> agent.floor == 2 || agent.floor == 3), "removing a pairing does not shift the built second machine suite");
			restored.environments.addFirst(new T3Config.Environment("Same name", a, "fixture-only"));
			check(restored.officePreferences.get("world-a").pinned(pin), "pin survives actual JSON save/load");
			OfficeRoster.sync(reordered, List.of(a, b), restored.officePreferences.get("world-a"));
			check(before.equals(restored.officePreferences.get("world-a").desks), "all desks survive process-style reload");
			check(restored.sharedOfficePreferences.pins.isEmpty() && restored.officePreferences.get("world-b").pins.isEmpty(), "client worlds and operator-owned pins are isolated");
			restored.upsert(new T3Config.Environment("New name", a + "/", "fixture-only"));
			check(restored.environments.size() == 2 && restored.environments.getFirst().label.equals("New name"), "re-pair keeps floor order and owner identity");
			if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) check(Files.getPosixFilePermissions(file).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")), "saved config is owner-only");
		} finally { Files.deleteIfExists(file); Files.deleteIfExists(directory); }

		// A pre-0.6 config: offices stay where they stood, plain villages are gone, and the old keys never come back.
		var legacyDir = Files.createTempDirectory("t3craft-legacy-config-");
		var legacy = legacyDir.resolve("config.json");
		try {
			Files.writeString(legacy, "{\"environments\":[{\"label\":\"Mac\",\"baseUrl\":\"" + a + "\",\"accessToken\":\"fixture-only\"}],"
				+ "\"village\":[1,2,3],\"villages\":{\"save:one|minecraft:overworld\":[10,64,-20],\"save:two|minecraft:overworld\":[5,70,5],\"save:bad|minecraft:overworld\":[1]},"
				+ "\"officeWorlds\":[\"save:one|minecraft:overworld\",\"save:bad|minecraft:overworld\",\"save:missing|minecraft:overworld\"],\"books\":false}");
			T3Config migrated = T3Config.load(legacy);
			check(migrated.offices.size() == 1 && java.util.Arrays.equals(migrated.offices.get("save:one|minecraft:overworld"), new int[] {10, 64, -20}), "office worlds migrate to offices; villages and malformed points are dropped");
			check(migrated.villages == null && migrated.officeWorlds == null && migrated.mcpPort == T3Config.DEFAULT_MCP_PORT, "legacy keys cleared; new defaults present");
			check(migrated.save(legacy) && !Files.readString(legacy).contains("villages") && !Files.readString(legacy).contains("officeWorlds") && Files.readString(legacy).contains("\"offices\""), "saved config carries only the office map");
			Files.writeString(legacy, "{\"mcpPort\":70000}");
			T3Config odd = T3Config.load(legacy);
			check(odd.mcpPort == T3Config.DEFAULT_MCP_PORT, "out-of-range port falls back to the default");
		} finally { Files.deleteIfExists(legacy); Files.deleteIfExists(legacyDir); }

		int saved = prefs.desks.get(OfficeRoster.key(pin));
		OfficeRoster.sync(List.of(), List.of(a, b), prefs);
		check(prefs.desks.get(OfficeRoster.key(pin)) == saved, "empty reconnect snapshot retains pin identity");
		OfficeRoster.sync(reordered, List.of(a, b), prefs);
		check(prefs.desks.get(OfficeRoster.key(pin)) == saved, "returning pin reclaims its desk");
		List<T3State.ThreadRow> absent = reordered.stream().filter(r -> !r.equals(pin)).toList();
		OfficeRoster.sync(absent, List.of(a, b), prefs);
		check(prefs.pinned(pin), "unavailable pin stays visible for removal");
		prefs.unpin(prefs.pins.getFirst()); OfficeRoster.sync(absent, List.of(a, b), prefs);
		check(!prefs.desks.containsKey(OfficeRoster.key(pin)), "unpin frees unavailable assignment");

		OfficeRoster.Preferences capacity = new OfficeRoster.Preferences();
		for (int i = 0; i < 16; i++) check(capacity.pin(row(a,i,T3State.Status.IDLE)), "sixteen pins accepted");
		check(!capacity.pin(row(a,16,T3State.Status.IDLE)) && capacity.pin(rows.get(12)), "limit applies per owner");
		List<T3State.ThreadRow> waiting = new ArrayList<>();
		for (int i = 0; i < 8; i++) waiting.add(row(a, i, T3State.Status.NEEDS_YOU));
		brain.clear(); brain.sync(waiting, List.of(a), new OfficeRoster.Preferences());
		check(brain.agents().values().stream().map(r -> r.goal).distinct().count() == 8, "eight waiting residents do not share a physical spot");
		System.out.println("PASS legacy village config migration, plain-field/build occupancy, daytime lamps/signs, lit interior and terrace, shared command budget, malformed desks, unloaded-target cancellation, queue world/session binding, unavailable-owner retention, built floor persistence, stable desks, pinned/waiting/working priority, restart and reconnect persistence, distinct owners/projects, pin removal and shared/client isolation");
	}
	private static void checkPlacement() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		var origin = new net.minecraft.core.BlockPos(0, 64, 0);
		var blueprint = new ArrayList<>(T3Office.blueprint(List.of("Machine")));
        blueprint.add("setblock 15 0 15 white_shulker_box"); // Synthetic container exercises rebuild protection independently of the furniture palette.
		var bounds = OfficeSite.bounds(origin, blueprint);
		var blocks = new java.util.HashMap<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState>();
		java.util.function.Function<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> read = pos -> blocks.getOrDefault(pos,
			pos.getY() == bounds.min().getY() ? net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState() : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		var spot = origin.offset(3, 0, 3);
		for (var block : List.of(net.minecraft.world.level.block.Blocks.AIR, net.minecraft.world.level.block.Blocks.SHORT_GRASS,
			net.minecraft.world.level.block.Blocks.DANDELION, net.minecraft.world.level.block.Blocks.SNOW, net.minecraft.world.level.block.Blocks.WATER)) {
			blocks.put(spot, block.defaultBlockState());
			check(!OfficeSite.occupied(bounds, null, List.of(), read, pos -> null), "plain terrain builds in one step: " + block);
		}
		// A hill or a forest above the plaza never stops the build; crafted blocks do, and the refusal names the spot.
		for (var block : List.of(net.minecraft.world.level.block.Blocks.STONE, net.minecraft.world.level.block.Blocks.DIRT, net.minecraft.world.level.block.Blocks.GRASS_BLOCK,
			net.minecraft.world.level.block.Blocks.OAK_LOG, net.minecraft.world.level.block.Blocks.OAK_LEAVES, net.minecraft.world.level.block.Blocks.SAND,
			net.minecraft.world.level.block.Blocks.GRAVEL, net.minecraft.world.level.block.Blocks.IRON_ORE, net.minecraft.world.level.block.Blocks.DEEPSLATE,
			net.minecraft.world.level.block.Blocks.TERRACOTTA, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("orange_terracotta")), net.minecraft.world.level.block.Blocks.MOSSY_COBBLESTONE,
			net.minecraft.world.level.block.Blocks.MOSS_CARPET, net.minecraft.world.level.block.Blocks.SMOOTH_BASALT, net.minecraft.world.level.block.Blocks.NETHER_QUARTZ_ORE)) {
			blocks.put(spot, block.defaultBlockState());
			check(!OfficeSite.occupied(bounds, null, List.of(), read, pos -> null), "natural terrain above ground builds in one step: " + block);
		}
		for (var block : List.of(net.minecraft.world.level.block.Blocks.OAK_PLANKS, net.minecraft.world.level.block.Blocks.GLASS, net.minecraft.world.level.block.Blocks.OAK_STAIRS,
			net.minecraft.world.level.block.Blocks.COBBLESTONE, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("white_wool")), net.minecraft.world.level.block.Blocks.STONE_BRICKS,
			net.minecraft.world.level.block.Blocks.OAK_DOOR, net.minecraft.world.level.block.Blocks.TORCH, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
			blocks.put(spot, block.defaultBlockState());
			check(spot.equals(OfficeSite.blocker(bounds, null, List.of(), read, pos -> null)), "player-built blocks above ground are protected and located: " + block);
		}
		blocks.put(spot, net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState());
		blocks.put(bounds.min(), net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState());
		check(!OfficeSite.occupied(bounds, null, List.of(), read, pos -> null) || spot.equals(OfficeSite.blocker(bounds, null, List.of(), read, pos -> null)), "the plaza layer is always replaceable");
		blocks.clear();
		var chest = new net.minecraft.world.level.block.entity.ChestBlockEntity(bounds.min(), net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
		check(bounds.min().equals(OfficeSite.blocker(bounds, null, List.of(), read, pos -> pos.equals(bounds.min()) ? chest : null)), "buried containers remain protected, even empty, and are located");
		// Recognize both lamp states and the office's own sign block entities on rebuild.
		var lamp = origin.offset(16, 3, 20);
		var sign = origin.offset(1, 2, 16);
		blocks.put(lamp, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("white_wool")).defaultBlockState());
		blocks.put(sign, net.minecraft.world.level.block.Blocks.BIRCH_WALL_SIGN.defaultBlockState());
		var signEntity = new net.minecraft.world.level.block.entity.SignBlockEntity(sign, blocks.get(sign));
		check(!OfficeSite.occupied(bounds, origin, blueprint, read, pos -> pos.equals(sign) ? signEntity : null), "daytime lamps and office whiteboards are rebuildable");
		// Use the blueprint coordinate so this protects the office's own container exemption.
		String shulkerCommand = blueprint.stream().filter(command -> command.contains("white_shulker_box")).findFirst().orElseThrow();
		String[] coordinates = shulkerCommand.split(" ");
		var shulkerPos = origin.offset(Integer.parseInt(coordinates[1]), Integer.parseInt(coordinates[2]), Integer.parseInt(coordinates[3]));
		var shulkerState = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("white_shulker_box")).defaultBlockState();
		var shulker = new net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity(shulkerPos, shulkerState);
		blocks.put(shulkerPos, shulkerState);
		check(!OfficeSite.occupied(bounds, origin, blueprint, read, pos -> pos.equals(shulkerPos) ? shulker : null), "empty office shulker permits rebuild");
		shulker.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.core.Holder.direct(net.minecraft.world.item.Items.DIAMOND, net.minecraft.core.component.DataComponentMap.EMPTY)));
		check(OfficeSite.occupied(bounds, origin, blueprint, read, pos -> pos.equals(shulkerPos) ? shulker : null), "non-empty office shulker requires confirmation");
		shulker.clearContent();
		check(OfficeSite.occupied(bounds, null, List.of(), read, pos -> pos.equals(shulkerPos) ? shulker : null), "unowned empty shulker remains protected");
	}

	/**
	 * Every prop of the office, for every turn and floor count: the full set, one stable identity
	 * each (so a rebuild replaces rather than duplicates), inside the build, and turned with it.
	 */
	private static void checkProps() {
		int perFloor = OfficeProps.PROPS.size();
		check(perFloor == 59, "props.json has the full pack: " + perFloor);
		var origin = new net.minecraft.core.BlockPos(-40, 70, 12);
		for (var rotation : net.minecraft.world.level.block.Rotation.values()) for (int floors : new int[] {2, 4}) {
			var frame = new OfficeFrame(origin, rotation);
			String where = rotation + ", " + floors + " floors";
			var placed = OfficeProps.placements(frame, floors);
			check(placed.size() == perFloor * floors, "every prop placed (" + where + "): " + placed.size());
			var ids = new java.util.HashSet<java.util.UUID>();
			for (var p : placed) check(ids.add(p.id()), "one identity per prop (" + where + "): " + p.item());
			check(ids.equals(new java.util.HashSet<>(OfficeProps.placements(frame, floors).stream().map(OfficeProps.Placement::id).toList())), "a rebuild reuses every identity, so nothing duplicates (" + where + ")");
			var box = OfficePlacement.bounds(frame, floors);
			int[] door = OfficeDesign.doorOutside();
			var doorAt = frame.point(door[0] + .5, 0, door[1] + .5);
			for (int i = 0; i < placed.size(); i++) {
				var p = placed.get(i);
				var design = OfficeProps.PROPS.get(i % perFloor).getAsJsonObject();
				var pos = design.getAsJsonArray("pos");
				double x = pos.get(0).getAsDouble(), y = pos.get(1).getAsDouble() + T3Office.FLOOR_HEIGHT * (i / perFloor), z = pos.get(2).getAsDouble();
				String what = design.get("id").getAsString() + " on floor " + (i / perFloor) + " (" + where + ")";
				check(p.item().equals(design.get("item").getAsString()) && p.floor() == i / perFloor, "placement order follows the design: " + what);
				check(box.contains(p.pos()), "inside the building: " + what + " at " + p.pos());
				check(Math.abs(p.pos().y - (origin.getY() + y)) < 1e-9, "on its floor: " + what);
				// Turning is rigid: every prop keeps its distance to the door, and its facing turns with the office.
				double designDistance = Math.hypot(x - (door[0] + .5), z - (door[1] + .5));
				double worldDistance = Math.hypot(p.pos().x - doorAt.x, p.pos().z - doorAt.z);
				check(Math.abs(designDistance - worldDistance) < 1e-9, "turned with the office: " + what);
				check(Math.abs(net.minecraft.util.Mth.wrapDegrees(p.yaw() - design.get("yaw").getAsFloat() - frame.degrees())) < 1e-3, "faces turn with the office: " + what);
				// Inside a cell (not on its edge), the prop stands in the turned copy of its design cell.
				if (x % 1 != 0 && z % 1 != 0)
					check(frame.local(net.minecraft.core.BlockPos.containing(p.pos())).equals(net.minecraft.core.BlockPos.containing(x, y, z)), "in its turned design cell: " + what);
			}
		}
		check(!OfficeProps.uuid(origin, 0, "desk0.chair").equals(OfficeProps.uuid(origin.east(), 0, "desk0.chair"))
			&& !OfficeProps.uuid(origin, 0, "desk0.chair").equals(OfficeProps.uuid(origin, 1, "desk0.chair")), "two offices or two floors never share a prop");
		System.out.println("Office props: " + perFloor + " per floor, every turn, stable identities");
	}

	private static void checkCommandDispatch() {
		Object level = new Object(), connection = new Object();
		var dispatched = new java.util.concurrent.CompletableFuture<Long>();
		ClientCommands.enqueue(level, connection, "test", () -> dispatched.complete(2_000L), () -> dispatched.completeExceptionally(new IllegalStateException("dropped")));
		ClientCommands.dispatch(level, connection, command -> false);
		check(!dispatched.isDone(), "budget-delayed MCP command does not start its feedback window");
		ClientCommands.dispatch(level, connection, command -> true);
		check(dispatched.join() == 2_000L, "MCP feedback window begins at actual dispatch");
		for (boolean enqueue : List.of(false, true)) {
			var dropped = new java.util.concurrent.CompletableFuture<Void>();
			ClientCommands.enqueue(level, connection, "old", () -> { throw new AssertionError("stale command sent"); },
				() -> dropped.completeExceptionally(new IllegalStateException("session changed")));
			if (enqueue) ClientCommands.enqueue(new Object(), connection, "new", () -> {}, () -> {});
			else ClientCommands.dispatch(null, null, command -> { throw new AssertionError("disconnected command sent"); });
			check(dropped.isCompletedExceptionally(), "session change reports queued MCP command failure");
		}
		ClientCommands.dispatch(null, null, command -> false);
	}

	private static T3State.ThreadRow row(String owner, int index, T3State.Status status) {
		JsonObject raw = new JsonObject(); raw.addProperty("projectId", "project");
		return new T3State.ThreadRow(owner.endsWith("0") ? "A-" + index : "B-" + index, "Thread " + index, "Same project", "Same machine", owner, status, Instant.now(), null, raw);
	}
	private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
