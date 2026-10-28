package com.example.camerapathaddon.server;

import com.example.camerapathaddon.block.CameraControlBlockEntity;
import com.example.camerapathaddon.entity.CameraRigEntity;
import com.example.camerapathaddon.network.Payloads;
import com.example.camerapathaddon.path.CameraPath;
import com.example.camerapathaddon.path.PathEvaluator;
import com.example.camerapathaddon.path.PathSettings;
import com.example.camerapathaddon.registry.ModChunkTickets;
import com.example.camerapathaddon.registry.ModEntityTypes;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import com.example.tudursvehiclemod.entity.DummyPilotEntity;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Runs every playback on the server.
 *
 * <p><b>Camera playback</b>: the player rides an invisible {@link CameraRigEntity} that is moved along the path, so
 * their chunks and entity tracking follow the camera. Their client places the camera from the same path every frame.
 * It ends when the player dismounts (sneak), the last key is reached (unless looping), playback is stopped, or they
 * log out / the server stops - and every time the player is put back where they started, like ending UAV remote
 * control. While on the path they take no damage (an ALLOW_DAMAGE listener, never setInvulnerable).
 *
 * <p><b>Vehicle playback</b>: tudursvehiclemod's ExternalMotionController replaces the vehicle's own physics; the
 * vehicle (server, and every client tracking it) is placed on the path each tick.
 *
 * <p>Both keep their path's chunks loaded with {@link ChunkPreloader}, may wait at the first key until those are
 * loaded, and are never saved: after a restart everything is back under normal control.
 *
 * <p>Both can start from any tick of the path and be <b>paused</b> (the pause key, a block's pause button,
 * /camerapath pause). A camera playback from a block that is paused follows edits to the block's path - that is the
 * editor's "view check": the player's view held at the selected key while it is adjusted.
 */
public final class PlaybackManager {

	/** Command tag on the display-only dummy pilots this addon seats, so a stale one (left in a save by a stop mid-playback) can be recognized and removed when it loads again. */
	public static final String DISPLAY_PILOT_TAG = "camerapathaddon.display_pilot";
	private static final int CLOCK_SYNC_INTERVAL = 20;
	private static final int VEHICLE_MISSING_TIMEOUT = 100;

	private static final Map<UUID, CameraPlayback> CAMERA = new HashMap<>();
	private static final Map<UUID, VehiclePlayback> VEHICLE = new HashMap<>();
	private static final Set<UUID> DISPLAY_PILOTS = new HashSet<>();

	private PlaybackManager() {
	}

	// ------------------------------------------------------------------ queries

	public static boolean isOnCameraPath(ServerPlayerEntity player) {
		return CAMERA.containsKey(player.getUuid());
	}

	/** The camera control block player's view playback came from, or null (none, or not from a block). */
	public static GlobalPos cameraSourceOf(ServerPlayerEntity player) {
		CameraPlayback playback = CAMERA.get(player.getUuid());
		return playback == null ? null : playback.source;
	}

	public static boolean ownsRig(CameraRigEntity rig) {
		for (CameraPlayback playback : CAMERA.values()) {
			if (playback.rig == rig) {
				return true;
			}
		}
		return false;
	}

	/** The chunk a player's pending chunk sends should be sorted around instead of their own position - a point ahead of them along the path they are moving on - or null for normal nearest-first sending. */
	public static ChunkPos aheadChunkFor(ServerPlayerEntity player) {
		CameraPlayback camera = CAMERA.get(player.getUuid());
		if (camera != null) {
			return camera.aheadChunk;
		}
		if (VEHICLE.isEmpty()) {
			return null;
		}
		VehiclePlayback vehicle = VEHICLE.get(player.getRootVehicle().getUuid());
		return vehicle != null ? vehicle.aheadChunk : null;
	}

	/** Whether a player may start playbacks from a camera control block - see CameraPathServerConfig.playbackPermission. */
	public static boolean mayPlay(ServerPlayerEntity player) {
		if ("everyone".equals(CameraPathServerConfig.get().playbackPermission)) {
			return true;
		}
		MinecraftServer server = player.getEntityWorld().getServer();
		return player.isCreative() || player.isSpectator()
				|| CommandManager.GAMEMASTERS_CHECK.allows(player.getPermissions())
				|| (server != null && !server.isDedicated() && server.isHost(player.getPlayerConfigEntry()));
	}

	// ------------------------------------------------------------------ starting / stopping from a camera control block

	private static GlobalPos sourceOf(CameraControlBlockEntity block) {
		return GlobalPos.create(block.getWorld().getRegistryKey(), block.getPos());
	}

