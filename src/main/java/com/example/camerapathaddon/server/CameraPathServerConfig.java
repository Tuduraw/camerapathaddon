package com.example.camerapathaddon.server;

import com.example.camerapathaddon.CameraPathAddon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/camerapathaddon-server.json - read at startup and when a server starts; missing keys keep their defaults and the file is rewritten with every key. */
public final class CameraPathServerConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static CameraPathServerConfig instance = new CameraPathServerConfig();

	/**
	 * Who may start playbacks from a camera control block:
	 * "restricted" - players in creative or spectator mode, operators (level 2+), and the host of a single-player/LAN world;
	 * "everyone" - every player.
	 * Commands always need level 2.
	 */
	public String playbackPermission = "restricted";
	/** Largest number of chunks one playback may keep loaded through its own tickets (the whole-path preload counts against this too). */
	public int maxLoadedChunksPerPlayback = 4096;
	/** How long (seconds) a playback waits at its first key for chunks before starting anyway. */
	public int prepareTimeoutSeconds = 30;

	public static CameraPathServerConfig get() {
		return instance;
	}

	public static void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve(CameraPathAddon.MOD_ID + "-server.json");
		CameraPathServerConfig loaded = null;
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				loaded = GSON.fromJson(reader, CameraPathServerConfig.class);
			} catch (IOException | RuntimeException e) {
				CameraPathAddon.LOGGER.warn("Could not read {} - using defaults", file, e);
			}
		}
		instance = loaded != null ? loaded : new CameraPathServerConfig();
		instance.sanitize();
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			CameraPathAddon.LOGGER.warn("Could not write {}", file, e);
		}
	}

	private void sanitize() {
		if (this.playbackPermission == null || !(this.playbackPermission.equals("everyone") || this.playbackPermission.equals("restricted"))) {
			this.playbackPermission = "restricted";
		}
		this.maxLoadedChunksPerPlayback = Math.max(9, Math.min(65536, this.maxLoadedChunksPerPlayback));
		this.prepareTimeoutSeconds = Math.max(0, Math.min(600, this.prepareTimeoutSeconds));
	}
}
