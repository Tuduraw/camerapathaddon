package com.example.camerapathaddon.mixin;

import com.example.camerapathaddon.path.ActionChannels;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a camera path drives a vehicle (its ExternalMotionController is one of this addon's vehicle playbacks), the
 * path's continuous vehicle actions stand in for the controls the vehicle's parts read: the throttle (propellers,
 * rotors - every spinning part - and the engine sound / HUD), the steering input (steering wheels, steered wheels)
 * and, per seat, the occupant's view that its turrets and weapons aim along. Evaluated identically on the server and
 * every client from the path, so nothing extra is synced. Anything the path doesn't set is left to the vehicle.
 */
@Mixin(value = AbstractVehicleEntity.class, remap = false)
public abstract class VehicleChannelMixin {

	private ActionChannels camerapathaddon$channels(float tickProgress) {
		AbstractVehicleEntity self = (AbstractVehicleEntity) (Object) this;
		return self.tudursvehiclemod$getExternalMotionController() instanceof ActionChannels.Source source
				? source.camerapathaddon$channels(tickProgress) : null;
	}

	@Inject(method = "getThrottle", at = @At("HEAD"), cancellable = true)
	private void camerapathaddon$throttle(CallbackInfoReturnable<Float> cir) {
		ActionChannels channels = this.camerapathaddon$channels(1f);
		if (channels != null && channels.throttle() != null) {
			cir.setReturnValue(channels.throttle());
		}
	}

	@Inject(method = "getSyncedThrottleInput", at = @At("HEAD"), cancellable = true)
	private void camerapathaddon$throttleInput(CallbackInfoReturnable<Float> cir) {
		ActionChannels channels = this.camerapathaddon$channels(1f);
		if (channels != null && channels.throttle() != null) {
			cir.setReturnValue(channels.throttle());
		}
	}

	@Inject(method = "getSyncedSidewaysInput", at = @At("HEAD"), cancellable = true)
	private void camerapathaddon$steering(CallbackInfoReturnable<Float> cir) {
		ActionChannels channels = this.camerapathaddon$channels(1f);
		if (channels != null && channels.steering() != null) {
			cir.setReturnValue(channels.steering());
		}
	}

	@Inject(method = "getSpinningPartSpeedMultiplier", at = @At("HEAD"), cancellable = true)
	private void camerapathaddon$spin(CallbackInfoReturnable<Float> cir) {
		ActionChannels channels = this.camerapathaddon$channels(1f);
		if (channels != null && channels.throttle() != null) {
			cir.setReturnValue(channels.throttle());
		}
	}

	/** The view a seat's turrets follow: the path's aim for that seat (relative to the vehicle) instead of its occupant's own yaw. */
	@Redirect(method = {"getWeaponAimYaw", "getWeaponAimPitch"},
			at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getYaw(F)F", remap = true))
	private float camerapathaddon$aimYaw(Entity occupant, float tickProgress) {
		AbstractVehicleEntity self = (AbstractVehicleEntity) (Object) this;
		if (occupant != self) {
			float[] aim = this.camerapathaddon$aimOf(occupant, tickProgress);
			if (aim != null) {
				return self.getYaw(tickProgress) + aim[0];
			}
		}
		return occupant.getYaw(tickProgress);
	}

	@Redirect(method = {"getWeaponAimYaw", "getWeaponAimPitch"},
			at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getPitch(F)F", remap = true))
	private float camerapathaddon$aimPitch(Entity occupant, float tickProgress) {
		AbstractVehicleEntity self = (AbstractVehicleEntity) (Object) this;
		if (occupant != self) {
			float[] aim = this.camerapathaddon$aimOf(occupant, tickProgress);
			if (aim != null) {
				return aim[1];
			}
		}
		return occupant.getPitch(tickProgress);
	}

	private float[] camerapathaddon$aimOf(Entity occupant, float tickProgress) {
		ActionChannels channels = this.camerapathaddon$channels(tickProgress);
		if (channels == null || channels.aims().isEmpty()) {
			return null;
		}
		int seat = ((AbstractVehicleEntity) (Object) this).tudursvehiclemod$getAssignedSeatIndex(occupant);
		return seat < 0 ? null : channels.aim(seat);
	}
}
