package dev.maxwellyoung.t3craft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.Player;

public final class AgentPose {
 public static boolean ours(Mannequin m) { return m.getProfile().skinPatch().body().map(t->t.texturePath().getNamespace().equals("t3craft")).orElse(false); }
 static void set(Mannequin avatar,boolean sitting){avatar.setPose(sitting?Pose.CROUCHING:Pose.STANDING);}
 static void rest(Mannequin avatar){avatar.setYHeadRot(Mth.rotLerp(.25f,avatar.getYHeadRot(),avatar.getYRot()));}
 static void face(Mannequin avatar,Player player) {
  float target=(float)(Math.toDegrees(Math.atan2(player.getZ()-avatar.getZ(),player.getX()-avatar.getX()))-90);
  float yaw=avatar.getYRot(),relative=Mth.clamp(Mth.wrapDegrees(target-yaw),-50,50);
  float current=Mth.clamp(Mth.wrapDegrees(avatar.getYHeadRot()-yaw),-50,50);
  avatar.setYHeadRot(yaw+Mth.lerp(.25f,current,relative));
 }
}
