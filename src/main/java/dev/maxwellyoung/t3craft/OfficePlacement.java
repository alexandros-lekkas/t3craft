package dev.maxwellyoung.t3craft;

import com.mojang.brigadier.StringReader;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Applies generated template data on the server thread, without chat or command packets. */
final class OfficePlacement {
 static void place(ServerLevel level, BlockPos origin, java.util.List<String> labels) {
  int floors=Math.max(2,labels.size()*2);place(level,origin,floors);
  for(int floor=0;floor<floors;floor++)label(level,origin.offset(29,floor*6+2,13),"FLOOR "+(floor+1),labels.isEmpty()?"T3 STUDIO":labels.get(floor/2));
  label(level,origin.offset(27,2,-2),"T3 OFFICE","SCANDI STUDIO");
 }
 private static void label(ServerLevel level,BlockPos pos,String first,String second){
  if(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign){
   var text=net.minecraft.world.level.block.entity.SignText.EMPTY.asMutable().setLine(0,net.minecraft.network.chat.Component.literal(first)).setLine(1,net.minecraft.network.chat.Component.literal(second)).asImmutable();
   sign.setText(text,net.minecraft.world.level.block.entity.SignTextSlot.FRONT);sign.setChanged();level.sendBlockUpdated(pos,level.getBlockState(pos),level.getBlockState(pos),3);
  }
 }
 /** Places the building and returns how many dropped items it had to sweep up afterwards. */
 static int place(ServerLevel level, BlockPos origin, int floors) {
  var data=OfficeDesign.template(floors);
  // Top-down, so carpets and signs are gone before the blocks under them; untouched air is skipped.
  for(int x=-2;x<=31;x++)for(int z=-6;z<=25;z++)for(int y=floors*6+5;y>=0;y--){var pos=origin.offset(x,y,z);if(!level.getBlockState(pos).isAir())level.setBlock(pos,Blocks.AIR.defaultBlockState(),2);}
  var registry=level.registryAccess().lookupOrThrow(Registries.BLOCK);
  try {
   for(var e:data.getAsJsonArray("blocks")) {var b=e.getAsJsonObject();var p=b.getAsJsonArray("pos");var state=BlockStateParser.parseForBlock(registry,new StringReader(b.get("state").getAsString()),false).blockState();level.setBlock(origin.offset(p.get(0).getAsInt(),p.get(1).getAsInt(),p.get(2).getAsInt()),state,2);}
  } catch(com.mojang.brigadier.exceptions.CommandSyntaxException e){throw new IllegalStateException("Invalid office template",e);}
  return sweep(level,origin,floors);
 }
 /** Anything that still popped off during the rebuild must not litter the office. */
 static int sweep(ServerLevel level, BlockPos origin, int floors) {
  var items=level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,bounds(origin,floors));
  items.forEach(net.minecraft.world.entity.item.ItemEntity::discard);return items.size();
 }
 static net.minecraft.world.phys.AABB bounds(BlockPos origin,int floors) {
  return new net.minecraft.world.phys.AABB(origin.getX()-3,origin.getY()-2,origin.getZ()-7,origin.getX()+33,origin.getY()+floors*6+8,origin.getZ()+27);
 }
}
