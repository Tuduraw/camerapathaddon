package com.example.camerapathaddon.mixin;

import com.example.camerapathaddon.server.PlaybackManager;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** PlayerManager.remove() saves the player before it dismounts them - a player logging out on a camera path would be saved riding the rig, far from where they started. Ending the playback first puts them back. (A server stop is covered by the SERVER_STOPPING listener, which runs before players are saved.) */
@Mixin(PlayerManager.class)
public abstract class PlayerManagerLogoutMixin {

	@Inject(method = "remove", at = @At("HEAD"))
	private void camerapathaddon$endCameraPathBeforeSave(ServerPlayerEntity player, CallbackInfo ci) {
		PlaybackManager.onPlayerLogout(player);
	}
}
