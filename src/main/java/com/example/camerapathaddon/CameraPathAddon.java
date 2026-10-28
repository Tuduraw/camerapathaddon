package com.example.camerapathaddon;

import com.example.camerapathaddon.command.CameraPathCommand;
import com.example.camerapathaddon.network.CameraPathNetworking;
import com.example.camerapathaddon.registry.ModChunkTickets;
import com.example.camerapathaddon.registry.ModBlocks;
import com.example.camerapathaddon.registry.ModEntityTypes;
import com.example.camerapathaddon.server.CameraPathServerConfig;
import com.example.camerapathaddon.server.PlaybackManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Camera path addon for tudursvehiclemod: keyframed camera moves for the player's own view, and the same paths applied to unmanned vehicles. */
public class CameraPathAddon implements ModInitializer {

	public static final String MOD_ID = "camerapathaddon";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		ModBlocks.register();
		ModEntityTypes.register();
		ModChunkTickets.register();
		CameraPathNetworking.register();
		CameraPathServerConfig.load();

		ServerTickEvents.END_SERVER_TICK.register(PlaybackManager::onEndServerTick);
		ServerLifecycleEvents.SERVER_STOPPING.register(PlaybackManager::stopAll);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> CameraPathServerConfig.load());

		// A player whose view is on a camera path takes no damage: their body sits on an invisible rig somewhere along the path, out of their own reach. A listener (not setInvulnerable) so nothing about it can be saved with the player.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
				!(entity instanceof ServerPlayerEntity player && PlaybackManager.isOnCameraPath(player)));

		ServerEntityEvents.ENTITY_LOAD.register(PlaybackManager::onEntityLoad);
		EntityTrackingEvents.START_TRACKING.register(PlaybackManager::onStartTracking);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				CameraPathCommand.register(dispatcher));
	}
}
