package dev.maxwellyoung.t3craft.mixin;

import dev.maxwellyoung.t3craft.DemoRecorder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The dev-only demo recorder ({@code -Dt3craft.demo}) steps the game clock by a fixed amount per rendered frame and
 * grabs the framebuffer at the same point. Without that property the real time passes straight through.
 */
@Mixin(Minecraft.class)
abstract class DemoClockMixin {
	@ModifyArg(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/DeltaTracker$Timer;advanceGameTime(J)I"))
	private long t3craft$demoClock(long realMs) {
		return DemoRecorder.clock(realMs);
	}
}
