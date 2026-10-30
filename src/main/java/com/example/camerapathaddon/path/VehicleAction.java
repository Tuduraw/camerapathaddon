package com.example.camerapathaddon.path;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.util.StringIdentifiable;

import java.util.ArrayList;
import java.util.List;

/**
 * One vehicle operation done during vehicle playback - the same effect the vehicle's own key would have, called
 * directly on the vehicle (server side) so it needs no player on board: the same public tudursvehiclemod methods
 * its unmanned features (drone autopilot, Fire Support Center, Carrier) already use.
 *
 * <p><b>State</b> types (gear, hatch, wing fold, lights, VTOL mode, selected weapon) set a state: from this keyframe
 * on, the vehicle is kept in that state until a later keyframe sets the same type differently - so the state at any
 * moment follows from the timeline alone, wherever playback starts or loops. {@code value} is 1 (deployed / open /
 * on / vertical mode) or 0, or the weapon index for SELECT_WEAPON.
 *
 * <p><b>Continuous</b> types (throttle, steering, aim) give a value that changes linearly from this keyframe to the
 * next keyframe that sets the same type (for aim: of the same seat), and is held after the last one. They don't
 * move the vehicle (the path does): they drive the parts that follow the controls - propellers and rotors
 * (THROTTLE, {@code x} = -1..1), steering wheels and steered wheels (STEERING, {@code x} = -1 left .. 1 right) - and
 * the turrets of seat {@code seat} (AIM: {@code x} = yaw relative to the vehicle, {@code y} = pitch, degrees,
 * 0/0 = straight ahead; positive pitch looks down, as in Minecraft).
 *
 * <p><b>Event</b> types: FIRE_WEAPON fires weapon {@code value}, once when the keyframe is reached (ONCE) or every
 * tick until the next keyframe (HOLD - the weapon's own fire rate, heat and ammo still apply); FLARES deploys flares
 * once.
 */
public record VehicleAction(Type type, int value, Mode mode, int seat, float x, float y) {

	public VehicleAction(Type type, int value, Mode mode) {
		this(type, value, mode, 0, 0f, 0f);
	}

	public static final int MAX_PER_KEYFRAME = 16;

	public enum Type implements StringIdentifiable {
		GEAR("gear", true),
		HATCH("hatch", true),
		WING_FOLD("wing_fold", true),
		SEARCH_LIGHT("search_light", true),
		NAV_LIGHTS("nav_lights", true),
		VTOL_MODE("vtol_mode", true),
		SELECT_WEAPON("select_weapon", true),
		FIRE_WEAPON("fire_weapon", false),
		FLARES("flares", false),
		THROTTLE("throttle", false),
		STEERING("steering", false),
		AIM("aim", false);

		public static final Codec<Type> CODEC = StringIdentifiable.createCodec(Type::values);
		private final String id;
		private final boolean state;

		Type(String id, boolean state) {
			this.id = id;
			this.state = state;
		}

		@Override
		public String asString() {
			return this.id;
		}

		/** A state type (kept until changed) rather than an event. */
		public boolean isState() {
			return this.state;
		}

		/** A continuous value interpolated between keyframes. */
		public boolean isContinuous() {
			return this == THROTTLE || this == STEERING || this == AIM;
		}

		/** Belongs to one seat (its weapons and aim) rather than to the vehicle's pilot controls. */
		public boolean isSeatAction() {
			return this == FIRE_WEAPON || this == AIM;
		}

		/** Takes a weapon index as its value. */
		public boolean usesWeapon() {
			return this == SELECT_WEAPON || this == FIRE_WEAPON;
		}

		public Type next() {
			return values()[(this.ordinal() + 1) % values().length];
		}
	}

	public enum Mode implements StringIdentifiable {
		ONCE("once"),
		HOLD("hold");

		public static final Codec<Mode> CODEC = StringIdentifiable.createCodec(Mode::values);
		private final String id;

		Mode(String id) {
			this.id = id;
		}

