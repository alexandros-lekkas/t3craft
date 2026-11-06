package dev.maxwellyoung.t3craft.mixin;

import dev.maxwellyoung.t3craft.AgentWalk;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Office agents are moved after the level tick, so vanilla's per-tick walk update (run inside it) always sees them
 * standing still and winds their legs down. {@code AgentWalk} animates them from the distance they really moved.
 */
@Mixin(LivingEntity.class)
abstract class AgentWalkMixin {
	@Inject(method = "calculateEntityAnimation(Z)V", at = @At("HEAD"), cancellable = true)
	private void t3craft$ownWalk(boolean flying, CallbackInfo ci) {
		if (AgentWalk.driven((LivingEntity) (Object) this)) ci.cancel();
	}
}
