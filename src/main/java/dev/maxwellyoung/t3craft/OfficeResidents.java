package dev.maxwellyoung.t3craft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.EntityHitResult;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Your threads as player-shaped agents in this world's office. The brain decides where each agent
 * goes; this mirrors them onto client-side Mannequins (negative entity ids, never sent to the
 * server), so it works on any server. Right-click one to open its thread. Right-clicks on a
 * dedicated server's shared office (real entities, see {@link ServerOffice}) open threads too.
 */
final class OfficeResidents {
	private static final int FIRST_ID = -7_300_000;

	private final T3CraftClient mod;
	private final Map<String, Mannequin> agents = new HashMap<>();
	private final Map<Integer, String> threadByEntity = new HashMap<>();
	private ClientLevel level;
	private int ticks;
	private int nextDecorationAttempt;
	private int nextId = FIRST_ID;
	private final OfficeBrain brain = new OfficeBrain();
	/** Thread → skin id for the agents in the office, so two agents only look alike once their provider runs out of skins. */
	private final Map<String, String> skins = new HashMap<>();
	private final Map<Integer, String> boards = new HashMap<>();
	private Boolean night;
	private final DeskLaptops laptops = new DeskLaptops();
	/** Parents meeting their subagents in person. */
	private final OfficeTalks talks = new OfficeTalks();
	private final UsageBoardWall usageBoard;
	/** When each agent last looked up at the player, and what it has in its hand. */
	private final Map<String, AgentGlance> glances = new HashMap<>();
	private final Map<String, String> inHand = new HashMap<>();

	OfficeResidents(T3CraftClient mod) {
		this.mod = mod;
		this.usageBoard = new UsageBoardWall(new UsageBoardSource(mod.state()));
	}

	/** Runs at the start of the client tick so a right-click on an agent is claimed before vanilla sees it. */
	void tick(Minecraft minecraft) {
		// The office's furniture is part of the building, so it does not wait for a pairing; the agents do.
		OfficeFrame frame = mod.officeFrame();
		boolean paired = mod.officeOrigin() != null;
		if (minecraft.level != level || frame == null) clear();
		level = minecraft.level;
		if (level == null || minecraft.player == null) return;
		ticks++;

		if (minecraft.gui.screen() == null && minecraft.hitResult instanceof EntityHitResult hit) {
			String threadId = threadByEntity.get(hit.getEntity().getId());
			// A desk laptop opens the laptop on its agent's thread; the agent itself opens the card.
			String laptop = threadId == null ? laptopThreadForUuid(hit.getEntity().getUUID()) : null;
			// A shared office's agents are real entities whose UUID encodes the thread.
			if (threadId == null) threadId = threadForUuid(hit.getEntity().getUUID());
			// A visiting helper opens the subagent it stands for.
			if (threadId == null) threadId = talks.threadFor(hit.getEntity().getId());
			if (threadId != null && minecraft.options.keyUse.consumeClick()) {
				while (minecraft.options.keyUse.consumeClick()) {
					// Drain queued clicks so vanilla never sends an interact for an entity the server doesn't have.
				}
				if (laptop != null) {
					mod.focus(laptop);
					mod.openLaptop();
					return;
				}
				brain.face(threadId);
				mod.openAgentCard(threadId);
				return;
			}
		}

		// Shared agents can be opened by a paired client without an office of its own.
		if (frame == null) return;
		if (minecraft.gui.screen() == null && minecraft.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit) {
			// Hit tests happen in the design's own frame, whichever way this office is turned.
			BlockPos position = frame.local(hit.getBlockPos());
			int x = position.getX(), z = position.getZ();
			int floor = Math.floorDiv(position.getY(), T3Office.FLOOR_HEIGHT);
			boolean board = x >= 0 && x <= 1 && z >= 14 && z <= 16;
			boolean lectern = x == 26 && z == 16;
			if (floor >= 0 && floor < Math.max(2, mod.floors().size() * 2) && (board || lectern) && minecraft.options.keyUse.consumeClick()) {
				while (minecraft.options.keyUse.consumeClick()) {}
				if (board) mod.openDecisions();
				else mod.openReview(mod.state().focusedThreadId());
				return;
			}
		}

		T3State.Snapshot snapshot = mod.state().snapshot();
		if (mod.office().building()) return;
		if (ticks % 10 == 0) sync(snapshot, frame, paired, minecraft.player);
		if (ticks % 8 == 0) {
			for (Map.Entry<String, Mannequin> entry : agents.entrySet()) effects(entry.getValue(), row(snapshot, entry.getKey()));
		}
	}