	/**
	 * "Play on view" (paused = false) or "View check" (paused = true) in a block's editor: puts player's view on the
	 * block's path at startTick (-1 = the first key).
	 */
	public static void startCameraFromBlock(CameraControlBlockEntity block, ServerPlayerEntity player, int startTick, boolean paused) {
		if (!mayPlay(player)) {
			player.sendMessage(Text.translatable("message.camerapathaddon.no_permission"), true);
			return;
		}
		if (player.getEntityWorld() != block.getWorld()) {
			return;
		}
		Text error = startCamera(player, block.getPath(), sourceOf(block), startTick, paused);
		if (error != null) {
			player.sendMessage(error, true);
		}
	}

	/** Starts the block's path on the vehicle registered to the stick in the block. player (may be null - a redstone start) gets the messages. */
	public static void startVehicleFromBlock(CameraControlBlockEntity block, ServerPlayerEntity player, int startTick) {
		if (player != null && !mayPlay(player)) {
			player.sendMessage(Text.translatable("message.camerapathaddon.no_permission"), true);
			return;
		}
		UUID target = block.getTargetVehicleId();
		if (target == null) {
			if (player != null) {
				player.sendMessage(Text.translatable("message.camerapathaddon.no_target"), true);
			}
			return;
		}
		if (!(block.getWorld() instanceof ServerWorld world) || !(world.getEntity(target) instanceof AbstractVehicleEntity vehicle)) {
			if (player != null) {
				player.sendMessage(Text.translatable("message.camerapathaddon.target_not_found"), true);
			}
			return;
		}
		Text error = startVehicle(vehicle, block.getPath(), player, sourceOf(block), startTick);
		if (error != null && player != null) {
			player.sendMessage(error, true);
		}
	}

	/** Stops every playback started from the block at pos (its Stop button, the block being broken, /camerapath stop &lt;pos&gt;). Returns how many. */
	public static int stopFromBlock(ServerWorld world, BlockPos pos) {
		GlobalPos source = GlobalPos.create(world.getRegistryKey(), pos);
		int stopped = 0;
		for (CameraPlayback playback : new ArrayList<>(CAMERA.values())) {
			if (source.equals(playback.source)) {
				endCamera(world.getServer(), playback, "message.camerapathaddon.stopped");
				stopped++;
			}
		}
		for (VehiclePlayback playback : new ArrayList<>(VEHICLE.values())) {
			if (source.equals(playback.source)) {
				endVehicle(playback);
				stopped++;
			}
		}
		return stopped;
	}

	/** A block's pause button / /camerapath pause: pauses every playback started from it, or resumes them all when they are all paused already. Returns how many changed. */
	public static int togglePauseFromBlock(ServerWorld world, BlockPos pos) {
		GlobalPos source = GlobalPos.create(world.getRegistryKey(), pos);
		List<CameraPlayback> cameras = new ArrayList<>();
		List<VehiclePlayback> vehicles = new ArrayList<>();
		boolean anyRunning = false;
		for (CameraPlayback playback : CAMERA.values()) {
			if (source.equals(playback.source) && playback.running) {
				cameras.add(playback);
				anyRunning |= !playback.paused;
			}
		}
		for (VehiclePlayback playback : VEHICLE.values()) {
			if (source.equals(playback.source) && playback.running && !playback.finished) {
				vehicles.add(playback);
				anyRunning |= !playback.paused;
			}
		}
		boolean pause = anyRunning;
		MinecraftServer server = world.getServer();
		for (CameraPlayback playback : cameras) {
			setCameraPaused(server, playback, pause);
		}
		for (VehiclePlayback playback : vehicles) {
			setVehiclePaused(playback, pause);
		}
		return cameras.size() + vehicles.size();
	}

	/** The pause key: the player's own view playback, else the playback of the vehicle they ride (if they may control it). */
	public static void togglePauseByKey(ServerPlayerEntity player) {
		CameraPlayback camera = CAMERA.get(player.getUuid());
		if (camera != null) {
			if (camera.running) {
				setCameraPaused(player.getEntityWorld().getServer(), camera, !camera.paused);
			}
			return;
		}
		if (VEHICLE.isEmpty()) {
			return;
		}
		VehiclePlayback vehicle = VEHICLE.get(player.getRootVehicle().getUuid());
		if (vehicle == null || !vehicle.running || vehicle.finished) {
			return;
		}
		if (!player.getUuid().equals(vehicle.starterId) && !mayPlay(player)) {
			player.sendMessage(Text.translatable("message.camerapathaddon.no_permission"), true);
			return;
		}
		setVehiclePaused(vehicle, !vehicle.paused);
		player.sendMessage(Text.translatable(vehicle.paused ? "message.camerapathaddon.paused" : "message.camerapathaddon.resumed"), true);
	}

