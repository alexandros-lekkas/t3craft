package dev.maxwellyoung.t3craft;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Generated Scandinavian studio templates; coordinates match the designer's anchors. */
final class OfficeDesign {
 static final JsonObject BASE = load("/data/t3craft/office/scandi-1.json");
 static JsonObject load(String path) {
  try (var stream = Objects.requireNonNull(OfficeDesign.class.getResourceAsStream(path), path);
       var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
   return JsonParser.parseReader(reader).getAsJsonObject();
  } catch (IOException e) { throw new IllegalStateException(path, e); }
 }
 static JsonObject floor() { return BASE.getAsJsonObject("anchors").getAsJsonArray("floors").get(0).getAsJsonObject(); }
 /** The design's footprint in its own frame: {minX, maxX, minZ, maxZ} over every block, plaza and fins included. */
 static final int[] EXTENT = extent();
 /** The plaza and floor slab sit one below the ground floor; everything is cleared from here up. */
 static final int GROUND = -1;
 private static int[] extent() {
  int[] e={Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE};
  for(var b:BASE.getAsJsonArray("blocks")){var p=b.getAsJsonObject().getAsJsonArray("pos");int x=p.get(0).getAsInt(),z=p.get(2).getAsInt();e[0]=Math.min(e[0],x);e[1]=Math.max(e[1],x);e[2]=Math.min(e[2],z);e[3]=Math.max(e[3],z);}
  return e;
 }
 /** Highest block of a build with this many floors (the roof terrace rail). */
 static int top(int floors) { return floors*6+5; }
 /** An exterior anchor ({@code door}, {@code sign}) as {x, y, z}. */
 static int[] exterior(String key) { return ints(BASE.getAsJsonObject("anchors").getAsJsonObject("exterior").getAsJsonArray(key)); }
 /** The cell just outside the entry door, {x, z}, where arrivals stand. */
 static int[] doorOutside() { return ints(floor().getAsJsonObject("door").getAsJsonArray("outside")); }
 /** Every column that has a plaza or floor-slab block at ground level, as {x, z}: what needs solid ground under it. */
 static List<int[]> plaza() {
  List<int[]> out=new ArrayList<>();
  for(var b:BASE.getAsJsonArray("blocks")){var p=b.getAsJsonObject().getAsJsonArray("pos");if(p.get(1).getAsInt()==GROUND)out.add(new int[]{p.get(0).getAsInt(),p.get(2).getAsInt()});}
  return out;
 }
 private static int[] ints(JsonArray a) { int[] out=new int[a.size()];for(int i=0;i<out.length;i++)out[i]=a.get(i).getAsInt();return out; }
 static T3Office.Spot spot(JsonObject a) { return new T3Office.Spot(a.get("x").getAsInt(),a.get("z").getAsInt(),a.get("sink").getAsDouble(),a.get("yaw").getAsFloat()); }
 static T3Office.Spot[] spots(String key) { return floor().getAsJsonArray(key).asList().stream().map(a->spot(a.getAsJsonObject())).toArray(T3Office.Spot[]::new); }
 static List<String> blueprint(int count) {
  JsonObject data = template(count);
  List<String> out = new ArrayList<>();
  // Clear one storey at a time (top-down, so upper carpets drop before their floor goes), each fill below the vanilla block limit.
  for(int y=count*6;y>=0;y-=6) out.add("fill -2 "+y+" -6 31 "+(y+5)+" 25 air");
  for(JsonElement e:data.getAsJsonArray("blocks")) { var b=e.getAsJsonObject();var p=b.getAsJsonArray("pos");out.add("setblock "+p.get(0).getAsInt()+" "+p.get(1).getAsInt()+" "+p.get(2).getAsInt()+" "+b.get("state").getAsString()); }
  return out;
 }
 private static final Map<Integer,JsonObject> TEMPLATES=new java.util.concurrent.ConcurrentHashMap<>();
 static JsonObject template(int count) {
  if(count<1)throw new IllegalArgumentException("floor count");
  return TEMPLATES.computeIfAbsent(count,n->{
   if(n<=2)return n==1?BASE:load("/data/t3craft/office/scandi-2.json");
   var source=load("/data/t3craft/office/scandi-2.json");var result=source.deepCopy();var blocks=new JsonArray();
   for(var e:source.getAsJsonArray("blocks")){
    var block=e.getAsJsonObject();int y=block.getAsJsonArray("pos").get(1).getAsInt();
    if(y<5)blocks.add(block.deepCopy());
    else if(y>=11){var copy=block.deepCopy();copy.getAsJsonArray("pos").set(1,new JsonPrimitive(y+6*(n-2)));blocks.add(copy);}
    else for(int floor=1;floor<n;floor++){var copy=block.deepCopy();copy.getAsJsonArray("pos").set(1,new JsonPrimitive(y+6*(floor-1)));blocks.add(copy);}
   }
   result.add("blocks",blocks);var floors=new JsonArray();var original=source.getAsJsonObject("anchors").getAsJsonArray("floors");
   floors.add(original.get(0).deepCopy());for(int i=1;i<n;i++){var f=original.get(1).deepCopy().getAsJsonObject();f.addProperty("floor",i);f.addProperty("y",i*6);floors.add(f);}result.getAsJsonObject("anchors").add("floors",floors);
   var lamps=new JsonArray();for(var e:source.getAsJsonArray("lamps")){var lamp=e.getAsJsonObject();String floor=lamp.get("floor").getAsString();if(floor.equals("0"))lamps.add(lamp.deepCopy());else if(floor.equals("roof")){var c=lamp.deepCopy();c.addProperty("y",c.get("y").getAsInt()+6*(n-2));lamps.add(c);}else for(int i=1;i<n;i++){var c=lamp.deepCopy();c.addProperty("floor",i);c.addProperty("y",c.get("y").getAsInt()+6*(i-1));lamps.add(c);}}result.add("lamps",lamps);return result;
  });
 }

