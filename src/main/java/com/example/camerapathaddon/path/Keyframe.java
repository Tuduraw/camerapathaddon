package com.example.camerapathaddon.path;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

import java.util.List;

/**
 * One point of a camera path.
 *
 * <p>Angles follow Minecraft's own conventions so a value read off the F3 screen can be typed in as-is:
 * {@code rotY} is the yaw (0 = facing south/+Z, 90 = west), {@code rotX} the pitch (positive looks down),
 * {@code rotZ} the roll. They are combined in the order Y, X, Z - the same order tudursvehiclemod's aircraft
 * use for their own attitude quaternion - so a path drives a camera and a vehicle identically.
 *
 * @param tick  frame (game tick) at which this point is reached, counted from the start of the path
 * @param fov    field of view in degrees, or 0 to keep the player's own setting
 * @param actions vehicle operations (parts, lights, weapons) done during vehicle playback - see {@link VehicleAction}; ignored by camera playback
 */
public record Keyframe(int tick, double x, double y, double z, float rotX, float rotY, float rotZ, float fov,
					   Interpolation interpolation, List<VehicleAction> actions) {

	public static final int MAX_TICK = 20 * 60 * 60 * 6;

	public Keyframe {
		actions = actions == null ? List.of() : List.copyOf(actions);
	}

	/** A keyframe without vehicle actions. */
	public Keyframe(int tick, double x, double y, double z, float rotX, float rotY, float rotZ, float fov, Interpolation interpolation) {
		this(tick, x, y, z, rotX, rotY, rotZ, fov, interpolation, List.of());
	}

	public static final Codec<Keyframe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("tick").forGetter(Keyframe::tick),
			Codec.DOUBLE.fieldOf("x").forGetter(Keyframe::x),
			Codec.DOUBLE.fieldOf("y").forGetter(Keyframe::y),
			Codec.DOUBLE.fieldOf("z").forGetter(Keyframe::z),
			Codec.FLOAT.optionalFieldOf("rot_x", 0f).forGetter(Keyframe::rotX),
			Codec.FLOAT.optionalFieldOf("rot_y", 0f).forGetter(Keyframe::rotY),
			Codec.FLOAT.optionalFieldOf("rot_z", 0f).forGetter(Keyframe::rotZ),
			Codec.FLOAT.optionalFieldOf("fov", 0f).forGetter(Keyframe::fov),
			Interpolation.CODEC.optionalFieldOf("interpolation", Interpolation.BEZIER).forGetter(Keyframe::interpolation),
			VehicleAction.CODEC.listOf().optionalFieldOf("actions", List.of()).forGetter(Keyframe::actions)
	).apply(instance, Keyframe::new));

	public static final PacketCodec<ByteBuf, Keyframe> PACKET_CODEC = new PacketCodec<>() {
		@Override
		public Keyframe decode(ByteBuf buf) {
			return new Keyframe(buf.readInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
					buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
					Interpolation.byOrdinal(buf.readByte()), ACTIONS_PACKET_CODEC.decode(buf)).sanitized();
		}

		@Override
		public void encode(ByteBuf buf, Keyframe key) {
			buf.writeInt(key.tick);
			buf.writeDouble(key.x);
			buf.writeDouble(key.y);
			buf.writeDouble(key.z);
			buf.writeFloat(key.rotX);
			buf.writeFloat(key.rotY);
			buf.writeFloat(key.rotZ);
			buf.writeFloat(key.fov);
			buf.writeByte(key.interpolation.ordinal());
			ACTIONS_PACKET_CODEC.encode(buf, key.actions);
		}
	};

	private static final PacketCodec<ByteBuf, List<VehicleAction>> ACTIONS_PACKET_CODEC =
			VehicleAction.PACKET_CODEC.collect(PacketCodecs.toList(VehicleAction.MAX_PER_KEYFRAME));

	/** Replaces anything non-finite or out of range - these values come from clients and end up saved on an item. */
	public Keyframe sanitized() {
		int safeTick = Math.max(0, Math.min(MAX_TICK, this.tick));
		double safeX = finiteOr(this.x, 0, 3.0e7);
		double safeY = finiteOr(this.y, 0, 1.0e5);
		double safeZ = finiteOr(this.z, 0, 3.0e7);
		float safeRotX = (float) finiteOr(this.rotX, 0, 1.0e5);
		float safeRotY = (float) finiteOr(this.rotY, 0, 1.0e5);
		float safeRotZ = (float) finiteOr(this.rotZ, 0, 1.0e5);
		float safeFov = (float) finiteOr(this.fov, 0, 170);
		if (safeFov < 0f || (safeFov > 0f && safeFov < 1f)) {
			safeFov = 0f;
		}
		Interpolation safeInterpolation = this.interpolation != null ? this.interpolation : Interpolation.BEZIER;
		return new Keyframe(safeTick, safeX, safeY, safeZ, safeRotX, safeRotY, safeRotZ, safeFov, safeInterpolation, VehicleAction.sanitize(this.actions));
	}

	private static double finiteOr(double value, double fallback, double limit) {
		if (!Double.isFinite(value)) {
			return fallback;
		}
		return Math.max(-limit, Math.min(limit, value));
	}

	public Keyframe withTick(int newTick) {
		return new Keyframe(newTick, this.x, this.y, this.z, this.rotX, this.rotY, this.rotZ, this.fov, this.interpolation, this.actions);
	}

	public Keyframe withPosition(double newX, double newY, double newZ) {
		return new Keyframe(this.tick, newX, newY, newZ, this.rotX, this.rotY, this.rotZ, this.fov, this.interpolation, this.actions);
	}

	public Keyframe withActions(List<VehicleAction> newActions) {
		return new Keyframe(this.tick, this.x, this.y, this.z, this.rotX, this.rotY, this.rotZ, this.fov, this.interpolation, newActions);
	}

	public Keyframe withRotation(float newRotX, float newRotY, float newRotZ) {
		return new Keyframe(this.tick, this.x, this.y, this.z, newRotX, newRotY, newRotZ, this.fov, this.interpolation, this.actions);
	}
}
