package dev.maxwellyoung.t3craft.devmixin;

import com.mojang.blaze3d.platform.Window;
import dev.maxwellyoung.t3craft.HiddenWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applied only with {@code -Dt3craft.hidden=true} (see {@link HiddenWindow}): the SDL window is created hidden and
 * the game keeps believing it is focused and not minimised, so it neither pauses nor throttles while nobody can see it.
 */
@Mixin(Window.class)
abstract class HiddenWindowMixin {
	@ModifyArg(method = "createWindow", index = 3,
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"))
	private long t3craft$createHidden(long flags) {
		return flags | HiddenWindow.SDL_WINDOW_HIDDEN;
	}

	/** A window that was never shown has no focus to lose; ignore whatever the window manager reports. */
	@Inject(method = {"onFocus", "onIconified"}, at = @At("HEAD"), cancellable = true)
	private void t3craft$keepActive(boolean value, CallbackInfo info) {
		info.cancel();
	}
}
