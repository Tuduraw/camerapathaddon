package com.example.camerapathaddon.path;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** A whole camera path: keyframes (always kept sorted by tick, no two on the same tick) plus its settings. Immutable - every edit returns a new instance. */
public record CameraPath(List<Keyframe> keys, PathSettings settings) {

	/** Upper bound on keys per path - keeps the block entity data (synced to clients in its update packet) bounded. */
	public static final int MAX_KEYS = 2000;

	public static final CameraPath EMPTY = new CameraPath(List.of(), PathSettings.DEFAULT);

	public static final Codec<CameraPath> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Keyframe.CODEC.listOf().optionalFieldOf("keys", List.of()).forGetter(CameraPath::keys),
			PathSettings.CODEC.optionalFieldOf("settings", PathSettings.DEFAULT).forGetter(CameraPath::settings)
	).apply(instance, CameraPath::of));

	public static final PacketCodec<RegistryByteBuf, CameraPath> PACKET_CODEC = new PacketCodec<>() {
		@Override
		public CameraPath decode(RegistryByteBuf buf) {
			int count = buf.readVarInt();
			if (count < 0 || count > MAX_KEYS) {
				throw new IllegalArgumentException("Too many camera path keys: " + count);
			}
			List<Keyframe> keys = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				keys.add(Keyframe.PACKET_CODEC.decode(buf));
			}
			return CameraPath.of(keys, PathSettings.PACKET_CODEC.decode(buf));
		}

		@Override
		public void encode(RegistryByteBuf buf, CameraPath path) {
			buf.writeVarInt(path.keys.size());
			for (Keyframe key : path.keys) {
				Keyframe.PACKET_CODEC.encode(buf, key);
			}
			PathSettings.PACKET_CODEC.encode(buf, path.settings);
		}
	};

	public CameraPath {
		keys = List.copyOf(keys);
	}

	/** Normalizing factory: sanitizes every value, sorts by tick, and drops later duplicates of a tick (the first one wins). */
	public static CameraPath of(List<Keyframe> rawKeys, PathSettings settings) {
		List<Keyframe> sorted = new ArrayList<>(Math.min(rawKeys.size(), MAX_KEYS));
		for (Keyframe key : rawKeys) {
			if (sorted.size() >= MAX_KEYS) {
				break;
			}
			sorted.add(key.sanitized());
		}
		sorted.sort(Comparator.comparingInt(Keyframe::tick));
		List<Keyframe> unique = new ArrayList<>(sorted.size());
		for (Keyframe key : sorted) {
			if (unique.isEmpty() || unique.get(unique.size() - 1).tick() != key.tick()) {
				unique.add(key);
			}
		}
		return new CameraPath(Collections.unmodifiableList(unique), settings == null ? PathSettings.DEFAULT : settings.sanitized());
	}

	public boolean isEmpty() {
		return this.keys.isEmpty();
	}

	public int firstTick() {
		return this.keys.isEmpty() ? 0 : this.keys.get(0).tick();
	}

	public int lastTick() {
		return this.keys.isEmpty() ? 0 : this.keys.get(this.keys.size() - 1).tick();
	}

	/** Playback length in ticks (from the first key to the last). */
	public int duration() {
		return this.lastTick() - this.firstTick();
	}

	public CameraPath withSettings(PathSettings newSettings) {
		return CameraPath.of(this.keys, newSettings);
	}

	public CameraPath withKeys(List<Keyframe> newKeys) {
		return CameraPath.of(newKeys, this.settings);
	}

	/** Replaces the key at index (or appends when index == size). If the new key's tick collides with ANOTHER key's tick it is moved to the nearest free tick after it, so an edit never silently deletes a different key. Returns the path and the new index of the edited key. */
	public Edit setKey(int index, Keyframe key) {
		List<Keyframe> list = new ArrayList<>(this.keys);
		Keyframe safe = key.sanitized();
		if (index >= 0 && index < list.size()) {
			list.remove(index);
		} else if (list.size() >= MAX_KEYS) {
			return new Edit(this, -1);
		}
		int tick = safe.tick();
		boolean collides;
		do {
			collides = false;
			for (Keyframe other : list) {
				if (other.tick() == tick) {
					collides = true;
					tick++;
					break;
				}
			}
		} while (collides && tick <= Keyframe.MAX_TICK);
		Keyframe placed = safe.withTick(Math.min(tick, Keyframe.MAX_TICK));
		list.add(placed);
		CameraPath result = CameraPath.of(list, this.settings);
		return new Edit(result, result.keys.indexOf(placed));
	}

	public CameraPath removeKey(int index) {
		if (index < 0 || index >= this.keys.size()) {
			return this;
		}
		List<Keyframe> list = new ArrayList<>(this.keys);
		list.remove(index);
		return CameraPath.of(list, this.settings);
	}

	/** Shifts the tick of every key from fromIndex on by delta (clamped so they never pass the key before fromIndex). Used to open up or close a gap without retyping every later key. */
	public CameraPath shiftFrom(int fromIndex, int delta) {
		if (fromIndex < 0 || fromIndex >= this.keys.size() || delta == 0) {
			return this;
		}
		int floor = fromIndex == 0 ? 0 : this.keys.get(fromIndex - 1).tick() + 1;
		int applied = Math.max(delta, floor - this.keys.get(fromIndex).tick());
		List<Keyframe> list = new ArrayList<>(this.keys.size());
		for (int i = 0; i < this.keys.size(); i++) {
			Keyframe key = this.keys.get(i);
			list.add(i >= fromIndex ? key.withTick(key.tick() + applied) : key);
		}
		return CameraPath.of(list, this.settings);
	}

	/** Scales every key's time around the first key (factor 2 = twice as long, i.e. half the speed). Keys that would land on the same tick are spread one tick apart so none are lost. */
	public CameraPath scaleTime(double factor) {
		if (!(factor > 0) || !Double.isFinite(factor) || this.keys.size() < 2) {
			return this;
		}
		int origin = this.firstTick();
		List<Keyframe> list = new ArrayList<>(this.keys.size());
		int previous = Integer.MIN_VALUE;
		for (Keyframe key : this.keys) {
			int tick = origin + (int) Math.round((key.tick() - origin) * factor);
			if (tick <= previous) {
				tick = previous + 1;
			}
			previous = tick;
			list.add(key.withTick(tick));
		}
		return CameraPath.of(list, this.settings);
	}

	/** Every key moved by (dx, dy, dz). */
	public CameraPath translated(double dx, double dy, double dz) {
		if (dx == 0 && dy == 0 && dz == 0) {
			return this;
		}
		List<Keyframe> list = new ArrayList<>(this.keys.size());
		for (Keyframe key : this.keys) {
			list.add(key.withPosition(key.x() + dx, key.y() + dy, key.z() + dz));
		}
		return CameraPath.of(list, this.settings);
	}

	/**
	 * The path tick shown at a given playback time (ticks since the path started running, possibly fractional).
	 * Looping paths wrap around after the last key; others stop at it.
	 */
	public double pathTime(double elapsed) {
		int duration = this.duration();
		if (duration <= 0) {
			return this.firstTick();
		}
		double t = Math.max(0.0, elapsed);
		if (this.settings.loop()) {
			t = t % duration;
		} else if (t > duration) {
			t = duration;
		}
		return this.firstTick() + t;
	}

	public record Edit(CameraPath path, int index) {
	}
}
