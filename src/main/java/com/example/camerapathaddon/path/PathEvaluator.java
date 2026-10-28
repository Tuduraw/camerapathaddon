package com.example.camerapathaddon.path;

import org.joml.Quaterniond;
import org.joml.Quaternionf;

import java.util.List;

/**
 * Evaluates a {@link CameraPath} at any (fractional) tick. Shared by the server (which moves the real entity)
 * and the client (which places the camera / the vehicle model every frame from the same formulas, so both
 * agree exactly and the picture is smooth between ticks).
 *
 * <p>Position and - in {@link PathSettings.RotationMode#EULER} mode - each angle are evaluated as their own
 * curve over time, like Blender's F-curves: the value passes exactly through every key, CONSTANT holds,
 * LINEAR is a straight line, BEZIER is a cubic Hermite curve whose tangents follow Blender's "auto clamped"
 * rule (flat at the first/last key and wherever the value turns around, otherwise a time-weighted average of
 * the neighbouring slopes). In QUATERNION mode the orientation is blended as a whole: LINEAR is slerp,
 * BEZIER is squad (a smooth spline through the key orientations), CONSTANT holds.
 */
public final class PathEvaluator {

	private static final int CHANNELS = 7;
	private static final int X = 0;
	private static final int Y = 1;
	private static final int Z = 2;
	private static final int ROT_X = 3;
	private static final int ROT_Y = 4;
	private static final int ROT_Z = 5;
	private static final int FOV = 6;

	private final int count;
	private final int[] ticks;
	private final Interpolation[] modes;
	/** values[key * CHANNELS + channel] */
	private final double[] values;
	/** Hermite slopes (per tick), same layout as values. */
	private final double[] slopes;
	private final boolean quaternionMode;
	private final Quaterniond[] keyRotations;
	private final Quaterniond[] squadControls;

	/**
	 * @param path       the path
	 * @param offsetX    added to every key's position
	 * @param defaultFov what a key with fov 0 ("keep the player's own setting") stands for
	 */
	public PathEvaluator(CameraPath path, double offsetX, double offsetY, double offsetZ, double defaultFov) {
		List<Keyframe> keys = path.keys();
		this.count = keys.size();
		this.ticks = new int[this.count];
		this.modes = new Interpolation[this.count];
		this.values = new double[this.count * CHANNELS];
		this.slopes = new double[this.count * CHANNELS];
		for (int i = 0; i < this.count; i++) {
			Keyframe key = keys.get(i);
			this.ticks[i] = key.tick();
			this.modes[i] = key.interpolation();
			int base = i * CHANNELS;
			this.values[base + X] = key.x() + offsetX;
			this.values[base + Y] = key.y() + offsetY;
			this.values[base + Z] = key.z() + offsetZ;
			this.values[base + ROT_X] = key.rotX();
			this.values[base + ROT_Y] = key.rotY();
			this.values[base + ROT_Z] = key.rotZ();
			this.values[base + FOV] = key.fov() > 0f ? key.fov() : defaultFov;
		}
		for (int channel = 0; channel < CHANNELS; channel++) {
			this.computeAutoClampedSlopes(channel);
		}

		this.quaternionMode = path.settings().rotationMode() == PathSettings.RotationMode.QUATERNION;
		if (this.quaternionMode && this.count > 0) {
			this.keyRotations = new Quaterniond[this.count];
			for (int i = 0; i < this.count; i++) {
				int base = i * CHANNELS;
				Quaterniond q = bodyRotation(this.values[base + ROT_Y], this.values[base + ROT_X], this.values[base + ROT_Z]);
				// Keep neighbours in the same hemisphere so slerp/squad take the short way round.
				if (i > 0 && this.keyRotations[i - 1].dot(q) < 0) {
					q.mul(-1.0);
				}
				this.keyRotations[i] = q;
			}
			this.squadControls = new Quaterniond[this.count];
			for (int i = 0; i < this.count; i++) {
				this.squadControls[i] = this.squadControl(i);
			}
		} else {
			this.keyRotations = null;
			this.squadControls = null;
		}
	}

	public boolean isEmpty() {
		return this.count == 0;
	}

	public int firstTick() {
		return this.count == 0 ? 0 : this.ticks[0];
	}

	public int lastTick() {
		return this.count == 0 ? 0 : this.ticks[this.count - 1];
	}

	public int keyCount() {
		return this.count;
	}

