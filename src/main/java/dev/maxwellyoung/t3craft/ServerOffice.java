package dev.maxwellyoung.t3craft;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared office: the same building and brain as the client village, but the agents are real
 * server Mannequins. Bundled skins and furniture require the client resources. Moving them on
 * the server lets each client interpolate and animate the walk on its own.
 */
final class ServerOffice {
	static final String TAG = "t3craft_office";

	private final T3State state;
	private final T3Config config;
	private final Runnable save;
	private final OfficeBrain brain = new OfficeBrain();
	private final Map<String, Mannequin> villagers = new HashMap<>();
	private final Map<Integer, String> boards = new HashMap<>();
	private Boolean night;
 private final DeskLaptops laptops=new DeskLaptops();
	private int ticks;

	ServerOffice(T3State state, T3Config config, Runnable save) {
		this.state = state;
		this.config = config;
		this.save = save;
	}

	List<String> floors() {
		return owners().stream().map(config::labelFor).toList();
	}

	List<String> owners() {
		if (config.sharedOfficePreferences.floorOwners == null) {
			config.sharedOfficePreferences.floorOwners = config.environments.stream().map(e -> T3Config.ownerKey(e.baseUrl)).toList();
			save.run();
		}
		return config.sharedOfficePreferences.floorOwners;
	}

	boolean pin(String threadId, boolean remove) {
		if (remove) {
			boolean removed = config.sharedOfficePreferences.pins.removeIf(p -> p.threadId().equals(threadId));
			if (removed) save.run();
			return removed;
		}
		T3State.ThreadRow row = state.snapshot().threads().stream().filter(r -> r.id().equals(threadId)).findFirst().orElse(null);
		if (row == null || !config.sharedOfficePreferences.pin(row)) return false;
		save.run(); return true;
	}

	BlockPos origin() {
		return config.serverOffice == null ? null : new BlockPos(config.serverOffice[0], config.serverOffice[1], config.serverOffice[2]);
	}

	private record Pending(BlockPos origin, List<String> owners, List<String> labels) {}
	private final Map<String, Pending> pending = new HashMap<>();

	int build(CommandSourceStack source, boolean confirm) {
		MinecraftServer server = source.getServer();
		if (source.getLevel() != server.overworld()) {
			source.sendFailure(net.minecraft.network.chat.Component.literal("The shared office belongs in the overworld.")); return 0;
		}
		if (!config.paired()) {
			source.sendFailure(net.minecraft.network.chat.Component.literal("Pair the server first: /t3village pair <link>")); return 0;
		}
		String requester = source.getTextName();
		List<String> owners = config.environments.stream().map(e -> T3Config.ownerKey(e.baseUrl)).toList();
		Pending request = confirm ? pending.remove(requester) : new Pending(BlockPos.containing(source.getPosition()).offset(-10, 0, 5), owners, owners.stream().map(config::labelFor).toList());
		if (request == null) {
			source.sendFailure(net.minecraft.network.chat.Component.literal("Run /t3office build first to select a site.")); return 0;
		}
		var blueprint = T3Office.blueprint(request.labels());
		var bounds = OfficeSite.bounds(request.origin(), blueprint);
		source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Office bounds: " + bounds.describe()), false);
		if (!OfficeSite.loaded(server.overworld(), request.origin(), blueprint)) {
			pending.remove(requester);
			source.sendFailure(net.minecraft.network.chat.Component.literal("The entire site must be loaded and inside the world's height limits.")); return 0;
		}
		if (!confirm && OfficeSite.occupied(server.overworld(), bounds, origin(), T3Office.blueprint(floors()))) {
			pending.put(requester, request);
			source.sendFailure(net.minecraft.network.chat.Component.literal("This site contains existing blocks. Run /t3office build confirm to replace them at these exact bounds.")); return 0;
		}
		pending.remove(requester);
		clear(server);
		config.sharedOfficePreferences.floorOwners = request.owners();
		BlockPos origin = request.origin();
		config.serverOffice = new int[] {origin.getX(), origin.getY(), origin.getZ()};
		save.run();
		OfficePlacement.place(server.overworld(),origin,request.labels());
		int litter=OfficePlacement.sweep(server.overworld(),origin,Math.max(2,request.labels().size()*2));
		if(litter>0)T3CraftClient.LOGGER.warn("Office rebuild swept {} dropped items",litter);
		source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Suite 408 is up; the agents are walking in."), true);
		return 1;
	}

	void off(MinecraftServer server) {
		pending.clear();
		clear(server);
		config.serverOffice = null;
		save.run();
	}

	/** Removes this office's villagers (and any left over from a previous run). */
	void clear(MinecraftServer server) {
		for (Mannequin villager : villagers.values()) villager.discard();
		villagers.clear(); laptops.clear();
		brain.clear();
		boards.clear();
		night = null;
		server.getCommands().performPrefixedCommand(quiet(server), "kill @e[tag=" + TAG + "]");
	}

	/** A tagged villager loaded from disk (an earlier run) that this office doesn't own: remove it. */
	void onLoad(net.minecraft.world.entity.Entity entity) {
		laptops.onLoad(entity);
        if (entity.entityTags().contains(TAG) && !villagers.containsValue(entity)) {
			entity.discard();
		}
	}

