package dev.maxwellyoung.t3craft;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Zero-setup rules without Minecraft: default thread choice, the one-line chat budget, and auto-pairing with the local T3. */
public final class FirstRunChecks {
	public static void main(String[] args) throws Exception {
		defaultThread();
		chatBudget();
		help();
		configMigration();
		repairBackoff();
		autoConnect(Integer.parseInt(args[0]));
		System.out.println("PASS default thread (chosen while listed, else needs-you, else most recent, else none), one connect line per game, player-only help, agent-build migration, re-pair backoff, auto-pairing with the local T3 through the fixture (probe before mint, no credential in errors)");
	}

	private static T3State.ThreadRow row(String id, T3State.Status status) {
		return new T3State.ThreadRow(id, "Thread " + id, "Project", "Studio", "http://127.0.0.1:1", status, null, null, new JsonObject());
	}

	/** A fresh token T3 refuses again waits 15 s, then twice as long each time, up to five minutes; a working pairing resets it. */
	private static void repairBackoff() {
		var backoff = new T3AutoConnect.Backoff();
		check(backoff.ready(0), "the first re-pair after a revoke is immediate");
		backoff.attempted(0);
		check(!backoff.ready(14_999) && backoff.ready(15_000), "a second refusal waits 15 s");
		backoff.attempted(15_000);
		check(!backoff.ready(44_999) && backoff.ready(45_000), "then 30 s");
		for (int i = 0; i < 10; i++) backoff.attempted(45_000);
		check(backoff.delayForTest() == 5 * 60_000, "capped at five minutes");
		backoff.reset();
		check(backoff.ready(45_001) && backoff.delayForTest() == 15_000, "a working pairing resets the backoff");
	}

	private static void defaultThread() {
		var chosen = row("chosen", T3State.Status.DONE);
		var recent = row("recent", T3State.Status.WORKING);
		var older = row("older", T3State.Status.DONE);
		var waiting = row("waiting", T3State.Status.NEEDS_YOU);
		check(FirstRun.defaultThread(List.of(), null) == null, "no threads: nothing to talk to, never a demand to pick one");
		check(FirstRun.defaultThread(List.of(recent, older), null) == recent, "no choice: the most recently active thread");
		check(FirstRun.defaultThread(List.of(recent, older, waiting), null) == waiting, "no choice: a thread that needs you wins over a more recent one");
		check(FirstRun.defaultThread(List.of(recent, chosen, waiting), chosen.id()) == chosen, "the player's own choice wins while it is listed");
		check(FirstRun.defaultThread(List.of(recent, waiting), chosen.id()) == waiting, "a settled, archived or child choice no longer counts: the thread that needs you is next");
		check(FirstRun.defaultThread(List.of(recent, older), chosen.id()) == recent, "a hidden choice with nothing waiting falls back to the most recent thread");
	}

	private static void chatBudget() {
		var notice = new FirstRun.ConnectNotice();
		int said = 0;
		// Launch unpaired, connect on the title screen, join a world, drop, reconnect, reconnect again.
		boolean[][] ticks = {{false, false}, {true, false}, {true, true}, {true, true}, {false, true}, {true, true}, {false, true}, {true, true}};
		for (boolean[] tick : ticks) if (notice.due(tick[0], tick[1])) said++;
		check(said == 1, "exactly one connect line per game, got " + said);
		check(!new FirstRun.ConnectNotice().due(true, false), "nothing is said before the player is in a world");
		check(!new FirstRun.ConnectNotice().due(false, true), "nothing is said while unpaired or disconnected");
		check(FirstRun.connectedLine("`").equals("T3 connected · press ` to open"), "the line names the bound key");
	}

	private static void help() {
		var tips = FirstRun.help("`");
		check(tips.size() == 2, "help is two lines, got " + tips.size());
		check(tips.get(0).command().equals("/t3 open") && tips.get(0).text().contains("`"), "first tip opens T3 and names the key");
		check(tips.get(1).command().equals("/t3 office"), "second tip is the office");
		for (var tip : tips) check(!tip.text().contains("pair") && !tip.text().contains("threads") && !tip.text().contains("debug"), "help never mentions setup or debug commands");
	}

