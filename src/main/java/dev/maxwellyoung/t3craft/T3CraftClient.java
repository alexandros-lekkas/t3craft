package dev.maxwellyoung.t3craft;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * T3 Code inside Minecraft. Everything is client-side: prompts go straight to your T3
 * environment over HTTP and never touch server chat, so it works on any server.
 */
public final class T3CraftClient implements ClientModInitializer {
	public static final String MOD_ID = "t3craft";
	public static final Logger LOGGER = T3Log.LOGGER;
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(6000L);

	private static T3CraftClient instance;

	// Overridable so tests can pair with a sandbox without touching the real pairing.
	private final Path configPath = System.getProperty("t3craft.config") != null
		? Path.of(System.getProperty("t3craft.config"))
		: FabricLoader.getInstance().getConfigDir().resolve("t3craft.json");
	private T3Config config;
	private T3State state;
	private KeyMapping openKey;
	private KeyMapping decisionsKey;
	private boolean openPanelNextTick;
	private int connectionRevision;
	private boolean pairingInProgress;
	private String lastPingThread;
	private long lastPingAt;
	private boolean jumpNextTick;
	private static final long PING_JUMP_WINDOW_MS = 60_000;
	private OfficeResidents residents;
	private final OfficeQueue office = new OfficeQueue();
	private final FirstRun.ConnectNotice connectNotice = new FirstRun.ConnectNotice();
	private int ticks;
	/** The last level checked for a T3 World marker, so each world is adopted once per visit. */
	private Object t3WorldLevel;

	public static T3CraftClient get() {
		return instance;
	}

	public T3State state() {
		return state;
	}

	public boolean paired() {
		return config.paired();
	}

	/** The registered (and possibly rebound) keys, so screens match and label them instead of hardcoding. */
	KeyMapping openKey() { return openKey; }
	KeyMapping decisionsKey() { return decisionsKey; }
	/** What the open key is bound to, for hints ("`" by default). */
	String openKeyName() { return openKey.getTranslatedKeyMessage().getString(); }

