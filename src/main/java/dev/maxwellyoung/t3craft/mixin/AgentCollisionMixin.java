package dev.maxwellyoung.t3craft.mixin;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(LivingEntity.class)
abstract class AgentCollisionMixin {
 @Inject(method="isPushable",at=@At("HEAD"),cancellable=true)
 private void t3craft$stationary(CallbackInfoReturnable<Boolean> ci) {
  if((Object)this instanceof Mannequin m && m.entityTags().contains("t3craft_agent"))ci.setReturnValue(false);
 }
}
