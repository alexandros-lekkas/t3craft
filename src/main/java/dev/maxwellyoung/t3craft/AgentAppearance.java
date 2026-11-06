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
  return SKINS.getAsJsonArray("skins").asList().stream().map(JsonElement::getAsJsonObject).filter(s->s.get("id").getAsString().equals(id)).findFirst().orElseThrow();
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
  var skin=skin(driver,row.id());
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
