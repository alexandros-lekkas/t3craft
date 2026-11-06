package dev.maxwellyoung.t3craft;

import com.google.gson.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** No real credentials, browser, Minecraft installation, or agent writes. */
public final class McpChecks {
 private static final HttpClient HTTP = HttpClient.newHttpClient();
 private static String base, second;
 public static void main(String[] args) throws Exception {
  int port = Integer.parseInt(args[0]); base = "http://127.0.0.1:" + port; second = "http://127.0.0.1:" + (port + 1);
  try {
   oauthHappyPath(); oauthDeny(); oauthReadOnlyRejected(); supervisedGrantLimits(); readOnlyTokenToolRefusal(); cancelAndReopen();
   sessionRestart(); incrementalHistory(); mutableHistory(); focusedDetailFailure(); mcpOnlyViaState(); hybridHealthy(); hybridMcpDown(); hybridMcpRevoked(); hybridLegacyBroken(); twoMachineRouting(); config050Migration(); credentialFailures(); visibility(false); visibility(true);
   schemaConformance();
   System.out.println("MCP checks passed: OAuth (approve/deny/read-only/limited/cancel), session recovery, per-leg health and fallbacks, refusals, schema conformance, routing and config migration.");
  } finally { reset(); }
 }
 static void reset() throws Exception { schemaConformance(); get(base + "/_qa/reset"); get(second + "/_qa/reset"); }
 static JsonArray array(String path) throws Exception { return JsonParser.parseString(HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).getAsJsonArray(); }
 static void schemaConformance() throws Exception { check(array("/_qa/mcp-violations").isEmpty(), "tool schema conformance"); }
 static long calls(String tool) throws Exception { return array("/_qa/mcp-calls").asList().stream().filter(c -> tool.equals(c.getAsJsonObject().get("name").getAsString())).count(); }
 static T3Config.Environment env(boolean hybrid) {
  var env = new T3Config.Environment("MCP", base, hybrid ? "fixture-only" : null); env.mcpUrl = base + "/mcp"; env.mcpToken = "mcp-fixture"; return env;
 }
 static T3Backend api(boolean hybrid) { return T3Backend.forEnvironment(env(hybrid)); }
 static JsonObject row(T3Backend api) throws Exception { return api.shell().getAsJsonArray("threads").get(0).getAsJsonObject(); }
 static T3State connect(T3Backend api) throws Exception {
  var state = new T3State(e -> {}); state.connect(api, "mcp-question"); state.setPanelOpen(true);
  await(() -> state.snapshot().connected() && state.snapshot().focus() != null, "state connects"); return state;
 }
 static void close(T3State state) throws Exception { state.connect(List.of(), null); await(() -> state.machines().isEmpty(), "state disconnected"); }
 static void oauthHappyPath() throws Exception {
  reset();
  var env = T3McpAuth.authorize(base + "/mcp/", uri -> {
   try {
    JsonObject consent = get(uri.toString()); String callback = consent.get("redirect").getAsString();
    check(HTTP.send(HttpRequest.newBuilder(URI.create(callback + "?state=wrong&code=bad")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 400, "invalid state rejected");
    var response = HTTP.send(HttpRequest.newBuilder(URI.create(callback + "?state=" + consent.get("state").getAsString() + "&code=fixture-code")).GET().build(), HttpResponse.BodyHandlers.ofString());
    check(response.statusCode() == 200 && response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"), "callback HTML");
   } catch (Exception e) { throw new RuntimeException(e); }
  });
  check(env.mcpScope.contains("settings:write") && Math.abs(env.mcpExpiresAt - System.currentTimeMillis() - 30L * 86400000) < 60000, "scope and 30-day expiry");
  check(T3McpAuth.sameResource("https://EXAMPLE.com:443/mcp/", "https://example.com/mcp"), "canonical OAuth address");
 }
 static void oauthDeny() throws Exception {
  reset(); get(base + "/_qa/mcp-consent?decision=deny");
  try { authorize(); throw new AssertionError("denial paired"); } catch (T3McpException e) { check(e.getMessage().contains("denied in the browser"), "denial readable"); }
 }
 static void oauthReadOnlyRejected() throws Exception {
  reset(); get(base + "/_qa/mcp-consent?access=read-only");
  try { authorize(); throw new AssertionError("read only paired"); } catch (T3McpException e) { check(e.getMessage().contains("Full access"), "read only pairing guidance"); }
 }
 static void supervisedGrantLimits() throws Exception {
  reset(); get(base + "/_qa/mcp-consent?access=approval-required");
  var env = authorize(); check(!env.mcpScope.contains("settings:write"), "limited scope retained");
  var api = T3Backend.forEnvironment(env); var row = row(api);
  try { api.sendPrompt(row, "Fixture prompt", null); throw new AssertionError("limited write accepted"); }
  catch (T3McpException e) { check(e.kind == T3McpException.Kind.DENIED && e.getMessage().contains("Full access") && !e.getMessage().contains("mcp-supervised"), "mode ceiling explained"); }
 }
 static void readOnlyTokenToolRefusal() throws Exception {
  reset(); var env = env(false); env.mcpToken = "mcp-readonly"; var api = T3Backend.forEnvironment(env); var row = row(api);
  denied(() -> api.sendPrompt(row, "Fixture prompt", null)); denied(() -> api.answerQuestions("mcp-question", "mcp-request", T3McpBackend.obj("format", "json")));
  check(api.shell().has("threads"), "read only can read");
 }
 static void denied(Action action) throws Exception {
  try { action.run(); throw new AssertionError("read only operation accepted"); }
  catch (T3McpException e) { check(e.kind == T3McpException.Kind.DENIED && "capability_denied".equals(e.code), "typed tool refusal"); }
 }
 static void cancelAndReopen() throws Exception {
  reset(); var pending = new T3McpAuth.Pending(); var error = new AtomicReference<Exception>();
  Thread worker = new Thread(() -> { try { T3McpAuth.authorize(base + "/mcp", uri -> {}, pending); } catch (Exception e) { error.set(e); } }); worker.start();
  await(() -> pending.consent != null, "consent published for reopen"); pending.cancel(); worker.join(2000);
  check(!worker.isAlive() && error.get() != null && "Authorization cancelled.".equals(error.get().getMessage()), "cancel releases listener promptly");
  check(authorize().mcpToken != null, "authorization retry after cancel");
 }
 static void sessionRestart() throws Exception {
  reset(); var api = api(false); var row = row(api); get(base + "/_qa/mcp-restart");
  check(api.shell().getAsJsonArray("threads").size() == 2 && calls("initialize") == 2, "session restored on read");
  get(base + "/_qa/mcp-restart"); api.sendPrompt(row, "Fixture prompt", null);
  check(calls("initialize") == 3 && calls("t3_thread_send") == 1, "write recovered without duplicate execution");
 }
 static void incrementalHistory() throws Exception {
  reset(); var api = api(false);
  var first = api.thread("mcp-question", 4); long before = calls("t3_thread_read");
  var second = api.thread("mcp-question", 4);
  check(calls("t3_thread_read") == before + 1 && second.getAsJsonArray("turnItems").equals(first.getAsJsonArray("turnItems")), "incremental history retains items without replay");
  var reads = array("/_qa/mcp-calls").asList().stream().map(JsonElement::getAsJsonObject).filter(c -> "t3_thread_read".equals(c.get("name").getAsString())).toList();
  check(reads.getLast().getAsJsonObject("arguments").get("afterPosition").getAsInt() == 2, "resumes at saved nextPosition");
  check(api.providers(null).getFirst().name().contains("more models in T3 Code"), "omitted models disclosed");
  get(base + "/_qa/mcp-history-long"); before = calls("t3_thread_read"); api.thread("long-thread", 4);
  check(calls("t3_thread_read") == before + 10, "history fetch capped at ten pages");
  before = calls("t3_thread_read"); var continued = api.thread("long-thread", 4);
  check(calls("t3_thread_read") == before + 10 && continued.getAsJsonArray("turnItems").size() == 20, "capped history resumes next fetch");
  get(base + "/_qa/mcp-history-null"); before = calls("t3_thread_read"); api.thread("null-thread", 4);
  check(calls("t3_thread_read") == before + 1, "null position stops even with hasMore");
 }
 static void mutableHistory() throws Exception {
  reset(); var api = api(false);
  for (String status : List.of("idle", "pending", "running", "waiting", "completed")) {
   get(base + "/_qa/mcp-item?itemId=tool-1&status=" + status + "&text=" + status);
   var detail = api.thread("mcp-question", 4);
   var items = detail.getAsJsonArray("turnItems");
   check(items.size() == 2, "same-position updates do not duplicate items");
   var item = items.get(1).getAsJsonObject();
   check(item.get("status").getAsString().equals(status) && item.get("text").getAsString().equals(status), "cached tool advances to " + status);
   check(item.get("itemId").getAsString().equals("tool-1") && item.get("position").getAsInt() == 2, "item identity stable");
  }
  long before = calls("t3_thread_read"); api.thread("mcp-question", 4);
  check(calls("t3_thread_read") == before + 1, "terminal item stops refreshing");
 }
 static void focusedDetailFailure() throws Exception {
  reset(); var state = connect(api(false));
  try {
   get(base + "/_qa/mcp-detail-error");
   await(() -> state.snapshot().detailError() != null, "thread error shown inline");
   check(state.snapshot().connected() && state.machines().getFirst().online() && state.snapshot().error() == null
     && state.online("mcp-approval"), "detail error does not take machine or peer offline");
   check(!state.snapshot().focus().messages().isEmpty(), "cached conversation survives detail failure");
   state.focus("mcp-approval");
   await(() -> "mcp-approval".equals(state.snapshot().focusedId()) && state.snapshot().detailError() == null, "switch clears thread error");
   get(base + "/_qa/mcp-detail-ok"); state.focus("mcp-question");
   await(() -> state.snapshot().focus() != null && "mcp-question".equals(state.snapshot().focus().threadId()), "detail recovers");
   check(state.snapshot().detailError() == null, "recovery clears inline error");
  } finally { close(state); }
 }
 static void mcpOnlyViaState() throws Exception {
  reset(); var api = api(false); var row = row(api); check(api.publicMcp() && !api.hasLegacy(), "MCP only");
  check(api.shell().getAsJsonArray("threads").size() == 2, "all pages loaded");
  check(T3State.focus(api.thread("mcp-question", 4)).activity().size() == 1, "activity pagination");
  check(api.providers(null).getFirst().models().getFirst().slug().equals("fixture-model"), "models listed");
  expectLegacy(() -> api.review("mcp-question")); expectLegacy(() -> api.respondToApproval("mcp-approval", "approval", "accept"));
  var state = connect(api);
  try {
   await(() -> state.decisions().stream().anyMatch(d -> d.thread().id().equals("mcp-approval") && d.detail().contains(T3McpBackend.LEGACY_REQUIRED)), "approval gap visible");
   for (int i = 0; i < 20; i++) { check(state.decisions().stream().noneMatch(d -> d.thread().id().equals("mcp-question") && d.detail().contains(T3McpBackend.LEGACY_REQUIRED)), "questions never mislabeled legacy"); Thread.sleep(100); }
   check(state.snapshot().focus().messages().getFirst().text().equals("MCP reply"), "message before send");
   var error = new AtomicReference<Exception>();
   state.run(() -> api.sendPrompt(row, "Fixture prompt", T3Api.modelSelection("fixture-provider", "changed-model")), error::set);
   await(() -> state.snapshot().focus().messages().getLast().text().equals("Sent"), "state observes actual message change");
   state.respond("mcp-question", "mcp-request", null, T3McpBackend.obj("format", "json"), error::set);
   await(() -> state.snapshot().focus().userInputs().isEmpty(), "question answered through state"); check(error.get() == null, "state writes succeed");
   check(api.startThread(row, "Fixture new thread ", null).equals("mcp-new"), "launch retains worktree"); api.interrupt("mcp-question");
   check(calls("t3_thread_configure") == 1, "model configured");
  } finally { close(state); }
 }
 static void hybridHealthy() throws Exception {
  reset(); get(base + "/_qa/mcp-hybrid"); var api = api(true); var state = connect(api);
  try {
   await(state::live, "hybrid live"); await(() -> state.decisions().stream().anyMatch(d -> d.thread().id().equals("mcp-approval") && d.requestId() != null), "hybrid approval visible");
   check(!T3Books.files(api.thread("mcp-approval", 2)).isEmpty(), "legacy checkpoint files copied"); check(api.review("mcp-approval").diff().contains("greeting"), "legacy review");
   var error = new AtomicReference<Exception>(); state.respond("mcp-approval", "shared-request", "accept", null, error::set);
   await(() -> state.decisions().stream().noneMatch(d -> d.thread().id().equals("mcp-approval") && d.requestId() != null), "approval answered through state");
   check(error.get() == null && api.legs().mcpError() == null && api.legs().legacyError() == null, "both legs healthy");
  } finally { close(state); }
 }
 static void hybridMcpDown() throws Exception {
  reset(); get(base + "/_qa/mcp-hybrid"); var api = api(true); var row = row(api); var state = connect(api);
  try {
   get(base + "/_qa/mcp-down"); await(() -> state.machines().getFirst().legs().mcpError() != null, "MCP failure visible");
   check(state.snapshot().connected() && state.online("mcp-approval") && state.machines().getFirst().error() == null, "legacy keeps machine online");
   var error = new AtomicReference<Exception>(); state.respond("mcp-approval", "shared-request", "accept", null, error::set);
   await(() -> state.decisions().stream().noneMatch(d -> d.thread().id().equals("mcp-approval") && d.requestId() != null), "legacy approval while MCP offline");
   state.run(() -> api.sendPrompt(row, "Fixture prompt", null), error::set);
   await(() -> state.snapshot().focus().messages().getFirst().text().equals("Sent via legacy"), "known unhealthy MCP uses legacy send");
   check(error.get() == null && calls("t3_thread_send") == 0, "no MCP send when known down");
   check(!array("/_qa/dispatches").isEmpty(), "legacy dispatch recorded");
   get(base + "/_qa/mcp-up"); await(() -> api.legs().mcpError() == null, "MCP recovers");
  } finally { close(state); }
 }
 static void hybridMcpRevoked() throws Exception {
  reset(); get(base + "/_qa/mcp-hybrid"); var api = api(true); var state = connect(api);
  try {
   get(base + "/_qa/mcp-revoke"); await(() -> api.legs().mcpError() != null && api.legs().mcpError().contains("Re-pair"), "revocation visible");
   check(state.snapshot().connected() && state.online("mcp-approval"), "revoked MCP keeps legacy online");
   int posts = posts(); Thread.sleep(5000);
   check(posts() == posts, "revoked token not hammered");
   get(base + "/_qa/protocol3"); await(() -> !state.live(), "legacy disconnect while MCP revoked");
   await(() -> api.legs().legacyError() != null && api.legs().legacyError().contains("protocol 3"), "reconnect re-checks the protocol instead of reusing the cached one");
   Thread.sleep(1000);
   check(posts() == posts, "legacy disconnect does not reset revoked MCP access");
   state.retry(api.ownerKey()); await(() -> posts() == posts + 1, "retry sends an auth probe"); Thread.sleep(1000);
   check(posts() == posts + 1, "retry sends one auth probe");
  } finally { close(state); }
 }
 static void hybridLegacyBroken() throws Exception {
  reset(); get(base + "/_qa/mcp-hybrid"); var api = api(true); var row = row(api); var state = connect(api);
  try {
   await(state::live, "hybrid initially live"); get(base + "/_qa/protocol3");
   await(() -> api.legs().legacyError() != null && !state.live(), "legacy failure visible");
   check(state.snapshot().connected() && state.online("mcp-question"), "MCP keeps machine online");
   api.sendPrompt(row, "Fixture prompt", null); api.answerQuestions("mcp-question", "mcp-request", T3McpBackend.obj("format", "json"));
   try { api.review("mcp-question"); throw new AssertionError("broken review succeeded"); } catch (IOException e) { check(e.getMessage().contains("Update T3"), "original legacy error"); }
  } finally { close(state); }
 }
 static void twoMachineRouting() throws Exception {
  reset(); var state = new T3State(e -> {}); var primary = api(false); var secondary = new T3Api(second, "fixture-only");
  state.connect(List.of(new T3State.Connection(primary, "MCP"), new T3State.Connection(secondary, "B")), "mcp-question"); state.setPanelOpen(true);
  try {
   await(() -> state.machines().size() == 2 && state.machines().stream().allMatch(T3State.MachineHealth::online), "two machines online");
   check(state.apiFor("mcp-question").publicMcp() && !state.apiFor("B-waiting").publicMcp(), "per-thread backend routing");
   get(base + "/_qa/mcp-down"); await(() -> state.machines().getFirst().error() != null, "MCP-only down"); check(state.online("B-waiting"), "other machine unaffected");
   var error = new AtomicReference<Exception>(); state.respond("B-waiting", "shared-request", null, T3McpBackend.obj("format", "JSON"), error::set);
   await(() -> state.decisions().stream().noneMatch(d -> d.thread().id().equals("B-waiting") && d.requestId() != null), "other machine question answered"); check(error.get() == null, "other machine answer succeeds");
  } finally { close(state); }
 }
 static void config050Migration() throws Exception {
  reset(); Path dir = Files.createTempDirectory("t3craft-migration"); Path file = dir.resolve("t3craft.json");
  try {
   JsonObject old = new JsonObject(); JsonArray envs = new JsonArray(); JsonObject legacy = T3McpBackend.obj("label", "Legacy"); legacy.addProperty("baseUrl", base); legacy.addProperty("accessToken", "fixture-only"); envs.add(legacy); old.add("environments", envs); old.addProperty("threadId", "mcp-question"); old.addProperty("books", true);
   Files.writeString(file, old.toString()); var config = T3Config.load(file); var grant = authorize(); config.upsert(grant); check(config.save(file), "migrated config saved");
   var loaded = T3Config.load(file).environments.getFirst();
   check("fixture-only".equals(loaded.accessToken) && "mcp-fixture".equals(loaded.mcpToken) && loaded.mcpScope.contains("settings:write"), "both credentials survive migration");
   check(T3Backend.forEnvironment(loaded).hasLegacy() && T3Backend.forEnvironment(loaded).publicMcp(), "migrated hybrid backend");
   check(Files.getPosixFilePermissions(file).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")), "private credential persistence");
   config.upsert(new T3Config.Environment("Legacy refreshed", base, "fixture-only")); check(config.environments.getFirst().mcpScope.equals(loaded.mcpScope), "legacy re-pair preserves MCP scope");
  } finally { Files.deleteIfExists(file); Files.deleteIfExists(dir); }
 }
 static void visibility(boolean mcp) throws Exception {
  reset(); get(base + "/_qa/visibility"); if (mcp) get(base + "/_qa/mcp-hybrid");
  T3Backend api = mcp ? api(true) : new T3Api(base, "fixture-only");
  var events = new java.util.concurrent.CopyOnWriteArrayList<T3State.Event>(); var state = new T3State(events::add);
  state.connect(api, "vis-snoozed-needs"); state.setPanelOpen(true);
  try {
   await(() -> state.snapshot().connected() && state.snapshot().threads().stream().anyMatch(r -> r.id().equals("vis-active-override")), "visibility fixtures loaded");
   var rows = state.snapshot().threads();
   check(rows.stream().noneMatch(r -> List.of("vis-settled", "vis-subagent", "vis-sidechat", "vis-archived").contains(r.id())), "settled, archived and children hidden");
   check(rows.stream().filter(r -> r.id().equals("vis-snoozed")).allMatch(T3Visibility::snoozedGroup), "snoozed belongs in collapsed group");
   check(rows.stream().anyMatch(r -> r.id().equals("vis-snoozed-needs") && !T3Visibility.snoozedGroup(r)), "needs-you raised from snoozed group");
   check(rows.stream().anyMatch(r -> r.id().equals("vis-settled-needs")), "needs-you raised from settled");
   check(rows.getFirst().status() == T3State.Status.NEEDS_YOU, "needs-you sorted first");
   await(() -> state.decisions().stream().filter(d -> List.of("vis-subagent", "vis-sidechat").contains(d.thread().id()) && d.requestId() != null).count() == 2, "hidden child decisions retained");
   var error = new AtomicReference<Exception>();
   for (String child : List.of("vis-subagent", "vis-sidechat")) {
    state.focus(child);
    await(() -> state.snapshot().focusedRow() != null && child.equals(state.snapshot().focusedRow().id())
      && state.snapshot().focus() != null && !state.snapshot().focus().userInputs().isEmpty(), "hidden child focus retains request and metadata");
    check(state.snapshot().threads().stream().noneMatch(r -> child.equals(r.id())), "focused child stays hidden");
    state.respond(child, mcp ? "mcp-request" : "shared-request", null, T3McpBackend.obj("format", "json"), error::set);
    await(() -> state.decisions().stream().noneMatch(d -> child.equals(d.thread().id()) && d.requestId() != null), "child response routes by child id");
   }
   check(error.get() == null, "hidden child answers succeed");
   state.focus("vis-settled");
   await(() -> state.snapshot().focusedRow() != null && "vis-settled".equals(state.snapshot().focusedRow().id())
     && state.snapshot().focus() != null && !state.snapshot().focus().messages().isEmpty(), "settled conversation remains available");
   check(state.snapshot().threads().stream().noneMatch(r -> "vis-settled".equals(r.id())), "focused settled thread stays hidden");
   if (mcp) check(array("/_qa/mcp-calls").asList().stream().map(JsonElement::getAsJsonObject).filter(c -> "t3_pending_request".equals(c.get("name").getAsString())).anyMatch(c -> "vis-subagent".equals(T3Protocol.string(c.getAsJsonObject("arguments"), "threadId")) && "respond".equals(T3Protocol.string(c.getAsJsonObject("arguments"), "action"))), "MCP child target recorded");
   else check(array("/_qa/dispatches").asList().stream().anyMatch(c -> "vis-sidechat".equals(T3Protocol.string(c.getAsJsonObject(), "threadId"))), "legacy child target recorded");
   var snoozed = rows.stream().filter(r -> r.id().equals("vis-snoozed-needs")).findFirst().orElseThrow();
   check(!T3Visibility.pings(snoozed) && events.stream().noneMatch(e -> e.thread().id().startsWith("vis-snoozed")), "snoozed threads do not ping");
   state.setPins(List.of(new OfficeRoster.Pin(base, "vis-settled", "Pinned settled")));
   await(() -> state.snapshot().threads().stream().anyMatch(r -> r.id().equals("vis-settled")), "pinned settled thread visible");
   var prefs = new OfficeRoster.Preferences(); prefs.pins.add(new OfficeRoster.Pin(base, "vis-settled", "Pinned settled"));
   var residents = OfficeRoster.sync(state.snapshot().threads(), List.of(base), prefs);
   check(residents.stream().anyMatch(r -> r.row().id().equals("vis-settled")) && residents.stream().noneMatch(r -> List.of("vis-snoozed", "vis-sidechat", "vis-subagent").contains(r.row().id())), "office shares visibility and pin rules");
   state.setPins(List.of()); await(() -> state.snapshot().threads().stream().noneMatch(r -> r.id().equals("vis-settled")), "unpin hides settled thread again");
  } finally { close(state); }
 }
 static void credentialFailures() throws Exception {
  reset(); var env = env(false); env.mcpToken = "private-marker"; env.mcpExpiresAt = 1; expectAuth(T3Backend.forEnvironment(env)); env.mcpExpiresAt = 0; expectAuth(T3Backend.forEnvironment(env));
 }
    static T3Config.Environment authorize() throws Exception {
        return T3McpAuth.authorize(base + "/mcp", uri -> {
            try {
                JsonObject consent = get(uri.toString());
                String query = consent.entrySet().stream().filter(e -> !e.getKey().equals("redirect"))
                    .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue().getAsString(), java.nio.charset.StandardCharsets.UTF_8)).reduce((a,b) -> a + "&" + b).orElse("");
                var response = HTTP.send(HttpRequest.newBuilder(URI.create(consent.get("redirect").getAsString() + "?" + query)).GET().build(), HttpResponse.BodyHandlers.ofString());
                check(response.statusCode() == 200 && response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"), "OAuth callback HTML");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
	private static JsonObject get(String url) throws Exception { return JsonParser.parseString(HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject(); }
	interface Action { void run() throws Exception; }
	interface Condition { boolean get() throws Exception; }
	static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
	static int posts() throws Exception { return get(base + "/_qa/mcp-posts").get("count").getAsInt(); }
	/** Returns as soon as the condition first holds; a condition that later flips back must not fail the wait. */
	static void await(Condition condition, String label) throws Exception { long end = System.currentTimeMillis() + 15000; while (System.currentTimeMillis() < end) { if (condition.get()) return; Thread.sleep(100); } check(condition.get(), label); }
	static void expectLegacy(Action action) throws Exception { try { action.run(); throw new AssertionError("legacy gap accepted"); } catch (IOException e) { check(e.getMessage().contains("Needs legacy connection"), "legacy limitation explained"); } }
	static void expectAuth(T3Backend api) throws Exception { try { api.shell(); throw new AssertionError("expired auth accepted"); } catch (IOException e) { check(e.getMessage().contains("Re-pair") && !e.getMessage().contains("private-marker"), "re-auth without leaking credentials"); } }
}
