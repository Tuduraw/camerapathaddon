package com.example.camerapathaddon.path;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.util.StringIdentifiable;


/**
 * Per-path settings, stored in the camera control block alongside the keyframes.
 *
 * @param rotationMode    how rotations are interpolated between keys
 * @param endAction       what an unmanned vehicle does when the path ends (camera playback always returns the player)
 * @param loop            restart from the first key when the last one is reached
 * @param chunkAhead      chunks kept loaded ahead of the current position, counted along the path
 * @param chunkWidth      extra chunks loaded on each side of the path (0 = only the chunks the path passes through)
 * @param chunkBehind     chunks kept loaded behind the current position before they are released
 * @param waitForLoad     hold at the first key until the chunks ahead of it are loaded
 * @param preloadWhole    load every chunk along the whole path before starting, and keep them until the end
 * @param prioritizeAhead send the player the chunks ahead along the path first, instead of nearest-first
 * @param keySpacing      tick gap of a duplicated key
 * @param dummyPilot      seat a display-only dummy pilot in an empty pilot seat during vehicle playback
 * @param dummyPilotSkin  skin id for that pilot ("" = default)
 * @param redstoneTrigger a rising redstone signal at the block starts vehicle playback
 */
public record PathSettings(RotationMode rotationMode, EndAction endAction, boolean loop,
						   int chunkAhead, int chunkWidth, int chunkBehind,
						   boolean waitForLoad, boolean preloadWhole, boolean prioritizeAhead,
						   int keySpacing,
						   boolean dummyPilot, String dummyPilotSkin, boolean redstoneTrigger) {

	public static final PathSettings DEFAULT = new PathSettings(RotationMode.EULER, EndAction.HOLD, false,
			8, 2, 2, true, false, true, 40, false, "", false);

	public static final int MAX_CHUNK_AHEAD = 64;
	public static final int MAX_CHUNK_WIDTH = 8;
	public static final int MAX_CHUNK_BEHIND = 16;
	public static final int MAX_INTERVAL = 20 * 60;

	public static final Codec<PathSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			RotationMode.CODEC.optionalFieldOf("rotation_mode", DEFAULT.rotationMode).forGetter(PathSettings::rotationMode),
			EndAction.CODEC.optionalFieldOf("end_action", DEFAULT.endAction).forGetter(PathSettings::endAction),
			Codec.BOOL.optionalFieldOf("loop", DEFAULT.loop).forGetter(PathSettings::loop),
			Codec.INT.optionalFieldOf("chunk_ahead", DEFAULT.chunkAhead).forGetter(PathSettings::chunkAhead),
			Codec.INT.optionalFieldOf("chunk_width", DEFAULT.chunkWidth).forGetter(PathSettings::chunkWidth),
			Codec.INT.optionalFieldOf("chunk_behind", DEFAULT.chunkBehind).forGetter(PathSettings::chunkBehind),
			Codec.BOOL.optionalFieldOf("wait_for_load", DEFAULT.waitForLoad).forGetter(PathSettings::waitForLoad),
			Codec.BOOL.optionalFieldOf("preload_whole", DEFAULT.preloadWhole).forGetter(PathSettings::preloadWhole),
			Codec.BOOL.optionalFieldOf("prioritize_ahead", DEFAULT.prioritizeAhead).forGetter(PathSettings::prioritizeAhead),
			Codec.INT.optionalFieldOf("key_spacing", DEFAULT.keySpacing).forGetter(PathSettings::keySpacing),
			Codec.BOOL.optionalFieldOf("dummy_pilot", DEFAULT.dummyPilot).forGetter(PathSettings::dummyPilot),
			Codec.STRING.optionalFieldOf("dummy_pilot_skin", DEFAULT.dummyPilotSkin).forGetter(PathSettings::dummyPilotSkin),
			Codec.BOOL.optionalFieldOf("redstone_trigger", DEFAULT.redstoneTrigger).forGetter(PathSettings::redstoneTrigger)
	).apply(instance, PathSettings::new));

	public static final PacketCodec<RegistryByteBuf, PathSettings> PACKET_CODEC = new PacketCodec<>() {
		@Override
		public PathSettings decode(RegistryByteBuf buf) {
			RotationMode rotationMode = RotationMode.byOrdinal(buf.readByte());
			EndAction endAction = EndAction.byOrdinal(buf.readByte());
			boolean loop = buf.readBoolean();
			int ahead = buf.readVarInt();
			int width = buf.readVarInt();
			int behind = buf.readVarInt();
			boolean wait = buf.readBoolean();
			boolean preload = buf.readBoolean();
			boolean prioritize = buf.readBoolean();
			int keySpacing = buf.readVarInt();
			boolean pilot = buf.readBoolean();
			String skin = buf.readString(64);
			boolean redstone = buf.readBoolean();
			return new PathSettings(rotationMode, endAction, loop, ahead, width, behind, wait, preload, prioritize,
					keySpacing, pilot, skin, redstone).sanitized();
		}

		@Override
		public void encode(RegistryByteBuf buf, PathSettings s) {
			buf.writeByte(s.rotationMode.ordinal());
			buf.writeByte(s.endAction.ordinal());
			buf.writeBoolean(s.loop);
			buf.writeVarInt(s.chunkAhead);
			buf.writeVarInt(s.chunkWidth);
			buf.writeVarInt(s.chunkBehind);
			buf.writeBoolean(s.waitForLoad);
			buf.writeBoolean(s.preloadWhole);
			buf.writeBoolean(s.prioritizeAhead);
			buf.writeVarInt(s.keySpacing);
			buf.writeBoolean(s.dummyPilot);
			buf.writeString(s.dummyPilotSkin.length() > 64 ? s.dummyPilotSkin.substring(0, 64) : s.dummyPilotSkin, 64);
			buf.writeBoolean(s.redstoneTrigger);
		}
	};

	public PathSettings sanitized() {
		return new PathSettings(
				this.rotationMode != null ? this.rotationMode : RotationMode.EULER,
				this.endAction != null ? this.endAction : EndAction.HOLD,
				this.loop,
				clamp(this.chunkAhead, 0, MAX_CHUNK_AHEAD),
				clamp(this.chunkWidth, 0, MAX_CHUNK_WIDTH),
				clamp(this.chunkBehind, 0, MAX_CHUNK_BEHIND),
				this.waitForLoad, this.preloadWhole, this.prioritizeAhead,
				clamp(this.keySpacing, 1, MAX_INTERVAL),
				this.dummyPilot,
				this.dummyPilotSkin == null ? "" : (this.dummyPilotSkin.length() > 64 ? this.dummyPilotSkin.substring(0, 64) : this.dummyPilotSkin),
				this.redstoneTrigger);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	/** How rotations are interpolated between keys. */
	public enum RotationMode implements StringIdentifiable {
		/** Each angle is interpolated on its own (Blender's default "XYZ Euler"). Turns of more than 180 degrees between two keys - and full spins, by typing e.g. yaw 0 then 720 - are possible. */
		EULER("euler"),
		/** Whole orientations are blended along the shortest arc (spherical interpolation). Free of gimbal effects near straight up/down and when rolling while pitched; a turn between two keys never exceeds 180 degrees. */
		QUATERNION("quaternion");

		public static final Codec<RotationMode> CODEC = StringIdentifiable.createCodec(RotationMode::values);
		private final String id;

		RotationMode(String id) {
			this.id = id;
		}

		@Override
		public String asString() {
			return this.id;
		}

		public static RotationMode byOrdinal(int ordinal) {
			RotationMode[] values = values();
			return ordinal >= 0 && ordinal < values.length ? values[ordinal] : EULER;
		}
	}

	/** What an unmanned vehicle does once its path has ended. */
	public enum EndAction implements StringIdentifiable {
		/** Stays exactly at the last key until playback is stopped. */
		HOLD("hold"),
		/** Hands control straight back to the vehicle's own physics (an aircraft will glide or fall from there). */
		RELEASE("release");

		public static final Codec<EndAction> CODEC = StringIdentifiable.createCodec(EndAction::values);
		private final String id;

		EndAction(String id) {
			this.id = id;
		}

		@Override
		public String asString() {
			return this.id;
		}

		public static EndAction byOrdinal(int ordinal) {
			EndAction[] values = values();
			return ordinal >= 0 && ordinal < values.length ? values[ordinal] : HOLD;
		}
	}
}
