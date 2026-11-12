package dev.maxwellyoung.t3craft.mixin;

import dev.maxwellyoung.t3craft.DemoRecorder;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The dev-only demo recorder ({@code -Dt3craft.demo}) places the camera itself in its cinematic and third-person
 * shots: once the game has put the camera on its entity, the recorder's rig moves it, every frame. The player stays
 * the camera entity in the third-person shots, so the game still draws and moves it. Without a recording nothing changes.
 */
@Mixin(Camera.class)
abstract class DemoCameraMixin {
	@Shadow protected abstract void setPosition(double x, double y, double z);
	@Shadow protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "alignWithEntity(F)V", at = @At("TAIL"))
	private void t3craft$demoCamera(float partialTick, CallbackInfo info) {
		double[] pose = DemoRecorder.camera(partialTick);
		if (pose == null) return;
		setRotation((float) pose[3], (float) pose[4]);
		setPosition(pose[0], pose[1], pose[2]);
	}
}