	/** The editor's view check: moves the player's paused view from the block at source to path tick `tick`. */
	public static void inspectTime(ServerPlayerEntity player, GlobalPos source, int tick) {
		CameraPlayback playback = CAMERA.get(player.getUuid());
		if (playback == null || !source.equals(playback.source) || !playback.paused) {
			return;
		}
		playback.elapsed = playback.elapsedAt(tick);
		ServerPlayNetworking.send(player, playback.clock());
		playback.place(player);
	}

	/** "Play from the selected key" while checking the view: resumes the player's paused view from the block at source, from tick (-1 = where it is). */
	public static void resumeCamera(ServerPlayerEntity player, GlobalPos source, int tick) {
		CameraPlayback playback = CAMERA.get(player.getUuid());
		if (playback == null || !source.equals(playback.source)) {
			return;
		}
		if (tick >= 0) {
			playback.elapsed = playback.elapsedAt(tick);
		}
		setCameraPaused(player.getEntityWorld().getServer(), playback, false);
	}

	/** Ends the player's view playback if it came from the block at source. */
	public static void endCameraFrom(ServerPlayerEntity player, GlobalPos source) {
		CameraPlayback playback = CAMERA.get(player.getUuid());
		if (playback != null && source.equals(playback.source)) {
			endCamera(player.getEntityWorld().getServer(), playback, null);
		}
	}

	private static void setCameraPaused(MinecraftServer server, CameraPlayback playback, boolean paused) {
		if (playback.paused == paused) {
			return;
		}
		playback.paused = paused;
		ServerPlayerEntity player = server.getPlayerManager().getPlayer(playback.playerId);
		if (player != null) {
			ServerPlayNetworking.send(player, playback.clock());
		}
	}

	private static void setVehiclePaused(VehiclePlayback playback, boolean paused) {
		if (playback.paused == paused) {
			return;
		}
		playback.paused = paused;
		if (!playback.vehicle.isRemoved()) {
			sendToTracking(playback.vehicle, playback.clock());
		}
	}

	// ------------------------------------------------------------------ camera playback

	/** Puts player's view on path from its first key. Returns an error message, or null when started. */
	public static Text startCamera(ServerPlayerEntity player, CameraPath path, GlobalPos source) {
		return startCamera(player, path, source, -1, false);
	}

	/**
	 * Puts player's view on path, starting at path tick startTick (-1 = the first key), paused or not. A paused start
	 * (the editor's view check) doesn't wait for chunks - it just stands there while they load.
	 * Returns an error message, or null when started.
	 */
	public static Text startCamera(ServerPlayerEntity player, CameraPath path, GlobalPos source, int startTick, boolean paused) {
		if (path.keys().size() < 2 || path.duration() <= 0) {
			return Text.translatable("message.camerapathaddon.need_two_keys");
		}
		if (CAMERA.containsKey(player.getUuid())) {
			return Text.translatable("message.camerapathaddon.already_playing");
		}
		if (player.hasVehicle() || AbstractVehicleEntity.tudursvehiclemod$isRemoteControlling(player)) {
			return Text.translatable("message.camerapathaddon.dismount_first");
		}
		if (player.isSleeping() || !player.isAlive()) {
			return Text.translatable("message.camerapathaddon.cannot_start");
		}
		ServerWorld world = player.getEntityWorld();
		PathEvaluator evaluator = new PathEvaluator(path, 0, 0, 0, 70.0);
		int startElapsed = elapsedAt(path, startTick);
		PathEvaluator.Pose first = evaluator.evaluate(path.pathTime(startElapsed));

		CameraRigEntity rig = new CameraRigEntity(ModEntityTypes.CAMERA_RIG, world);
		rig.refreshPositionAndAngles(first.x(), first.y(), first.z(), first.yaw(), first.pitch());
		CameraPlayback playback = new CameraPlayback(player.getUuid(), world, rig, path, evaluator,
				world.getRegistryKey(), player.getEntityPos(), player.getYaw(), player.getPitch(), source);
		// Registered before spawning: an unowned rig removes itself on its first tick.
		CAMERA.put(player.getUuid(), playback);
		if (!world.spawnEntity(rig)) {
			CAMERA.remove(player.getUuid());
			return Text.translatable("message.camerapathaddon.cannot_start");
		}
		player.teleport(world, first.x(), first.y() - player.getStandingEyeHeight(), first.z(), Set.of(), first.yaw(), first.pitch(), false);
		if (!player.startRiding(rig, true, false)) {
			CAMERA.remove(player.getUuid());
			rig.discard();
			player.teleport(world, playback.originPos.x, playback.originPos.y, playback.originPos.z, Set.of(), playback.originYaw, playback.originPitch, false);
			return Text.translatable("message.camerapathaddon.cannot_start");
		}
		playback.elapsed = startElapsed;
		playback.paused = paused;
		playback.preloader = new ChunkPreloader(world, evaluator, path.settings(), CameraPathServerConfig.get().maxLoadedChunksPerPlayback);
		playback.preloader.update(path.pathTime(startElapsed));
		playback.running = paused || (!path.settings().waitForLoad() && !path.settings().preloadWhole());
		if (source != null && source.dimension() == world.getRegistryKey()) {
			playback.sourceTicket = new ChunkPos(source.pos());
			world.getChunkManager().addTicket(ModChunkTickets.SOURCE_BLOCK, playback.sourceTicket, 0);
		}
		if (playback.preloader.isCapped()) {
			player.sendMessage(Text.translatable("message.camerapathaddon.chunk_cap", CameraPathServerConfig.get().maxLoadedChunksPerPlayback), false);
		}
		ServerPlayNetworking.send(player, playback.startPacket());
		return null;
	}

