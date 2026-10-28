package com.example.camerapathaddon.registry;

import com.example.camerapathaddon.CameraPathAddon;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ChunkTicketType;

/**
 * Chunk tickets used to load chunks along a path. Neither is serialized: unlike the FORCED tickets of
 * {@code /forceload} (and tudursvehiclemod's own ChunkForceTracker), nothing is written to the world, so a crash
 * or a stop mid-playback can't leave chunks loaded for good.
 */
public final class ModChunkTickets {

	/** Loads a chunk (full status, so it can be sent to players) without ticking it - the chunks ahead of and behind the current position. */
	public static ChunkTicketType PRELOAD;
	/** Also ticks entities there - placed where an unmanned vehicle on a path is now and is about to be, so it keeps ticking with no player nearby. */
	public static ChunkTicketType SIMULATION;
	/** Keeps the camera control block a camera playback came from loaded while the player is away on its path, so its editor keeps working there (inspecting a key, editing while paused). */
	public static ChunkTicketType SOURCE_BLOCK;

	private ModChunkTickets() {
	}

	public static void register() {
		PRELOAD = Registry.register(Registries.TICKET_TYPE, CameraPathAddon.id("preload"),
				new ChunkTicketType(ChunkTicketType.NO_EXPIRATION, ChunkTicketType.FOR_LOADING));
		SIMULATION = Registry.register(Registries.TICKET_TYPE, CameraPathAddon.id("simulation"),
				new ChunkTicketType(ChunkTicketType.NO_EXPIRATION,
						ChunkTicketType.FOR_LOADING | ChunkTicketType.FOR_SIMULATION | ChunkTicketType.RESETS_IDLE_TIMEOUT));
		SOURCE_BLOCK = Registry.register(Registries.TICKET_TYPE, CameraPathAddon.id("source_block"),
				new ChunkTicketType(ChunkTicketType.NO_EXPIRATION, ChunkTicketType.FOR_LOADING));
	}
}
