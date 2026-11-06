package dev.maxwellyoung.t3craft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.Player;

public final class AgentPose {
 /** How far each agent's body is turned from its spot's heading, toward the player; it eases back to 0 at rest. */
 private static final java.util.Map<Mannequin,Float> TURN=new java.util.WeakHashMap<>();
 /** Body and head together: the body turns toward a target yaw (offset 0 = the spot's heading), the head follows within its 50° reach. */
 private static void turn(Mannequin avatar,float target,float goal){
  float yaw=avatar.getYRot(),offset=Mth.rotLerp(.2f,TURN.getOrDefault(avatar,0f),goal);
  if(goal==0&&Math.abs(offset)<.5f){TURN.remove(avatar);offset=0;}else TURN.put(avatar,offset);
  float body=yaw+offset;avatar.setYBodyRot(body);
  float relative=Mth.clamp(Mth.wrapDegrees(target-body),-50,50),current=Mth.clamp(Mth.wrapDegrees(avatar.getYHeadRot()-body),-50,50);
  avatar.setYHeadRot(body+Mth.lerp(.25f,current,relative));
 }
 /** A seated agent that isn't working (the lounge) keeps its name tag down unless you look at it. */
 public static boolean quietTag(boolean seated,String status,boolean lookedAt){return seated&&!"WORKING".equals(status)&&!lookedAt;}
 public static boolean ours(Mannequin m) { return m.getProfile().skinPatch().body().map(t->t.texturePath().getNamespace().equals("t3craft")).orElse(false); }
 static void set(Mannequin avatar,boolean sitting){avatar.setPose(sitting?Pose.CROUCHING:Pose.STANDING);}
 static void rest(Mannequin avatar){turn(avatar,avatar.getYRot(),0);}
 /** Head (and a little of the body) toward a world yaw, e.g. at someone it is talking to; the body stays on its heading. */
 static void toward(Mannequin avatar,float target){turn(avatar,target,0);}
 static void face(Mannequin avatar,Player player) {
  float target=(float)(Math.toDegrees(Math.atan2(player.getZ()-avatar.getZ(),player.getX()-avatar.getX()))-90);
  // The whole agent turns to you, seated too, not just its head; at rest it turns back to its laptop or sofa.
  turn(avatar,target,Mth.wrapDegrees(target-avatar.getYRot()));
 }
}