 static List<String> lamps(int count,boolean night) {
  List<String> out=new ArrayList<>();for(var e:template(Math.max(1,count)).getAsJsonArray("lamps")){var a=e.getAsJsonObject();out.add("setblock "+a.get("x").getAsInt()+" "+a.get("y").getAsInt()+" "+a.get("z").getAsInt()+" "+a.get(night?"night":"day").getAsString());}return out;
 }
 /** A cell an earlier design filled with a block this one dropped or swapped: still the office's own on a rebuild. */
 record Legacy(int x,int y,int z,String block) {}
 static List<Legacy> legacy(int count) {
  List<Legacy> out=new ArrayList<>();
  for(var e:template(Math.min(Math.max(1,count),2)).getAsJsonArray("legacy")){
   var a=e.getAsJsonArray();int x=a.get(0).getAsInt(),y=a.get(1).getAsInt(),z=a.get(2).getAsInt();String block="minecraft:"+a.get(3).getAsString();
   // The same repeat as template(): ground floor once, the upper module on every floor above it.
   if(count<=2||y<5)out.add(new Legacy(x,y,z,block));
   else if(y>=11)out.add(new Legacy(x,y+6*(count-2),z,block));
   else for(int floor=1;floor<count;floor++)out.add(new Legacy(x,y+6*(floor-1),z,block));
  }
  return out;
 }
 static boolean[][] blocked() {
  boolean[][] b=new boolean[30][24];
  for(var e:BASE.getAsJsonArray("blocks")){var a=e.getAsJsonObject();var p=a.getAsJsonArray("pos");int x=p.get(0).getAsInt(),y=p.get(1).getAsInt(),z=p.get(2).getAsInt();String s=a.get("state").getAsString();if(y==0&&x>=0&&x<30&&z>=0&&z<24&&!s.contains("carpet")&&!s.contains("sign")&&!s.startsWith("light["))b[x][z]=true;}
  for(var e:floor().getAsJsonArray("work")){var a=e.getAsJsonObject();b[a.get("x").getAsInt()][a.get("z").getAsInt()]=true;}
  for(var e:load("/data/t3craft/office/props.json").getAsJsonArray("props")){var p=e.getAsJsonObject().getAsJsonArray("pos");int x=(int)Math.floor(p.get(0).getAsDouble()),z=(int)Math.floor(p.get(2).getAsDouble());if(p.get(1).getAsDouble()<.1 && x>=0&&x<30&&z>=0&&z<24)b[x][z]=true;}
  for(int x=27;x<=29;x++)for(int z=2;z<=7;z++)b[x][z]=true;
  return b;
 }
}
