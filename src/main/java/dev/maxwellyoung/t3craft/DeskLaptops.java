package dev.maxwellyoung.t3craft;

import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.Level;

/** One deterministic display/hitbox pair per thread, including when its agent leaves the desk. */
final class DeskLaptops {
 static final class Pair {
  final Display.ItemDisplay display;final Interaction hitbox;T3State.Status shown;
  Pair(Display.ItemDisplay display,Interaction hitbox){this.display=display;this.hitbox=hitbox;}
  void show(T3State.Status status){if(status!=shown){display.setItemStack(item(status));shown=status;}}
 }
 private final OfficeProps props=new OfficeProps();
 private final Map<String,Pair> pairs=new HashMap<>();
 private static int nextId=-7_600_000;
 static UUID uuid(String thread,boolean hitbox) { return UUID.nameUUIDFromBytes(("t3craft:laptop:"+(hitbox?"hit:":"display:")+thread).getBytes(StandardCharsets.UTF_8)); }
 static String state(T3State.Status status) { return switch(status){case WORKING->"working";case NEEDS_YOU,ERROR->"needs_you";default->"idle";}; }
 static ItemStack item(T3State.Status status) {
  var item=new ItemStack(Items.PAPER);item.set(DataComponents.ITEM_MODEL,Identifier.fromNamespaceAndPath("t3craft","laptop"));
  item.set(DataComponents.CUSTOM_MODEL_DATA,new CustomModelData(List.of(),List.of(),List.of(state(status)),List.of()));return item;
 }
 void sync(Level level,BlockPos origin,Collection<OfficeBrain.Agent> agents, int floors) {
  props.sync(level,origin,floors,agents);
  Set<String> keep=new HashSet<>();
  for(var a:agents) {
   keep.add(a.threadId);var p=pairs.get(a.threadId);
   if(p==null||p.display.isRemoved()||p.hitbox.isRemoved()) {
    if(p!=null)remove(p);
    var display=EntityTypes.ITEM_DISPLAY.create(level,EntitySpawnReason.COMMAND);var hit=EntityTypes.INTERACTION.create(level,EntitySpawnReason.COMMAND);
    display.setUUID(uuid(a.threadId,false));hit.setUUID(uuid(a.threadId,true));
    display.addTag("t3craft_laptop");hit.addTag("t3craft_laptop");
    display.setItemTransform(ItemDisplayContext.FIXED);hit.setWidth(.85f);hit.setHeight(.65f);
    p=new Pair(display,hit);pairs.put(a.threadId,p);
    position(p,origin,a);p.show(a.status);
    if(level instanceof net.minecraft.server.level.ServerLevel server) {
     for(Entity e:List.of(display,hit)){var stale=server.getEntity(e.getUUID());if(stale!=null)stale.discard();server.addFreshEntity(e);}
    }else if(level instanceof net.minecraft.client.multiplayer.ClientLevel client){display.setId(nextId--);hit.setId(nextId--);client.addEntity(display);client.addEntity(hit);}
   }
   position(p,origin,a);p.show(a.status);
  }
  pairs.entrySet().removeIf(e->{if(keep.contains(e.getKey()))return false;remove(e.getValue());return true;});
 }
 private static void position(Pair p,BlockPos o,OfficeBrain.Agent a) {
  var desk=OfficeDesign.floor().getAsJsonArray("work").get(a.desk).getAsJsonObject().getAsJsonArray("desk");
  double x=o.getX()+desk.get(0).getAsInt()+.5,z=o.getZ()+desk.get(1).getAsInt()+(a.desk<4?.45:.55),y=o.getY()+a.floor*T3Office.FLOOR_HEIGHT+1;
  p.display.setPos(x,y,z);p.display.setYRot(a.desk<4?0:180);p.hitbox.setPos(x,y,z);
 }
 private static void remove(Pair p){p.display.discard();p.hitbox.discard();}
 void onLoad(Entity entity){props.onLoad(entity);if(entity.entityTags().contains("t3craft_laptop")&&pairs.values().stream().noneMatch(p->p.display==entity||p.hitbox==entity))entity.discard();}
 void clear(){props.clear();pairs.values().forEach(DeskLaptops::remove);pairs.clear();}
}