	/** Called by the rig when its passenger leaves it for any reason (sneaking, a teleport, ...). The return to the start position happens at the end of this tick, outside the dismount in progress. */
	public static void onRigPassengerRemoved(CameraRigEntity rig, Entity passenger) {
		CameraPlayback playback = CAMERA.get(passenger.getUuid());
		if (playback != null && playback.rig == rig) {
			playback.endRequested = true;
		}
	}

	/** Before a logging-out player is saved (see the PlayerManager mixin): back to where they started, so they log in there again rather than on a rig that no longer exists. */
	public static void onPlayerLogout(ServerPlayerEntity player) {
		CameraPlayback playback = CAMERA.get(player.getUuid());
		if (playback != null) {
			endCamera(player.getEntityWorld().getServer(), playback, null);
		}
	}

	private static void tickCamera(MinecraftServer server, CameraPlayback playback) {
		ServerPlayerEntity player = server.getPlayerManager().getPlayer(playback.playerId);
		if (player == null) {
			endCamera(server, playback, null);
			return;
		}
		if (playback.endRequested || playback.rig.isRemoved() || player.getVehicle() != playback.rig) {
			endCamera(server, playback, "message.camerapathaddon.stopped");
			return;
		}
		if (playback.paused) {
			followSourceBlock(player, playback);
		}
		CameraPath path = playback.path;
		if (!playback.running) {
			playback.prepareTicks++;
			boolean ready = playback.preloader.isReady();
			boolean timedOut = playback.prepareTicks >= CameraPathServerConfig.get().prepareTimeoutSeconds * 20;
			if (ready || timedOut) {
				playback.running = true;
				ServerPlayNetworking.send(player, playback.clock());
				if (timedOut && !ready) {
					player.sendMessage(Text.translatable("message.camerapathaddon.prepare_timeout"), true);
				}
			} else if (playback.prepareTicks % 10 == 1) {
				player.sendMessage(Text.translatable("message.camerapathaddon.preparing",
						playback.preloader.loadedCount(), playback.preloader.requestedCount()), true);
			}
		} else if (!playback.paused) {
			playback.elapsed++;
			if (!path.settings().loop() && playback.elapsed > path.duration()) {
				endCamera(server, playback, "message.camerapathaddon.finished");
				return;
			}
		}
		double t = path.pathTime(playback.elapsed);
		playback.place(player);
		playback.preloader.update(t);
		playback.aheadChunk = path.settings().prioritizeAhead()
				? ChunkPreloader.aheadChunk(playback.evaluator, t, Math.max(1, player.getViewDistance() - 2) * 16 * 0.75, 400)
				: null;
		if (playback.running && !playback.paused && playback.elapsed % CLOCK_SYNC_INTERVAL == 0) {
			ServerPlayNetworking.send(player, playback.clock());
		}
	}

	/** While paused, a view playback from a block takes up edits to the block's path (its client gets the new path). */
	private static void followSourceBlock(ServerPlayerEntity player, CameraPlayback playback) {
		if (playback.source == null || playback.source.dimension() != playback.world.getRegistryKey()
				|| !playback.world.isChunkLoaded(ChunkPos.toLong(playback.source.pos().getX() >> 4, playback.source.pos().getZ() >> 4))
				|| !(playback.world.getBlockEntity(playback.source.pos()) instanceof CameraControlBlockEntity block)) {
			return;
		}
		CameraPath newPath = block.getPath();
		if (newPath == playback.path || newPath.keys().isEmpty()) {
			return;
		}
		double time = playback.path.pathTime(playback.elapsed);
		playback.path = newPath;
		playback.evaluator = new PathEvaluator(newPath, 0, 0, 0, 70.0);
		playback.elapsed = elapsedAt(newPath, (int) Math.round(time));
		playback.preloader.releaseAll();
		playback.preloader = new ChunkPreloader(playback.world, playback.evaluator, newPath.settings(), CameraPathServerConfig.get().maxLoadedChunksPerPlayback);
		ServerPlayNetworking.send(player, playback.startPacket());
	}

