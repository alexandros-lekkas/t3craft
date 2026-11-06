package dev.maxwellyoung.t3craft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Paired environments, office locations and the last focused thread. Tokens are credentials, so the file is owner-only.
 * There is no settings screen: the mod pairs with the local T3 Code by itself, and everything here is a power-user
 * override edited by hand.
 */
public final class T3Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** The Minecraft MCP server's default port; {@link #mcpPort} or {@code -Dt3craft.mcpPort} override it. */
	public static final int DEFAULT_MCP_PORT = 25590;

	public static final class Environment {
		public String label;
		public String baseUrl;
		public String accessToken;
		public String mcpUrl;
		public String mcpToken;
		public String mcpScope;
		public long mcpExpiresAt;

		public Environment(String label, String baseUrl, String accessToken) {
			this.label = label;
			this.baseUrl = baseUrl;
			this.accessToken = accessToken;
		}
	}

	/** Bumped when a default changes for existing files; {@link #load} migrates older ones. 2: agent building on. */
	public int configVersion = 2;
	public java.util.List<Environment> environments = new java.util.ArrayList<>();
	// Single-environment fields from 0.1; migrated into environments on load.
	public String baseUrl;
	public String accessToken;
	public String threadId;
	/** Client: each world's office origin, keyed by world (server address or save) and dimension. */
	public java.util.Map<String, int[]> offices = new java.util.HashMap<>();
	/** Client: how each world's office is turned (a {@code Rotation} name); absent means the design's own orientation. */
	public java.util.Map<String, String> officeRotations = new java.util.HashMap<>();
	// Pre-0.6 fields: the village anchor per world and the worlds whose village was an office. Migrated into offices on load.
	public java.util.Map<String, int[]> villages;
	public java.util.Set<String> officeWorlds;
	/** Server: where the shared office stands; null when it's off. */
	public int[] serverOffice;
	/** Client spatial preferences, independently saved per world and dimension. */
	public java.util.Map<String, OfficeRoster.Preferences> officePreferences = new java.util.HashMap<>();
	/** Operator-owned shared office preferences; independent of client worlds. */
	public OfficeRoster.Preferences sharedOfficePreferences = new OfficeRoster.Preferences();
	/** Hand the player a written report when a watched thread finishes (needs command permission). */
	public boolean books = true;
	/** Lets local agents run commands through the MCP server. On by default: no gates ({@code /t3 agent-build off} turns it off). */
	public boolean agentCommands = true;
	/** Shows the debug and admin subcommands of {@code /t3} (threads, pins, books, unpair…). Off: players see only what they need. */
	public boolean debug;
	/**
	 * Stream T3 Code's web app through a hidden Chrome instead of the native screens. Off by default, and there is
	 * no in-game switch: edit {@code t3craft.json}. While it is off, Chrome is never started.
	 */
	public boolean webUi;
	/** Preferred port for the Minecraft MCP server; the next free port is used when it is taken. */
	public int mcpPort = DEFAULT_MCP_PORT;

	public boolean paired() {
		return !environments.isEmpty();
	}

	/** Adds or refreshes the environment at {@code baseUrl}. */
	public void upsert(Environment environment) {
		for (int i = 0; i < environments.size(); i++) {
			if (ownerKey(environments.get(i).baseUrl).equals(ownerKey(environment.baseUrl))) { Environment old = environments.get(i);
				if (environment.mcpUrl == null) { environment.mcpUrl = old.mcpUrl; environment.mcpToken = old.mcpToken; environment.mcpExpiresAt = old.mcpExpiresAt; environment.mcpScope = old.mcpScope; }
				if (environment.accessToken == null) environment.accessToken = old.accessToken;
				environments.set(i, environment); return; }
		}
		environments.add(environment);
	}

	static String ownerKey(String baseUrl) { return baseUrl.replaceAll("/+$", ""); }

	String labelFor(String owner) {
		for (T3Config.Environment env : environments) if (T3Config.ownerKey(env.baseUrl).equals(owner)) {
			boolean duplicate = environments.stream().filter(e -> java.util.Objects.equals(e.label, env.label)).count() > 1;
			if (!duplicate) return env.label;
			java.net.URI address = java.net.URI.create(owner);
			boolean sameHost = environments.stream().filter(e -> java.util.Objects.equals(e.label, env.label))
				.allMatch(e -> java.util.Objects.equals(java.net.URI.create(e.baseUrl).getHost(), address.getHost()));
			return env.label + " · " + (sameHost && address.getPort() != -1 ? ":" + address.getPort() : address.getAuthority());
		}
		return "Unpaired machine";
	}

	public static T3Config load(Path path) {
		try {
			if (Files.exists(path)) {
				String text = Files.readString(path);
				T3Config config = GSON.fromJson(text, T3Config.class);
				if (config != null) {
					// Files from before 0.6 carry "agentCommands": false because Gson wrote the old default, not because the player chose it.
					JsonObject raw = JsonParser.parseString(text).getAsJsonObject();
					int version = raw.has("configVersion") && !raw.get("configVersion").isJsonNull() ? raw.get("configVersion").getAsInt() : 1;
					if (version < 2) config.agentCommands = true;
					config.configVersion = 2;
					if (config.environments == null) config.environments = new java.util.ArrayList<>();
					if (config.environments.isEmpty() && config.baseUrl != null && config.accessToken != null) {
						config.environments.add(new Environment("This Mac", config.baseUrl, config.accessToken));
					}
					if (config.offices == null) config.offices = new java.util.HashMap<>();
					if (config.officeRotations == null) config.officeRotations = new java.util.HashMap<>();
					migrateVillages(config);
					config.offices.values().removeIf(point -> point == null || point.length != 3);
					if (config.officePreferences == null) config.officePreferences = new java.util.HashMap<>();
					config.officePreferences.values().removeIf(java.util.Objects::isNull);
					config.officePreferences.values().forEach(OfficeRoster.Preferences::normalize);
					if (config.sharedOfficePreferences == null) config.sharedOfficePreferences = new OfficeRoster.Preferences();
					config.sharedOfficePreferences.normalize();
					if (config.mcpPort <= 0 || config.mcpPort > 65535) config.mcpPort = DEFAULT_MCP_PORT;
					config.baseUrl = null;
					config.accessToken = null;
					return config;
				}
			}
		} catch (IOException | RuntimeException e) {
			T3Log.LOGGER.warn("Could not read {}; starting unpaired", path, e);
		}
		return new T3Config();
	}

	/** A pre-0.6 world keeps its office where it stood; plain villages (no office) are dropped with the village mode. */
	static void migrateVillages(T3Config config) {
		if (config.villages != null && config.officeWorlds != null) {
			for (String world : config.officeWorlds) {
				int[] point = config.villages.get(world);
				if (point != null && !config.offices.containsKey(world)) config.offices.put(world, point);
			}
		}
		config.villages = null;
		config.officeWorlds = null;
	}

	/** Write a private replacement first so a new pairing is never briefly world-readable. */
	public boolean save(Path path) {
		Path temporary = null;
		try {
			Path destination = path.toAbsolutePath();
			Files.createDirectories(destination.getParent());
			try {
				temporary = Files.createTempFile(destination.getParent(), ".t3craft-", ".tmp",
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
			} catch (UnsupportedOperationException ignored) {
				// Windows: the replacement inherits the user profile directory ACLs.
				temporary = Files.createTempFile(destination.getParent(), ".t3craft-", ".tmp");
			}
			Files.writeString(temporary, GSON.toJson(this));
			try { Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
			catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
			return true;
		} catch (IOException e) {
			T3Log.LOGGER.warn("Could not save {}", path, e);
			return false;
		} finally {
			if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
		}
	}
}
