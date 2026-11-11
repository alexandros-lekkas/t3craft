package dev.maxwellyoung.t3craft.mixin;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.maxwellyoung.t3craft.AgentPose;
import dev.maxwellyoung.t3craft.AgentTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(AvatarRenderer.class)
abstract class AgentRendererMixin {
 /** The tag to draw for each of our agents this frame (render states are reused per entity); absent for everyone else. */
 @Unique private static final java.util.Map<AvatarRenderState,AgentTag.Look> t3craft$tags=new java.util.WeakHashMap<>();
 @Inject(method="extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",at=@At("TAIL"))
 private void t3craft$seat(Avatar avatar,AvatarRenderState state,float delta,CallbackInfo ci){
  if(avatar instanceof Mannequin m && AgentPose.ours(m)){
   state.isCrouching=false;state.isPassenger=m.getPose()==Pose.CROUCHING;
   // Name tags behind floors and walls stay hidden there, as a sneaking player's do.
   state.isDiscrete=true;
   if(state.nameTag==null){t3craft$tags.remove(state);return;}
   var description=((MannequinAccess)m).t3craft$getDescription();
   String status=description==null?null:description.getString();
   var minecraft=Minecraft.getInstance();
   boolean lookedAt=minecraft.crosshairPickEntity==m;
   Font font=minecraft.font;
   // Seated side by side on the lounge sofa, titles ran into one another: a dot each, and the title when you look at one.
   t3craft$tags.put(state,AgentTag.look(state.nameTag.getString(),status,Math.sqrt(state.distanceToCameraSq),lookedAt,
    AgentPose.quietTag(state.isPassenger,status,lookedAt),state.isPassenger,state.ageInTicks,text->font.width(text),(text,width)->font.plainSubstrByWidth(text,width)));
  } else t3craft$tags.remove(state);
 }
 /** Our agents' tag replaces vanilla's two dim lines: one line, full brightness, depth-tested so walls and floors hide it. */
 @Inject(method="submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",at=@At("HEAD"),cancellable=true)
 private void t3craft$tag(AvatarRenderState state,PoseStack pose,SubmitNodeCollector collector,CameraRenderState camera,CallbackInfo ci){
  if(!t3craft$tags.containsKey(state))return;
  ci.cancel();
  AgentTag.Look look=t3craft$tags.get(state);
  if(look==null||state.nameTagAttachment==null)return;
  Font font=Minecraft.getInstance().font;
  pose.pushPose();
  pose.translate(state.nameTagAttachment.x,state.nameTagAttachment.y+.5+look.lift(),state.nameTagAttachment.z);
  pose.rotate(camera.orientation);
  float scale=.025f*look.scale();
  pose.scale(scale,-scale,scale);
  collector.submitText(pose,-font.width(look.text())/2f,0,look.text().getVisualOrderText(),false,Font.DisplayMode.NORMAL,LightCoordsUtil.FULL_BRIGHT,0xFFFFFFFF,look.background(),0);
  pose.popPose();
 }
}
