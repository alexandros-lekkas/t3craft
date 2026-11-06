package dev.maxwellyoung.t3craft;

import dev.maxwellyoung.t3craft.mixin.DisplayAccess;
import dev.maxwellyoung.t3craft.mixin.MannequinAccess;
import dev.maxwellyoung.t3craft.mixin.TextDisplayAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.Vec3;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Draws {@link AgentTalk} conversations in the client office: the visiting Helper's Mannequin, a speech bubble
 * (a client-side TextDisplay) over whoever is talking, and a small puff when a line starts. Like the agents, every
 * entity here has a negative id and never reaches the server.
 */
final class OfficeTalks {
	private static final int FIRST_ID = -7_400_000;
	/** Above the name tag and status line. */
	private static final double BUBBLE_HEIGHT = 2.6;
	private static final int BUBBLE_BACKGROUND = 0xE8F7F3EA;
	private static final int BUBBLE_TEXT = 0x2B2B2B;

	private final AgentTalk.Director director = new AgentTalk.Director();
	private final Map<AgentTalk.Conversation, Mannequin> helpers = new IdentityHashMap<>();
	private final Map<AgentTalk.Conversation, Display.TextDisplay> bubbles = new IdentityHashMap<>();
	private final Map<AgentTalk.Conversation, String> shown = new IdentityHashMap<>();
	/** Parents whose name tag is hidden while they talk; side by side, two tags and a bubble would overlap. */
	private final Map<String, Mannequin> quiet = new java.util.HashMap<>();
	private int nextId = FIRST_ID;

	AgentTalk.Director director() {
		return director;
	}

	void observe(T3State.Snapshot snapshot, OfficeBrain brain) {
		// The detector takes its first call as the baseline, so wait for T3: a join before it connects must not
		// replay a "Can you take…?" for every subagent that was already running.
		if (!snapshot.connected()) return;
		Set<String> online = snapshot.machines().stream().filter(T3State.MachineHealth::online).map(T3State.MachineHealth::owner)
			.collect(java.util.stream.Collectors.toSet());
		director.observe(snapshot.children(), brain, online);
	}

