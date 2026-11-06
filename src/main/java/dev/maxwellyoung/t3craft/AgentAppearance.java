package dev.maxwellyoung.t3craft;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import dev.maxwellyoung.t3craft.mixin.MannequinAccess;

final class AgentAppearance {
 static final JsonObject SKINS=OfficeDesign.load("/assets/t3craft/skins.json");
 static final JsonObject ICONS=OfficeDesign.load("/assets/t3craft/provider-icons.json");
 static String provider(String driver) {
  var mapped=ICONS.getAsJsonObject("drivers").get(driver==null?"":driver);
  String key=mapped==null?driver:mapped.getAsString();return key!=null&&SKINS.getAsJsonObject("providers").has(key)?key:"default";
 }
 static JsonObject skin(String driver,String thread) {
  var choices=SKINS.getAsJsonObject("providers").getAsJsonObject(provider(driver)).getAsJsonArray("skins");
  String id=choices.get(Math.floorMod(thread.hashCode(),choices.size())).getAsString();
  return byId(id);
 }
 private static JsonObject byId(String id) {
  return SKINS.getAsJsonArray("skins").asList().stream().map(JsonElement::getAsJsonObject).filter(s->s.get("id").getAsString().equals(id)).findFirst().orElseThrow();
 }
 private static java.util.List<String> choices(String driver) {
  return SKINS.getAsJsonObject("providers").getAsJsonObject(provider(driver)).getAsJsonArray("skins").asList().stream().map(JsonElement::getAsString).toList();
 }
 /**
  * A skin for each agent in the office: an agent keeps the one it has (while it is still one of its provider's), and a
  * newcomer gets its usual one unless another agent wears it, then the provider's next free one. Only when every one
  * of the provider's skins is in use do two agents look alike. {@code assigned} (thread to skin id) is updated.
  */
 static JsonObject skin(String driver,String thread,java.util.Map<String,String> assigned) {
  var choices=choices(driver);
  String current=assigned.get(thread);
  if(current!=null&&choices.contains(current))return byId(current);
  var taken=new java.util.HashSet<>(assigned.values());
  if(current!=null)taken.remove(current);
  int start=Math.floorMod(thread.hashCode(),choices.size());
  String id=choices.get(start);
  for(int i=0;i<choices.size();i++){String candidate=choices.get((start+i)%choices.size());if(!taken.contains(candidate)){id=candidate;break;}}
  assigned.put(thread,id);
  return byId(id);
 }
 static String driver(T3State.ThreadRow row) {
  JsonObject raw=row.raw(); if(raw==null)return "default";
  for(String key:new String[]{"driverId","driverKind","provider","providerInstanceId"})if(raw.has(key)&&raw.get(key).isJsonPrimitive())return raw.get(key).getAsString();
  if(raw.has("modelSelection")&&raw.get("modelSelection").isJsonObject())return T3Protocol.string(raw.getAsJsonObject("modelSelection"),"instanceId");
  return "default";
 }
 private static final java.util.Map<String,ResolvableProfile> PROFILES=new java.util.concurrent.ConcurrentHashMap<>();
 static ResolvableProfile profile(JsonObject skin) {
  return PROFILES.computeIfAbsent(skin.get("id").getAsString(),id->ResolvableProfile.CODEC.parse(JsonOps.INSTANCE,skin.get("profile")).getOrThrow());
 }
 static void update(Mannequin avatar,T3State.ThreadRow row,boolean online,String driver) {
  update(avatar,row,online,driver,skin(driver,row.id()));
 }
 static void update(Mannequin avatar,T3State.ThreadRow row,boolean online,String driver,JsonObject skin) {
  var profile=profile(skin);
  if(!profile.equals(avatar.getProfile())) avatar.setComponent(DataComponents.PROFILE,profile);
  avatar.addTag("t3craft_agent");avatar.setNoGravity(true);avatar.setPermanentlyInvulnerable(true);
  ((MannequinAccess)avatar).t3craft$immovable(true);
  String p=provider(driver), icon=p.equals("default")?"fallback":p;
  // A small dot in the provider's accent colour reads at name-tag size; the 8 px bitmap glyph did not.
  int accent=Integer.parseInt(ICONS.getAsJsonObject("icons").getAsJsonObject(icon).get("accent").getAsString().substring(1),16);
  var dot=Component.literal("\u25CF ").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(accent)));
  avatar.setCustomName(dot.append(Component.literal(row.title().length()>28?row.title().substring(0,27)+"…":row.title())));
  ((MannequinAccess)avatar).t3craft$description(Component.literal(online?row.status().name().replace('_',' '):"OFFLINE"));
 }
}
