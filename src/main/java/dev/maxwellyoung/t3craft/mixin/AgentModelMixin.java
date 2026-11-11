package dev.maxwellyoung.t3craft.mixin;
import dev.maxwellyoung.t3craft.AgentLife;
import dev.maxwellyoung.t3craft.AgentPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.Mannequin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Poses office agents: what {@link AgentLife} says the arms, head and back are doing, on top of vanilla's walk and seat. */
@Mixin(PlayerModel.class)
abstract class AgentModelMixin {
 @Unique private static final float[] t3craft$vanilla=new float[AgentLife.CHANNELS];
 @Inject(method="setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V",at=@At("TAIL"))
 private void t3craft$pose(AvatarRenderState state,CallbackInfo ci){
  var level=Minecraft.getInstance().level;
  if(level==null||!(level.getEntity(state.id) instanceof Mannequin m)||!AgentPose.ours(m))return;
  var model=(PlayerModel)(Object)this;
  float[] v=t3craft$vanilla;
  v[AgentLife.RAX]=model.rightArm.xRot;v[AgentLife.RAY]=model.rightArm.yRot;v[AgentLife.RAZ]=model.rightArm.zRot;
  v[AgentLife.LAX]=model.leftArm.xRot;v[AgentLife.LAY]=model.leftArm.yRot;v[AgentLife.LAZ]=model.leftArm.zRot;
  float[] p=AgentLife.pose(m,state.ageInTicks,state.isPassenger,v);
  if(p==null){
   // An agent this client does not drive (a shared office on a server): it types while seated, working and facing its laptop.
   var description=((MannequinAccess)m).t3craft$getDescription();
   boolean typing=state.isPassenger&&description!=null&&description.getString().equals("WORKING")
    &&Math.abs(Mth.wrapDegrees(m.getYHeadRot()-m.getYRot()))<10&&Math.abs(Mth.wrapDegrees(m.yBodyRot-m.getYRot()))<10;
   if(!typing)return;
   float pulse=(float)Math.sin(state.ageInTicks*.7f+m.getId())*.055f;
   model.rightArm.xRot=-1.15f+pulse;model.leftArm.xRot=-1.15f-pulse;
   model.rightArm.yRot=-.12f;model.leftArm.yRot=.12f;
   return;
  }
  model.rightArm.xRot=p[AgentLife.RAX];model.rightArm.yRot=p[AgentLife.RAY];model.rightArm.zRot=p[AgentLife.RAZ];
  model.leftArm.xRot=p[AgentLife.LAX];model.leftArm.yRot=p[AgentLife.LAY];model.leftArm.zRot=p[AgentLife.LAZ];
  model.head.xRot+=p[AgentLife.HEAD_X];model.head.zRot+=p[AgentLife.HEAD_Z];
  float lean=p[AgentLife.LEAN];
  if(lean!=0){
   // Lean from the hips (the body's pivot is the neck): the back tilts, and the shoulders and head ride along with it.
   float back=(float)Math.sin(lean),down=1-(float)Math.cos(lean);
   model.body.xRot-=lean;model.body.z+=12*back;model.body.y+=12*down;
   model.head.z+=12*back;model.head.y+=12*down;model.head.xRot-=lean*.4f;
   model.rightArm.z+=10*back;model.rightArm.y+=10*down;model.leftArm.z+=10*back;model.leftArm.y+=10*down;
  }
 }
}