	public int keyTick(int index) {
		return this.ticks[index];
	}

	/** Pose at path tick t (clamped to the first/last key). */
	public Pose evaluate(double t) {
		if (this.count == 0) {
			return new Pose(0, 0, 0, 0f, 0f, 0f, new Quaternionf(), 70f);
		}
		int segment = this.findSegment(t);
		double[] out = new double[CHANNELS];
		if (segment < 0) {
			int key = t <= this.ticks[0] ? 0 : this.count - 1;
			System.arraycopy(this.values, key * CHANNELS, out, 0, CHANNELS);
			return this.toPose(out, key, key, 0.0);
		}
		int i0 = segment;
		int i1 = segment + 1;
		double h = this.ticks[i1] - this.ticks[i0];
		double u = h <= 0 ? 1.0 : clamp01((t - this.ticks[i0]) / h);
		Interpolation mode = this.modes[i0];
		for (int channel = 0; channel < CHANNELS; channel++) {
			double p0 = this.values[i0 * CHANNELS + channel];
			double p1 = this.values[i1 * CHANNELS + channel];
			out[channel] = switch (mode) {
				case CONSTANT -> p0;
				case LINEAR -> p0 + (p1 - p0) * u;
				case BEZIER -> hermite(p0, this.slopes[i0 * CHANNELS + channel] * h, p1, this.slopes[i1 * CHANNELS + channel] * h, u);
			};
		}
		return this.toPose(out, i0, i1, mode == Interpolation.CONSTANT ? 0.0 : u);
	}

	/** Index of the segment [ticks[i], ticks[i+1]] containing t, or -1 when t is outside the path or there is only one key. */
	private int findSegment(double t) {
		if (this.count < 2 || t <= this.ticks[0] || t >= this.ticks[this.count - 1]) {
			return -1;
		}
		int lo = 0;
		int hi = this.count - 1;
		while (hi - lo > 1) {
			int mid = (lo + hi) >>> 1;
			if (this.ticks[mid] <= t) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return lo;
	}

	private Pose toPose(double[] v, int i0, int i1, double u) {
		Quaterniond rotation;
		float yaw;
		float pitch;
		float roll;
		if (this.quaternionMode) {
			if (i0 == i1 || u <= 0.0) {
				rotation = new Quaterniond(this.keyRotations[i0]);
			} else if (this.modes[i0] == Interpolation.BEZIER) {
				rotation = squad(this.keyRotations[i0], this.squadControls[i0], this.squadControls[i1], this.keyRotations[i1], u);
			} else {
				rotation = new Quaterniond(this.keyRotations[i0]).slerp(this.keyRotations[i1], u);
			}
			rotation.normalize();
			float[] angles = extractYawPitchRoll(rotation);
			yaw = angles[0];
			pitch = angles[1];
			roll = angles[2];
		} else {
			yaw = (float) v[ROT_Y];
			pitch = (float) v[ROT_X];
			roll = (float) v[ROT_Z];
			rotation = bodyRotation(v[ROT_Y], v[ROT_X], v[ROT_Z]);
		}
		Quaternionf rotationF = new Quaternionf((float) rotation.x, (float) rotation.y, (float) rotation.z, (float) rotation.w);
		return new Pose(v[X], v[Y], v[Z], yaw, pitch, roll, rotationF, (float) v[FOV]);
	}

	private void computeAutoClampedSlopes(int channel) {
		for (int i = 0; i < this.count; i++) {
			double slope = 0.0;
			if (i > 0 && i < this.count - 1) {
				double p = this.values[i * CHANNELS + channel];
				double pPrev = this.values[(i - 1) * CHANNELS + channel];
				double pNext = this.values[(i + 1) * CHANNELS + channel];
				double hPrev = this.ticks[i] - this.ticks[i - 1];
				double hNext = this.ticks[i + 1] - this.ticks[i];
				boolean extremum = (p >= pPrev && p >= pNext) || (p <= pPrev && p <= pNext);
				if (!extremum && hPrev > 0 && hNext > 0) {
					double slopePrev = (p - pPrev) / hPrev;
					double slopeNext = (pNext - p) / hNext;
					// Time-weighted average (the slope over the shorter neighbouring segment counts more), which keeps the speed continuous across the key even when the two segments last very different times.
					slope = (slopePrev * hNext + slopeNext * hPrev) / (hPrev + hNext);
					// Clamp so the curve can't overshoot either neighbour (Fritsch-Carlson style limit) - "auto clamped".
					double limit = 3.0 * Math.min(Math.abs(slopePrev), Math.abs(slopeNext));
					if (Math.abs(slope) > limit) {
						slope = Math.copySign(limit, slope);
					}
				}
			}
			this.slopes[i * CHANNELS + channel] = slope;
		}
	}

	private Quaterniond squadControl(int i) {
		Quaterniond qi = this.keyRotations[i];
		if (i == 0 || i == this.count - 1) {
			return new Quaterniond(qi);
		}
		Quaterniond inv = new Quaterniond(qi).conjugate();
		Quaterniond toNext = log(new Quaterniond(inv).mul(this.keyRotations[i + 1]));
		Quaterniond toPrev = log(new Quaterniond(inv).mul(this.keyRotations[i - 1]));
		Quaterniond sum = new Quaterniond(toNext.x + toPrev.x, toNext.y + toPrev.y, toNext.z + toPrev.z, 0.0);
		sum.x *= -0.25;
		sum.y *= -0.25;
		sum.z *= -0.25;
		return new Quaterniond(qi).mul(exp(sum)).normalize();
	}

	private static Quaterniond squad(Quaterniond q0, Quaterniond s0, Quaterniond s1, Quaterniond q1, double u) {
		Quaterniond a = new Quaterniond(q0).slerp(q1, u);
		Quaterniond b = new Quaterniond(s0).slerp(s1, u);
		return a.slerp(b, 2.0 * u * (1.0 - u));
	}

	/** Logarithm of a unit quaternion (result has w = 0). */
	private static Quaterniond log(Quaterniond q) {
		double w = Math.max(-1.0, Math.min(1.0, q.w));
		double sinHalf = Math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z);
		double angle = Math.atan2(sinHalf, w);
		double scale = sinHalf > 1.0e-9 ? angle / sinHalf : 1.0;
		return new Quaterniond(q.x * scale, q.y * scale, q.z * scale, 0.0);
	}

