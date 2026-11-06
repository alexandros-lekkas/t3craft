package dev.maxwellyoung.t3craft.mixin;
import dev.maxwellyoung.t3craft.AgentPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.decoration.Mannequin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(PlayerModel.class)
abstract class AgentModelMixin {
 @Inject(method="setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V",at=@At("TAIL"))
 private void t3craft$type(AvatarRenderState state,CallbackInfo ci){
  var level=Minecraft.getInstance().level;
  if(level==null||!(level.getEntity(state.id) instanceof Mannequin m)||!AgentPose.ours(m)||!state.isPassenger)return;
  if(((MannequinAccess)m).t3craft$getDescription()==null||!((MannequinAccess)m).t3craft$getDescription().getString().equals("WORKING"))return;
  var model=(PlayerModel)(Object)this;
  float pulse=(float)Math.sin(state.ageInTicks*.7f)*.055f;
  model.rightArm.xRot=-1.15f+pulse;model.leftArm.xRot=-1.15f-pulse;
  model.rightArm.yRot=-.12f;model.leftArm.yRot=.12f;
 }
}