	@Override
	public void onInitializeClient() {
		instance = this;
		config = T3Config.load(configPath);
		state = new T3State(event -> Minecraft.getInstance().execute(() -> announce(event)));
		connectAll();
		startAutoConnect();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.t3craft.open", InputConstants.KEY_GRAVE, category));

		decisionsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.t3craft.decisions", InputConstants.KEY_J, category));

		// Dev captures run beside the player's real game: never take over its MCP port.
		if (System.getProperty("t3craft.webShowcase") == null) new T3Mcp(() -> config.agentCommands, T3Mcp.preferredPort(config), configPath.resolveSibling("t3craft-mcp.json")).start();
		residents = new OfficeResidents(this);
		ClientTickEvents.START_CLIENT_TICK.register(residents::tick);
		ClientTickEvents.START_CLIENT_TICK.register(ClientCommands::tick);
		ClientTickEvents.START_CLIENT_TICK.register(office::tick);
		ClientTickEvents.END_CLIENT_TICK.register(residents::animate);
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.ALLOW_GAME.register(
			(message, overlay) -> overlay || !(T3Books.isOwnFeedback(message.getString()) || office.hideFeedback(message)));
		SelfTest selfTest = SelfTest.fromSystemProperty(this);
		WorldSelfTest worldTest = WorldSelfTest.fromSystemProperty(this);
		WebShowcase webShowcase = WebShowcase.fromSystemProperty(this);
		if (webShowcase != null) ClientTickEvents.END_CLIENT_TICK.register(webShowcase::tick);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			state.setPins(List.copyOf(officePreferences().pins));
			// The one line the mod says on its own: once per game, when connected and in a world. Reconnects stay in the log.
			if (connectNotice.due(state.snapshot().connected(), client.player != null))
				chat(Component.literal(FirstRun.connectedLine(openKeyName())).withStyle(ChatFormatting.GREEN));
			if (++ticks % 100 == 0) recoverLocalPairing();
			if (selfTest != null) selfTest.tick(client);
			if (worldTest != null) worldTest.tick(client);
			while (decisionsKey.consumeClick()) if (client.gui.screen() == null) openDecisions();
			while (openKey.consumeClick()) {
				openPanelNextTick = true;
				// Answering a ping: open the thread that pinged, not whatever was focused before.
				if (lastPingThread != null && System.currentTimeMillis() - lastPingAt < PING_JUMP_WINDOW_MS) { focus(lastPingThread); jumpNextTick = true; }
				lastPingThread = null;
			}
			if (openPanelNextTick && client.gui.screen() == null) {
				openPanelNextTick = false;
				boolean jump = jumpNextTick;
				jumpNextTick = false;
				openLaptop(jump);
			}
		});

		// The hidden Chrome behind the laptop never outlives the game.
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ChromeHost.shutdown());
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "status"), new T3Hud(this));
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> { if (screen instanceof TitleScreen) addWorldButton(screen); });
		ClientTickEvents.END_CLIENT_TICK.register(this::trackWorld);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> registerCommands(dispatcher));
	}

	/**
	 * "T3 World" beside Singleplayer: the vanilla button shrinks to the left half of its row and ours
	 * takes the right half, like the Options/Quit row. Nothing else on the title screen moves.
	 */
	private void addWorldButton(net.minecraft.client.gui.screens.Screen screen) {
		for (var widget : Screens.getWidgets(screen)) {
			if (!(widget instanceof Button single) || !(single.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text)
				|| !text.getKey().equals("menu.singleplayer")) continue;
			int half = (single.getWidth() - 4) / 2;
			single.setWidth(half);
			Button ours = Button.builder(Component.literal("T3 World"), b -> Minecraft.getInstance().gui.setScreen(T3WorldScreen.open(screen, this)))
				.bounds(single.getX() + half + 4, single.getY(), half, single.getHeight())
				.tooltip(Tooltip.create(Component.literal("Your world with your agents in it. Office at spawn, ready to go."))).build();
			Screens.getWidgets(screen).add(ours);
			return;
		}
	}

	/** The New T3 World screen turns agent build on: that's what the world is for. */
	void saveAgentBuildOn() {
		if (config.agentCommands) return;
		config.agentCommands = true;
		saveConfig();
	}

	/**
	 * Entering a T3 World: adopt the office the server built as this world's office (once, so a
	 * later rebuild is respected), and show the one welcome toast. After that, bare /t3 office
	 * takes you to the door instead of building a second office.
	 */
	private void trackWorld(Minecraft minecraft) {
		if (minecraft.level == t3WorldLevel) return;
		t3WorldLevel = minecraft.level;
		var server = minecraft.getSingleplayerServer();
		if (minecraft.level == null || server == null) return;
		var level = minecraft.level;
		server.execute(() -> {
			T3WorldData data = T3WorldData.get(server);
			if (data == null) return;
			boolean welcome = data.setupDone && !data.welcomed;
			if (welcome) { data.welcomed = true; data.setDirty(); }
			var origin = data.origin;
			var rotation = data.rotation;
			minecraft.execute(() -> {
				if (minecraft.level != level) return;
				if (origin != null) adoptOffice(origin, rotation);
				if (!welcome) return;
				SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST, Component.literal("Welcome to " + data.name),
					Component.literal(origin != null ? "Your office is ready. Press " + openKeyName() + " to talk to your agents."
						: "No flat spot for the office. Stand somewhere and run /t3 office."));
			});
		});
	}

	/** Records a server-built office for the overworld of this save unless the world already has an office. */
	private void adoptOffice(net.minecraft.core.BlockPos origin, net.minecraft.world.level.block.Rotation rotation) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
		String world = worldKey();
		if (world == null || config.offices.containsKey(world)) return;
		config.offices.put(world, new int[] {origin.getX(), origin.getY(), origin.getZ()});
		if (rotation != net.minecraft.world.level.block.Rotation.NONE) config.officeRotations.put(world, rotation.getSerializedName());
		else config.officeRotations.remove(world);
		saveConfig();
		LOGGER.info("Adopted the T3 World office at {} ({}) for {}", origin.toShortString(), rotation, world);
	}

	/** How this world's office is turned, from the config; the design's own orientation when nothing is recorded. */
	private net.minecraft.world.level.block.Rotation officeRotation(String world) {
		String name = config.officeRotations.get(world);
		if (name == null) return net.minecraft.world.level.block.Rotation.NONE;
		for (var rotation : net.minecraft.world.level.block.Rotation.values()) if (rotation.getSerializedName().equals(name)) return rotation;
		return net.minecraft.world.level.block.Rotation.NONE;
	}

	/**
	 * This world's office as a frame (origin and turn), or null when this world has no office. Unlike
	 * {@link #officeOrigin()} it does not need a pairing: a built office keeps its furniture while no machine is paired.
	 */
	OfficeFrame officeFrame() {
		String world = worldKey();
		int[] pos = world == null ? null : config.offices.get(world);
		return pos == null || pos.length != 3 ? null : new OfficeFrame(new net.minecraft.core.BlockPos(pos[0], pos[1], pos[2]), officeRotation(world));
	}

	/** Where this world's office stands (its origin), or null when this world has none or nothing is paired: where agents live. */
	public net.minecraft.core.BlockPos officeOrigin() {
		String world = worldKey();
		if (world == null || !config.paired()) return null;
		int[] pos = config.offices.get(world);
		return pos == null || pos.length != 3 ? null : new net.minecraft.core.BlockPos(pos[0], pos[1], pos[2]);
	}

	/** The world and dimension the player is in, so each keeps its own office; null outside a world. */
	private static String worldKey() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) return null;
		String where;
		if (minecraft.getSingleplayerServer() != null) {
			where = "save:" + minecraft.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
				.toAbsolutePath().normalize().getFileName();
		} else if (minecraft.getCurrentServer() != null) {
			where = "server:" + minecraft.getCurrentServer().ip.toLowerCase(java.util.Locale.ROOT);
		} else {
			return null;
		}
		return where + "|" + minecraft.level.dimension().identifier();
	}

	OfficeResidents residents() {
		return residents;
	}

	OfficeQueue office() {
		return office;
	}

	/** One office floor per paired machine, bottom up. */
	List<String> floors() {
		return floorOwners().stream().map(config::labelFor).toList();
	}

	List<String> floorOwners() {
		OfficeRoster.Preferences prefs = officePreferences();
		if (prefs.floorOwners == null) {
			prefs.floorOwners = pairedOwners();
			if (officeOrigin() != null) saveOfficePreferences();
		}
		return prefs.floorOwners;
	}

	private List<String> pairedOwners() { return config.environments.stream().map(e -> T3Config.ownerKey(e.baseUrl)).toList(); }

	OfficeRoster.Preferences officePreferences() {
		String world = worldKey();
		return world == null ? new OfficeRoster.Preferences() : config.officePreferences.computeIfAbsent(world, k -> new OfficeRoster.Preferences());
	}

	boolean canPin() { return worldKey() != null; }
	private void saveConfig() {
		if (!config.save(configPath)) chat(Component.literal("Could not save settings; changes apply for this session only. Check Minecraft's config folder permissions."));
	}

	void saveOfficePreferences() { saveConfig(); }
	boolean pinned(T3State.ThreadRow row) { return row != null && officePreferences().pinned(row); }
	void togglePin(T3State.ThreadRow row) {
		if (row == null || !canPin()) return;
		OfficeRoster.Preferences prefs = officePreferences();
		if (prefs.pinned(row)) prefs.pins.removeIf(p -> p.key().equals(OfficeRoster.key(row)));
		else if (!prefs.pin(row)) { chat(Component.literal("All sixteen desks for this machine are pinned. Unpin one in Pins first.")); return; }
		saveOfficePreferences();
	}
	void unpin(OfficeRoster.Pin pin) { officePreferences().unpin(pin); saveOfficePreferences(); }
	String machineLabel(String owner) { return config.labelFor(owner); }

    private record OfficeDestination(OfficeFrame frame, String dimension) {}
    private OfficeDestination officeDestination() {
        String current = worldKey();
        if (current == null) return null;
        String world = current.substring(0, current.lastIndexOf('|') + 1);
        String key = config.offices.containsKey(current) ? current : config.offices.keySet().stream()
            .filter(k -> k.startsWith(world)).sorted().findFirst().orElse(null);
        if (key == null) return null;
        int[] point = config.offices.get(key);
        return point == null || point.length != 3 ? null : new OfficeDestination(
            new OfficeFrame(new net.minecraft.core.BlockPos(point[0], point[1], point[2]), officeRotation(key)), key.substring(key.lastIndexOf('|') + 1));
    }

    /** Return to the entrance even from an unloaded area or another dimension of this world. */
    int goToOffice() {
        Minecraft mc = Minecraft.getInstance();
        OfficeDestination destination = officeDestination();
        if (mc.player == null || destination == null) {
            chat(Component.literal("No office in this world yet. /t3 office builds one where you stand."));
            return 0;
        }
        // Just outside the door, looking in, however the office is turned.
        int[] outside = OfficeDesign.doorOutside();
        var at = destination.frame().point(outside[0] + 0.5, 0, outside[1] - 2.5);
        double x = at.x, y = at.y, z = at.z;
        float yaw = destination.frame().yaw(0);
        var server = mc.getSingleplayerServer();
        if (server == null) {
            String command = "tp @s " + x + " " + y + " " + z + " " + yaw + " 0";
            if (!mc.level.dimension().identifier().toString().equals(destination.dimension()))
                command = "execute in " + destination.dimension() + " run " + command;
            ClientCommands.send(command);
        } else {
            var playerId = mc.player.getUUID();
            var connection = mc.getConnection();
            server.execute(() -> {
                if (mc.getConnection() != connection || mc.getSingleplayerServer() != server) return;
                var target = server.getLevel(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.Identifier.parse(destination.dimension())));
                var player = server.getPlayerList().getPlayer(playerId);
                if (target != null && player != null) {
                    player.teleportTo(target, x, y, z, java.util.Set.of(), yaw, 0, true);
                    player.fallDistance = 0;
                }
            });
        }
        return 1;
    }

    private int officeAction() {
        if (officeDestination() != null) return goToOffice();
        buildOffice(); return 1;
    }

	/**
	 * Builds the Scandinavian office just south of the player (door facing them) and moves this world's agents into it.
	 * No confirmations: terrain at the site is replaced and the bounds go to the log; the player's own storage and
	 * builds make it refuse instead, with one line saying what and where.
	 */
	void buildOffice() {
		var minecraft = Minecraft.getInstance();
		var player = minecraft.player;
		String world = worldKey();
		if (player == null || world == null) return;
		var owners = pairedOwners();
		var labels = owners.stream().map(config::labelFor).toList();
		var origin = player.blockPosition().offset(-10, 0, 5);
		var blueprint = T3Office.blueprint(labels);
		var bounds = OfficeSite.bounds(origin, blueprint);
		if (!OfficeSite.loaded(minecraft.level, origin, blueprint)) {
			chat(Component.literal("The whole office site must be loaded and inside the world's height limits.").withStyle(ChatFormatting.RED)); return;
		}
		// Terrain is replaced without a word; the player's storage and builds are not. This is a refusal, not a confirmation.
		var blocker = OfficeSite.blocker(minecraft.level, bounds, officeOrigin(), T3Office.blueprint(floors()));
		if (blocker != null) {
			String what = minecraft.level.getBlockState(blocker).getBlock().getName().getString();
			chat(Component.literal("The office would cover your " + what + " at " + blocker.toShortString()
				+ ". The office goes just south of where you stand: move somewhere clear of your chests and builds, then run /t3 office again. Nothing was changed.")
				.withStyle(ChatFormatting.RED));
			return;
		}
		LOGGER.info("Building the office at {}", bounds.describe());
		var oldOffice = config.offices.get(world);
		var oldOwners = officePreferences().floorOwners;
		var oldRotation = config.officeRotations.get(world);
		config.offices.put(world, new int[] {origin.getX(), origin.getY(), origin.getZ()});
		config.officeRotations.remove(world); // Built by hand: the design's own orientation.
		officePreferences().floorOwners = List.copyOf(owners);
		if (!config.save(configPath)) {
			if (oldOffice == null) config.offices.remove(world); else config.offices.put(world, oldOffice);
			if (oldRotation != null) config.officeRotations.put(world, oldRotation);
			officePreferences().floorOwners = oldOwners;
			chat(Component.literal("Could not save office settings; no blocks were changed.").withStyle(ChatFormatting.RED)); return;
		}
		office.build(origin, labels);
		residents.clear();
		LOGGER.info("Office build queued{}", minecraft.getSingleplayerServer() == null ? " (needs command permission on this server)" : "");
	}

	private int officeOff() {
		String world = worldKey();
		var oldOffice = world == null ? null : config.offices.remove(world);
		var oldRotation = world == null ? null : config.officeRotations.remove(world);
		if (!config.save(configPath)) {
			if (oldOffice != null) config.offices.put(world, oldOffice);
			if (oldRotation != null) config.officeRotations.put(world, oldRotation);
			chat(Component.literal("Could not save office settings; office kept.")); return 0;
		}
		office.clear();
		residents.clear();
		chat(Component.literal("Office residents hidden; building blocks kept."));
		return 1;
	}

	private List<T3State.Connection> connections() {
		List<T3State.Connection> connections = new java.util.ArrayList<>();
		for (T3Config.Environment env : config.environments) {
			connections.add(new T3State.Connection(T3Backend.forEnvironment(env), env.label));
		}
		return connections;
	}

	private void connectAll() { state.connect(connections(), config.threadId); }

	/** Zero setup: pair with the T3 Code on this computer by itself. Dev runs (self-tests, captures) keep their own pairing. */
	private void startAutoConnect() {
		if (config.paired() || devRun()) return;
		T3AutoConnect.ensureStarted(() -> !config.paired(), env -> Minecraft.getInstance().execute(() -> adoptLocal(env)));
	}

	/** Self-tests and captures run against fixtures beside the real game: they must never pair with the real T3. */
	private static boolean devRun() {
		return System.getProperty("t3craft.selftest") != null || System.getProperty("t3craft.worldtest") != null || System.getProperty("t3craft.webShowcase") != null;
	}

	/**
	 * A local pairing T3 no longer accepts (revoked in T3's settings, expired, or T3 reinstalled): forget it and pair
	 * again silently, so the fix is never a hand-edited config file.
	 */
	private void recoverLocalPairing() {
		for (String owner : state.authFailedOwners()) {
			if (!T3Web.loopback(owner)) continue;
			if (removeEnvironment(owner)) LOGGER.info("The local T3 no longer accepts its pairing; pairing again");
			else LOGGER.warn("The local T3 no longer accepts its pairing and the config could not be saved; still trying with it");
		}
		startAutoConnect();
	}

	/** Auto-connect paired the local T3 in the background: keep it, unless the player paired something meanwhile. */
	private void adoptLocal(T3Config.Environment env) {
		if (config.paired()) return;
		connectionRevision++;
		config.upsert(env);
		if (!config.save(configPath)) LOGGER.warn("Could not save the local T3 pairing; connected for this session only");
		state.reconfigure(connections(), config.threadId);
		LOGGER.info("Connected to {} at {}", env.label, env.baseUrl);
	}
	List<T3Config.Environment> environments() { return List.copyOf(config.environments); }

	/** Forgets one paired machine (the local T3 when it stops accepting its pairing); false when the config could not be saved. */
	boolean removeEnvironment(String owner) {
		connectionRevision++;
		var previous = new java.util.ArrayList<>(config.environments);
		String previousFocus = config.threadId;
		var row = state.snapshot().focusedRow();
		if (row != null && owner.equals(row.ownerKey()) || config.environments.size() == 1) config.threadId = null;
		config.environments.removeIf(e -> T3Config.ownerKey(e.baseUrl).equals(owner));
		if (!config.save(configPath)) { config.environments = previous; config.threadId = previousFocus; return false; }
		state.reconfigure(connections(), config.threadId); return true;
	}

	/** Dev runs only ({@code -PwebPair}): pairs with an explicit link. Players never see a link; auto-connect does this. */
	record PairOutcome(String error, String note) {}
 private volatile T3McpAuth.Pending mcpPairing;
 void pairEnvironment(String link, String label, String expectedOwner, java.util.function.Consumer<PairOutcome> result) {
		if (pairingInProgress) { result.accept(new PairOutcome("A pairing is already in progress. Wait for it to finish.", null)); return; }
		pairingInProgress = true;
		int revision = connectionRevision;
		Thread thread = new Thread(() -> {
			String error = null;
			try {
				boolean mcp = T3McpAuth.isMcp(link);
				String actual = mcp ? T3McpAuth.address(link) : T3Api.pairingAddress(link);
                if (expectedOwner != null && !expectedOwner.equals(actual))
					throw new java.io.IOException("This URL is for " + actual + ", but this machine is saved as " + expectedOwner + ". Use Add machine for a different address.");
				T3Config.Environment environment;
				if (mcp) { mcpPairing = new T3McpAuth.Pending(); environment = T3McpAuth.authorize(link, uri -> Minecraft.getInstance().execute(() -> com.mojang.blaze3d.Blaze3D.openUri(uri)), mcpPairing); }
				else { T3Api.Pairing pairing = T3Api.pair(link, "Minecraft"); environment = new T3Config.Environment(T3Api.environmentLabel(pairing.baseUrl()), pairing.baseUrl(), pairing.accessToken()); }
				if (label != null && !label.isBlank()) environment.label = label.trim();
				Minecraft.getInstance().execute(() -> {
					pairingInProgress = false; mcpPairing = null;
					if (revision != connectionRevision) { result.accept(new PairOutcome("Connections changed during pairing. Try a fresh link.", null)); return; }
					connectionRevision++;
					var previous = new java.util.ArrayList<>(config.environments);
					config.upsert(environment);
					if (!config.save(configPath)) { config.environments = previous; result.accept(new PairOutcome("Could not save this pairing. Check Minecraft's config folder permissions, then try a fresh link.", null)); return; }
					state.reconfigure(connections(), config.threadId); String note = environment.mcpScope != null && !java.util.Arrays.asList(environment.mcpScope.split(" ")).contains("settings:write")
                        ? "Paired with limited MCP access. Threads running in Full access will refuse prompts from T3 Craft. Re-pair and choose Full access." : null;
                    chat(Component.literal("Paired " + environment.label + "." + (note == null ? "" : " " + note)).withStyle(ChatFormatting.GREEN));
                    result.accept(new PairOutcome(null, note));
				});
				return;
			} catch (T3Api.UnsupportedVersionException e) { error = e.getMessage(); }
			catch (java.io.IOException e) {
				error = (e.getClass() == java.io.IOException.class || e instanceof T3McpException) ? e.getMessage() : "Could not reach this machine. Check T3 Code, the address and network, then try again.";
			} catch (Exception e) { error = "Pairing could not finish. Check T3 Code and try a fresh link."; }
			String message = error;
			Minecraft.getInstance().execute(() -> { pairingInProgress = false; mcpPairing = null; chat(Component.literal(message).withStyle(ChatFormatting.RED)); result.accept(new PairOutcome(message, null)); });
		}, "t3craft-pair");
		thread.setDaemon(true); thread.start();
	}

	public void openPanel() {
		Minecraft minecraft = Minecraft.getInstance();
		if (!config.paired()) {
			// Nothing to show yet, and nothing to set up either: auto-connect pairs as soon as T3 Code is running.
			chat(Component.literal("Not connected to T3 Code yet. Start T3 Code on this computer and T3 Craft connects by itself."));
			return;
		}
		lastPingThread = null;
        residents.faceThread(state.focusedThreadId());
		minecraft.gui.setScreen(new T3Screen(this));
	}


	/**
	 * The streamed web app is opt-in ({@code webUi} in the config file); the dev showcase that captures it turns it on
	 * for that run only. Otherwise the native screens are used and Chrome never starts.
	 */
	boolean webUi() { return config.webUi || System.getProperty("t3craft.webShowcase") != null; }

	/** The panel key and the laptop: the native T3 screen, or T3 Code's web app when {@code webUi} is on. */
	public void openLaptop() { openLaptop(false); }

	/** {@code jump}: the player is answering a ping, so the web laptop must show that thread even if it was already open on another. */
	public void openLaptop(boolean jump) {
		if (!webUi() || !T3WebScreen.available()) { openPanel(); return; }
		lastPingThread = null;
		String focused = state.focusedThreadId();
		Minecraft.getInstance().gui.setScreen(T3WebScreen.laptop(focused, ownerOf(focused), jump, this::openPanel));
	}

	/** Click an agent: a small native card beside it, or its thread in the web app when {@code webUi} is on. */
	public void openAgentCard(String threadId) {
		focus(threadId);
		if (webUi() && T3WebScreen.available()) {
			// A thread on another paired machine cannot be streamed from the local T3; the web view falls back to the panel for it.
			Minecraft.getInstance().gui.setScreen(T3WebScreen.card(threadId, ownerOf(threadId), this::openPanel));
			return;
		}
		if (!config.paired()) { openPanel(); return; }
		lastPingThread = null;
		Minecraft.getInstance().gui.setScreen(new T3AgentCardScreen(this, threadId));
	}

	/** The paired machine that owns {@code threadId} (its address), or null when unknown. */
	private String ownerOf(String threadId) {
		T3Backend owner = threadId == null ? null : state.apiFor(threadId);
		return owner == null ? null : owner.ownerKey();
	}

	void openDecisions() {
		if (paired()) Minecraft.getInstance().gui.setScreen(new T3DeskScreen(this));
		else openPanel();
	}

	void openRequest(T3Decisions.Entry entry) {
		focus(entry.thread().id());
		Minecraft.getInstance().gui.setScreen(new T3Screen(this, entry.requestId()));
	}

	void openReview(String threadId) {
		if (threadId != null) Minecraft.getInstance().gui.setScreen(new T3ReviewScreen(this, threadId));
	}

	/** Remembers the focused thread across sessions. */
	public void focus(String threadId) {
		// Choosing a thread yourself outranks an earlier ping, so ` won't jump back to it.
		lastPingThread = null;
		state.focus(threadId);
		config.threadId = threadId;
		saveConfig();
	}

	/** Sends to the focused thread, or starts a new thread beside it. Runs off-thread; reports back in chat. */
	public void send(String text, boolean newThread) {
		send(text, newThread, null, null);
	}

	/**
	 * {@code model} overrides the thread's model for this turn (and the thread from then on); null keeps it.
	 * Returns false when nothing was sent; {@code onFailure} runs on the game thread if the request later fails.
	 */
	/**
	 * Sends to a specific thread, never to whatever is focused: the focus snapshot can lag a click on another agent.
	 * Returns false (and says so) while that thread hasn't loaded yet.
	 */
	public boolean sendTo(String threadId, String text, Runnable onFailure) {
		T3State.ThreadRow target = null;
		for (T3State.ThreadRow row : state.snapshot().threads()) if (row.id().equals(threadId)) { target = row; break; }
		if (target == null) {
			chat(Component.literal("That agent is still loading; try again in a moment.").withStyle(ChatFormatting.RED));
			return false;
		}
		T3State.ThreadRow row = target;
		T3Backend api = state.apiFor(row.id());
		state.run(() -> { api.sendPrompt(row.raw(), text, null); state.watch(row.id()); },
			e -> { reportError(e); if (onFailure != null) Minecraft.getInstance().execute(onFailure); });
		return true;
	}

	public boolean send(String text, boolean newThread, com.google.gson.JsonObject model, Runnable onFailure) {
		// Nothing to pick first: the chosen thread, else the one that needs you, else the most recent one
		// (a new thread only borrows its project and settings). With no threads at all, T3 itself is where to start one.
		T3State.ThreadRow target = targetThread();
		if (target == null) {
			openPanelNextTick = true;
			return false;
		}
		// The target's own environment: a thread from the home server goes to the home server.
		T3Backend api = state.apiFor(target.id());
		state.run(() -> {
			if (newThread) {
				String id = api.startThread(target.raw(), text, model);
				state.focus(id);
				Minecraft.getInstance().execute(() -> focus(id));
			} else {
				api.sendPrompt(target.raw(), text, model);
				state.watch(target.id());
			}
		}, e -> { reportError(e); if (onFailure != null) Minecraft.getInstance().execute(onFailure); });
		return true;
	}

	public void respond(T3State.Approval approval, String decision) {
		var focus = state.snapshot().focus();
		if (focus != null) respond(focus.threadId(), approval, decision);
	}

	void respond(String threadId, T3State.Approval approval, String decision) {
		state.respond(threadId, approval.requestId(), decision, null, this::reportError);
	}

	public void answer(T3State.UserInput input, com.google.gson.JsonObject answers) {
		var focus = state.snapshot().focus();
		if (focus != null) answer(focus.threadId(), input, answers);
	}

	void answer(String threadId, T3State.UserInput input, com.google.gson.JsonObject answers) {
		answer(threadId, input, answers, null);
	}

	/** {@code onFailure} runs on the game thread if the answers didn't go through. */
	void answer(String threadId, T3State.UserInput input, com.google.gson.JsonObject answers, Runnable onFailure) {
		state.respond(threadId, input.requestId(), null, answers.deepCopy(),
			e -> { reportError(e); if (onFailure != null) Minecraft.getInstance().execute(onFailure); });
	}

	/** Unsent composer text per thread ("new" for a new thread), so switching threads never loses a draft. */
	private final java.util.Map<String, String> drafts = new java.util.HashMap<>();

	String draft(String key) {
		return drafts.getOrDefault(key, "");
	}

	void saveDraft(String key, String text) {
		if (key == null) return;
		if (text == null || text.isBlank()) drafts.remove(key);
		else drafts.put(key, text);
	}

	public void interrupt() {
		T3State.ThreadRow target = targetThread();
		if (target == null) return;
		T3Backend api = state.apiFor(target.id());
		state.run(() -> api.interrupt(target.id()), this::reportError);
	}

	/**
	 * The thread a command talks to (see {@link FirstRun#defaultThread}). An automatic pick is never saved as the
	 * player's choice: chat commands pick afresh every time, so a thread that needs you is never shadowed by one
	 * the mod chose earlier. The panel is the exception: it sends to the thread it shows, and follows the thread it
	 * just sent to for this session, so the reply appears where the player is looking.
	 */
	private T3State.ThreadRow targetThread() {
		T3State.Snapshot snapshot = state.snapshot();
		boolean onPanel = Minecraft.getInstance().gui.screen() instanceof T3Screen;
		if (onPanel && snapshot.focusedRow() != null) return snapshot.focusedRow();
		T3State.ThreadRow row = FirstRun.defaultThread(snapshot.threads(), snapshot.focusedId());
		if (onPanel && row != null) state.focus(row.id());
		return row;
	}

	private void reportError(Exception e) {
		LOGGER.warn("T3 request failed", e);
		Minecraft.getInstance().execute(() -> chat(Component.literal(e.getMessage() == null ? e.toString() : e.getMessage())
			.withStyle(ChatFormatting.RED)));
	}

	/** Dev showcase only: a "Done" ping for {@code threadId}, exactly as a finished agent would raise it. */
	void pingForShowcase(String threadId) {
		T3State.ThreadRow row = state.snapshot().threads().stream().filter(r -> r.id().equals(threadId)).findFirst()
			.orElse(new T3State.ThreadRow(threadId, "Thread " + threadId.substring(0, Math.min(8, threadId.length())), null, null, null, T3State.Status.DONE, null, null, null));
		announce(new T3State.Event(row, T3State.Status.DONE));
	}

	/** The point of the mod: tell the player when an agent finishes or needs them, wherever they are. */
	private void announce(T3State.Event event) {
		Minecraft minecraft = Minecraft.getInstance();
		String title = event.thread().title();
		Component heading;
		switch (event.status()) {
			case NEEDS_YOU -> {
				heading = Component.literal("T3 · Needs you").withStyle(ChatFormatting.GOLD);
				minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME, 1.0f));
			}
			case ERROR -> {
				heading = Component.literal("T3 · Failed").withStyle(ChatFormatting.RED);
				minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS, 0.8f));
			}
			default -> {
				heading = Component.literal("T3 · Done").withStyle(ChatFormatting.GREEN);
				minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, 1.2f));
			}
		}
		// One signal per event: the toast (and sound). The corner status only shows ongoing state,
		// and ` right after a ping opens the thread that pinged, which replaces a chat [open] link.
		lastPingThread = event.thread().id();
		lastPingAt = System.currentTimeMillis();
		if (config.books && event.status() == T3State.Status.DONE) T3Books.deliver(state, event.thread());
		// Already looking at this thread in the panel: the sound is enough, a toast would cover it.
		boolean viewing = minecraft.gui.screen() instanceof T3Screen || minecraft.gui.screen() instanceof T3AgentCardScreen || minecraft.gui.screen() instanceof T3WebScreen;
		if (viewing && event.thread().id().equals(state.focusedThreadId())) return;
		// Short text keeps the toast at its 190-px minimum, so it never grows over the corner status.
		Component toastTitle = heading.copy().append(Component.literal(" · " + openKeyName() + " to open").withStyle(ChatFormatting.GRAY));
		SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST, toastTitle,
			Component.literal(T3Hud.ellipsize(minecraft.font, title, 160)));
	}

	private int setBooks(boolean on) {
		config.books = on;
		saveConfig();
		chat(Component.literal(on ? "Finished threads you started or opened here hand you a written report (needs command permission)."
			: "Report books off."));
		return 1;
	}

	private static void chat(Component message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			LOGGER.info(message.getString());
			return;
		}
		minecraft.gui.hud.getChat().addClientSystemMessage(
			Component.empty().append(Component.literal("[T3] ").withStyle(ChatFormatting.DARK_AQUA)).append(message));
	}

	private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		// Players get /t3, /t3 open and /t3 office. Everything else is for debugging and admin, and exists only with
		// "debug": true in config/t3craft.json: Brigadier keeps unmet nodes out of tab-complete and usage.
		java.util.function.Predicate<FabricClientCommandSource> debug = source -> config.debug;
		dispatcher.register(literal("t3")
			.executes(ctx -> help())
			.then(literal("open").executes(ctx -> { openPanelNextTick = true; return 1; })
				.then(argument("id", StringArgumentType.word()).requires(debug).executes(ctx -> {
					focus(StringArgumentType.getString(ctx, "id"));
					openPanelNextTick = true;
					return 1;
				})))
			.then(literal("office").executes(ctx -> officeAction())
				.then(literal("go").executes(ctx -> goToOffice()))
				.then(literal("build").executes(ctx -> { buildOffice(); return 1; }))
				.then(literal("off").requires(debug).executes(ctx -> officeOff())))
			.then(literal("unpair").requires(debug).executes(ctx -> {
				connectionRevision++;
				var previous = new java.util.ArrayList<>(config.environments);
				String previousFocus = config.threadId;
				config.environments.clear(); config.threadId = null;
				if (!config.save(configPath)) {
					config.environments = previous; config.threadId = previousFocus;
					chat(Component.literal("Could not save removal. Your machines remain paired.").withStyle(ChatFormatting.RED)); return 0;
				}
				state.connect(List.of(), null);
				chat(Component.literal("Forgot all environments. Revoke \"Minecraft\" in each T3's Settings → Connections too. The local T3 is paired again by itself."));
				startAutoConnect();
				return 1;
			}))
			.then(literal("threads").requires(debug).executes(ctx -> listThreads()))
			.then(literal("decisions").requires(debug).executes(ctx -> { openDecisions(); return 1; }))
			.then(literal("review").requires(debug).executes(ctx -> { var target = targetThread(); openReview(target == null ? null : target.id()); return 1; }))
			.then(literal("agent-build").requires(debug)
				.then(literal("on").executes(ctx -> setAgentCommands(true)))
				.then(literal("off").executes(ctx -> setAgentCommands(false))))
			.then(literal("books").requires(debug)
				.then(literal("on").executes(ctx -> setBooks(true)))
				.then(literal("off").executes(ctx -> setBooks(false))))
			.then(literal("pin").requires(debug).executes(ctx -> { togglePin(state.snapshot().focusedRow()); return 1; }))
			.then(literal("pins").requires(debug).executes(ctx -> { Minecraft.getInstance().gui.setScreen(new T3PinsScreen(this)); return 1; }))
			.then(literal("use").requires(debug).then(argument("n", IntegerArgumentType.integer(1)).executes(ctx -> {
				List<T3State.ThreadRow> rows = state.snapshot().threads();
				int n = IntegerArgumentType.getInteger(ctx, "n");
				if (n > rows.size()) {
					chat(Component.literal("No thread #" + n + ". Try /t3 threads.").withStyle(ChatFormatting.RED));
					return 0;
				}
				focus(rows.get(n - 1).id());
				chat(Component.literal("Now talking to: " + rows.get(n - 1).title()));
				return 1;
			})))
			.then(literal("ask").requires(debug).then(argument("prompt", StringArgumentType.greedyString()).executes(ctx -> prompt(ctx, false))))
			.then(literal("new").requires(debug).then(argument("prompt", StringArgumentType.greedyString()).executes(ctx -> prompt(ctx, true))))
			.then(literal("approve").requires(debug).executes(ctx -> answer("accept")))
			.then(literal("deny").requires(debug).executes(ctx -> answer("decline")))
			.then(literal("stop").requires(debug).executes(ctx -> {
				interrupt();
				chat(Component.literal("Asked the agent to stop."));
				return 1;
			})));
	}

	/** {@code /t3}: the two things a player needs, each clickable. */
	private int help() {
		for (FirstRun.Tip tip : FirstRun.help(openKeyName()))
			chat(Component.literal(tip.text()).withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(tip.command()))));
		return 1;
	}

	int setAgentCommands(boolean allowed) {
		config.agentCommands = allowed;
		saveConfig();
		chat(Component.literal(allowed
			? "Agents on this machine can now run commands in your world (you'll see each one in chat). /t3 agent-build off to stop."
			: "Agents can no longer run commands in your world."));
		return 1;
	}

	private int listThreads() {
		T3State.Snapshot snapshot = state.snapshot();
		if (!snapshot.connected()) {
			chat(Component.literal(snapshot.error() == null ? "Not connected to T3 yet." : snapshot.error()).withStyle(ChatFormatting.RED));
			return 0;
		}
		int shown = Math.min(20, snapshot.threads().size());
		if (snapshot.threads().size() > shown) {
			chat(Component.literal("Most recent " + shown + " of " + snapshot.threads().size() + " threads; press " + openKeyName() + " for all of them.")
				.withStyle(ChatFormatting.GRAY));
		}
		for (int i = 0; i < shown; i++) {
			int n = i + 1;
			T3State.ThreadRow row = snapshot.threads().get(i);
			boolean focused = row.id().equals(state.focusedThreadId());
			chat(Component.literal((focused ? "▶ " : "  ") + n + ". ")
				.append(Component.literal(row.title()).withStyle(style -> style
					.withColor(focused ? ChatFormatting.WHITE : ChatFormatting.GRAY)
					.withClickEvent(new ClickEvent.RunCommand("/t3 use " + n))))
				.append(Component.literal("  " + row.projectTitle() + " · " + T3Hud.label(row.status()))
					.withStyle(ChatFormatting.DARK_GRAY)));
		}
		return 1;
	}

	private int prompt(CommandContext<FabricClientCommandSource> ctx, boolean newThread) {
		send(StringArgumentType.getString(ctx, "prompt"), newThread);
		return 1;
	}

	private int answer(String decision) {
		T3State.Focus focus = state.snapshot().focus();
		if (focus == null || focus.approvals().isEmpty()) {
			chat(Component.literal("Nothing waiting for approval on this thread."));
			return 0;
		}
		respond(focus.approvals().getFirst(), decision);
		return 1;
	}
}