	/** Playback time (ticks since the first key) at path tick `tick`, clamped to the path; -1 = the first key. */
	static int elapsedAt(CameraPath path, int tick) {
		if (tick < 0) {
			return 0;
		}
		return Math.max(0, Math.min(path.duration(), tick - path.firstTick()));
	}

	private static void endCamera(MinecraftServer server, CameraPlayback playback, String messageKey) {
		if (CAMERA.get(playback.playerId) != playback) {
			return;
		}
		CAMERA.remove(playback.playerId);
		if (playback.preloader != null) {
			playback.preloader.releaseAll();
		}
		if (playback.sourceTicket != null) {
			playback.world.getChunkManager().removeTicket(ModChunkTickets.SOURCE_BLOCK, playback.sourceTicket, 0);
			playback.sourceTicket = null;
		}
		ServerPlayerEntity player = server == null ? null : server.getPlayerManager().getPlayer(playback.playerId);
		if (player != null) {
			if (player.getVehicle() == playback.rig) {
				player.stopRiding();
			}
			ServerWorld originWorld = server.getWorld(playback.originWorld);
			if (originWorld == null) {
				originWorld = playback.world;
			}
			player.teleport(originWorld, playback.originPos.x, playback.originPos.y, playback.originPos.z, Set.of(),
					playback.originYaw, playback.originPitch, false);
			player.setVelocity(Vec3d.ZERO);
			player.fallDistance = 0;
			ServerPlayNetworking.send(player, new Payloads.CameraEnd());
			if (messageKey != null) {
				player.sendMessage(Text.translatable(messageKey), true);
			}
		}
		playback.rig.removeAllPassengers();
		playback.rig.discard();
	}

	// ------------------------------------------------------------------ vehicle playback

	/** Puts vehicle on path from its first key. Returns an error message, or null when started. starter (may be null) gets progress messages. */
	public static Text startVehicle(AbstractVehicleEntity vehicle, CameraPath path, ServerPlayerEntity starter, GlobalPos source) {
		return startVehicle(vehicle, path, starter, source, -1);
	}

	/** Puts vehicle on path from path tick startTick (-1 = the first key). Returns an error message, or null when started. */
	public static Text startVehicle(AbstractVehicleEntity vehicle, CameraPath path, ServerPlayerEntity starter, GlobalPos source, int startTick) {
		if (path.keys().size() < 2 || path.duration() <= 0) {
			return Text.translatable("message.camerapathaddon.need_two_keys");
		}
		if (vehicle.tudursvehiclemod$isDestroyed() || vehicle.isRemoved()) {
			return Text.translatable("message.camerapathaddon.target_not_found");
		}
		VehiclePlayback existing = VEHICLE.get(vehicle.getUuid());
		if (existing != null) {
			endVehicle(existing);
		} else if (vehicle.tudursvehiclemod$getExternalMotionController() != null) {
			return Text.translatable("message.camerapathaddon.vehicle_busy");
		}
		if (!(vehicle.getEntityWorld() instanceof ServerWorld world)) {
			return Text.translatable("message.camerapathaddon.cannot_start");
		}
		PathEvaluator evaluator = new PathEvaluator(path, 0, 0, 0, 70.0);
		VehiclePlayback playback = new VehiclePlayback(vehicle.getUuid(), world, vehicle, path, evaluator,
				starter != null ? starter.getUuid() : null, source);
		playback.elapsed = elapsedAt(path, startTick);
		playback.preloader = new ChunkPreloader(world, evaluator, path.settings(), CameraPathServerConfig.get().maxLoadedChunksPerPlayback);
		playback.preloader.update(path.pathTime(playback.elapsed));
		playback.running = !path.settings().waitForLoad() && !path.settings().preloadWhole();
		VEHICLE.put(vehicle.getUuid(), playback);
		vehicle.tudursvehiclemod$setExternalMotionController(playback);
		if (path.settings().dummyPilot()) {
			spawnDisplayPilot(world, vehicle, playback);
		}
		if (starter != null && playback.preloader.isCapped()) {
			starter.sendMessage(Text.translatable("message.camerapathaddon.chunk_cap", CameraPathServerConfig.get().maxLoadedChunksPerPlayback), false);
		}
		Payloads.VehicleStart start = new Payloads.VehicleStart(vehicle.getId(), path, playback.elapsed, playback.running);
		for (ServerPlayerEntity player : PlayerLookup.tracking(vehicle)) {
			ServerPlayNetworking.send(player, start);
		}
		if (vehicle.getControllingPassenger() instanceof ServerPlayerEntity rider && !PlayerLookup.tracking(vehicle).contains(rider)) {
			ServerPlayNetworking.send(rider, start);
		}
		return null;
	}

