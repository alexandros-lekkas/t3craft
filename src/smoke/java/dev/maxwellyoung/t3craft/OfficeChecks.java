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
	public static void main(String[] args) throws Exception {
		checkPlacement();
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
		check(selected.size() == 16, "each machine has eight residents despite duplicate names");
		check(selected.stream().anyMatch(r -> r.row().equals(pin)), "old pin beats recent threads");
		check(selected.stream().filter(r -> r.row().status() == T3State.Status.NEEDS_YOU).count() == 2, "old waiting work is included on both machines");
		check(selected.stream().filter(r -> r.row().status() == T3State.Status.WORKING).count() == 2, "working rows beat recent done rows");
		Map<String, Integer> before = Map.copyOf(prefs.desks);
		List<T3State.ThreadRow> reordered = new ArrayList<>(selected.stream().map(OfficeRoster.Resident::row).toList());
		Collections.reverse(reordered);
		OfficeRoster.sync(reordered, List.of(a, b), prefs);
		check(before.equals(prefs.desks), "recency reorder keeps every included desk");
		OfficeBrain brain = new OfficeBrain(); brain.sync(reordered, List.of(a, b), prefs);
		check(brain.agents().values().stream().filter(r -> r.floor == 1).count() == 8, "same display name still has a distinct second floor");
		check(brain.agents().get(pin.id()).desk == before.get(OfficeRoster.key(pin)), "brain consumes the saved desk");
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
			check(brain.agents().values().stream().allMatch(agent -> agent.floor == 1), "removing a pairing does not shift the built second floor");
			restored.environments.addFirst(new T3Config.Environment("Same name", a, "fixture-only"));
			check(restored.officePreferences.get("world-a").pinned(pin), "pin survives actual JSON save/load");
			OfficeRoster.sync(reordered, List.of(a, b), restored.officePreferences.get("world-a"));
			check(before.equals(restored.officePreferences.get("world-a").desks), "all desks survive process-style reload");
			check(restored.sharedOfficePreferences.pins.isEmpty() && restored.officePreferences.get("world-b").pins.isEmpty(), "client worlds and operator-owned pins are isolated");
			restored.upsert(new T3Config.Environment("New name", a + "/", "fixture-only"));
			check(restored.environments.size() == 2 && restored.environments.getFirst().label.equals("New name"), "re-pair keeps floor order and owner identity");
			if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) check(Files.getPosixFilePermissions(file).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")), "saved config is owner-only");
		} finally { Files.deleteIfExists(file); Files.deleteIfExists(directory); }

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
		for (int i = 0; i < 8; i++) check(capacity.pin(rows.get(i)), "eight pins accepted");
		check(!capacity.pin(rows.get(8)) && capacity.pin(rows.get(12)), "limit applies per owner");
		List<T3State.ThreadRow> waiting = new ArrayList<>();
		for (int i = 0; i < 8; i++) waiting.add(row(a, i, T3State.Status.NEEDS_YOU));
		brain.clear(); brain.sync(waiting, List.of(a), new OfficeRoster.Preferences());
		check(brain.agents().values().stream().map(r -> r.goal).distinct().count() == 8, "eight waiting residents do not share a physical spot");
		System.out.println("PASS plain-field/build occupancy, daytime lamps/signs, shared command budget, malformed desks, unloaded-target cancellation, queue world/session binding, unavailable-owner retention, built floor persistence, stable desks, pinned/waiting/working priority, restart and reconnect persistence, distinct owners/projects, pin removal and shared/client isolation");
	}
	private static void checkPlacement() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		var origin = new net.minecraft.core.BlockPos(0, 64, 0);
		var blueprint = T3Office.blueprint(List.of("Machine"));
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
		blocks.put(spot, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
		check(OfficeSite.occupied(bounds, null, List.of(), read, pos -> null), "player blocks above ground require confirmation");
		blocks.clear();
		var chest = new net.minecraft.world.level.block.entity.ChestBlockEntity(bounds.min(), net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
		check(OfficeSite.occupied(bounds, null, List.of(), read, pos -> pos.equals(bounds.min()) ? chest : null), "buried containers remain protected");
		// Recognize both lamp states and the office's own sign block entities on rebuild.
		var lamp = origin.offset(6, T3Office.HEIGHT - 3, 9);
		var sign = origin.offset(2, 3, 27);
		blocks.put(lamp, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("white_concrete")).defaultBlockState());
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
