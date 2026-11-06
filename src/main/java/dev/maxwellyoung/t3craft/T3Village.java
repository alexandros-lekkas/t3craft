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

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Your threads as villagers standing around a spot you choose. Name and particles show
 * status; right-click one to open its thread. They exist only on this client (negative
 * entity ids, never sent to the server), so this works on any server. Right-clicks on a
 * server's shared village (real villagers, see {@link ServerVillage}) open threads too.
 */
final class T3Village {
	private static final int FIRST_ID = -7_300_000;

	private final T3CraftClient mod;
	private final Map<String, Mannequin> villagers = new HashMap<>();
	private final Map<Integer, String> threadByEntity = new HashMap<>();
	private ClientLevel level;
	private int ticks;
	private int nextDecorationAttempt;
	private int nextId = FIRST_ID;
	// Office mode: the brain decides where agents go; this mirrors them onto client-side villagers.
	private final OfficeBrain brain = new OfficeBrain();
	private final Map<Integer, String> boards = new HashMap<>();
	private Boolean night;
 private final DeskLaptops laptops=new DeskLaptops();

	T3Village(T3CraftClient mod) {
		this.mod = mod;
	}

	/** Runs at the start of the client tick so a right-click on a villager is claimed before vanilla sees it. */
	void tick(Minecraft minecraft) {
		BlockPos anchor = mod.villageAnchor();
		if (minecraft.level != level || anchor == null) clear();
		level = minecraft.level;
		if (level == null || minecraft.player == null) return;
		ticks++;

		if (minecraft.gui.screen() == null && minecraft.hitResult instanceof EntityHitResult hit) {
			String threadId = threadByEntity.get(hit.getEntity().getId());
			// A shared village's villagers are real entities whose UUID encodes the thread.
			if (threadId == null) threadId = threadForUuid(hit.getEntity().getUUID());
			if (threadId != null && minecraft.options.keyUse.consumeClick()) {
				while (minecraft.options.keyUse.consumeClick()) {
					// Drain queued clicks so vanilla never sends an interact for an entity the server doesn't have.
				}
				brain.face(threadId);
				mod.openAgentCard(threadId);
				return;
			}
		}

		// Shared villagers can be opened by a paired client without placing a local village.
		if (anchor == null) return;

		if (mod.officeMode() && minecraft.gui.screen() == null
			&& minecraft.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit) {
			BlockPos position = hit.getBlockPos();
			int x = position.getX() - anchor.getX(), z = position.getZ() - anchor.getZ();
			int floor = Math.floorDiv(position.getY() - anchor.getY(), T3Office.FLOOR_HEIGHT);
			boolean board = x >= 0 && x <= 1 && z >= 14 && z <= 16;
			boolean lectern = x == 26 && z == 16;
			if (floor >= 0 && floor < Math.max(2, mod.floors().size()*2) && (board || lectern) && minecraft.options.keyUse.consumeClick()) {
				while (minecraft.options.keyUse.consumeClick()) {}
				if (board) mod.openDecisions();
				else mod.openReview(mod.state().focusedThreadId());
				return;
			}
		}

		T3State.Snapshot snapshot = mod.state().snapshot();
		boolean office = mod.officeMode();
		if (office && mod.office().building()) return;
		if (ticks % 10 == 0) {
			if (office) syncOffice(snapshot, anchor);
			else sync(snapshot, anchor);
		}
		for (Map.Entry<String, Mannequin> entry : villagers.entrySet()) {
			Mannequin villager = entry.getValue();
			if (!office) face(villager, minecraft);
			if (ticks % 8 == 0) effects(villager, row(snapshot, entry.getKey()));
		}
	}

