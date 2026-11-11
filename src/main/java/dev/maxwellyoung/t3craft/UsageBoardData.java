package dev.maxwellyoung.t3craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure normalized view of the usage fields needed by the office board. With no providers, {@code title} and
 * {@code detail} say why (still reading, T3 unreachable, nothing enabled); a provider without numbers carries a
 * short {@code note} instead.
 */
public record UsageBoardData(List<Provider> providers, String title, String detail) {
	public enum State { UNKNOWN, NORMAL, NEAR_LIMIT, LIMITED }
	public record Provider(String name, Integer windowPercent, Instant resetsAt, Long tokensToday, State state, Integer secondaryPercent, Instant secondaryReset, List<Agent> topAgents, String primaryLabel, String secondaryLabel, String note) {
		public Provider(String name,Integer windowPercent,Instant resetsAt,Long tokensToday,State state){this(name,windowPercent,resetsAt,tokensToday,state,null,null,List.of());}
		public Provider(String name,Integer windowPercent,Instant resetsAt,Long tokensToday,State state,Integer secondaryPercent,Instant secondaryReset,List<Agent> topAgents){this(name,windowPercent,resetsAt,tokensToday,state,secondaryPercent,secondaryReset,topAgents,"SESSION","WEEKLY",windowPercent==null?"NO USAGE DATA":null);}
		public Provider { topAgents=List.copyOf(topAgents); }
	}
	public record Agent(String title,long tokens) {}

	/** Paired, before the first answer from T3. */
	static final UsageBoardData READING = new UsageBoardData(List.of(), "READING USAGE", "ASKING T3 FOR PROVIDER LIMITS");

	public UsageBoardData { providers = List.copyOf(providers); }
	public UsageBoardData(List<Provider> providers) { this(providers, null, null); }

	/** T3 could not be asked; never shows the last numbers as if they were current. */
	static UsageBoardData failed(String reason) {
		String detail = reason != null && reason.toLowerCase(Locale.ROOT).contains("legacy") ? "THIS PAIRING CANNOT READ LIMITS" : "COULD NOT READ T3. RETRYING";
		return new UsageBoardData(List.of(), "USAGE UNAVAILABLE", detail);
	}

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

	/**
	 * Parse server.getConfig's providers[].usageLimits contract (orchestration:read; no diagnostics scope needed).
	 * Disabled and uninstalled providers are left out. Windows come as session, weekly, monthly or other, in any
	 * mix (Codex may report only a monthly one, Claude two weeklies and no session): the board shows the session,
	 * else the first window, then the next one, each under T3's own label, and the state follows the fullest
	 * window. Providers with numbers come first, fullest first, so the three on the board are the ones that matter.
	 */
	public static UsageBoardData fromServerConfig(JsonObject config) {
		List<JsonObject> rows=new ArrayList<>();JsonArray all=array(config,"providers");
		if(all!=null)for(var e:all){if(!e.isJsonObject())continue;JsonObject p=e.getAsJsonObject();if(bool(p,"enabled")==Boolean.FALSE||bool(p,"installed")==Boolean.FALSE)continue;rows.add(p);}
		if(rows.isEmpty())return new UsageBoardData(List.of(),"NO PROVIDERS","T3 HAS NO ENABLED PROVIDER");
		java.util.Map<String,Long> names=new java.util.HashMap<>();for(JsonObject p:rows)names.merge(displayName(p),1L,Long::sum);
		record Window(String kind,String label,int percent,Instant resetsAt){}
		record Row(Provider provider,int fullest){}
		List<Row> result=new ArrayList<>();
		for(JsonObject p:rows){
			String name=displayName(p);
			// Two accounts of one provider ("Claude" twice): name the extra one after its instance, "Claude Personal".
			if(names.get(name)>1){String inst=string(p,"instanceId",""),driver=string(p,"driver","");if(!inst.isEmpty()&&!inst.equals(driver)){String human=humanize(inst);name=human.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT))?human:name+" "+human;}}
			JsonObject u=object(p,"usageLimits");JsonArray ws=u==null?null:array(u,"windows");
			List<Window> windows=new ArrayList<>();
			if(ws!=null)for(var we:ws){if(!we.isJsonObject())continue;JsonObject w=we.getAsJsonObject();Double pct=decimal(w,"usedPercent");if(pct==null)continue;String kind=string(w,"kind","other");Instant at=null;String reset=string(w,"resetsAt",null);if(reset!=null)try{at=Instant.parse(reset);}catch(RuntimeException ignored){}
				windows.add(new Window(kind,string(w,"label",kind).toUpperCase(Locale.ROOT),(int)Math.round(Math.max(0,Math.min(100,pct))),at));}
			Window primary=windows.stream().filter(w->w.kind().equals("session")).findFirst().orElse(windows.isEmpty()?null:windows.getFirst());
			Window secondary=windows.stream().filter(w->w!=primary).findFirst().orElse(null);
			int fullest=windows.stream().mapToInt(Window::percent).max().orElse(-1);
			State state=fullest<0?State.UNKNOWN:fullest>=100?State.LIMITED:fullest>=80?State.NEAR_LIMIT:State.NORMAL;
			JsonObject unavailable=u==null?null:object(u,"unavailable");String reason=unavailable==null?null:string(unavailable,"reason",null);
			String note=primary!=null?null:"unsupported".equals(reason)?"T3 TRACKS NO LIMITS FOR IT":"probeFailed".equals(reason)?"COULD NOT CHECK ITS LIMITS":"NO USAGE DATA";
			result.add(new Row(new Provider(name,primary==null?null:primary.percent(),primary==null?null:primary.resetsAt(),null,state,secondary==null?null:secondary.percent(),secondary==null?null:secondary.resetsAt(),List.of(),primary==null?"USAGE":primary.label(),secondary==null?null:secondary.label(),note),fullest));
		}
		result.sort(java.util.Comparator.comparingInt(Row::fullest).reversed());
		return new UsageBoardData(result.stream().map(Row::provider).toList());
	}

	private static String displayName(JsonObject p){String d=string(p,"displayName",null);return d!=null&&!d.isBlank()?d.trim():humanize(string(p,"driver",string(p,"instanceId","Unknown")));}
	/** "claude-personal" and "claudeAgent" to "Claude Personal" and "Claude Agent". */
	private static String humanize(String id){StringBuilder out=new StringBuilder();boolean up=true;char prev=' ';for(char c:id.toCharArray()){if(c=='-'||c=='_'||c==' '){if(out.length()>0&&out.charAt(out.length()-1)!=' ')out.append(' ');up=true;prev=c;continue;}if(Character.isUpperCase(c)&&Character.isLowerCase(prev))out.append(' ');out.append(up?Character.toUpperCase(c):c);up=false;prev=c;}return out.toString().trim();}
	private static Boolean bool(JsonObject o,String k){try{return o.has(k)&&o.get(k).isJsonPrimitive()?o.get(k).getAsBoolean():null;}catch(RuntimeException e){return null;}}
	private static Double decimal(JsonObject o,String k){try{return o.has(k)&&o.get(k).isJsonPrimitive()&&o.get(k).getAsJsonPrimitive().isNumber()?o.get(k).getAsDouble():null;}catch(RuntimeException e){return null;}}

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