	void tick(MinecraftServer server) {
		BlockPos o = origin();
		if (o == null) return;
		ServerLevel level = server.overworld();
		if (!level.isLoaded(o)) return;
		ticks++;
		if (ticks % 10 == 0) sync(server, level, o);
		brain.tick();
		for (OfficeBrain.Agent agent : brain.agents().values()) {
			Mannequin villager = villagers.get(agent.threadId);
			if (villager == null || villager.isRemoved()) continue;
			villager.setPos(o.getX() + agent.x, o.getY() + agent.y(), o.getZ() + agent.z);
			villager.setYRot(agent.yaw);
			villager.setYBodyRot(agent.yaw);

   var player=level.getNearestPlayer(villager,agent.faceHold>0?32:3);
   if(player!=null){if(player.distanceToSqr(villager)<9)agent.faceHold=Math.max(agent.faceHold,8);AgentPose.face(villager,player);}else AgentPose.rest(villager);
   AgentPose.set(villager,agent.settled()&&agent.goal.seat());
			boolean holds = !villager.getMainHandItem().isEmpty();
			if (agent.holding != holds) {
				villager.setItemSlot(EquipmentSlot.MAINHAND, agent.holding ? new ItemStack(Items.HONEY_BOTTLE) : ItemStack.EMPTY);
			}
			if (state.online(agent.threadId) && agent.settled() && agent.zone == OfficeBrain.Zone.WORK && Math.random() < 0.05) {
				level.playSound(null, villager.blockPosition(), SoundEvents.WOODEN_BUTTON_CLICK_ON, SoundSource.NEUTRAL,
					0.12F, 1.7F + (float) Math.random() * 0.4F);
			}
		}
	}

	private void sync(MinecraftServer server, ServerLevel level, BlockPos o) {
		T3State.Snapshot snapshot = state.snapshot();
		List<String> floors = floors();
		config.sharedOfficePreferences.normalize();
		Map<String, Integer> previousDesks = Map.copyOf(config.sharedOfficePreferences.desks);
		for (OfficeBrain.Agent gone : brain.sync(snapshot.threads(), owners(), config.sharedOfficePreferences, snapshot.machines().stream().filter(T3State.MachineHealth::online).map(T3State.MachineHealth::owner).collect(java.util.stream.Collectors.toSet()))) {
			Mannequin villager = villagers.remove(gone.threadId);
			if (villager != null) villager.discard();
		}
		if (!previousDesks.equals(config.sharedOfficePreferences.desks)) save.run();
		for (OfficeBrain.Agent agent : brain.agents().values()) {
			Mannequin villager = villagers.get(agent.threadId);
			if (villager == null || villager.isRemoved()) {
				villager = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
				if (villager == null) continue;
				villager.setSilent(true);
				villager.setNoGravity(true);
				villager.setPermanentlyInvulnerable(true);
				villager.addTag(TAG);
				villager.setCustomNameVisible(true);
				villager.snapTo(o.getX() + agent.x, o.getY() + agent.y(), o.getZ() + agent.z, 0, 0);
				villager.setUUID(OfficeBrain.villagerUuid(agent.threadId));
				villagers.put(agent.threadId, villager);
				if (!level.addFreshEntity(villager)) {
					// A stale copy with this UUID is still in the world; it is discarded on load, so retry next sync.
					villagers.remove(agent.threadId);
					if (level.getEntity(villager.getUUID()) instanceof Mannequin stale) stale.discard();
					continue;
				}
			}
			T3State.ThreadRow row = state.snapshot().threads().stream().filter(r -> r.id().equals(agent.threadId)).findFirst().orElse(null);
			if (row != null) {
				AgentAppearance.update(villager, row, state.online(row.id()), state.driverFor(row));
			}
		}
		laptops.sync(level,o,brain.agents().values(),Math.max(2,floors.size()*2));
		// Invalidate updates while their chunks are unloaded.
		for (int floor = 0; floor < Math.max(2, floors.size()*2); floor++) {
			List<T3Office.Note> notes = brain.notes(floor, state::waitingDetail);
			String key = notes.toString();
			if (!OfficeSite.loaded(level, o, T3Office.whiteboard(floor, notes))) { boards.remove(floor); continue; }
			if (!key.equals(boards.get(floor))) {
				run(server, o, T3Office.whiteboard(floor, notes));
				boards.put(floor, key);
			}
		}
		boolean dark = OfficeProps.night(level);
		if (!OfficeSite.loaded(level, o, T3Office.lamps(floors.size(), dark))) { night = null; return; }
		if (night == null || night != dark) {
			run(server, o, T3Office.lamps(floors.size(), dark));
			night = dark;
		}
	}

	private static void run(MinecraftServer server, BlockPos o, List<String> commands) {
		CommandSourceStack source = quiet(server);
		for (String command : commands) {
			server.getCommands().performPrefixedCommand(source, T3Office.absolute(command, o.getX(), o.getY(), o.getZ()));
		}
	}

	private static CommandSourceStack quiet(MinecraftServer server) {
		return server.createCommandSourceStack().withSuppressedOutput();
	}
}