	/** A display-only tudursvehiclemod dummy pilot in the empty pilot seat. Its combat weapon stays at the "none selected" default, so it never targets or fires at anything. */
	private static void spawnDisplayPilot(ServerWorld world, AbstractVehicleEntity vehicle, VehiclePlayback playback) {
		if (vehicle.tudursvehiclemod$getSeatOccupant(0) != null) {
			return;
		}
		DummyPilotEntity pilot = new DummyPilotEntity(com.example.tudursvehiclemod.registry.ModEntityTypes.DUMMY_PILOT, world);
		pilot.refreshPositionAndAngles(vehicle.getX(), vehicle.getY(), vehicle.getZ(), vehicle.getYaw(), 0f);
		String skin = playback.path.settings().dummyPilotSkin();
		if (!skin.isEmpty()) {
			pilot.tudursvehiclemod$setSkinId(skin);
		}
		pilot.addCommandTag(DISPLAY_PILOT_TAG);
		DISPLAY_PILOTS.add(pilot.getUuid());
		if (!world.spawnEntity(pilot)) {
			DISPLAY_PILOTS.remove(pilot.getUuid());
			return;
		}
		if (!vehicle.tudursvehiclemod$mountToSeat(pilot, 0)) {
			DISPLAY_PILOTS.remove(pilot.getUuid());
			pilot.discard();
			return;
		}
		playback.pilot = pilot;
	}

	private static void tickVehicle(MinecraftServer server, VehiclePlayback playback) {
		Entity found = playback.world.getEntity(playback.vehicleId);
		if (!(found instanceof AbstractVehicleEntity vehicle) || found.isRemoved()) {
			// Possibly just between unloading and loading again - give it a moment before giving up.
			if (++playback.missingTicks > VEHICLE_MISSING_TIMEOUT) {
				endVehicle(playback);
			}
			return;
		}
		playback.missingTicks = 0;
		if (vehicle != playback.vehicle) {
			// Reloaded as a new entity instance: the controller lives on the instance, so it has to be installed again.
			playback.vehicle = vehicle;
			vehicle.tudursvehiclemod$setExternalMotionController(playback);
		}
		if (vehicle.tudursvehiclemod$isDestroyed()) {
			endVehicle(playback);
			return;
		}
		CameraPath path = playback.path;
		ServerPlayerEntity starter = playback.starterId == null ? null : server.getPlayerManager().getPlayer(playback.starterId);
		if (!playback.running) {
			playback.prepareTicks++;
			boolean ready = playback.preloader.isReady();
			boolean timedOut = playback.prepareTicks >= CameraPathServerConfig.get().prepareTimeoutSeconds * 20;
			if (ready || timedOut) {
				playback.running = true;
				sendToTracking(vehicle, playback.clock());
				if (starter != null && timedOut && !ready) {
					starter.sendMessage(Text.translatable("message.camerapathaddon.prepare_timeout"), true);
				}
			} else if (starter != null && playback.prepareTicks % 10 == 1) {
				starter.sendMessage(Text.translatable("message.camerapathaddon.preparing",
						playback.preloader.loadedCount(), playback.preloader.requestedCount()), true);
			}
		} else if (!playback.finished && !playback.paused) {
			playback.elapsed++;
			if (!path.settings().loop() && playback.elapsed >= path.duration()) {
				if (path.settings().endAction() == PathSettings.EndAction.RELEASE) {
					endVehicle(playback);
					return;
				}
				playback.finished = true;
				playback.elapsed = path.duration();
				if (starter != null) {
					starter.sendMessage(Text.translatable("message.camerapathaddon.vehicle_holding"), true);
				}
			}
		}
		double t = playback.currentPathTime();
		playback.preloader.update(t);
		// Entity ticking where the vehicle is now and over the next few ticks, so it never steps into a chunk that doesn't tick it.
		LongOpenHashSet simulation = new LongOpenHashSet();
		simulation.add(ChunkPos.toLong(vehicle.getBlockX() >> 4, vehicle.getBlockZ() >> 4));
		for (int ahead = 0; ahead <= 4; ahead++) {
			PathEvaluator.Pose pose = playback.evaluator.evaluate(path.pathTime(playback.elapsed + ahead));
			simulation.add(ChunkPos.toLong((int) Math.floor(pose.x()) >> 4, (int) Math.floor(pose.z()) >> 4));
		}
		playback.preloader.updateSimulation(simulation);
		boolean riddenByPlayer = false;
		for (Entity passenger : vehicle.getPassengersDeep()) {
			if (passenger instanceof ServerPlayerEntity) {
				riddenByPlayer = true;
				break;
			}
		}
		playback.aheadChunk = riddenByPlayer && path.settings().prioritizeAhead()
				? ChunkPreloader.aheadChunk(playback.evaluator, t, 6 * 16, 400)
				: null;
		if (playback.running && !playback.finished && !playback.paused && playback.elapsed % CLOCK_SYNC_INTERVAL == 0) {
			sendToTracking(vehicle, playback.clock());
		}
	}

