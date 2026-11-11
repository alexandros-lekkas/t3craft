package dev.maxwellyoung.t3craft;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Dev-only: {@code -Dt3craft.hidden=true} runs the client without ever showing its window, so self-tests and
 * agent-driven runs don't pop up on the desktop or take focus. The game still renders into its own framebuffer,
 * so screenshots come out as usual. Gates {@code t3craft.dev.mixins.json}: without the property none of those
 * mixins is applied (or even loaded), so normal play is untouched.
 */
public final class HiddenWindow implements IMixinConfigPlugin {
	public static final boolean ENABLED = Boolean.getBoolean("t3craft.hidden");
	/** {@code SDL_WINDOW_HIDDEN}: the window is created but never mapped. */
	public static final long SDL_WINDOW_HIDDEN = 0x8L;

	/** Says once in the log that this client runs hidden, so a missing window is never a mystery. */
	public static void announce() {
		T3Log.LOGGER.info("Hidden dev client (-Dt3craft.hidden=true): no window, no Dock icon, no focus; screenshots still work. -Pshow=true shows it.");
	}

	@Override public void onLoad(String mixinPackage) {}
	@Override public String getRefMapperConfig() { return null; }
	@Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return ENABLED; }
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
