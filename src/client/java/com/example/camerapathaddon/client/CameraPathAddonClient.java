package com.example.camerapathaddon.client;

import com.example.camerapathaddon.client.screen.CameraControlScreen;
import com.example.camerapathaddon.client.screen.CameraControlSlotsScreen;
import com.example.camerapathaddon.network.Payloads;
import com.example.camerapathaddon.registry.ModBlocks;
import com.example.camerapathaddon.registry.ModEntityTypes;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.render.entity.EmptyEntityRenderer;

public class CameraPathAddonClient implements ClientModInitializer {

	/**
	 * Pauses / resumes the playback the player is in (their view, or a vehicle on a path they ride). Only does
	 * anything during a playback, so its default - Space, the usual pause key - sharing the jump key is harmless
	 * (nothing jumps on a camera rig). It is its own binding so it can be moved independently of jump.
	 */
	public static KeyBinding pauseKey;

	@Override
	public void onInitializeClient() {
		CameraPathClientConfig.load();
		pauseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.camerapathaddon.pause", InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_SPACE, KeyBinding.Category.create(Identifier.of("camerapathaddon", "camerapathaddon"))));
		EntityRendererRegistry.register(ModEntityTypes.CAMERA_RIG, EmptyEntityRenderer::new);
		HandledScreens.register(ModBlocks.CAMERA_CONTROL_SCREEN, CameraControlSlotsScreen::new);

		ClientPlayNetworking.registerGlobalReceiver(Payloads.OpenEditor.ID, (payload, context) ->
				context.client().setScreen(new CameraControlScreen(payload.pos())));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.CameraStart.ID, (payload, context) -> ClientPlaybackState.onCameraStart(payload));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.CameraEnd.ID, (payload, context) -> ClientPlaybackState.onCameraEnd());
		ClientPlayNetworking.registerGlobalReceiver(Payloads.VehicleStart.ID, (payload, context) -> ClientPlaybackState.onVehicleStart(payload));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.VehicleEnd.ID, (payload, context) -> ClientPlaybackState.onVehicleEnd(payload));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Clock.ID, (payload, context) -> ClientPlaybackState.onClock(payload));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ClientPlaybackState::clear));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ClientPlaybackState.clear());

		ClientTickEvents.START_CLIENT_TICK.register(ClientPlaybackState::onStartTick);
		ClientTickEvents.END_CLIENT_TICK.register(PathPreviewRenderer::tick);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (pauseKey.wasPressed()) {
				if (client.currentScreen == null && ClientPlaybackState.canTogglePause(client)) {
					ClientPlayNetworking.send(new Payloads.TogglePause());
				}
			}
		});
	}
}
