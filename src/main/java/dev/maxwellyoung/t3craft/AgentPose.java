package dev.maxwellyoung.t3craft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.Player;

public final class AgentPose {
 /** How far each agent's body is turned from its spot's heading, toward the player; it eases back to 0 at rest. */
 private static final java.util.Map<Mannequin,Float> TURN=new java.util.WeakHashMap<>();
 /** A seated agent's chair stays where it is: the shoulders turn this far and the head does the rest, an over-the-shoulder look. */
 static final float SEATED_BODY=40,SEATED_HEAD=65,STANDING_HEAD=50;
 /** Body and head together: the body turns toward a target yaw (offset 0 = the spot's heading), the head follows within its reach. */
 private static void turn(Mannequin avatar,float target,float goal,float reach,float pitch){
  float yaw=avatar.getYRot(),offset=Mth.rotLerp(.2f,TURN.getOrDefault(avatar,0f),goal);
  if(goal==0&&Math.abs(offset)<.5f){TURN.remove(avatar);offset=0;}else TURN.put(avatar,offset);
  float body=yaw+offset;avatar.setYBodyRot(body);
  float relative=Mth.clamp(Mth.wrapDegrees(target-body),-reach,reach),current=Mth.clamp(Mth.wrapDegrees(avatar.getYHeadRot()-body),-SEATED_HEAD,SEATED_HEAD);
  avatar.setYHeadRot(body+Mth.lerp(.25f,current,relative));
  // The head tips up or down toward what it looks at, and levels out again, eased like the turn.
  float tilt=Mth.lerp(.25f,avatar.getXRot(),pitch);avatar.setXRot(Math.abs(tilt-pitch)<.2f?pitch:tilt);
 }
 /** A seated agent that isn't working (the lounge) keeps its name tag down unless you look at it. */
 public static boolean quietTag(boolean seated,String status,boolean lookedAt){return seated&&!"WORKING".equals(status)&&!lookedAt;}
 public static boolean ours(Mannequin m) { return m.getProfile().skinPatch().body().map(t->t.texturePath().getNamespace().equals("t3craft")).orElse(false); }
 static void set(Mannequin avatar,boolean sitting){avatar.setPose(sitting?Pose.CROUCHING:Pose.STANDING);}
 static void rest(Mannequin avatar){turn(avatar,avatar.getYRot(),0,STANDING_HEAD,0);}
 /** Degrees the body is still turned away from its spot's heading (0 once it faces its laptop or sofa again). */
 static float turned(Mannequin avatar){return Math.abs(TURN.getOrDefault(avatar,0f));}
 /** Head (and a little of the body) toward a world yaw, e.g. at someone it is talking to; the body stays on its heading. */
 static void toward(Mannequin avatar,float target){turn(avatar,target,0,STANDING_HEAD,0);}
 static void face(Mannequin avatar,Player player){face(avatar,player,false);}
 /** Looks at the player: standing, the whole agent turns to you; seated, it looks round as far as a person in a chair can. */
 static void face(Mannequin avatar,Player player,boolean seated) {
  double dx=player.getX()-avatar.getX(),dz=player.getZ()-avatar.getZ();
  float target=(float)(Math.toDegrees(Math.atan2(dz,dx))-90),want=Mth.wrapDegrees(target-avatar.getYRot());
  float pitch=Mth.clamp((float)-Math.toDegrees(Math.atan2(player.getEyeY()-avatar.getEyeY(),Math.sqrt(dx*dx+dz*dz))),-30,30);
  if(!seated){turn(avatar,target,want,STANDING_HEAD,pitch);return;}
  // Straight behind, keep looking over the shoulder it already turned to, rather than flipping from side to side.
  float now=TURN.getOrDefault(avatar,0f);
  if(Math.abs(want)>150&&now!=0)want=Math.copySign(180,now);
  turn(avatar,avatar.getYRot()+Mth.clamp(want,-SEATED_BODY-SEATED_HEAD,SEATED_BODY+SEATED_HEAD),Mth.clamp(want,-SEATED_BODY,SEATED_BODY),SEATED_HEAD,pitch);
 }
}