	/** Files from before 0.6 carry the old agent-build default; the player's explicit choice in a current file is kept. */
	private static void configMigration() throws Exception {
		Path dir = Files.createTempDirectory("t3craft-first-run-config-");
		try {
			Path old = dir.resolve("old.json"), current = dir.resolve("current.json");
			Files.writeString(old, "{\"environments\":[],\"agentCommands\":false,\"books\":true}");
			Files.writeString(current, "{\"configVersion\":2,\"environments\":[],\"agentCommands\":false}");
			T3Config migrated = T3Config.load(old);
			check(migrated.agentCommands && migrated.configVersion == 2, "a pre-0.6 file gets agent building on and the current version");
			check(migrated.save(old) && T3Config.load(old).agentCommands, "the migrated file stays migrated once saved");
			check(!T3Config.load(current).agentCommands, "an explicit off in a current file is respected");
			check(new T3Config().configVersion == 2 && new T3Config().agentCommands, "fresh configs are current and allow agent building");
		} finally {
			try (var files = Files.walk(dir)) { files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (java.io.IOException ignored) {} }); }
		}
	}

	/** The fixture stands in for the local T3: its port is read from the runtime file T3 writes, then paired with a minted link. */
	private static void autoConnect(int port) throws Exception {
		String base = "http://127.0.0.1:" + port;
		get(base + "/_qa/reset");
		Path home = Files.createTempDirectory("t3craft-first-run-");
		try {
			check(T3Web.find(home) == null || !T3Web.find(home).origin().equals(base), "no runtime file: the fixture is not found by accident");
			Files.createDirectories(home.resolve("userdata"));
			Files.writeString(home.resolve("userdata").resolve("server-runtime.json"), "{\"version\":1,\"port\":" + port + "}");
			T3Web.Local local = T3Web.find(home);
			check(local != null && local.origin().equals(base) && local.environmentId().startsWith("fixture-"), "local T3 found through its runtime file");
			T3Config.Environment env = T3AutoConnect.pairLocal(home, found -> {
				check(found.origin().equals(base), "the pairing link is minted for the T3 that was found");
				return found.origin() + "/pair#token=fixture-pairing";
			});
			check(env != null && env.baseUrl.equals(base) && "fixture-only".equals(env.accessToken) && env.label.startsWith("Fixture"), "paired silently: address, token and T3's own label");
			T3Config config = new T3Config(); config.upsert(env);
			check(config.paired() && config.agentCommands && !config.debug, "a fresh config is paired, lets agents build, and hides debug commands");
			try { T3AutoConnect.pairLocal(home, found -> found.origin() + "/pair#token=expired-fixture"); throw new IllegalStateException("expired link accepted"); }
			catch (java.io.IOException e) { check(!e.getMessage().contains("expired-fixture"), "a failed attempt never echoes the credential"); }
			// An incompatible T3 is refused by one HTTP probe: its CLI is never asked for a credential.
			get(base + "/_qa/protocol3");
			try { T3AutoConnect.pairLocal(home, found -> { throw new IllegalStateException("minted"); }); throw new IllegalStateException("future protocol paired"); }
			catch (T3Api.UnsupportedVersionException e) { check(e.getMessage().contains("Update T3 Craft"), "protocol checked before minting"); }
			get(base + "/_qa/reset");
			check(T3Web.loopback("http://127.0.0.1:3773") && T3Web.loopback("http://localhost:3800") && !T3Web.loopback("https://studio.example.com:3773") && !T3Web.loopback("not a url"), "only this computer's addresses count as the local T3");
			check(T3AutoConnect.SCOPES.equals(List.of("orchestration:read", "orchestration:operate")), "the minted link is least-privilege");
		} finally {
			try (var files = Files.walk(home)) { files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (java.io.IOException ignored) {} }); }
			get(base + "/_qa/reset");
		}
	}

	private static void get(String url) throws Exception {
		HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.discarding());
	}
	private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
