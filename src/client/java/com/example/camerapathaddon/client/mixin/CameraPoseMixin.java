package com.example.camerapathaddon.client.mixin;

import com.example.camerapathaddon.client.ClientPlaybackState;
import com.example.camerapathaddon.path.PathEvaluator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the local player's view is on a camera path, the camera is placed straight from the path every frame
 * (sub-tick accurate) with its full orientation - roll included, which a player's own yaw/pitch can't express.
 * Applied after everything else (priority above tudursvehiclemod's own camera mixin, which leaves this case alone
 * anyway because the rig isn't one of its vehicles).
 */
@Mixin(value = Camera.class, priority = 1100)
public abstract class CameraPoseMixin {

	@Invoker("setRotation")
	protected abstract void camerapathaddon$setRotation(float yaw, float pitch);

	@Invoker("setPos")
	protected abstract void camerapathaddon$setPos(Vec3d pos);

	@Inject(method = "update(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;ZZF)V", at = @At("TAIL"))
	private void camerapathaddon$followPath(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView,
											float tickProgress, CallbackInfo ci) {
		ClientPlaybackState.CameraPlayback playback = ClientPlaybackState.camera;
		if (playback == null || focusedEntity != MinecraftClient.getInstance().player) {
			return;
		}
		PathEvaluator.Pose pose = playback.pose(tickProgress);
		// Vanilla's own yaw/pitch bookkeeping (the planes used for sound and particles) first...
		this.camerapathaddon$setRotation(pose.yaw(), pose.pitch());
		// ...then the exact orientation: the body attitude turned to face the way the camera looks (see PathEvaluator.Pose).
		((Camera) (Object) this).getRotation().set(new Quaternionf(pose.rotation()).rotateY((float) Math.PI));
		this.camerapathaddon$setPos(new Vec3d(pose.x(), pose.y(), pose.z()));
	}
}
