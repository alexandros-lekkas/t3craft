package dev.maxwellyoung.t3craft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * Runs on every server, including the integrated one behind singleplayer: this is where a T3 World
 * gets its office on first start. Dedicated servers keep using {@code /t3office}.
 */
public final class T3CraftCommon implements ModInitializer {
	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(T3WorldSetup::onServerStarted);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> T3WorldSetup.onJoin(handler.player, server));
	}
}
