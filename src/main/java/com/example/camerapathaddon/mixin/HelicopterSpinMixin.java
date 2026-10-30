package com.example.camerapathaddon.mixin;

import com.example.camerapathaddon.path.ActionChannels;
import com.example.tudursvehiclemod.entity.HelicopterEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** HelicopterEntity's own spinning-part speed (its propellers / rotors) follows a camera path's throttle while the path drives it - see VehicleChannelMixin. */
@Mixin(value = HelicopterEntity.class, remap = false)
public abstract class HelicopterSpinMixin {

	@Inject(method = "getSpinningPartSpeedMultiplier", at = @At("HEAD"), cancellable = true)
	private void camerapathaddon$spin(CallbackInfoReturnable<Float> cir) {
		if (((HelicopterEntity) (Object) this).tudursvehiclemod$getExternalMotionController() instanceof ActionChannels.Source source) {
			ActionChannels channels = source.camerapathaddon$channels(1f);
			if (channels.throttle() != null) {
				cir.setReturnValue(channels.throttle());
			}
		}
	}
}