	/**
	 * Office mode, after the world has ticked: advance the brain and mirror each agent onto its
	 * villager. Moving here (not before the tick) lets the renderer interpolate from the old
	 * position. Vanilla owns walk animation; only position, yaw and head yaw are set here.
	 */
	void animate(Minecraft minecraft) {
		if (level == null || !mod.officeMode() || minecraft.player == null) return;
		BlockPos o = mod.villageAnchor();
		if (o == null) return;
		brain.tick();
		for (OfficeBrain.Agent agent : brain.agents().values()) {
			Mannequin villager = villagers.get(agent.threadId);
			if (villager == null) continue;
			villager.setPos(o.getX() + agent.x, o.getY() + agent.y(), o.getZ() + agent.z);
			turn(villager, agent.yaw);
   if(minecraft.player.distanceToSqr(villager)<9)agent.faceHold=Math.max(agent.faceHold,8);
   if(agent.faceHold>0) AgentPose.face(villager,minecraft.player);else AgentPose.rest(villager);
   AgentPose.set(villager,agent.settled()&&agent.goal.seat());
			// A drink from the fridge, carried to the sofa.
			boolean holds = !villager.getMainHandItem().isEmpty();
			if (agent.holding != holds) {
				villager.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
					agent.holding ? new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.HONEY_BOTTLE)
						: net.minecraft.world.item.ItemStack.EMPTY);
			}
			// Faint typing from seated workers when you're close.
			if (mod.state().online(agent.threadId) && agent.settled() && agent.zone == OfficeBrain.Zone.WORK && Math.random() < 0.05
				&& minecraft.player.distanceToSqr(villager) < 12 * 12) {
				level.playLocalSound(villager.getX(), villager.getY() + 0.8, villager.getZ(),
					net.minecraft.sounds.SoundEvents.WOODEN_BUTTON_CLICK_ON, net.minecraft.sounds.SoundSource.NEUTRAL,
					0.12F, 1.7F + (float) Math.random() * 0.4F, false);
			}
		}
	}

	private static void turn(Mannequin villager, float yaw) {
		villager.setYRot(yaw);
		villager.setYBodyRot(yaw);

	}

	/** Office mode: sync the brain with the thread list, then villagers, whiteboards and lamps. */
	private void syncOffice(T3State.Snapshot snapshot, BlockPos o) {
		List<String> floors = mod.floors();
		OfficeRoster.Preferences prefs = mod.officePreferences();
		prefs.normalize();
		Map<String, Integer> previousDesks = Map.copyOf(prefs.desks);
		for (OfficeBrain.Agent gone : brain.sync(snapshot.threads(), mod.floorOwners(), prefs, snapshot.machines().stream().filter(T3State.MachineHealth::online).map(T3State.MachineHealth::owner).collect(java.util.stream.Collectors.toSet()))) {
			Mannequin villager = villagers.remove(gone.threadId);
			if (villager != null) {
				threadByEntity.remove(villager.getId());
				level.removeEntity(villager.getId(), Entity.RemovalReason.DISCARDED);
			}
		}
		if (!previousDesks.equals(prefs.desks)) mod.saveOfficePreferences();
		for (OfficeBrain.Agent agent : brain.agents().values()) {
			if (!level.isLoaded(o.offset((int) agent.x, (int) agent.y(), (int) agent.z))) continue;
			Mannequin villager = villagers.get(agent.threadId);
			if (villager == null || villager.isRemoved() || level.getEntity(villager.getId()) != villager) {
				if (villager != null) threadByEntity.remove(villager.getId());
				villager = EntityTypes.MANNEQUIN.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				villager.setId(nextId--);
                villager.noPhysics=true;
				villager.setSilent(true);
				villager.setCustomNameVisible(true);
				villager.snapTo(o.getX() + agent.x, o.getY() + agent.y(), o.getZ() + agent.z, 0, 0);
				level.addEntity(villager);
				villagers.put(agent.threadId, villager);
				threadByEntity.put(villager.getId(), agent.threadId);
			}
			T3State.ThreadRow row = row(snapshot, agent.threadId);
			if (row != null) {
				AgentAppearance.update(villager, row, mod.state().online(row.id()), mod.state().driverFor(row));
			}
		}
		laptops.sync(level,o,brain.agents().values(),Math.max(2,floors.size()*2));
		if (mod.office().building() || ticks < nextDecorationAttempt) return;
		nextDecorationAttempt = ticks + 100;
		// Retry after chunk reloads; never resend unchanged content on a timer.
		// Each floor's whiteboard: who needs you and what for.
		for (int floor = 0; floor < Math.max(2, floors.size()*2); floor++) {
			List<T3Office.Note> notes = brain.notes(floor, mod.state()::waitingDetail);
			String key = notes.toString();
			if (!OfficeSite.loaded(level, o, T3Office.whiteboard(floor, notes))) { boards.remove(floor); continue; }
			if (!key.equals(boards.get(floor))) {
				int index = floor;
				mod.office().send(o, T3Office.whiteboard(floor, notes), () -> boards.put(index, key));
			}
		}
		// Lamps follow this world's day/night cycle.
		boolean dark = OfficeProps.night(level);
		if (!OfficeSite.loaded(level, o, T3Office.lamps(floors.size(), dark))) { night = null; return; }
		if (night == null || night != dark) {
			mod.office().send(o, T3Office.lamps(floors.size(), dark), () -> night = dark);
		}
	}

	/** The thread a shared village or shared office villager stands for, if it is one of ours. */
	private String threadForUuid(java.util.UUID uuid) {
		for (T3State.ThreadRow row : mod.state().snapshot().threads()) {
			if (VillageLayout.villagerUuid(row.id()).equals(uuid) || OfficeBrain.villagerUuid(row.id()).equals(uuid) || DeskLaptops.uuid(row.id(),true).equals(uuid) || DeskLaptops.uuid(row.id(),false).equals(uuid)) return row.id();
		}
		return null;
	}

	/** The office brain, for the self-test. */
	OfficeBrain brainForTest() {
		return brain;
	}

	/** The villager standing in for {@code threadId}, for the dev showcase; null when it is not in this world. */
	Mannequin villagerFor(String threadId) { return villagers.get(threadId); }

	/** Entity id → thread, for the self-test. */
	Map<Integer, String> threadsByEntity() {
		return Map.copyOf(threadByEntity);
	}

 void faceThread(String thread){brain.face(thread);}
	void clear() {
		if (level != null) {
			for (Mannequin villager : villagers.values()) level.removeEntity(villager.getId(), Entity.RemovalReason.DISCARDED);
		}
		villagers.clear();
  laptops.clear();
		threadByEntity.clear();
		brain.clear();
		boards.clear();
		night = null;
	}

	private void sync(T3State.Snapshot snapshot, BlockPos anchor) {
		List<T3State.ThreadRow> rows = snapshot.threads().stream().filter(r -> !T3Visibility.snoozedGroup(r)).limit(VillageLayout.MAX_VILLAGERS).toList();
		Set<String> keep = new HashSet<>();
		for (int slot = 0; slot < rows.size(); slot++) {
			T3State.ThreadRow row = rows.get(slot);
			keep.add(row.id());
			BlockPos column = VillageLayout.column(anchor, slot);
			if (!level.isLoaded(column)) continue;
			int y = VillageLayout.standY(level, column);

			Mannequin villager = villagers.get(row.id());
			if (villager == null || villager.isRemoved() || level.getEntity(villager.getId()) != villager) {
				if (villager != null) threadByEntity.remove(villager.getId());
				villager = EntityTypes.MANNEQUIN.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
				villager.setId(nextId--);
                villager.noPhysics=true;
				villager.setSilent(true);
				villager.setCustomNameVisible(true);
				level.addEntity(villager);
				villagers.put(row.id(), villager);
				threadByEntity.put(villager.getId(), row.id());
			}
			villager.snapTo(column.getX() + 0.5, y, column.getZ() + 0.5, villager.getYRot(), 0);
			AgentAppearance.update(villager, row, mod.state().online(row.id()), mod.state().driverFor(row));
		}
		villagers.entrySet().removeIf(entry -> {
			if (keep.contains(entry.getKey())) return false;
			threadByEntity.remove(entry.getValue().getId());
			level.removeEntity(entry.getValue().getId(), Entity.RemovalReason.DISCARDED);
			return true;
		});
	}

	private static void face(Mannequin villager, Minecraft minecraft) {
		double dx = minecraft.player.getX() - villager.getX();
		double dz = minecraft.player.getZ() - villager.getZ();
		if (dx * dx + dz * dz > 24 * 24) return;
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
		villager.setYRot(yaw);
		villager.setYBodyRot(yaw);

	}

	private void effects(Mannequin villager, T3State.ThreadRow row) {
		if (row == null || !mod.state().online(row.id())) return;
		ParticleOptions particle = switch (row.status()) {
			case WORKING -> ParticleTypes.ENCHANT;
			case NEEDS_YOU -> ParticleTypes.NOTE;
			case ERROR -> ParticleTypes.ANGRY_VILLAGER;
			case DONE -> VillageLayout.recentlyDone(row) ? ParticleTypes.HAPPY_VILLAGER : null;
			case IDLE -> null;
		};
		if (particle == null) return;
		for (int i = 0; i < (row.status() == T3State.Status.WORKING ? 3 : 1); i++) {
			level.addParticle(particle, villager.getX() + (Math.random() - 0.5) * 0.8, villager.getY() + 2.2 + Math.random() * 0.3,
				villager.getZ() + (Math.random() - 0.5) * 0.8, 0, 0.05, 0);
		}
	}

	private static T3State.ThreadRow row(T3State.Snapshot snapshot, String threadId) {
		for (T3State.ThreadRow row : snapshot.threads()) if (row.id().equals(threadId)) return row;
		return null;
	}
}