	private static void sendToTracking(AbstractVehicleEntity vehicle, net.minecraft.network.packet.CustomPayload payload) {
		for (ServerPlayerEntity player : PlayerLookup.tracking(vehicle)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	public static void endVehicle(VehiclePlayback playback) {
		if (VEHICLE.get(playback.vehicleId) != playback) {
			return;
		}
		VEHICLE.remove(playback.vehicleId);
		playback.preloader.releaseAll();
		AbstractVehicleEntity vehicle = playback.vehicle;
		if (vehicle.tudursvehiclemod$getExternalMotionController() == playback) {
			vehicle.tudursvehiclemod$setExternalMotionController(null);
		}
		if (!vehicle.isRemoved()) {
			sendToTracking(vehicle, new Payloads.VehicleEnd(vehicle.getId()));
			if (vehicle.getControllingPassenger() instanceof ServerPlayerEntity rider) {
				ServerPlayNetworking.send(rider, new Payloads.VehicleEnd(vehicle.getId()));
			}
		}
		if (playback.pilot != null) {
			DISPLAY_PILOTS.remove(playback.pilot.getUuid());
			playback.pilot.stopRiding();
			playback.pilot.discard();
			playback.pilot = null;
		}
	}

	/** Ends the playback of the vehicle with this UUID, if any. Returns whether there was one. */
	public static boolean stopVehicle(UUID vehicleId) {
		VehiclePlayback playback = VEHICLE.get(vehicleId);
		if (playback == null) {
			return false;
		}
		endVehicle(playback);
		return true;
	}

	/** Ends player's camera playback, if any. Returns whether there was one. */
	public static boolean stopCamera(ServerPlayerEntity player) {
		CameraPlayback playback = CAMERA.get(player.getUuid());
		if (playback == null) {
			return false;
		}
		endCamera(player.getEntityWorld().getServer(), playback, "message.camerapathaddon.stopped");
		return true;
	}

	// ------------------------------------------------------------------ events

	public static void onEndServerTick(MinecraftServer server) {
		if (!CAMERA.isEmpty()) {
			for (CameraPlayback playback : new ArrayList<>(CAMERA.values())) {
				tickCamera(server, playback);
			}
		}
		if (!VEHICLE.isEmpty()) {
			for (VehiclePlayback playback : new ArrayList<>(VEHICLE.values())) {
				tickVehicle(server, playback);
			}
		}
	}

	public static void stopAll(MinecraftServer server) {
		for (CameraPlayback playback : new ArrayList<>(CAMERA.values())) {
			endCamera(server, playback, null);
		}
		for (VehiclePlayback playback : new ArrayList<>(VEHICLE.values())) {
			endVehicle(playback);
		}
		CAMERA.clear();
		VEHICLE.clear();
		DISPLAY_PILOTS.clear();
	}

	/** Removes a display pilot left over from a playback that no longer runs (the server stopped mid-playback and it was saved with its vehicle). */
	public static void onEntityLoad(Entity entity, ServerWorld world) {
		if (entity instanceof DummyPilotEntity && entity.getCommandTags().contains(DISPLAY_PILOT_TAG)
				&& !DISPLAY_PILOTS.contains(entity.getUuid())) {
			entity.stopRiding();
			entity.discard();
		}
	}

	/** A player who starts tracking a vehicle on a path learns about the playback, so their client moves it smoothly too. */
	public static void onStartTracking(Entity entity, ServerPlayerEntity player) {
		if (VEHICLE.isEmpty() || !(entity instanceof AbstractVehicleEntity)) {
			return;
		}
		VehiclePlayback playback = VEHICLE.get(entity.getUuid());
		if (playback != null) {
			ServerPlayNetworking.send(player, new Payloads.VehicleStart(entity.getId(), playback.path, playback.elapsed, playback.running && !playback.paused));
		}
	}

	public static List<String> describeActive() {
		List<String> lines = new ArrayList<>();
		CAMERA.values().forEach(p -> lines.add("camera " + p.playerId + " " + p.elapsed + "/" + p.path.duration() + (p.paused ? " paused" : "")));
		VEHICLE.values().forEach(p -> lines.add("vehicle " + p.vehicleId + " " + p.elapsed + "/" + p.path.duration() + (p.paused ? " paused" : "")));
		return lines;
	}

	// ------------------------------------------------------------------ state

	private static final class CameraPlayback {
		final UUID playerId;
		final ServerWorld world;
		final CameraRigEntity rig;
		CameraPath path;
		PathEvaluator evaluator;
		final RegistryKey<World> originWorld;
		final Vec3d originPos;
		final float originYaw;
		final float originPitch;
		/** The camera control block this was started from, or null (a command with a raw path). */
		final GlobalPos source;
		ChunkPreloader preloader;
		int elapsed;
		boolean running;
		int prepareTicks;
		boolean endRequested;
		boolean paused;
		ChunkPos aheadChunk;
		ChunkPos sourceTicket;

		int elapsedAt(int tick) {
			return PlaybackManager.elapsedAt(this.path, tick);
		}

		Payloads.Clock clock() {
			return new Payloads.Clock(this.rig.getId(), this.elapsed, this.running && !this.paused, this.paused);
		}

		Payloads.CameraStart startPacket() {
			return new Payloads.CameraStart(this.rig.getId(), this.path, this.elapsed, this.running && !this.paused, this.paused,
					Optional.ofNullable(this.source != null && this.source.dimension() == this.world.getRegistryKey() ? this.source.pos() : null));
		}

		/** Puts the rig at the current path time. */
		void place(ServerPlayerEntity player) {
			PathEvaluator.Pose pose = this.evaluator.evaluate(this.path.pathTime(this.elapsed));
			this.rig.setPosition(pose.x(), pose.y(), pose.z());
			this.rig.setYaw(pose.yaw());
			this.rig.setPitch(pose.pitch());
		}

		CameraPlayback(UUID playerId, ServerWorld world, CameraRigEntity rig, CameraPath path, PathEvaluator evaluator,
					   RegistryKey<World> originWorld, Vec3d originPos, float originYaw, float originPitch, GlobalPos source) {
			this.playerId = playerId;
			this.world = world;
			this.rig = rig;
			this.path = path;
			this.evaluator = evaluator;
			this.originWorld = originWorld;
			this.originPos = originPos;
			this.originYaw = originYaw;
			this.originPitch = originPitch;
			this.source = source;
		}
	}

	/** One vehicle on a path. Also its ExternalMotionController (server side). */
	public static final class VehiclePlayback implements AbstractVehicleEntity.ExternalMotionController {
		final UUID vehicleId;
		final ServerWorld world;
		AbstractVehicleEntity vehicle;
		final CameraPath path;
		final PathEvaluator evaluator;
		final UUID starterId;
		final GlobalPos source;
		ChunkPreloader preloader;
		int elapsed;
		boolean running;
		boolean finished;
		boolean paused;
		int prepareTicks;
		int missingTicks;
		DummyPilotEntity pilot;
		ChunkPos aheadChunk;

		VehiclePlayback(UUID vehicleId, ServerWorld world, AbstractVehicleEntity vehicle, CameraPath path, PathEvaluator evaluator, UUID starterId, GlobalPos source) {
			this.source = source;
			this.vehicleId = vehicleId;
			this.world = world;
			this.vehicle = vehicle;
			this.path = path;
			this.evaluator = evaluator;
			this.starterId = starterId;
		}

		double currentPathTime() {
			return this.path.pathTime(this.elapsed);
		}

		Payloads.Clock clock() {
			return new Payloads.Clock(this.vehicle.getId(), this.elapsed, this.running && !this.paused && !this.finished, this.paused);
		}

		@Override
		public boolean tudursvehiclemod$tickMotion(AbstractVehicleEntity vehicle) {
			if (vehicle.tudursvehiclemod$isDestroyed()) {
				return false;
			}
			PathEvaluator.Pose pose = this.evaluator.evaluate(this.currentPathTime());
			vehicle.tudursvehiclemod$applyExternalPose(new Vec3d(pose.x(), pose.y(), pose.z()), pose.rotation());
			return true;
		}
	}
}