	/**
	 * After the world has ticked: advance the brain and mirror each agent onto its Mannequin. Moving
	 * here (not before the tick) lets the renderer interpolate from the old position. Vanilla owns
	 * walk animation; only position, yaw and head yaw are set here.
	 */
	void animate(Minecraft minecraft) {
		if (level == null || minecraft.player == null) return;
		OfficeFrame frame = mod.officeFrame();
		if (frame == null) return;
		brain.settleStragglers();
		brain.tick();
		talks.tick(level, frame, brain, agents::get, mod.state()::driverFor);
		for (OfficeBrain.Agent agent : brain.agents().values()) {
			Mannequin mannequin = agents.get(agent.threadId);
			if (mannequin == null) continue;
			// Walk there: interpolated position, a body that turns into the heading, and legs that swing.
			AgentWalk.step(mannequin, frame.point(agent.x, agent.y(), agent.z), frame.yaw(agent.yaw), agent.settled() && agent.goal.seat());
			float talking = talks.faceYaw(frame, agent.threadId);
			boolean seated = agent.settled() && agent.goal.seat(), online = mod.state().online(agent.threadId);
			int seed = AgentGlance.seed(agent.threadId);
			AgentLife.Activity activity = AgentLife.Activity.FREE;
			if (!Float.isNaN(talking)) {
				AgentPose.toward(mannequin, talking);
				activity = talks.activity(agent.threadId);
			} else {
				// A glance when you walk up, then back to work; the one that needs you watches you. An agent on its way
				// somewhere keeps walking (holding it froze it mid-route for as long as you stood near).
				double distance = Math.sqrt(minecraft.player.distanceToSqr(mannequin));
				boolean waiting = agent.zone == OfficeBrain.Zone.NEEDS_YOU && agent.settled();
				boolean glance = agent.settled() && glances.computeIfAbsent(agent.threadId, id -> new AgentGlance())
					.tick(distance, minecraft.crosshairPickEntity == mannequin && distance < 6, waiting, seed);
				boolean looking = online && !agent.errand && (agent.faceHold > 0 || glance);
				if (looking) AgentPose.face(mannequin, minecraft.player, seated); else AgentPose.rest(mannequin);
				// Hands follow the eyes: nobody types, sips or stretches while looking at you, and work resumes once it has turned back.
				if (!online) activity = AgentLife.Activity.FREE;
				else if (waiting) activity = distance > 3 && distance < AgentGlance.WAITING ? AgentLife.Activity.WAVE : AgentLife.Activity.FREE;
				else if (seated && !looking && AgentPose.turned(mannequin) < 12) {
					activity = agent.zone == OfficeBrain.Zone.WORK ? AgentLife.desk(seed, ticks) : AgentLife.Activity.LOUNGE;
				}
			}
			AgentLife.set(mannequin, activity, seed, agent.holding);
			activity = AgentLife.activity(mannequin);
			AgentPose.set(mannequin, agent.settled() && agent.goal.seat());
			// In the hand: the drink from the fridge, carried to the sofa; or the desk mug, for a sip.
			String want = agent.holding ? "drink" : activity == AgentLife.Activity.SIP ? "mug" : "";
			if (!want.equals(inHand.getOrDefault(agent.threadId, ""))) {
				net.minecraft.world.item.ItemStack stack = net.minecraft.world.item.ItemStack.EMPTY;
				if (want.equals("drink")) stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.HONEY_BOTTLE);
				else if (want.equals("mug")) {
					stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER);
					stack.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, net.minecraft.resources.Identifier.fromNamespaceAndPath("t3craft", "coffee_mug"));
				}
				mannequin.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, stack);
				inHand.put(agent.threadId, want);
			}
			// Faint typing from seated workers when you're close, only while the hands are on the keys.
			if (activity == AgentLife.Activity.TYPE && Math.random() < 0.07 * AgentLife.burst(seed, mannequin.tickCount + seed % 613)
				&& minecraft.player.distanceToSqr(mannequin) < 12 * 12) {
				level.playLocalSound(mannequin.getX(), mannequin.getY() + 0.8, mannequin.getZ(),
					net.minecraft.sounds.SoundEvents.WOODEN_BUTTON_CLICK_ON, net.minecraft.sounds.SoundSource.NEUTRAL,
					0.12F, 1.7F + (float) Math.random() * 0.4F, false);
			}
		}
	}

	/**
	 * Sync the brain with the thread list, then the agents, desk laptops, props, usage board, whiteboards and lamps.
	 * Unpaired, the office keeps its props, lamps and usage board (asking you to connect T3) and has no agents.
	 */
	private void sync(T3State.Snapshot snapshot, OfficeFrame frame, boolean paired, net.minecraft.world.entity.player.Player player) {
		List<String> floors = mod.floors();
		if (paired) {
			syncResidents(snapshot, frame);
			talks.observe(snapshot, brain);
		}
		else if (!agents.isEmpty() || !brain.agents().isEmpty()) clearResidents();
		laptops.sync(level, frame, paired ? brain.agents().values() : List.of(), Math.max(2, floors.size() * 2));
		usageBoard.sync(level, frame, paired, player);
		if (mod.office().building() || ticks < nextDecorationAttempt) return;
		nextDecorationAttempt = ticks + 100;
		// Retry after chunk reloads; never resend unchanged content on a timer.
		// Each floor's whiteboard: who needs you and what for.
		for (int floor = 0; floor < Math.max(2, floors.size() * 2); floor++) {
			List<T3Office.Note> notes = brain.notes(floor, mod.state()::waitingDetail);
			String key = notes.toString();
			if (!OfficeSite.loaded(level, frame, T3Office.whiteboard(floor, notes))) { boards.remove(floor); continue; }
			if (!key.equals(boards.get(floor))) {
				int index = floor;
				mod.office().send(frame, T3Office.whiteboard(floor, notes), () -> boards.put(index, key));
			}
		}
		// Lamps follow this world's day/night cycle.
		boolean dark = OfficeProps.night(level);
		if (!OfficeSite.loaded(level, frame, T3Office.lamps(floors.size(), dark))) { night = null; return; }
		if (night == null || night != dark) {
			mod.office().send(frame, T3Office.lamps(floors.size(), dark), () -> night = dark);
		}
	}

	/** The paired part of the office: one Mannequin per agent the brain seats. */
	private void syncResidents(T3State.Snapshot snapshot, OfficeFrame frame) {
		OfficeRoster.Preferences prefs = mod.officePreferences();
		prefs.normalize();
		Map<String, Integer> previousDesks = Map.copyOf(prefs.desks);
		for (OfficeBrain.Agent gone : brain.sync(snapshot.threads(), mod.floorOwners(), prefs, snapshot.machines().stream().filter(T3State.MachineHealth::online).map(T3State.MachineHealth::owner).collect(java.util.stream.Collectors.toSet()))) {
			Mannequin mannequin = agents.remove(gone.threadId);
			glances.remove(gone.threadId);
			inHand.remove(gone.threadId);
			if (mannequin != null) {
				threadByEntity.remove(mannequin.getId());
				level.removeEntity(mannequin.getId(), Entity.RemovalReason.DISCARDED);
			}
		}
		if (!previousDesks.equals(prefs.desks)) mod.saveOfficePreferences();
		// Skins in a stable order, so who gets the fallback when two agents' usual skins collide never flips between visits.
		skins.keySet().retainAll(brain.agents().keySet());
		for (String id : new java.util.TreeSet<>(brain.agents().keySet())) {
			T3State.ThreadRow row = row(snapshot, id);
			if (row != null) AgentAppearance.skin(mod.state().driverFor(row), id, skins);
		}
		for (OfficeBrain.Agent agent : brain.agents().values()) {
			if (!level.isLoaded(frame.block((int) Math.floor(agent.x), (int) agent.y(), (int) Math.floor(agent.z)))) continue;
			Mannequin mannequin = agents.get(agent.threadId);
			if (mannequin == null || mannequin.isRemoved() || level.getEntity(mannequin.getId()) != mannequin) {
				if (mannequin != null) threadByEntity.remove(mannequin.getId());
				mannequin = EntityTypes.MANNEQUIN.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				mannequin.setId(nextId--);
				mannequin.noPhysics = true;
				mannequin.setSilent(true);
				mannequin.setCustomNameVisible(true);
				var at = frame.point(agent.x, agent.y(), agent.z);
				mannequin.snapTo(at.x, at.y, at.z, frame.yaw(agent.yaw), 0);
				level.addEntity(mannequin);
				agents.put(agent.threadId, mannequin);
				inHand.remove(agent.threadId);
				threadByEntity.put(mannequin.getId(), agent.threadId);
			}
			T3State.ThreadRow row = row(snapshot, agent.threadId);
			if (row != null) {
				String driver = mod.state().driverFor(row);
				AgentAppearance.update(mannequin, row, mod.state().online(row.id()), driver, AgentAppearance.skin(driver, row.id(), skins));
			}
		}
	}

	/** The thread a desk laptop (its display or its hitbox) belongs to, if it is one of ours. */
	private String laptopThreadForUuid(java.util.UUID uuid) {
		for (T3State.ThreadRow row : mod.state().snapshot().threads()) {
			if (DeskLaptops.uuid(row.id(), true).equals(uuid) || DeskLaptops.uuid(row.id(), false).equals(uuid)) return row.id();
		}
		return null;
	}

	/** The thread a shared office agent or desk laptop stands for, if it is one of ours. */
	private String threadForUuid(java.util.UUID uuid) {
		for (T3State.ThreadRow row : mod.state().snapshot().threads()) {
			if (OfficeBrain.agentUuid(row.id()).equals(uuid) || DeskLaptops.uuid(row.id(), true).equals(uuid) || DeskLaptops.uuid(row.id(), false).equals(uuid)) return row.id();
		}
		return null;
	}

	/** The office brain, for the self-test. */
	OfficeBrain brainForTest() {
		return brain;
	}

	/** The Mannequin standing in for {@code threadId}, for the dev showcase; null when it is not in this world. */
	Mannequin mannequinFor(String threadId) { return agents.get(threadId); }

	/** The usage board on the wall, for the self-test. */
	UsageBoardWall usageBoardForTest() { return usageBoard; }

	/** Entity id → thread, for the self-test. */
	Map<Integer, String> threadsByEntity() {
		return Map.copyOf(threadByEntity);
	}

	void faceThread(String thread) { brain.face(thread); }

	/** Conversations between agents, for the self-test and dev showcase. */
	OfficeTalks talks() { return talks; }

	void clear() {
		clearResidents();
		laptops.clear();
		usageBoard.clear();
		boards.clear();
		night = null;
	}

	/** Removes the agents (unpaired, or leaving the world); the office's props stay with {@link #laptops}, the board with {@link #usageBoard}. */
	private void clearResidents() {
		if (level != null) {
			for (Mannequin mannequin : agents.values()) level.removeEntity(mannequin.getId(), Entity.RemovalReason.DISCARDED);
		}
		talks.clear(level);
		agents.clear();
		glances.clear();
		inHand.clear();
		threadByEntity.clear();
		skins.clear();
		brain.clear();
	}

	private void effects(Mannequin mannequin, T3State.ThreadRow row) {
		if (row == null || !mod.state().online(row.id())) return;
		// The tag's dot and the typing say "working"; enchant glyphs drifting through the tag only made it harder to read.
		ParticleOptions particle = switch (row.status()) {
			case WORKING -> null;
			case NEEDS_YOU -> ticks % 40 == 0 ? ParticleTypes.NOTE : null;
			case ERROR -> ParticleTypes.ANGRY_VILLAGER;
			case DONE -> recentlyDone(row) ? ParticleTypes.HAPPY_VILLAGER : null;
			case IDLE -> null;
		};
		if (particle == null) return;
		for (int i = 0; i < 1; i++) {
			level.addParticle(particle, mannequin.getX() + (Math.random() - 0.5) * 0.8, mannequin.getY() + 2.55 + Math.random() * 0.3,
				mannequin.getZ() + (Math.random() - 0.5) * 0.8, 0, 0.05, 0);
		}
	}

	/** Recently finished threads sparkle for two minutes. */
	static boolean recentlyDone(T3State.ThreadRow row) {
		var turn = row.raw().get("latestTurn");
		if (turn == null || !turn.isJsonObject() || !turn.getAsJsonObject().has("completedAt")
			|| turn.getAsJsonObject().get("completedAt").isJsonNull()) return false;
		return Instant.parse(turn.getAsJsonObject().get("completedAt").getAsString()).plus(Duration.ofMinutes(2)).isAfter(Instant.now());
	}

	private static T3State.ThreadRow row(T3State.Snapshot snapshot, String threadId) {
		for (T3State.ThreadRow row : snapshot.threads()) if (row.id().equals(threadId)) return row;
		return null;
	}
}
