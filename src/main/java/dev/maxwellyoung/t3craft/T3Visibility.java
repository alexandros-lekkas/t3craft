package dev.maxwellyoung.t3craft;

import com.google.gson.JsonObject;
import java.time.Instant;

/** Shared T3 sidebar semantics for both wire protocols and every spatial view. */
final class T3Visibility {
 static boolean flag(JsonObject value, String key) { return value != null && value.has(key) && !value.get(key).isJsonNull() && value.get(key).getAsBoolean(); }
 static boolean child(JsonObject raw) {
  JsonObject lineage = T3Protocol.object(raw, "lineage");
  return flag(raw, "childRequestsOnly") || "subagent".equals(T3Protocol.string(raw, "relationshipToParent"))
   || lineage != null && ("subagent".equals(T3Protocol.string(lineage, "relationshipToParent")) || flag(lineage, "sideChat")) || flag(raw, "sideChat");
 }
 static boolean settled(JsonObject raw) {
  String override = T3Protocol.string(raw, "settledOverride");
  if ("active".equals(override)) return false;
  return "settled".equals(override) || flag(raw, "settled") || T3Protocol.string(raw, "settledAt") != null;
 }
 static boolean snoozed(JsonObject raw) {
  String until = T3Protocol.string(raw, "snoozedUntil");
  if (until != null) try { return Instant.parse(until).isAfter(Instant.now()); } catch (RuntimeException ignored) {}
  return flag(raw, "snoozed");
 }
 static boolean snoozedGroup(T3State.ThreadRow row) { return snoozed(row.raw()) && row.status() != T3State.Status.NEEDS_YOU; }
 static boolean visible(T3State.ThreadRow row, boolean pinned) {
  return !child(row.raw()) && T3Protocol.string(row.raw(), "archivedAt") == null
   && (!settled(row.raw()) || pinned || row.status() == T3State.Status.NEEDS_YOU);
 }
 static boolean pings(T3State.ThreadRow row) { return !child(row.raw()) && !snoozed(row.raw()) && (!settled(row.raw()) || row.status() == T3State.Status.NEEDS_YOU); }
}
