package com.example.camerapathaddon.server;

import com.example.camerapathaddon.path.CameraPath;
import com.example.camerapathaddon.path.Keyframe;
import com.example.camerapathaddon.path.VehicleAction;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import com.example.tudursvehiclemod.entity.VtolEntity;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Carries out the keyframes' {@link VehicleAction}s on a vehicle on a path, server side, through the same public
 * tudursvehiclemod methods its unmanned features use - so no player has to be on board. Things a vehicle doesn't
 * have (no gear, no search light, a weapon index past its last weapon) are simply ignored by those methods.
 */
final class VehicleActionRunner {

	private VehicleActionRunner() {
	}

	/**
	 * One tick at path time t. previous = path time of the previous tick (NaN on the first). Events of keyframes
	 * reached in (previous, t] fire once; HOLD fire of the keyframe whose segment contains t fires every tick.
	 * advancing = the clock moved this tick (no events while paused, but states are still kept).
	 */
	static void tick(AbstractVehicleEntity vehicle, CameraPath path, double previous, double t, boolean advancing) {
		List<Keyframe> keys = path.keys();
		if (keys.isEmpty()) {
			return;
		}
		// States: the latest keyframe at or before t that sets each type decides it.
		Map<VehicleAction.Type, VehicleAction> states = new EnumMap<>(VehicleAction.Type.class);
		int segment = -1;
		for (int i = 0; i < keys.size() && keys.get(i).tick() <= t; i++) {
			segment = i;
			for (VehicleAction action : keys.get(i).actions()) {
				if (action.type().isState()) {
					states.put(action.type(), action);
				}
			}
		}
		for (VehicleAction action : states.values()) {
			applyState(vehicle, action);
		}
		if (!advancing) {
			return;
		}
		// Events of keyframes passed this tick (across the loop point too).
		for (Keyframe key : keys) {
			if (reached(key.tick(), previous, t, path)) {
				for (VehicleAction action : key.actions()) {
					if (action.type() == VehicleAction.Type.FLARES) {
						vehicle.tudursvehiclemod$deployFlares();
					} else if (action.type() == VehicleAction.Type.FIRE_WEAPON && action.mode() == VehicleAction.Mode.ONCE) {
						vehicle.tryFireWeapon(action.value(), null);
					}
				}
			}
		}
		if (segment >= 0) {
			for (VehicleAction action : keys.get(segment).actions()) {
				if (action.type() == VehicleAction.Type.FIRE_WEAPON && action.mode() == VehicleAction.Mode.HOLD) {
					// The weapon's own fire rate, heat and ammo decide whether it actually fires this tick.
					vehicle.tryFireWeapon(action.value(), null);
				}
			}
		}
	}

	private static boolean reached(int tick, double previous, double t, CameraPath path) {
		if (Double.isNaN(previous)) {
			return tick == Math.floor(t) || (tick <= t && tick > t - 1);
		}
		if (t >= previous) {
			return tick > previous && tick <= t;
		}
		// Looped back to the start this tick.
		return tick > previous || (tick >= path.firstTick() && tick <= t);
	}

	private static void applyState(AbstractVehicleEntity vehicle, VehicleAction action) {
		boolean on = action.value() != 0;
		switch (action.type()) {
			case GEAR -> {
				if (vehicle.isGearDeployed() != on) {
					vehicle.tudursvehiclemod$setGearDeployed(on);
				}
			}
			case HATCH -> {
				// Hatch and canopy together, like the player's hatch key.
				if (vehicle.isHatchOpen() != on || vehicle.isCanopyOpen() != on) {
					vehicle.setHatchOpen(on);
					vehicle.setCanopyOpen(on);
				}
			}
			case WING_FOLD -> {
				if (vehicle.isWingFoldOpen() != on) {
					vehicle.tudursvehiclemod$forceWingFoldOpen(on);
				}
			}
			case SEARCH_LIGHT -> {
				if (vehicle.tudursvehiclemod$isSearchLightOn() != on && !vehicle.getDefinition().searchLightParts().isEmpty()) {
					vehicle.tudursvehiclemod$toggleSearchLight();
				}
			}
			case NAV_LIGHTS -> {
				if (vehicle.tudursvehiclemod$areNavLightsOn() != on && !vehicle.getDefinition().navLightParts().isEmpty()) {
					vehicle.tudursvehiclemod$toggleNavLights();
				}
			}
			case VTOL_MODE -> {
				// The VTOL refuses to switch while banked or already switching - asked again every tick until it does.
				if (vehicle instanceof VtolEntity vtol && !vtol.isTransitioning() && vtol.isHelicopterMode() != on) {
					vtol.tudursvehiclemod$tryToggleVtolMode();
				}
			}
			case SELECT_WEAPON -> {
				if (vehicle.tudursvehiclemod$getSelectedWeaponIndex() != action.value()
						&& action.value() < vehicle.getDefinition().weapons().size()) {
					vehicle.tudursvehiclemod$setSelectedWeaponIndex(action.value());
				}
			}
			default -> {
			}
		}
	}
}
