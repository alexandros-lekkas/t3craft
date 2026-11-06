package dev.maxwellyoung.t3craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Pure normalized view of the usage fields needed by the office board. */
public record UsageBoardData(List<Provider> providers) {
	public enum State { UNKNOWN, NORMAL, NEAR_LIMIT, LIMITED }
	public record Provider(String name, Integer windowPercent, Instant resetsAt, Long tokensToday, State state, Integer secondaryPercent, Instant secondaryReset, List<Agent> topAgents) {
		public Provider(String name,Integer windowPercent,Instant resetsAt,Long tokensToday,State state){this(name,windowPercent,resetsAt,tokensToday,state,null,null,List.of());}
		public Provider { topAgents=List.copyOf(topAgents); }
	}
	public record Agent(String title,long tokens) {}

	public UsageBoardData { providers = List.copyOf(providers); }

	public static UsageBoardData parse(JsonObject root) {
		List<Provider> result = new ArrayList<>();
		JsonArray rows = root.has("providers") && root.get("providers").isJsonArray() ? root.getAsJsonArray("providers") : new JsonArray();
		for (var element : rows) {
			if (!element.isJsonObject()) continue;
			JsonObject provider = element.getAsJsonObject();
			String driver = string(provider, "driver", string(provider, "instanceId", "Unknown"));
			JsonObject limits = object(provider, "usageLimits");
			JsonArray windows = limits == null ? null : array(limits, "windows");
			Integer percent = null, secondPercent=null; Instant reset = null, secondReset=null; int windowIndex=0;
			if (windows != null) for (var window : windows) {
				if (!window.isJsonObject()) continue;
				JsonObject w = window.getAsJsonObject();
				if (number(w, "usedPercent") != null) {
					if(windowIndex++==0){percent=number(w,"usedPercent");}
					else if(secondPercent==null){secondPercent=number(w,"usedPercent");}
					String at = string(w, "resetsAt", null);
					if (at != null) try { if(reset==null)reset=Instant.parse(at); else if(secondReset==null)secondReset=Instant.parse(at); } catch (RuntimeException ignored) { }
				}
			}
			Long tokens = tokensFor(root, driver);
			State state = percent == null ? State.UNKNOWN : percent >= 100 ? State.LIMITED : percent >= 80 ? State.NEAR_LIMIT : State.NORMAL;
			List<Agent> agents=new ArrayList<>(); JsonArray agentRows=array(provider,"topAgents");
			if(agentRows!=null)for(var ae:agentRows){if(!ae.isJsonObject())continue;JsonObject a=ae.getAsJsonObject();Long at=longNumber(a,"tokensToday");if(at==null)at=longNumber(a,"tokens");agents.add(new Agent(string(a,"title",string(a,"threadTitle","Agent")),at==null?0:at));}
			result.add(new Provider(driver, percent, reset, tokens, state,secondPercent,secondReset,agents.stream().sorted(java.util.Comparator.comparingLong(Agent::tokens).reversed()).limit(2).toList()));
		}
		JsonObject allowance = object(root, "allowance");
		if (allowance != null) {
			JsonObject window = object(allowance, "window");
			Integer percent = window == null ? null : number(window, "usedPercent");
			Long resetMs = window == null ? null : longNumber(window, "resetsAtMs");
			Instant reset = resetMs == null ? null : Instant.ofEpochMilli(resetMs);
			String tier = string(allowance, "tier", "Muse");
			result.add(new Provider(tier, percent, reset, null, percent == null ? State.UNKNOWN : percent >= 100 ? State.LIMITED : percent >= 80 ? State.NEAR_LIMIT : State.NORMAL));
		}
		return new UsageBoardData(result);
	}

	/** Parse server.getConfig's providers[].usageLimits contract (no diagnostics scope needed). */
	public static UsageBoardData fromServerConfig(JsonObject config) {
		List<Provider> result=new ArrayList<>();JsonArray rows=array(config,"providers");if(rows==null)return new UsageBoardData(result);
		for(var e:rows){if(!e.isJsonObject())continue;JsonObject p=e.getAsJsonObject();String name=string(p,"driver",string(p,"instanceId","Unknown"));JsonObject u=object(p,"usageLimits");JsonArray ws=u==null?null:array(u,"windows");
			Integer session=null,weekly=null;Instant sessionAt=null,weeklyAt=null;String sessionKind=null;
			if(ws!=null)for(var we:ws){if(!we.isJsonObject())continue;JsonObject w=we.getAsJsonObject();Integer pct=number(w,"usedPercent");if(pct==null)continue;String kind=string(w,"kind","other");Instant at=null;String reset=string(w,"resetsAt",null);if(reset!=null)try{at=Instant.parse(reset);}catch(RuntimeException ignored){}
				if(kind.equals("session")&&session==null){session=pct;sessionAt=at;sessionKind=kind;}else if(kind.equals("weekly")&&weekly==null){weekly=pct;weeklyAt=at;}}
			Integer primary=session!=null?session:weekly;Instant primaryAt=session!=null?sessionAt:weeklyAt;Integer secondary=session!=null?weekly:null;Instant secondaryAt=session!=null?weeklyAt:null;
			State state=primary==null?State.UNKNOWN:primary>=100?State.LIMITED:primary>=80?State.NEAR_LIMIT:State.NORMAL;
			result.add(new Provider(name,primary,primaryAt,null,state,secondary,secondaryAt,List.of()));
		}
		return new UsageBoardData(result);
	}

	private static Long tokensFor(JsonObject root, String driver) {
		JsonObject summary = object(root, "summary"); if (summary == null) return null;
		JsonArray buckets = array(summary, "buckets"); if (buckets == null) return null;
		String today = string(summary, "untilDay", null); long total = 0; boolean found = false;
		for (var item : buckets) if (item.isJsonObject()) {
			JsonObject b=item.getAsJsonObject(); if (!driver.equals(string(b,"provider","")) || today != null && !today.equals(string(b,"day",""))) continue;
			JsonObject t=object(b,"totals"); if(t==null) continue;
			for(String key:List.of("uncachedInputTokens","cachedInputTokens","cacheCreationTokens","outputTokens")) { Long n=longNumber(t,key); if(n!=null) total+=n; }
			found=true;
		}
		return found ? total : null;
	}
	private static JsonObject object(JsonObject o,String k){return o.has(k)&&o.get(k).isJsonObject()?o.getAsJsonObject(k):null;}
	private static JsonArray array(JsonObject o,String k){return o.has(k)&&o.get(k).isJsonArray()?o.getAsJsonArray(k):null;}
	private static String string(JsonObject o,String k,String d){return o.has(k)&&o.get(k).isJsonPrimitive()?o.get(k).getAsString():d;}
	private static Integer number(JsonObject o,String k){Long n=longNumber(o,k);return n==null?null:n.intValue();}
	private static Long longNumber(JsonObject o,String k){try{return o.has(k)&&o.get(k).isJsonPrimitive()?o.get(k).getAsLong():null;}catch(RuntimeException e){return null;}}
}
