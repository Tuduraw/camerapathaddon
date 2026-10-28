package com.example.camerapathaddon.network;

import com.example.camerapathaddon.server.BlockRequests;
import com.example.camerapathaddon.server.PlaybackManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class CameraPathNetworking {

	private CameraPathNetworking() {
	}

	public static void register() {
		PayloadTypeRegistry.playC2S().register(Payloads.EditKey.ID, Payloads.EditKey.CODEC);
		PayloadTypeRegistry.playC2S().register(Payloads.EditSettings.ID, Payloads.EditSettings.CODEC);
		PayloadTypeRegistry.playC2S().register(Payloads.BlockAction.ID, Payloads.BlockAction.CODEC);
		PayloadTypeRegistry.playC2S().register(Payloads.TogglePause.ID, Payloads.TogglePause.CODEC);

		PayloadTypeRegistry.playS2C().register(Payloads.OpenEditor.ID, Payloads.OpenEditor.CODEC);
		PayloadTypeRegistry.playS2C().register(Payloads.CameraStart.ID, Payloads.CameraStart.CODEC);
		PayloadTypeRegistry.playS2C().register(Payloads.CameraEnd.ID, Payloads.CameraEnd.CODEC);
		PayloadTypeRegistry.playS2C().register(Payloads.VehicleStart.ID, Payloads.VehicleStart.CODEC);
		PayloadTypeRegistry.playS2C().register(Payloads.VehicleEnd.ID, Payloads.VehicleEnd.CODEC);
		PayloadTypeRegistry.playS2C().register(Payloads.Clock.ID, Payloads.Clock.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(Payloads.EditKey.ID, (payload, context) ->
				BlockRequests.editKey(context.player(), payload.pos(), payload.index(), payload.key()));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.EditSettings.ID, (payload, context) ->
				BlockRequests.editSettings(context.player(), payload.pos(), payload.settings(), payload.name()));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.BlockAction.ID, (payload, context) ->
				BlockRequests.action(context.player(), payload.pos(), payload.action(), payload.index(), payload.value()));
		ServerPlayNetworking.registerGlobalReceiver(Payloads.TogglePause.ID, (payload, context) ->
				PlaybackManager.togglePauseByKey(context.player()));
	}
}
