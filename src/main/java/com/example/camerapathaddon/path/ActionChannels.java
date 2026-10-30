package com.example.camerapathaddon.path;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The continuous {@link VehicleAction}s (throttle, steering, per-seat aim) of a path at one moment - evaluated the
 * same way on the server and on every client from the path itself, so parts follow them smoothly with nothing extra
 * sent over the network. A value is linear between the keyframe that set it last and the next keyframe that sets it
 * again, held after the last one, and absent (the vehicle's own value is used) before the first.
 */
public record ActionChannels(Float throttle, Float steering, Map<Integer, float[]> aims) {

	public static final ActionChannels NONE = new ActionChannels(null, null, Map.of());

	/** Aim {yaw relative to the vehicle, pitch} of seat, or null. */
	public float[] aim(int seat) {
		return this.aims.get(seat);
	}

	public static ActionChannels at(CameraPath path, double t) {
		List<Keyframe> keys = path.keys();
		if (keys.isEmpty()) {
			return NONE;
		}
		boolean any = false;
		for (Keyframe key : keys) {
			for (VehicleAction action : key.actions()) {
				if (action.type().isContinuous()) {
					any = true;
					break;
				}
			}
		}
		if (!any) {
			return NONE;
		}
		float[] throttle = channel(keys, t, VehicleAction.Type.THROTTLE, 0);
		float[] steering = channel(keys, t, VehicleAction.Type.STEERING, 0);
		Map<Integer, float[]> aims = new HashMap<>();
		for (Keyframe key : keys) {
			for (VehicleAction action : key.actions()) {
				if (action.type() == VehicleAction.Type.AIM && !aims.containsKey(action.seat())) {
					float[] aim = channel(keys, t, VehicleAction.Type.AIM, action.seat());
					if (aim != null) {
						aims.put(action.seat(), aim);
					}
				}
			}
		}
		return new ActionChannels(throttle != null ? throttle[0] : null, steering != null ? steering[0] : null, aims);
	}

	/** {x, y} of type (for seat) at t, or null before the first keyframe that sets it. */
	private static float[] channel(List<Keyframe> keys, double t, VehicleAction.Type type, int seat) {
		VehicleAction before = null;
		int beforeTick = 0;
		VehicleAction after = null;
		int afterTick = 0;
		for (Keyframe key : keys) {
			VehicleAction found = null;
			for (VehicleAction action : key.actions()) {
				if (action.type() == type && (type != VehicleAction.Type.AIM || action.seat() == seat)) {
					found = action;
					break;
				}
			}
			if (found == null) {
				continue;
			}
			if (key.tick() <= t) {
				before = found;
				beforeTick = key.tick();
			} else {
				after = found;
				afterTick = key.tick();
				break;
			}
		}
		if (before == null) {
			return null;
		}
		if (after == null || afterTick <= beforeTick) {
			return new float[]{before.x(), before.y()};
		}
		float f = (float) ((t - beforeTick) / (afterTick - beforeTick));
		return new float[]{before.x() + (after.x() - before.x()) * f, before.y() + (after.y() - before.y()) * f};
	}

	/** Something that can tell what the continuous actions of the vehicle it drives are right now - the vehicle playbacks, server and client side (their ExternalMotionController). */
	public interface Source {
		ActionChannels camerapathaddon$channels(float tickProgress);
	}
}
