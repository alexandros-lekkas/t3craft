package dev.maxwellyoung.t3craft.mixin;
import dev.maxwellyoung.t3craft.AgentPose;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(AvatarRenderer.class)
abstract class AgentRendererMixin {
 @Inject(method="extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",at=@At("TAIL"))
 private void t3craft$seat(Avatar avatar,AvatarRenderState state,float delta,CallbackInfo ci){
  if(avatar instanceof Mannequin m && AgentPose.ours(m)){
   state.isCrouching=false;state.isPassenger=m.getPose()==Pose.CROUCHING;
   // Name tags behind floors and walls stay hidden there, as a sneaking player's do.
   state.isDiscrete=true;
   // Seated side by side on the lounge sofa their tags ran into one another: show one only when you look at it.
   var description=((MannequinAccess)m).t3craft$getDescription();
   if(AgentPose.quietTag(state.isPassenger,description==null?null:description.getString(),net.minecraft.client.Minecraft.getInstance().crosshairPickEntity==m)){state.nameTag=null;state.scoreText=null;}
  }
 }
}
