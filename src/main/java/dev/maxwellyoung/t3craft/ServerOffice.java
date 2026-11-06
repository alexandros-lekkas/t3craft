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
 * The shared office: the same building and brain as the client office, but the agents are real
 * server Mannequins. Bundled skins and furniture require the client resources. Moving them on
 * the server lets each client interpolate and animate the walk on its own.
 */
final class ServerOffice {
	static final String TAG = "t3craft_office";
	/** The tag the removed shared village gave its agents. Tags match exactly, so the office's own tags never match it. */
	static final String LEGACY_VILLAGE_TAG = "t3craft";

	private final T3State state;
	private final T3Config config;
	private final Runnable save;
	private final OfficeBrain brain = new OfficeBrain();
	private final Map<String, Mannequin> mannequins = new HashMap<>();
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

	/** The shared office is built where the operator stood, in the design's own orientation. */
	private OfficeFrame frame() {
		BlockPos o = origin();
		return o == null ? null : OfficeFrame.at(o);
	}

	private record Pending(BlockPos origin, List<String> owners, List<String> labels) {}
	private final Map<String, Pending> pending = new HashMap<>();

	int build(CommandSourceStack source, boolean confirm) {
		MinecraftServer server = source.getServer();
		if (source.getLevel() != server.overworld()) {
			source.sendFailure(net.minecraft.network.chat.Component.literal("The shared office belongs in the overworld.")); return 0;
		}
		if (!config.paired()) {
			source.sendFailure(net.minecraft.network.chat.Component.literal("Pair the server first: /t3office pair <link>")); return 0;
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
		if (!confirm && OfficeSite.occupied(server.overworld(), bounds, frame(), T3Office.blueprint(floors()))) {
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
		if(litter>0)T3Log.LOGGER.warn("Office rebuild swept {} dropped items",litter);
		source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("Suite 408 is up; the agents are walking in."), true);
		return 1;
	}

	void off(MinecraftServer server) {
		pending.clear();
		clear(server);
		config.serverOffice = null;
		save.run();
	}

	/** Removes this office's mannequins (and any left over from a previous run). */
	void clear(MinecraftServer server) {
		for (Mannequin mannequin : mannequins.values()) mannequin.discard();
		mannequins.clear(); laptops.clear();
		brain.clear();
		boards.clear();
		night = null;
		server.getCommands().performPrefixedCommand(quiet(server), "kill @e[tag=" + TAG + "]");
	}

	/** Agents left behind by the shared village (before 0.6): saved with their chunks and invulnerable, so they go as they load. */
	static boolean legacyVillager(java.util.Set<String> tags) { return tags.contains(LEGACY_VILLAGE_TAG); }

	/** A tagged agent loaded from disk (an earlier run) that this office doesn't own, or one from the old village: remove it. */
	void onLoad(net.minecraft.world.entity.Entity entity) {
		if (legacyVillager(entity.entityTags())) { entity.discard(); return; }
		laptops.onLoad(entity);
        if (entity.entityTags().contains(TAG) && !mannequins.containsValue(entity)) {
			entity.discard();
		}
	}

	void tick(MinecraftServer server) {
		OfficeFrame frame = frame();
		if (frame == null) return;
		BlockPos o = frame.origin();
		ServerLevel level = server.overworld();
		if (!level.isLoaded(o)) return;
		ticks++;
		if (ticks % 10 == 0) sync(server, level, frame);
		brain.tick();
		for (OfficeBrain.Agent agent : brain.agents().values()) {
			Mannequin mannequin = mannequins.get(agent.threadId);
			if (mannequin == null || mannequin.isRemoved()) continue;
			mannequin.setPos(frame.point(agent.x, agent.y(), agent.z));
			float yaw = frame.yaw(agent.yaw);
			mannequin.setYRot(yaw);
			mannequin.setYBodyRot(yaw);

   var player=level.getNearestPlayer(mannequin,agent.faceHold>0?32:3);
   if(player!=null){if(player.distanceToSqr(mannequin)<9)agent.faceHold=Math.max(agent.faceHold,8);AgentPose.face(mannequin,player);}else AgentPose.rest(mannequin);
   AgentPose.set(mannequin,agent.settled()&&agent.goal.seat());
			boolean holds = !mannequin.getMainHandItem().isEmpty();
			if (agent.holding != holds) {
				mannequin.setItemSlot(EquipmentSlot.MAINHAND, agent.holding ? new ItemStack(Items.HONEY_BOTTLE) : ItemStack.EMPTY);
			}
			if (state.online(agent.threadId) && agent.settled() && agent.zone == OfficeBrain.Zone.WORK && Math.random() < 0.05) {
				level.playSound(null, mannequin.blockPosition(), SoundEvents.WOODEN_BUTTON_CLICK_ON, SoundSource.NEUTRAL,
					0.12F, 1.7F + (float) Math.random() * 0.4F);
			}
		}
	}

	private void sync(MinecraftServer server, ServerLevel level, OfficeFrame frame) {
		BlockPos o = frame.origin();
		T3State.Snapshot snapshot = state.snapshot();
		List<String> floors = floors();
		config.sharedOfficePreferences.normalize();
		Map<String, Integer> previousDesks = Map.copyOf(config.sharedOfficePreferences.desks);
		for (OfficeBrain.Agent gone : brain.sync(snapshot.threads(), owners(), config.sharedOfficePreferences, snapshot.machines().stream().filter(T3State.MachineHealth::online).map(T3State.MachineHealth::owner).collect(java.util.stream.Collectors.toSet()))) {
			Mannequin mannequin = mannequins.remove(gone.threadId);
			if (mannequin != null) mannequin.discard();
		}
		if (!previousDesks.equals(config.sharedOfficePreferences.desks)) save.run();
		for (OfficeBrain.Agent agent : brain.agents().values()) {
			Mannequin mannequin = mannequins.get(agent.threadId);
			if (mannequin == null || mannequin.isRemoved()) {
				mannequin = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
				if (mannequin == null) continue;
				mannequin.setSilent(true);
				mannequin.setNoGravity(true);
				mannequin.setPermanentlyInvulnerable(true);
				mannequin.addTag(TAG);
				mannequin.setCustomNameVisible(true);
				var at = frame.point(agent.x, agent.y(), agent.z);
				mannequin.snapTo(at.x, at.y, at.z, 0, 0);
				mannequin.setUUID(OfficeBrain.agentUuid(agent.threadId));
				mannequins.put(agent.threadId, mannequin);
				if (!level.addFreshEntity(mannequin)) {
					// A stale copy with this UUID is still in the world; it is discarded on load, so retry next sync.
					mannequins.remove(agent.threadId);
					if (level.getEntity(mannequin.getUUID()) instanceof Mannequin stale) stale.discard();
					continue;
				}
			}
			T3State.ThreadRow row = state.snapshot().threads().stream().filter(r -> r.id().equals(agent.threadId)).findFirst().orElse(null);
			if (row != null) {
				AgentAppearance.update(mannequin, row, state.online(row.id()), state.driverFor(row));
			}
		}
		laptops.sync(level,frame,brain.agents().values(),Math.max(2,floors.size()*2));
		// Invalidate updates while their chunks are unloaded.
		for (int floor = 0; floor < Math.max(2, floors.size()*2); floor++) {
			List<T3Office.Note> notes = brain.notes(floor, state::waitingDetail);
			String key = notes.toString();
			if (!OfficeSite.loaded(level, frame, T3Office.whiteboard(floor, notes))) { boards.remove(floor); continue; }
			if (!key.equals(boards.get(floor))) {
				run(server, frame, T3Office.whiteboard(floor, notes));
				boards.put(floor, key);
			}
		}
		boolean dark = OfficeProps.night(level);
		if (!OfficeSite.loaded(level, frame, T3Office.lamps(floors.size(), dark))) { night = null; return; }
		if (night == null || night != dark) {
			run(server, frame, T3Office.lamps(floors.size(), dark));
			night = dark;
		}
	}

	private static void run(MinecraftServer server, OfficeFrame frame, List<String> commands) {
		CommandSourceStack source = quiet(server);
		for (String command : commands) {
			server.getCommands().performPrefixedCommand(source, frame.command(command));
		}
	}

	private static CommandSourceStack quiet(MinecraftServer server) {
		return server.createCommandSourceStack().withSuppressedOutput();
	}
}