		@Override
		public String asString() {
			return this.id;
		}

		public Mode next() {
			return this == ONCE ? HOLD : ONCE;
		}
	}

	public static final Codec<VehicleAction> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Type.CODEC.fieldOf("type").forGetter(VehicleAction::type),
			Codec.INT.optionalFieldOf("value", 1).forGetter(VehicleAction::value),
			Mode.CODEC.optionalFieldOf("mode", Mode.ONCE).forGetter(VehicleAction::mode),
			Codec.INT.optionalFieldOf("seat", 0).forGetter(VehicleAction::seat),
			Codec.FLOAT.optionalFieldOf("x", 0f).forGetter(VehicleAction::x),
			Codec.FLOAT.optionalFieldOf("y", 0f).forGetter(VehicleAction::y)
	).apply(instance, VehicleAction::new));

	public static final PacketCodec<ByteBuf, VehicleAction> PACKET_CODEC = PacketCodec.tuple(
			PacketCodecs.indexed(i -> Type.values()[Math.floorMod(i, Type.values().length)], Type::ordinal), VehicleAction::type,
			PacketCodecs.VAR_INT, VehicleAction::value,
			PacketCodecs.indexed(i -> Mode.values()[Math.floorMod(i, Mode.values().length)], Mode::ordinal), VehicleAction::mode,
			PacketCodecs.VAR_INT, VehicleAction::seat,
			PacketCodecs.FLOAT, VehicleAction::x,
			PacketCodecs.FLOAT, VehicleAction::y,
			VehicleAction::new);

	/** A new action of type for seat (seat actions) with its default setting. */
	public static VehicleAction create(Type type, int seat) {
		return new VehicleAction(type, type.usesWeapon() ? 0 : 1, Mode.ONCE, type.isSeatAction() ? seat : 0, 0f, 0f);
	}

	public VehicleAction withType(Type newType) {
		return create(newType, this.seat);
	}

	public VehicleAction withValue(int newValue) {
		return new VehicleAction(this.type, newValue, this.mode, this.seat, this.x, this.y);
	}

	public VehicleAction withMode(Mode newMode) {
		return new VehicleAction(this.type, this.value, newMode, this.seat, this.x, this.y);
	}

	public VehicleAction withXY(float newX, float newY) {
		return new VehicleAction(this.type, this.value, this.mode, this.seat, newX, newY);
	}

	/** Fixes out-of-range values and keeps at most MAX_PER_KEYFRAME, one per state type - these lists come from clients. */
	public static List<VehicleAction> sanitize(List<VehicleAction> actions) {
		if (actions == null || actions.isEmpty()) {
			return List.of();
		}
		List<VehicleAction> result = new ArrayList<>();
		for (VehicleAction action : actions) {
			if (action == null || action.type == null || result.size() >= MAX_PER_KEYFRAME) {
				continue;
			}
			int seat = action.type.isSeatAction() ? Math.max(0, Math.min(63, action.seat)) : 0;
			if ((action.type.isState() || action.type.isContinuous())
					&& result.stream().anyMatch(other -> other.type == action.type && other.seat == seat)) {
				continue;
			}
			int value = action.type.usesWeapon() ? Math.max(0, Math.min(255, action.value)) : (action.value != 0 ? 1 : 0);
			float x = finite(action.x);
			float y = finite(action.y);
			if (action.type == Type.THROTTLE || action.type == Type.STEERING) {
				x = Math.max(-1f, Math.min(1f, x));
				y = 0f;
			} else if (action.type == Type.AIM) {
				x = Math.max(-3600f, Math.min(3600f, x));
				y = Math.max(-90f, Math.min(90f, y));
			} else {
				x = 0f;
				y = 0f;
			}
			result.add(new VehicleAction(action.type, value, action.mode == null ? Mode.ONCE : action.mode, seat, x, y));
		}
		return List.copyOf(result);
	}

	private static float finite(float value) {
		return Float.isFinite(value) ? value : 0f;
	}
}
