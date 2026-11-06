package dev.maxwellyoung.t3craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.math.Transformation;
import java.util.*;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Full original prop pack, with stable identities and updates only when visual state changes. */
final class OfficeProps {
 static final JsonArray PROPS = OfficeDesign.load("/data/t3craft/office/props.json").getAsJsonArray("props");
 private static final class Prop {
  final Display.ItemDisplay display;
  String state;
  float swivel;
  Prop(Display.ItemDisplay display) { this.display = display; }
 }
 private final Map<UUID, Prop> displays = new HashMap<>();
 private static int nextId = -7_900_000;
 static boolean night(Level level) { long time = Math.floorMod(level.getOverworldClockTime(), 24000); return time >= 12600 && time < 23400; }
 static String state(String item, String base, T3State.Status status, boolean night) {
  return switch (item) {
   case "coffee_mug" -> base + (status == T3State.Status.WORKING ? "_steam" : "");
   case "monitor" -> status == null ? "off" : status == T3State.Status.WORKING ? "on" : "idle";
   case "desk_lamp" -> night ? "on" : "off";
   default -> base;
  };
 }
 void sync(Level level, OfficeFrame frame, int floors, Collection<OfficeBrain.Agent> agents) {
  BlockPos origin = frame.origin();
  Map<String, OfficeBrain.Agent> desks = new HashMap<>();
  for (var agent : agents) desks.put(agent.floor + ":" + agent.desk, agent);
  for (int floor = 0; floor < floors; floor++) for (var e : PROPS) {
   JsonObject p = e.getAsJsonObject();
   String item = p.get("item").getAsString();
   UUID id = UUID.nameUUIDFromBytes(("t3craft:prop:" + origin + ":" + floor + ":" + p.get("id").getAsString()).getBytes(StandardCharsets.UTF_8));
   Prop prop = displays.get(id);
   boolean fresh = prop == null || prop.display.isRemoved();
   if (fresh) {
    var display = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND);
    display.setUUID(id); display.addTag("t3craft_prop");
    display.setItemTransform(ItemDisplayContext.FIXED);
    display.setTransformationInterpolationDuration(8);
    display.setViewRange(p.has("viewRange") ? p.get("viewRange").getAsFloat() : .8f);
    prop = new Prop(display); displays.put(id, prop);
   }
   var display = prop.display;
   var pos = p.getAsJsonArray("pos");
   var at = frame.point(pos.get(0).getAsDouble(), 6 * floor + pos.get(1).getAsDouble(), pos.get(2).getAsDouble());
   double x = at.x, y = at.y, z = at.z;
   float yaw = frame.yaw(p.get("yaw").getAsFloat());
   var agent = p.has("desk") ? desks.get(floor + ":" + p.get("desk").getAsInt()) : null;
   String base = p.has("state") && !p.get("state").isJsonNull() ? p.get("state").getAsString() : "";
   String state = state(item, base, agent == null ? null : agent.status, night(level));
   if (!state.equals(prop.state)) {
    var stack = new ItemStack(Items.PAPER);
    stack.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("t3craft", item));
    if (!state.isEmpty()) stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(state), List.of()));
    display.setItemStack(stack); prop.state = state;
   }
   if (item.equals("desk_chair")) {
    float swivel = 0;
    if (agent == null) { x -= Math.sin(Math.toRadians(yaw)) * .18; z += Math.cos(Math.toRadians(yaw)) * .18; }
    else if (agent.status == T3State.Status.NEEDS_YOU || agent.status == T3State.Status.DONE && agent.faceHold > 0) {
     var player = level.getNearestPlayer(x, y, z, 32, false);
     if (player != null) {
      float toward = (float) Math.toDegrees(Math.atan2(player.getZ() - z, player.getX() - x)) - 90;
      swivel = Math.round(Mth.clamp(Mth.wrapDegrees(toward - yaw), -150, 150) / 15) * 15;
     } else swivel = prop.swivel;
    }
    if (swivel != prop.swivel) {
     display.setTransformationInterpolationDelay(0);
     display.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotationY((float) Math.toRadians(-swivel)), new Vector3f(1), new Quaternionf()));
     prop.swivel = swivel;
    }
   }
   display.setPos(x, y, z); display.setYRot(yaw);
   if (fresh) {
    if (level instanceof net.minecraft.server.level.ServerLevel server) {
     var stale = server.getEntity(id); if (stale != null) stale.discard(); server.addFreshEntity(display);
    } else if (level instanceof net.minecraft.client.multiplayer.ClientLevel client) {
     display.setId(nextId--); client.addEntity(display);
    }
   }
  }
 }
 void onLoad(Entity entity) {
  if (entity.entityTags().contains("t3craft_prop") && displays.values().stream().noneMatch(p -> p.display == entity)) entity.discard();
 }
 void clear() { displays.values().forEach(p -> p.display.discard()); displays.clear(); }
}