	/** After the brain has ticked: move the talks along and mirror the helpers and bubbles. */
	void tick(ClientLevel level, OfficeFrame frame, OfficeBrain brain, Function<String, Mannequin> agentEntity, Function<T3State.ThreadRow, String> driver) {
		director.tick(brain);
		var live = director.conversations();
		for (var it = helpers.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next();
			if (live.contains(entry.getKey())) continue;
			level.removeEntity(entry.getValue().getId(), Entity.RemovalReason.DISCARDED);
			it.remove();
		}
		for (var it = bubbles.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next();
			if (live.contains(entry.getKey()) && entry.getKey().speaker() != null) continue;
			level.removeEntity(entry.getValue().getId(), Entity.RemovalReason.DISCARDED);
			shown.remove(entry.getKey());
			it.remove();
		}
		Set<String> talking = new java.util.HashSet<>();
		for (AgentTalk.Conversation conversation : live) if (conversation.speaker() != null) talking.add(conversation.parent.threadId);
		for (var it = quiet.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next();
			if (talking.contains(entry.getKey())) continue;
			entry.getValue().setCustomNameVisible(true);
			it.remove();
		}
		for (AgentTalk.Conversation conversation : live) {
			Mannequin helper = helper(level, frame, conversation, driver);
			boolean speaking = conversation.speaker() != null;
			helper.setCustomNameVisible(!speaking);
			Mannequin parentEntity = agentEntity.apply(conversation.parent.threadId);
			if (speaking && parentEntity != null) {
				parentEntity.setCustomNameVisible(false);
				quiet.put(conversation.parent.threadId, parentEntity);
			}
			OfficeBrain.Agent h = conversation.helper;
			AgentWalk.step(helper, frame.point(h.x, h.y(), h.z), frame.yaw(h.yaw), false);
			helper.setYHeadRot(Mth.rotLerp(.25f, helper.getYHeadRot(), helper.getYRot()));
			AgentPose.set(helper, false);
			OfficeBrain.Agent speaker = conversation.speaker();
			if (speaker == null) continue;
			Entity speakingEntity = speaker == conversation.helper ? helper : parentEntity;
			if (speakingEntity == null) continue;
			Display.TextDisplay bubble = bubble(level, conversation);
			String line = conversation.line();
			if (!line.equals(shown.get(conversation))) {
				((TextDisplayAccess) bubble).t3craft$text(Component.literal(line).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(BUBBLE_TEXT))));
				shown.put(conversation, line);
			}
			bubble.setPos(speakingEntity.getX(), speakingEntity.getY() + BUBBLE_HEIGHT, speakingEntity.getZ());
			if (conversation.lineStarted()) puff(level, speakingEntity, conversation.talk.kind() == AgentTalk.Kind.REPORT && speaker == conversation.helper);
		}
	}

	/** Head yaw for a parent in a conversation: toward whoever it is talking to. NaN when it is not talking. */
	float faceYaw(OfficeFrame frame, String threadId) {
		AgentTalk.Conversation conversation = director.of(threadId);
		if (conversation == null || conversation.phase == AgentTalk.Phase.APPROACH && !conversation.parent.arrived()) return Float.NaN;
		if (conversation.phase == AgentTalk.Phase.LEAVE || conversation.phase == AgentTalk.Phase.DONE) return Float.NaN;
		OfficeBrain.Agent a = conversation.parent, b = conversation.helper;
		return frame.yaw(AgentTalk.yaw(a.x, a.z, b.x, b.z));
	}

	/** The helper's Mannequin and the bubble over the speaker, for the self-test. */
	Mannequin helperFor(AgentTalk.Conversation conversation) { return helpers.get(conversation); }
	Display.TextDisplay bubbleFor(AgentTalk.Conversation conversation) { return bubbles.get(conversation); }

	/** The thread a helper Mannequin stands for, so right-clicking it opens the subagent. */
	String threadFor(int entityId) {
		for (var entry : helpers.entrySet()) if (entry.getValue().getId() == entityId) return entry.getKey().talk.childId();
		return null;
	}

	private Mannequin helper(ClientLevel level, OfficeFrame frame, AgentTalk.Conversation conversation, Function<T3State.ThreadRow, String> driver) {
		Mannequin helper = helpers.get(conversation);
		if (helper != null && !helper.isRemoved() && level.getEntity(helper.getId()) == helper) return helper;
		helper = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
		helper.setId(nextId--);
		helper.noPhysics = true;
		helper.setSilent(true);
		helper.setCustomNameVisible(true);
		OfficeBrain.Agent h = conversation.helper;
		Vec3 at = frame.point(h.x, h.y(), h.z);
		helper.snapTo(at.x, at.y, at.z, frame.yaw(h.yaw), 0);
		T3State.ThreadRow row = conversation.talk.child();
		AgentAppearance.update(helper, row, true, driver.apply(row));
		helper.setCustomName(Component.literal("Helper").withStyle(ChatFormatting.GRAY));
		((MannequinAccess) helper).t3craft$description(Component.literal(AgentTalk.topic(row.title())));
		level.addEntity(helper);
		helpers.put(conversation, helper);
		return helper;
	}

	private Display.TextDisplay bubble(ClientLevel level, AgentTalk.Conversation conversation) {
		Display.TextDisplay bubble = bubbles.get(conversation);
		if (bubble != null && !bubble.isRemoved() && level.getEntity(bubble.getId()) == bubble) return bubble;
		bubble = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
		bubble.setId(nextId--);
		((DisplayAccess) bubble).t3craft$billboard(Display.BillboardConstraints.CENTER);
		((DisplayAccess) bubble).t3craft$viewRange(0.5f);
		((TextDisplayAccess) bubble).t3craft$lineWidth(120);
		((TextDisplayAccess) bubble).t3craft$background(BUBBLE_BACKGROUND);
		level.addEntity(bubble);
		bubbles.put(conversation, bubble);
		shown.remove(conversation);
		return bubble;
	}

	/** A few particles as a line starts: notes for a request, sparkles for a result. */
	private static void puff(ClientLevel level, Entity speaker, boolean result) {
		for (int i = 0; i < 4; i++) {
			level.addParticle(result ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.NOTE, speaker.getX() + (Math.random() - 0.5) * 0.6,
				speaker.getY() + 2.2 + Math.random() * 0.3, speaker.getZ() + (Math.random() - 0.5) * 0.6, Math.random(), 0.05, 0);
		}
	}

	void clear(ClientLevel level) {
		if (level != null) {
			for (Mannequin helper : helpers.values()) level.removeEntity(helper.getId(), Entity.RemovalReason.DISCARDED);
			for (Display.TextDisplay bubble : bubbles.values()) level.removeEntity(bubble.getId(), Entity.RemovalReason.DISCARDED);
		}
		for (Mannequin parent : quiet.values()) parent.setCustomNameVisible(true);
		quiet.clear();
		helpers.clear();
		bubbles.clear();
		shown.clear();
		director.clear();
	}
}
