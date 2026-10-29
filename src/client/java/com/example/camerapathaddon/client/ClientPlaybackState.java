package com.example.camerapathaddon.client;

import com.example.camerapathaddon.network.Payloads;
import com.example.camerapathaddon.path.ActionChannels;
import com.example.camerapathaddon.path.CameraPath;
import com.example.camerapathaddon.path.PathEvaluator;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The client's copy of the playbacks it needs to show: its own camera playback, and every vehicle on a path that it
 * tracks. Each evaluates the same path as the server from a local clock (advanced every client tick, corrected by
 * the server's clock packets when it drifts), so motion is smooth between ticks instead of following the 20 Hz
 * network updates.
 */
public final class ClientPlaybackState {

	/** A clock this many ticks off from the server's is snapped to it; smaller differences are left alone (they're just network delay). */
	private static final int CLOCK_TOLERANCE = 2;

	public static CameraPlayback camera;
	private static final Map<Integer, VehiclePlayback> VEHICLES = new HashMap<>();

	private ClientPlaybackState() {
	}

	public static boolean isCameraActive() {
		return camera != null;
	}

	// ---- packets

	public static void onCameraStart(Payloads.CameraStart payload) {
		MinecraftClient client = MinecraftClient.getInstance();
		CameraPlayback previous = camera;
		double defaultFov = client.options.getFov().getValue();
		camera = new CameraPlayback(payload.rigId(), payload.path(), new PathEvaluator(payload.path(), 0, 0, 0, defaultFov),
				payload.elapsed(), payload.running(), payload.paused(), payload.source().orElse(null));
		if (previous != null) {
			// The same playback again with a changed path (edited while paused): keep the HUD bookkeeping.
			camera.restoreHud = previous.restoreHud;
		} else if (CameraPathClientConfig.get().hideHudDuringPlayback && !client.options.hudHidden) {
			client.options.hudHidden = true;
			camera.restoreHud = true;
		}
	}

	public static void onCameraEnd() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (camera != null && camera.restoreHud) {
			client.options.hudHidden = false;
		}
		camera = null;
	}

	public static void onVehicleStart(Payloads.VehicleStart payload) {
		VehiclePlayback previous = VEHICLES.remove(payload.entityId());
		if (previous != null) {
			previous.uninstall();
		}
		VEHICLES.put(payload.entityId(), new VehiclePlayback(payload.entityId(), payload.path(),
				new PathEvaluator(payload.path(), 0, 0, 0, 70.0), payload.elapsed(), payload.running()));
	}

	public static void onVehicleEnd(Payloads.VehicleEnd payload) {
		VehiclePlayback playback = VEHICLES.remove(payload.entityId());
		if (playback != null) {
			playback.uninstall();
		}
	}

	public static void onClock(Payloads.Clock payload) {
		if (camera != null && camera.rigId == payload.entityId()) {
			camera.sync(payload.elapsed(), payload.running());
			camera.paused = payload.paused();
		}
		VehiclePlayback vehicle = VEHICLES.get(payload.entityId());
		if (vehicle != null) {
			vehicle.sync(payload.elapsed(), payload.running());
		}
	}

	// ---- ticking

	/** START_CLIENT_TICK: advances every clock before the world (and the vehicles on paths) tick. */
	public static void onStartTick(MinecraftClient client) {
		if (camera != null) {
			camera.advance();
		}
		if (VEHICLES.isEmpty()) {
			return;
		}
		Iterator<VehiclePlayback> iterator = VEHICLES.values().iterator();
		while (iterator.hasNext()) {
			VehiclePlayback playback = iterator.next();
			playback.advance();
			if (client.world == null) {
				continue;
			}
			Entity entity = client.world.getEntityById(playback.entityId);
			if (entity instanceof AbstractVehicleEntity vehicle) {
				playback.install(vehicle);
			} else if (++playback.missingTicks > 200) {
				// Out of range for a while: the server will send the playback again if it comes back into view.
				playback.uninstall();
				iterator.remove();
			}
		}
	}

	/** Whether the pause key has anything to act on: the player's own view playback, or a vehicle on a path they ride. */
	public static boolean canTogglePause(MinecraftClient client) {
		if (camera != null) {
			return true;
		}
		return client.player != null && client.player.hasVehicle() && VEHICLES.containsKey(client.player.getRootVehicle().getId());
	}

	public static void clear() {
		onCameraEnd();
		for (VehiclePlayback playback : VEHICLES.values()) {
			playback.uninstall();
		}
		VEHICLES.clear();
	}

	// ---- state classes

	abstract static class Clocked {
		final CameraPath path;
		final PathEvaluator evaluator;
		int clock;
		boolean running;

		Clocked(CameraPath path, PathEvaluator evaluator, int elapsed, boolean running) {
			this.path = path;
			this.evaluator = evaluator;
			this.clock = elapsed;
			this.running = running;
		}

		void advance() {
			if (this.running) {
				this.clock++;
			}
		}

		void sync(int elapsed, boolean nowRunning) {
			if (!nowRunning) {
				// Stopped (waiting for chunks, paused, moved by the editor): show exactly the server's time.
				this.running = false;
				this.clock = elapsed;
				return;
			}
			if (!this.running) {
				this.running = true;
				this.clock = elapsed;
				return;
			}
			this.running = nowRunning;
			if (Math.abs(this.clock - elapsed) > CLOCK_TOLERANCE) {
				this.clock = elapsed;
			}
		}

		/** Path time to show at the current frame: one tick behind the clock and interpolated across it, the same way entities are rendered between their last two ticks. */
		double frameTime(float tickProgress) {
			if (!this.running) {
				return this.path.pathTime(this.clock);
			}
			return this.path.pathTime(Math.max(0.0, this.clock - 1 + tickProgress));
		}
	}

	public static final class CameraPlayback extends Clocked {
		public final int rigId;
		/** The camera control block this came from, or null. */
		public final net.minecraft.util.math.BlockPos source;
		public boolean paused;
		boolean restoreHud;
		private int cachedFrameClock = Integer.MIN_VALUE;
		private float cachedFrameProgress = Float.NaN;
		private PathEvaluator.Pose cachedPose;

		CameraPlayback(int rigId, CameraPath path, PathEvaluator evaluator, int elapsed, boolean running, boolean paused,
					   net.minecraft.util.math.BlockPos source) {
			super(path, evaluator, elapsed, running);
			this.rigId = rigId;
			this.paused = paused;
			this.source = source;
		}

		public CameraPath path() {
			return this.path;
		}

		/** The path tick the view is at (exact while stopped). */
		public double pathTime() {
			return this.path.pathTime(this.clock);
		}

		/** The camera pose for this frame (cached - the camera and the FOV both ask for it). */
		public PathEvaluator.Pose pose(float tickProgress) {
			if (this.cachedPose == null || this.cachedFrameClock != this.clock || this.cachedFrameProgress != tickProgress) {
				this.cachedPose = this.evaluator.evaluate(this.frameTime(tickProgress));
				this.cachedFrameClock = this.clock;
				this.cachedFrameProgress = tickProgress;
			}
			return this.cachedPose;
		}

		public boolean isRunning() {
			return this.running;
		}
	}

	/** One vehicle on a path, and its client-side ExternalMotionController. */
	static final class VehiclePlayback extends Clocked implements AbstractVehicleEntity.ExternalMotionController, ActionChannels.Source {
		final int entityId;
		AbstractVehicleEntity installedOn;
		int missingTicks;

		VehiclePlayback(int entityId, CameraPath path, PathEvaluator evaluator, int elapsed, boolean running) {
			super(path, evaluator, elapsed, running);
			this.entityId = entityId;
		}

		private double channelsTime = Double.NaN;
		private ActionChannels channels = ActionChannels.NONE;

		/** Throttle, steering and aim from the path at this frame (sub-tick, so turrets and wheels move smoothly). */
		@Override
		public ActionChannels camerapathaddon$channels(float tickProgress) {
			double t = this.frameTime(tickProgress);
			if (t != this.channelsTime) {
				this.channelsTime = t;
				this.channels = ActionChannels.at(this.path, t);
			}
			return this.channels;
		}

		void install(AbstractVehicleEntity vehicle) {
			this.missingTicks = 0;
			if (this.installedOn == vehicle && vehicle.tudursvehiclemod$getExternalMotionController() == this) {
				return;
			}
			this.installedOn = vehicle;
			vehicle.tudursvehiclemod$setExternalMotionController(this);
		}

		void uninstall() {
			if (this.installedOn != null && this.installedOn.tudursvehiclemod$getExternalMotionController() == this) {
				this.installedOn.tudursvehiclemod$setExternalMotionController(null);
			}
			this.installedOn = null;
		}

		@Override
		public boolean tudursvehiclemod$tickMotion(AbstractVehicleEntity vehicle) {
			double t = this.path.pathTime(this.clock);
			PathEvaluator.Pose pose = this.evaluator.evaluate(t);
			vehicle.tudursvehiclemod$applyExternalPose(new Vec3d(pose.x(), pose.y(), pose.z()), pose.rotation());
			return true;
		}
	}
}
