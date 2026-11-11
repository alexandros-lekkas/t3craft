package dev.maxwellyoung.t3craft.devmixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.maxwellyoung.t3craft.HiddenWindow;
import org.lwjgl.sdl.SDLHints;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applied only with {@code -Dt3craft.hidden=true} (see {@link HiddenWindow}). On macOS SDL would otherwise turn
 * the process into a regular foreground app when video starts: a Dock icon, a menu bar and stolen focus even
 * though the window is hidden. The hint has to be set before {@code SDL_Init}.
 */
@Mixin(RenderSystem.class)
abstract class HiddenAppMixin {
	@Inject(method = "initBackendSystem", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLInit;SDL_Init(I)Z"))
	private static void t3craft$stayInBackground(CallbackInfoReturnable<?> info) {
		SDLHints.SDL_SetHint("SDL_MAC_BACKGROUND_APP", "1");
		HiddenWindow.announce();
	}
}
