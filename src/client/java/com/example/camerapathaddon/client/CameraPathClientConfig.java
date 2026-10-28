package com.example.camerapathaddon.client;

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

/** config/camerapathaddon-client.json. */
public final class CameraPathClientConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static CameraPathClientConfig instance = new CameraPathClientConfig();

	/** Hide the HUD and hand (as F1 does) while the view is on a camera path. */
	public boolean hideHudDuringPlayback = true;
	/** No view bobbing while the view is on a camera path. */
	public boolean disableBobbingDuringPlayback = true;
	/**
	 * Preview colouring: chunks per second the client is expected to need while moving along a segment
	 * (speed / 16 x the width of the view in chunks). Up to half of this value is drawn green, up to this value yellow,
	 * above it red - a hint for where terrain may not keep up. Depends on the network and the machine; adjust to taste.
	 */
	public double warnChunksPerSecond = 200.0;

	public static CameraPathClientConfig get() {
		return instance;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(CameraPathAddon.MOD_ID + "-client.json");
	}

	public static void load() {
		Path file = file();
		CameraPathClientConfig loaded = null;
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				loaded = GSON.fromJson(reader, CameraPathClientConfig.class);
			} catch (IOException | RuntimeException e) {
				CameraPathAddon.LOGGER.warn("Could not read {} - using defaults", file, e);
			}
		}
		instance = loaded != null ? loaded : new CameraPathClientConfig();
		if (!(instance.warnChunksPerSecond > 0) || !Double.isFinite(instance.warnChunksPerSecond)) {
			instance.warnChunksPerSecond = 200.0;
		}
		save();
	}

	public static void save() {
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			}
		} catch (IOException e) {
			CameraPathAddon.LOGGER.warn("Could not write {}", file, e);
		}
	}
}
