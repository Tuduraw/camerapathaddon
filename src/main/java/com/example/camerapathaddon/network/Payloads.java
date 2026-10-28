package com.example.camerapathaddon.network;

import com.example.camerapathaddon.CameraPathAddon;
import com.example.camerapathaddon.path.CameraPath;
import com.example.camerapathaddon.path.Keyframe;
import com.example.camerapathaddon.path.PathSettings;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * Every packet of this addon.
 *
 * <p>Editing is sent as small operations on a camera control block (the server applies them and the block entity
 * update brings the result back) rather than as whole paths, which could outgrow a client-to-server packet.
 */
public final class Payloads {

	private Payloads() {
	}

	private static <T extends CustomPayload> CustomPayload.Id<T> id(String path) {
		return new CustomPayload.Id<>(CameraPathAddon.id(path));
	}

	// ---- client -> server (all aimed at one camera control block) ----

	/** Replaces key `index` of the block's path (or appends when index is -1 / past the end). */
	public record EditKey(BlockPos pos, int index, Keyframe key) implements CustomPayload {
		public static final Id<EditKey> ID = id("edit_key");
		public static final PacketCodec<RegistryByteBuf, EditKey> CODEC = PacketCodec.tuple(
				BlockPos.PACKET_CODEC, EditKey::pos,
				PacketCodecs.VAR_INT, EditKey::index,
				Keyframe.PACKET_CODEC.cast(), EditKey::key,
				EditKey::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** Replaces the block's path settings and its name. */
	public record EditSettings(BlockPos pos, PathSettings settings, String name) implements CustomPayload {
		public static final Id<EditSettings> ID = id("edit_settings");
		public static final PacketCodec<RegistryByteBuf, EditSettings> CODEC = PacketCodec.tuple(
				BlockPos.PACKET_CODEC, EditSettings::pos,
				PathSettings.PACKET_CODEC, EditSettings::settings,
				PacketCodecs.string(64), EditSettings::name,
				EditSettings::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** Every other operation on a block, done server-side. */
	public record BlockAction(BlockPos pos, Action action, int index, double value) implements CustomPayload {
		public static final Id<BlockAction> ID = id("block_action");
		public static final PacketCodec<RegistryByteBuf, BlockAction> CODEC = PacketCodec.tuple(
				BlockPos.PACKET_CODEC, BlockAction::pos,
				PacketCodecs.indexed(i -> Action.values()[Math.floorMod(i, Action.values().length)], Action::ordinal), BlockAction::action,
				PacketCodecs.VAR_INT, BlockAction::index,
				PacketCodecs.DOUBLE, BlockAction::value,
				BlockAction::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public enum Action {
		REMOVE_KEY,
		/** Copies key `index` keySpacing ticks after itself (later keys move back to make room). */
		DUPLICATE_KEY,
		/** Moves key `index` and every later key by `value` ticks. */
		SHIFT_FROM,
		/** Scales the whole timing by `value`. */
		SCALE_TIME,
		/** Rebuilds the keys from the route book in the block, at `value` blocks per second. */
		IMPORT_ROUTE,
		CLEAR_KEYS,
		TOGGLE_PREVIEW,
		/** Opens the block's item slots (route book, drone control stick). */
		OPEN_SLOTS,
		/** Puts the requesting player's view on the path, from tick `index` (-1 = from the first key). */
		PLAY_CAMERA,
		/** Starts the path on the vehicle registered to the stick in the block, from tick `index` (-1 = from the first key). */
		PLAY_VEHICLE,
		/** Stops every playback this block started. */
		STOP,
		/** Puts the requesting player's view on the path, paused at tick `index` - the editor's "view check". */
		INSPECT,
		/** Moves the requesting player's paused view (from this block) to tick `index`. */
		INSPECT_TIME,
		/** Resumes the requesting player's paused view (from this block) from where it is. */
		RESUME_CAMERA,
		/** Ends the requesting player's view playback from this block (back to where they started). */
		END_CAMERA,
		/** Pauses every playback this block started, or resumes them when all are paused. */
		TOGGLE_PAUSE
	}

	/** The pause key: pauses / resumes the playback the player is in (their view, or the vehicle they ride). */
	public record TogglePause() implements CustomPayload {
		public static final Id<TogglePause> ID = id("toggle_pause");
		public static final PacketCodec<RegistryByteBuf, TogglePause> CODEC = PacketCodec.unit(new TogglePause());

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	// ---- server -> client ----

	/** Opens the editor of the camera control block at pos. */
	public record OpenEditor(BlockPos pos) implements CustomPayload {
		public static final Id<OpenEditor> ID = id("open_editor");
		public static final PacketCodec<RegistryByteBuf, OpenEditor> CODEC = PacketCodec.tuple(BlockPos.PACKET_CODEC, OpenEditor::pos, OpenEditor::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/**
	 * The player's own view is now on a path (or its state changed).
	 *
	 * @param rigId   the rig they ride
	 * @param path    the path
	 * @param elapsed playback time on the server when this was sent
	 * @param running whether the clock is advancing (false while waiting for chunks, and while paused)
	 * @param paused  paused (by the pause key, a block's pause button, or a view check in the editor)
	 * @param source  the camera control block it was started from, if any
	 */
	public record CameraStart(int rigId, CameraPath path, int elapsed, boolean running, boolean paused, Optional<BlockPos> source) implements CustomPayload {
		public static final Id<CameraStart> ID = id("camera_start");
		public static final PacketCodec<RegistryByteBuf, CameraStart> CODEC = PacketCodec.tuple(
				PacketCodecs.VAR_INT, CameraStart::rigId,
				CameraPath.PACKET_CODEC, CameraStart::path,
				PacketCodecs.VAR_INT, CameraStart::elapsed,
				PacketCodecs.BOOLEAN, CameraStart::running,
				PacketCodecs.BOOLEAN, CameraStart::paused,
				PacketCodecs.optional(BlockPos.PACKET_CODEC), CameraStart::source,
				CameraStart::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public record CameraEnd() implements CustomPayload {
		public static final Id<CameraEnd> ID = id("camera_end");
		public static final PacketCodec<RegistryByteBuf, CameraEnd> CODEC = PacketCodec.unit(new CameraEnd());

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** A vehicle in view is on a path (sent at start, and to anyone who starts tracking it later). */
	public record VehicleStart(int entityId, CameraPath path, int elapsed, boolean running) implements CustomPayload {
		public static final Id<VehicleStart> ID = id("vehicle_start");
		public static final PacketCodec<RegistryByteBuf, VehicleStart> CODEC = PacketCodec.tuple(
				PacketCodecs.VAR_INT, VehicleStart::entityId,
				CameraPath.PACKET_CODEC, VehicleStart::path,
				PacketCodecs.VAR_INT, VehicleStart::elapsed,
				PacketCodecs.BOOLEAN, VehicleStart::running,
				VehicleStart::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public record VehicleEnd(int entityId) implements CustomPayload {
		public static final Id<VehicleEnd> ID = id("vehicle_end");
		public static final PacketCodec<RegistryByteBuf, VehicleEnd> CODEC = PacketCodec.tuple(PacketCodecs.VAR_INT, VehicleEnd::entityId, VehicleEnd::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/**
	 * Keeps a client's playback clock in step with the server (sent when a playback starts running, is paused, resumed
	 * or moved, and every second). running = the clock is advancing.
	 *
	 * @param entityId the rig (camera playback) or the vehicle
	 */
	public record Clock(int entityId, int elapsed, boolean running, boolean paused) implements CustomPayload {
		public static final Id<Clock> ID = id("clock");
		public static final PacketCodec<RegistryByteBuf, Clock> CODEC = PacketCodec.tuple(
				PacketCodecs.VAR_INT, Clock::entityId,
				PacketCodecs.VAR_INT, Clock::elapsed,
				PacketCodecs.BOOLEAN, Clock::running,
				PacketCodecs.BOOLEAN, Clock::paused,
				Clock::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}
}