	private static Quaterniond exp(Quaterniond q) {
		double angle = Math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z);
		double scale = angle > 1.0e-9 ? Math.sin(angle) / angle : 1.0;
		return new Quaterniond(q.x * scale, q.y * scale, q.z * scale, Math.cos(angle));
	}

	private static double hermite(double p0, double m0, double p1, double m1, double u) {
		double u2 = u * u;
		double u3 = u2 * u;
		return (2 * u3 - 3 * u2 + 1) * p0 + (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) * p1 + (u3 - u2) * m1;
	}

	private static double clamp01(double value) {
		return value < 0 ? 0 : (value > 1 ? 1 : value);
	}

	/** Ry(-yaw) * Rx(pitch) * Rz(roll) - the attitude convention tudursvehiclemod's aircraft use. */
	public static Quaterniond bodyRotation(double yawDeg, double pitchDeg, double rollDeg) {
		return new Quaterniond()
				.rotationY(Math.toRadians(-yawDeg))
				.rotateX(Math.toRadians(pitchDeg))
				.rotateZ(Math.toRadians(rollDeg));
	}

	/** Inverse of {@link #bodyRotation} - same formula as tudursvehiclemod's AircraftEntity.extractYawPitchRoll(). */
	public static float[] extractYawPitchRoll(Quaterniond q) {
		double x = q.x;
		double y = q.y;
		double z = q.z;
		double w = q.w;
		double pitchRad = Math.asin(Math.max(-1.0, Math.min(1.0, 2.0 * (x * w - y * z))));
		double negYawRad = Math.atan2(2.0 * (x * z + y * w), 1.0 - 2.0 * (x * x + y * y));
		double rollRad = Math.atan2(2.0 * (x * y + z * w), 1.0 - 2.0 * (x * x + z * z));
		return new float[]{(float) -Math.toDegrees(negYawRad), (float) Math.toDegrees(pitchRad), (float) Math.toDegrees(rollRad)};
	}

	/**
	 * Evaluated state at one instant.
	 *
	 * @param rotation body orientation (Ry(-yaw)·Rx(pitch)·Rz(roll)); for a camera multiply by Ry(180°)
	 * @param yaw      yaw/pitch/roll equivalent of rotation (in EULER mode exactly the interpolated values)
	 */
	public record Pose(double x, double y, double z, float yaw, float pitch, float roll, Quaternionf rotation, float fov) {
	}
}
