package dev.maxwellyoung.t3craft;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated-server side: a shared office of the server's T3 threads that every player sees.
 * An operator pairs the server (/t3office pair <link>) and builds it (/t3office build).
 */
public final class T3CraftServer implements DedicatedServerModInitializer {
	private final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("t3craft.json");
	private T3Config config;
	private T3State state;
	private ServerOffice office;

	@Override
	public void onInitializeServer() {
		config = T3Config.load(configPath);
		state = new T3State(event -> {});
		connect();
		office = new ServerOffice(state, config, () -> config.save(configPath));
		ServerTickEvents.END_SERVER_TICK.register(office::tick);
		// Office agents are saved with their chunks; ones from an earlier run are removed as they load.
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> office.onLoad(entity));

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
			Commands.literal("t3office")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("build").executes(ctx -> office.build(ctx.getSource(), false))
					.then(Commands.literal("confirm").executes(ctx -> office.build(ctx.getSource(), true))))
				.then(Commands.literal("pin").then(Commands.argument("thread", StringArgumentType.word()).executes(ctx -> {
					boolean ok = office.pin(StringArgumentType.getString(ctx, "thread"), false);
					if (ok) ctx.getSource().sendSuccess(() -> Component.literal("Thread pinned to the shared office."), false);
					else ctx.getSource().sendFailure(Component.literal("Thread unavailable or all sixteen desks on its machine are pinned."));
					return ok ? 1 : 0;
				})))
				.then(Commands.literal("unpin").then(Commands.argument("thread", StringArgumentType.word()).executes(ctx -> {
					boolean ok = office.pin(StringArgumentType.getString(ctx, "thread"), true);
					if (ok) ctx.getSource().sendSuccess(() -> Component.literal("Thread unpinned from the shared office."), false);
					else ctx.getSource().sendFailure(Component.literal("That thread is not pinned."));
					return ok ? 1 : 0;
				})))
				.then(Commands.literal("off").executes(ctx -> {
					office.off(ctx.getSource().getServer());
					ctx.getSource().sendSuccess(() -> Component.literal("Office agents sent home."), true);
					return 1;
				}))
				.then(Commands.literal("status").executes(ctx -> {
					var snapshot = state.snapshot();
					String where = office.origin() == null ? "not built" : "at " + office.origin().toShortString();
					ctx.getSource().sendSuccess(() -> Component.literal("T3 " + (snapshot.connected() ? "connected" : "not connected")
						+ ", " + snapshot.threads().size() + " threads; office " + where), false);
					return 1;
				}))
				// A dedicated server has no local T3 to find, so the operator pairs it once with a link.
				.then(Commands.literal("pair").then(Commands.argument("link", StringArgumentType.greedyString()).executes(ctx -> {
					String link = StringArgumentType.getString(ctx, "link");
					var source = ctx.getSource();
					if (T3McpAuth.isMcp(link)) { source.sendFailure(Component.literal("The shared server needs a legacy pairing link from T3 Settings → Environments. MCP URLs authorize in a browser, so they belong in a client's config file.")); return 0; }
					Thread thread = new Thread(() -> {
						try {
							T3Api.Pairing pairing = T3Api.pair(link, "Minecraft server");
							String label = T3Api.environmentLabel(pairing.baseUrl());
							source.getServer().execute(() -> {
								config.upsert(new T3Config.Environment(label, pairing.baseUrl(), pairing.accessToken()));
								config.save(configPath);
								connect();
								source.sendSuccess(() -> Component.literal("Paired the server with " + label + ". Build the office with /t3office build."), true);
							});
						} catch (Exception e) {
							source.getServer().execute(() -> source.sendFailure(Component.literal("Pairing failed: " + e.getMessage())));
						}
					}, "t3craft-server-pair");
					thread.setDaemon(true);
					thread.start();
					return 1;
				})))));
	}

	private void connect() {
		List<T3State.Connection> connections = new ArrayList<>();
		for (T3Config.Environment env : config.environments) {
			connections.add(new T3State.Connection(T3Backend.forEnvironment(env), env.label));
		}
		state.setPins(List.copyOf(config.sharedOfficePreferences.pins));
		state.connect(connections, null);
	}
}
