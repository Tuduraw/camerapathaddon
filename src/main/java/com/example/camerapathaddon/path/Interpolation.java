package com.example.camerapathaddon.path;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringIdentifiable;

/** How the segment that STARTS at a keyframe is interpolated up to the next keyframe (Blender's convention: the mode belongs to the left key of the segment). */
public enum Interpolation implements StringIdentifiable {
	/** Holds this keyframe's values until the next keyframe, then jumps. */
	CONSTANT("constant"),
	/** Straight line at constant speed. */
	LINEAR("linear"),
	/** Smooth curve (cubic Hermite with Blender-like "auto clamped" tangents): eases in/out at the ends of the path and wherever a channel turns around, passes through intermediate keys without a speed kink. */
	BEZIER("bezier");

	public static final Codec<Interpolation> CODEC = StringIdentifiable.createCodec(Interpolation::values);

	private final String id;

	Interpolation(String id) {
		this.id = id;
	}

	@Override
	public String asString() {
		return this.id;
	}

	public Interpolation next() {
		Interpolation[] values = values();
		return values[(this.ordinal() + 1) % values.length];
	}

	public static Interpolation byOrdinal(int ordinal) {
		Interpolation[] values = values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : BEZIER;
	}
}
