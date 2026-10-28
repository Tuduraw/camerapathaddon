package com.example.camerapathaddon.mixin;

import com.example.camerapathaddon.server.PlaybackManager;
import net.minecraft.server.network.ChunkDataSender;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Vanilla sends a player's pending chunks nearest-first around the player. While the player moves along a path, they are sorted around a point ahead on the path instead, so the chunks the camera is heading into arrive first. Only the order changes: which chunks are sent, and how fast, stays vanilla's. */
@Mixin(ChunkDataSender.class)
public abstract class ChunkDataSenderMixin {

	@Redirect(method = "sendChunkBatches",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerEntity;getChunkPos()Lnet/minecraft/util/math/ChunkPos;"))
	private ChunkPos camerapathaddon$sortAroundPathAhead(ServerPlayerEntity player) {
		ChunkPos ahead = PlaybackManager.aheadChunkFor(player);
		return ahead != null ? ahead : player.getChunkPos();
	}
}
