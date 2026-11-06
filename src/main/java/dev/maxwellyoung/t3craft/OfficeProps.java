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
import net.minecraft.world.phys.Vec3;
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
 /** One prop as placed: its stable identity, what it shows, and where it stands in the world. */
 record Placement(UUID id, String item, String base, Vec3 pos, float yaw, int floor, int desk, float viewRange) {}

 /** A prop's identity: the office origin, the floor and the prop's design id, so a rebuild replaces it instead of adding another. */
 static UUID uuid(BlockPos origin, int floor, String id) {
  return UUID.nameUUIDFromBytes(("t3craft:prop:" + origin + ":" + floor + ":" + id).getBytes(StandardCharsets.UTF_8));
 }

 /** Every prop of a {@code floors}-floor office in this frame, turned with it. Pure, so it is checked without a world. */
 static List<Placement> placements(OfficeFrame frame, int floors) {
  List<Placement> out = new ArrayList<>(PROPS.size() * floors);
  for (int floor = 0; floor < floors; floor++) for (var e : PROPS) {
   JsonObject p = e.getAsJsonObject();
   var pos = p.getAsJsonArray("pos");
   String base = p.has("state") && !p.get("state").isJsonNull() ? p.get("state").getAsString() : "";
   out.add(new Placement(uuid(frame.origin(), floor, p.get("id").getAsString()), p.get("item").getAsString(), base,
    frame.point(pos.get(0).getAsDouble(), T3Office.FLOOR_HEIGHT * floor + pos.get(1).getAsDouble(), pos.get(2).getAsDouble()),
    frame.yaw(p.get("yaw").getAsFloat()), floor, p.has("desk") ? p.get("desk").getAsInt() : -1,
    p.has("viewRange") ? p.get("viewRange").getAsFloat() : .8f));
  }
  return out;
 }

 private OfficeFrame planned;
 private int plannedFloors;
 private List<Placement> plan = List.of();

 /**
  * Whether this prop's entity is still in the level: a server one until it is removed (killed or
  * unloaded with its chunk; UUIDs are unique there), a client one until another entity takes its id.
  */
 private static boolean live(Level level, Display.ItemDisplay display) {
  return !display.isRemoved() && (level instanceof net.minecraft.server.level.ServerLevel || level.getEntity(display.getId()) == display);
 }

 void sync(Level level, OfficeFrame frame, int floors, Collection<OfficeBrain.Agent> agents) {
  if (!frame.equals(planned) || floors != plannedFloors) {
   // A different office (rebuilt elsewhere, turned, more floors): its props replace these.
   if (planned != null) clear();
   plan = placements(frame, floors); planned = frame; plannedFloors = floors;
  }
  Map<String, OfficeBrain.Agent> desks = new HashMap<>();
  for (var agent : agents) desks.put(agent.floor + ":" + agent.desk, agent);
  boolean night = night(level);
  for (var p : plan) {
   String item = p.item();
   UUID id = p.id();
   Prop prop = displays.get(id);
   boolean fresh = prop == null || !live(level, prop.display);
   // A server only takes entities into loaded chunks; anything else is lost on save. Try again next sync.
   if (fresh && level instanceof net.minecraft.server.level.ServerLevel && !level.isLoaded(BlockPos.containing(p.pos()))) continue;
   if (fresh) {
    if (prop != null) prop.display.discard();
    var display = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND);
    display.setUUID(id); display.addTag("t3craft_prop");
    display.setItemTransform(ItemDisplayContext.FIXED);
    display.setTransformationInterpolationDuration(8);
    display.setViewRange(p.viewRange());
    prop = new Prop(display); displays.put(id, prop);
   }
   var display = prop.display;
   double x = p.pos().x, y = p.pos().y, z = p.pos().z;
   float yaw = p.yaw();
   var agent = p.desk() >= 0 ? desks.get(p.floor() + ":" + p.desk()) : null;
   String state = state(item, p.base(), agent == null ? null : agent.status, night);
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
     var stale = server.getEntity(id); if (stale != null) stale.discard();
     if (!server.addFreshEntity(display)) displays.remove(id);
    } else if (level instanceof net.minecraft.client.multiplayer.ClientLevel client) {
     display.setId(nextId--); client.addEntity(display);
    }
   }
  }
 }

 void onLoad(Entity entity) {
  if (entity.entityTags().contains("t3craft_prop") && displays.values().stream().noneMatch(p -> p.display == entity)) entity.discard();
 }
 void clear() { displays.values().forEach(p -> p.display.discard()); displays.clear(); planned = null; plannedFloors = 0; plan = List.of(); }
}
