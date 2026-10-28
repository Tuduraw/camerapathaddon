package com.example.camerapathaddon.client.mixin;

import com.example.camerapathaddon.entity.CameraRigEntity;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A player riding a camera rig isn't drawn - neither for others (who would see someone sitting in mid-air) nor in front of their own camera in third person. */
@Mixin(EntityRenderManager.class)
public abstract class EntityRenderManagerMixin {

	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private <E extends Entity> void camerapathaddon$hideRigRider(E entity, Frustum frustum, double x, double y, double z,
																  CallbackInfoReturnable<Boolean> cir) {
		if (entity.getVehicle() instanceof CameraRigEntity) {
			cir.setReturnValue(false);
		}
	}
}
