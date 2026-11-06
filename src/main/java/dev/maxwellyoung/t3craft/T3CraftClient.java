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
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
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
	private static final long PING_JUMP_WINDOW_MS = 60_000;
	private T3Village village;
	private final OfficeQueue office = new OfficeQueue();

	public static T3CraftClient get() {
		return instance;
	}

	public T3State state() {
		return state;
	}

	public boolean paired() {
		return config.paired();
	}

	@Override
	public void onInitializeClient() {
		instance = this;
		config = T3Config.load(configPath);
		state = new T3State(event -> Minecraft.getInstance().execute(() -> announce(event)));
		connectAll();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.t3craft.open", InputConstants.KEY_GRAVE, category));

		decisionsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.t3craft.decisions", InputConstants.KEY_J, category));

		new T3Mcp(() -> config.agentCommands).start();
		village = new T3Village(this);
		ClientTickEvents.START_CLIENT_TICK.register(village::tick);
		ClientTickEvents.START_CLIENT_TICK.register(ClientCommands::tick);
		ClientTickEvents.START_CLIENT_TICK.register(office::tick);
		ClientTickEvents.END_CLIENT_TICK.register(village::animate);
		net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.ALLOW_GAME.register(
			(message, overlay) -> overlay || !(T3Books.isOwnFeedback(message.getString()) || office.hideFeedback(message)));
		SelfTest selfTest = SelfTest.fromSystemProperty(this);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			state.setPins(List.copyOf(officePreferences().pins));
			if (selfTest != null) selfTest.tick(client);
			while (decisionsKey.consumeClick()) if (client.gui.screen() == null) openDecisions();
			while (openKey.consumeClick()) {
				openPanelNextTick = true;
				// Answering a ping: open the thread that pinged, not whatever was focused before.
				if (lastPingThread != null && System.currentTimeMillis() - lastPingAt < PING_JUMP_WINDOW_MS) focus(lastPingThread);
				lastPingThread = null;
			}
			if (openPanelNextTick && client.gui.screen() == null) {
				openPanelNextTick = false;
				openPanel();
			}
		});

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "status"), new T3Hud(this));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> registerCommands(dispatcher));
	}

	public net.minecraft.core.BlockPos villageAnchor() {
		String world = worldKey();
		if (world == null || !config.paired()) return null;
		int[] pos = config.villages.get(world);
		if (pos == null && config.village != null) {
			// Before 0.1.5 one village followed you into every world; it stays with the first one visited.
			pos = config.village;
			config.villages.put(world, pos);
			config.village = null;
			config.save(configPath);
		}
		return pos == null || pos.length != 3 ? null : new net.minecraft.core.BlockPos(pos[0], pos[1], pos[2]);
	}

	/** The world and dimension the player is in, so each keeps its own village; null outside a world. */
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

	T3Village village() {
		return village;
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
			if (officeMode()) saveOfficePreferences();
		}
		return prefs.floorOwners;
	}

	private List<String> pairedOwners() { return config.environments.stream().map(e -> T3Config.ownerKey(e.baseUrl)).toList(); }

	private record PendingOffice(Object level, Object connection, net.minecraft.core.BlockPos origin, List<String> owners, List<String> labels) {}
	private PendingOffice pendingOffice;

	OfficeRoster.Preferences officePreferences() {
		String world = worldKey();
		return world == null ? new OfficeRoster.Preferences() : config.officePreferences.computeIfAbsent(world, k -> new OfficeRoster.Preferences());
	}

	boolean canPin() { return worldKey() != null; }
	void saveOfficePreferences() { config.save(configPath); }
	boolean pinned(T3State.ThreadRow row) { return row != null && officePreferences().pinned(row); }
	void togglePin(T3State.ThreadRow row) {
		if (row == null || !canPin()) return;
		OfficeRoster.Preferences prefs = officePreferences();
		if (prefs.pinned(row)) prefs.pins.removeIf(p -> p.key().equals(OfficeRoster.key(row)));
		else if (!prefs.pin(row)) { chat(Component.literal("All eight desks on this machine are pinned. Unpin one in Pins first.")); return; }
		saveOfficePreferences();
	}
	void unpin(OfficeRoster.Pin pin) { officePreferences().unpin(pin); saveOfficePreferences(); }
	String machineLabel(String owner) { return config.labelFor(owner); }

	/** This world's village is the Silk office. */
	boolean officeMode() {
		String world = worldKey();
		return world != null && config.officeWorlds.contains(world);
	}

	/** Builds the Silk office just south of the player (door facing them) and moves this world's village into it. */
	void buildOffice() { buildOffice(false); }

	private void buildOffice(boolean confirm) {
		var minecraft = Minecraft.getInstance();
		var player = minecraft.player;
		String world = worldKey();
		if (player == null || world == null) return;
		PendingOffice request = pendingOffice;
		if (confirm) {
			if (request == null || request.level() != minecraft.level || request.connection() != minecraft.getConnection()) {
				pendingOffice = null;
				chat(Component.literal("No office site to confirm in this world. Run /t3 office first.")); return;
			}
		} else {
			var feet = player.blockPosition();
			var owners = pairedOwners();
			request = new PendingOffice(minecraft.level, minecraft.getConnection(), feet.offset(-10, 0, 5), owners,
				owners.stream().map(config::labelFor).toList());
		}
		var origin = request.origin();
		var blueprint = T3Office.blueprint(request.labels());
		var bounds = OfficeSite.bounds(origin, blueprint);
		chat(Component.literal("Office bounds: " + bounds.describe()));
		if (!OfficeSite.loaded(minecraft.level, origin, blueprint)) {
			pendingOffice = null;
			chat(Component.literal("The entire site must be loaded and inside the world's height limits.")); return;
		}
		if (!confirm && OfficeSite.occupied(minecraft.level, bounds, officeMode() ? villageAnchor() : null, T3Office.blueprint(floors()))) {
			pendingOffice = request;
			chat(Component.literal("This site contains existing blocks (including any containers). Run /t3 office confirm to replace them at these exact bounds.")); return;
		}
		pendingOffice = null;
		var oldVillage = config.villages.get(world);
		boolean oldOffice = config.officeWorlds.contains(world);
		var oldOwners = officePreferences().floorOwners;
		config.villages.put(world, new int[] {origin.getX(), origin.getY(), origin.getZ()});
		config.officeWorlds.add(world);
		officePreferences().floorOwners = List.copyOf(request.owners());
		if (!config.save(configPath)) {
			if (oldVillage == null) config.villages.remove(world); else config.villages.put(world, oldVillage);
			if (!oldOffice) config.officeWorlds.remove(world);
			officePreferences().floorOwners = oldOwners;
			chat(Component.literal("Could not save office settings; no blocks were changed.")); return;
		}
		office.build(origin, request.labels());
		village.clear();
		chat(Component.literal("Office build accepted. Needs command permission (op). Rebuild to add floors for newly paired machines."));
	}

	private int officeOff() {
		String world = worldKey();
		var oldVillage = world == null ? null : config.villages.get(world);
		boolean oldOffice = world != null && config.officeWorlds.contains(world);
		if (world != null) { config.officeWorlds.remove(world); config.villages.remove(world); }
		if (!config.save(configPath)) {
			if (oldVillage != null) config.villages.put(world, oldVillage);
			if (oldOffice) config.officeWorlds.add(world);
			chat(Component.literal("Could not save office settings; office kept.")); return 0;
		}
		pendingOffice = null;
		office.clear();
		village.clear();
		chat(Component.literal("Office residents hidden; building blocks kept."));
		return 1;
	}

	/** Puts the agent village a few blocks in front of the player. */
	public void placeVillage() {
		placeVillage(4);
	}

	void placeVillage(int distance) {
		var player = Minecraft.getInstance().player;
		if (player == null) return;
		String world = worldKey();
		if (world == null) return;
		var ahead = player.blockPosition().relative(player.getDirection(), distance);
		var previous = config.villages.put(world, new int[] {ahead.getX(), ahead.getY(), ahead.getZ()});
		boolean wasOffice = config.officeWorlds.remove(world);
		if (!config.save(configPath)) {
			if (previous == null) config.villages.remove(world); else config.villages.put(world, previous);
			if (wasOffice) config.officeWorlds.add(world);
			chat(Component.literal("Could not save village settings; village kept.")); return;
		}
		office.clear();
		village.clear();
	}

	private List<T3State.Connection> connections() {
		List<T3State.Connection> connections = new java.util.ArrayList<>();
		for (T3Config.Environment env : config.environments) {
			connections.add(new T3State.Connection(T3Backend.forEnvironment(env), env.label));
		}
		return connections;
	}

	private void connectAll() { state.connect(connections(), config.threadId); }
	List<T3Config.Environment> environments() { return List.copyOf(config.environments); }
	void openConnections() { Minecraft.getInstance().gui.setScreen(new T3ConnectionsScreen(this)); }
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

	/** Both entry points share the exchange; callback messages never contain the link or token. */
	record PairOutcome(String error, String note) {}
 private volatile T3McpAuth.Pending mcpPairing;
 void cancelPairing() { var pending = mcpPairing; if (pending != null) pending.cancel(); }
 void reopenPairingBrowser() { var pending = mcpPairing; if (pending != null && pending.consent != null) com.mojang.blaze3d.Blaze3D.openUri(pending.consent); }
 java.net.URI pairingUri() { var pending = mcpPairing; return pending == null ? null : pending.consent; }
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
                    if (!(Minecraft.getInstance().gui.screen() instanceof T3ConnectionsScreen)) chat(Component.literal("Paired " + environment.label + "." + (note == null ? "" : " " + note)).withStyle(ChatFormatting.GREEN));
                    result.accept(new PairOutcome(null, note));
				});
				return;
			} catch (T3Api.UnsupportedVersionException e) { error = e.getMessage(); }
			catch (java.io.IOException e) {
				error = (e.getClass() == java.io.IOException.class || e instanceof T3McpException) ? e.getMessage() : "Could not reach this machine. Check T3 Code, the address and network, then try again.";
			} catch (Exception e) { error = "Pairing could not finish. Check T3 Code and try a fresh link."; }
			String message = error;
			Minecraft.getInstance().execute(() -> { pairingInProgress = false; mcpPairing = null; if (!(Minecraft.getInstance().gui.screen() instanceof T3ConnectionsScreen)) chat(Component.literal(message).withStyle(ChatFormatting.RED)); result.accept(new PairOutcome(message, null)); });
		}, "t3craft-pair");
		thread.setDaemon(true); thread.start();
	}

	public void openPanel() {
		Minecraft minecraft = Minecraft.getInstance();
		if (!config.paired()) {
			openConnections();
			return;
		}
		minecraft.gui.setScreen(new T3Screen(this));
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
		state.focus(threadId);
		config.threadId = threadId;
		config.save(configPath);
	}

	/** Sends to the focused thread, or starts a new thread beside it. Runs off-thread; reports back in chat. */
	public void send(String text, boolean newThread) {
		send(text, newThread, null);
	}

	/** {@code model} overrides the thread's model for this turn (and the thread from then on); null keeps it. */
	public void send(String text, boolean newThread, com.google.gson.JsonObject model) {
		T3State.Snapshot snapshot = state.snapshot();
		// A new thread only borrows project and settings, so any recent thread can stand in.
		T3State.ThreadRow row = snapshot.focusedRow();
		if (row == null && newThread && !snapshot.threads().isEmpty()) row = snapshot.threads().getFirst();
		if (row == null) {
			chat(Component.literal("Pick a thread first (` then click one, or /t3 threads).").withStyle(ChatFormatting.RED));
			return;
		}
		T3State.ThreadRow target = row;
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
		}, this::reportError);
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
		state.respond(threadId, input.requestId(), null, answers.deepCopy(), this::reportError);
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
		String threadId = state.focusedThreadId();
		T3Backend api = state.api();
		if (threadId != null) state.run(() -> api.interrupt(threadId), this::reportError);
	}

	private void reportError(Exception e) {
		LOGGER.warn("T3 request failed", e);
		Minecraft.getInstance().execute(() -> chat(Component.literal(e.getMessage() == null ? e.toString() : e.getMessage())
			.withStyle(ChatFormatting.RED)));
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
		if (minecraft.gui.screen() instanceof T3Screen && event.thread().id().equals(state.focusedThreadId())) return;
		// Short text keeps the toast at its 190-px minimum, so it never grows over the corner status.
		Component toastTitle = heading.copy().append(Component.literal(" · ` to open").withStyle(ChatFormatting.GRAY));
		SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST, toastTitle,
			Component.literal(T3Hud.ellipsize(minecraft.font, title, 160)));
	}

	private int setBooks(boolean on) {
		config.books = on;
		config.save(configPath);
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
		dispatcher.register(literal("t3")
			.executes(ctx -> {
				openPanelNextTick = true;
				return 1;
			})
			.then(literal("connections").executes(ctx -> { openConnections(); return 1; }))
			.then(literal("pair").executes(ctx -> { openConnections(); return 1; }).then(argument("link", StringArgumentType.greedyString()).executes(this::pair)))
			.then(literal("unpair").executes(ctx -> {
				connectionRevision++;
				var previous = new java.util.ArrayList<>(config.environments);
				String previousFocus = config.threadId;
				config.environments.clear(); config.threadId = null;
				if (!config.save(configPath)) {
					config.environments = previous; config.threadId = previousFocus;
					chat(Component.literal("Could not save removal. Your machines remain paired.").withStyle(ChatFormatting.RED)); return 0;
				}
				state.connect(List.of(), null);
				chat(Component.literal("Forgot all environments. Revoke \"Minecraft\" in each T3's Settings → Connections too."));
				return 1;
			}))
			.then(literal("threads").executes(ctx -> listThreads()))
			.then(literal("decisions").executes(ctx -> { openDecisions(); return 1; }))
			.then(literal("review").executes(ctx -> { openReview(state.focusedThreadId()); return 1; }))
			.then(literal("agent-build")
				.then(literal("on").executes(ctx -> setAgentCommands(true)))
				.then(literal("off").executes(ctx -> setAgentCommands(false))))
			.then(literal("books")
				.then(literal("on").executes(ctx -> setBooks(true)))
				.then(literal("off").executes(ctx -> setBooks(false))))
			.then(literal("pin").executes(ctx -> { togglePin(state.snapshot().focusedRow()); return 1; }))
			.then(literal("pins").executes(ctx -> { Minecraft.getInstance().gui.setScreen(new T3PinsScreen(this)); return 1; }))
			.then(literal("office").executes(ctx -> { buildOffice(); return 1; })
				.then(literal("confirm").executes(ctx -> { buildOffice(true); return 1; }))
				.then(literal("off").executes(ctx -> officeOff())))
			.then(literal("village")
				.executes(ctx -> {
					placeVillage();
					chat(Component.literal("Your agents moved in here. Right-click one to open its thread; /t3 village off to hide them."));
					return 1;
				})
				.then(literal("off").executes(ctx -> {
					String world = worldKey();
					if (world != null) {
						config.villages.remove(world);
						config.officeWorlds.remove(world);
					}
					config.save(configPath);
					village.clear();
					chat(Component.literal("Village hidden."));
					return 1;
				})))
			.then(literal("use").then(argument("n", IntegerArgumentType.integer(1)).executes(ctx -> {
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
			.then(literal("open").then(argument("id", StringArgumentType.word()).executes(ctx -> {
				focus(StringArgumentType.getString(ctx, "id"));
				openPanelNextTick = true;
				return 1;
			})))
			.then(literal("ask").then(argument("prompt", StringArgumentType.greedyString()).executes(ctx -> prompt(ctx, false))))
			.then(literal("new").then(argument("prompt", StringArgumentType.greedyString()).executes(ctx -> prompt(ctx, true))))
			.then(literal("approve").executes(ctx -> answer("accept")))
			.then(literal("deny").executes(ctx -> answer("decline")))
			.then(literal("stop").executes(ctx -> {
				interrupt();
				chat(Component.literal("Asked the agent to stop."));
				return 1;
			})));
	}

	int setAgentCommands(boolean allowed) {
		config.agentCommands = allowed;
		config.save(configPath);
		chat(Component.literal(allowed
			? "Agents on this machine can now run commands in your world (you'll see each one in chat). /t3 agent-build off to stop."
			: "Agents can no longer run commands in your world."));
		return 1;
	}

	private int pair(CommandContext<FabricClientCommandSource> ctx) {
		chat(Component.literal("Pairing…"));
		pairEnvironment(StringArgumentType.getString(ctx, "link"), null, null, outcome -> chat(Component.literal(outcome.error() == null
			? "Paired. Press ` to open the panel; /t3 connections to manage machines." + (outcome.note() == null ? "" : " " + outcome.note()) : outcome.error())
			.withStyle(outcome.error() == null ? ChatFormatting.GREEN : ChatFormatting.RED)));
		return 1;
	}

	private int listThreads() {
		T3State.Snapshot snapshot = state.snapshot();
		if (!snapshot.connected()) {
			chat(Component.literal(snapshot.error() == null ? "Connecting…" : snapshot.error()).withStyle(ChatFormatting.RED));
			return 0;
		}
		int shown = Math.min(20, snapshot.threads().size());
		if (snapshot.threads().size() > shown) {
			chat(Component.literal("Most recent " + shown + " of " + snapshot.threads().size() + " threads; press ` for all of them.")
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
