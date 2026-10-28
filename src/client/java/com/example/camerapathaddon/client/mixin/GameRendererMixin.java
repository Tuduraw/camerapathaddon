package com.example.camerapathaddon.client.mixin;

import com.example.camerapathaddon.client.CameraPathClientConfig;
import com.example.camerapathaddon.client.ClientPlaybackState;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** FOV from the path (keys with FOV 0 use the player's own setting; no sprint/speed FOV effects on a path), and no view bobbing on a path. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void camerapathaddon$pathFov(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
		ClientPlaybackState.CameraPlayback playback = ClientPlaybackState.camera;
		if (playback != null) {
			cir.setReturnValue(playback.pose(tickProgress).fov());
		}
	}

	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
	private void camerapathaddon$noBobbing(MatrixStack matrices, float tickProgress, CallbackInfo ci) {
		if (ClientPlaybackState.camera != null && CameraPathClientConfig.get().disableBobbingDuringPlayback) {
			ci.cancel();
		}
	}
}
