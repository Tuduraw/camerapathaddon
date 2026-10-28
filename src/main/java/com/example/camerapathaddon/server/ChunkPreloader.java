package com.example.camerapathaddon.server;

import com.example.camerapathaddon.path.PathEvaluator;
import com.example.camerapathaddon.path.PathSettings;
import com.example.camerapathaddon.registry.ModChunkTickets;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

/**
 * Keeps the chunks along a path loaded around the current position: {@code chunkAhead} chunks ahead and
 * {@code chunkBehind} behind, counted along the path (the order the path actually visits them, not straight-line
 * distance), each widened by {@code chunkWidth} chunks on every side. With {@code preloadWhole}, every chunk of the
 * whole path is requested at the start and held until the end.
 *
 * <p>Uses this addon's own non-serialized tickets (see ModChunkTickets) rather than forced chunks, so nothing is
 * left loaded in the save if the server stops or crashes mid-playback.
 */
final class ChunkPreloader {

	/** Path samples are taken at most this far apart (blocks), so even a very fast segment can't skip over a chunk. */
	private static final double SAMPLE_SPACING = 4.0;
	/** Hard cap on samples, for absurdly long and fast paths. */
	private static final int MAX_SAMPLES = 2_000_000;

	private final ServerWorld world;
	private final PathSettings settings;
	private final int maxChunks;
	/** Distinct chunks in the order the path enters them (packed ChunkPos longs). */
	private final long[] sequence;
	/** Path time at which the path enters sequence[i]. */
	private final double[] enterTimes;
	private final LongSet held = new LongOpenHashSet();
	private final LongSet simulated = new LongOpenHashSet();
	private int lastIndex = Integer.MIN_VALUE;
	private boolean capped;

	ChunkPreloader(ServerWorld world, PathEvaluator evaluator, PathSettings settings, int maxChunks) {
		this.world = world;
		this.settings = settings;
		this.maxChunks = maxChunks;
		LongArrayList chunks = new LongArrayList();
		it.unimi.dsi.fastutil.doubles.DoubleArrayList times = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
		if (!evaluator.isEmpty()) {
			int first = evaluator.firstTick();
			int last = evaluator.lastTick();
			PathEvaluator.Pose previous = evaluator.evaluate(first);
			addSample(chunks, times, previous, first);
			int samples = 1;
			for (int tick = first; tick < last && samples < MAX_SAMPLES; tick++) {
				PathEvaluator.Pose next = evaluator.evaluate(tick + 1);
				double distance = Math.sqrt(sq(next.x() - previous.x()) + sq(next.y() - previous.y()) + sq(next.z() - previous.z()));
				int steps = Math.max(1, (int) Math.ceil(distance / SAMPLE_SPACING));
				for (int s = 1; s <= steps && samples < MAX_SAMPLES; s++) {
					double t = tick + (double) s / steps;
					addSample(chunks, times, s == steps ? next : evaluator.evaluate(t), t);
					samples++;
				}
				previous = next;
			}
		}
		this.sequence = chunks.toLongArray();
		this.enterTimes = times.toDoubleArray();
	}

	private static void addSample(LongArrayList chunks, it.unimi.dsi.fastutil.doubles.DoubleArrayList times, PathEvaluator.Pose pose, double t) {
		long chunk = ChunkPos.toLong((int) Math.floor(pose.x()) >> 4, (int) Math.floor(pose.z()) >> 4);
		if (chunks.isEmpty() || chunks.getLong(chunks.size() - 1) != chunk) {
			chunks.add(chunk);
			times.add(t);
		}
	}

	private static double sq(double v) {
		return v * v;
	}

	/** Number of distinct chunks the path passes through (without the width). */
	int pathChunkCount() {
		return this.sequence.length;
	}

	/** True when the requested chunk count hit the cap (maxLoadedChunksPerPlayback) and some were left out. */
	boolean isCapped() {
		return this.capped;
	}

	/** Requests/releases tickets for path time t. Cheap when the current chunk hasn't changed since the last call. */
	void update(double t) {
		if (this.sequence.length == 0) {
			return;
		}
		int index = this.indexAt(t);
		if (index == this.lastIndex) {
			return;
		}
		this.lastIndex = index;
		LongSet desired = new LongOpenHashSet();
		int from;
		int to;
		if (this.settings.preloadWhole()) {
			from = 0;
			to = this.sequence.length - 1;
		} else {
			from = Math.max(0, index - this.settings.chunkBehind());
			to = Math.min(this.sequence.length - 1, index + this.settings.chunkAhead());
		}
		int width = this.settings.chunkWidth();
		// Nearest-first along the path from the current index outwards, so the cap (if hit) cuts the far end.
		outer:
		for (int offset = 0; ; offset++) {
			boolean any = false;
			int[] candidates = offset == 0 ? new int[]{index} : new int[]{index + offset, index - offset};
			for (int i : candidates) {
				if (i < from || i > to) {
					continue;
				}
				any = true;
				int cx = ChunkPos.getPackedX(this.sequence[i]);
				int cz = ChunkPos.getPackedZ(this.sequence[i]);
				for (int dx = -width; dx <= width; dx++) {
					for (int dz = -width; dz <= width; dz++) {
						desired.add(ChunkPos.toLong(cx + dx, cz + dz));
						if (desired.size() >= this.maxChunks) {
							this.capped = true;
							break outer;
						}
					}
				}
			}
			if (!any && (index + offset > to) && (index - offset < from)) {
				break;
			}
		}
		this.apply(this.held, desired, ModChunkTickets.PRELOAD, 0);
	}

	/** Entity-ticking tickets at the chunks of the given positions (an unmanned vehicle's position now and over the next few ticks), so it keeps ticking with nobody nearby. */
	void updateSimulation(LongSet chunks) {
		this.apply(this.simulated, chunks, ModChunkTickets.SIMULATION, 2);
	}

	/** True when every chunk currently requested for preloading is actually loaded (full status available - not just ticketed). */
	boolean isReady() {
		ServerChunkManager manager = this.world.getChunkManager();
		LongIterator iterator = this.held.iterator();
		while (iterator.hasNext()) {
			long chunk = iterator.nextLong();
			if (manager.getWorldChunk(ChunkPos.getPackedX(chunk), ChunkPos.getPackedZ(chunk)) == null) {
				return false;
			}
		}
		return true;
	}

	int loadedCount() {
		ServerChunkManager manager = this.world.getChunkManager();
		int loaded = 0;
		LongIterator iterator = this.held.iterator();
		while (iterator.hasNext()) {
			long chunk = iterator.nextLong();
			if (manager.getWorldChunk(ChunkPos.getPackedX(chunk), ChunkPos.getPackedZ(chunk)) != null) {
				loaded++;
			}
		}
		return loaded;
	}

	int requestedCount() {
		return this.held.size();
	}

	void releaseAll() {
		this.apply(this.held, new LongOpenHashSet(), ModChunkTickets.PRELOAD, 0);
		this.apply(this.simulated, new LongOpenHashSet(), ModChunkTickets.SIMULATION, 2);
		this.lastIndex = Integer.MIN_VALUE;
	}

	private void apply(LongSet current, LongSet desired, net.minecraft.server.world.ChunkTicketType type, int radius) {
		ServerChunkManager manager = this.world.getChunkManager();
		LongIterator removeIterator = current.iterator();
		while (removeIterator.hasNext()) {
			long chunk = removeIterator.nextLong();
			if (!desired.contains(chunk)) {
				manager.removeTicket(type, new ChunkPos(chunk), radius);
				removeIterator.remove();
			}
		}
		LongIterator addIterator = desired.iterator();
		while (addIterator.hasNext()) {
			long chunk = addIterator.nextLong();
			if (current.add(chunk)) {
				manager.addTicket(type, new ChunkPos(chunk), radius);
			}
		}
	}

	private int indexAt(double t) {
		int lo = 0;
		int hi = this.enterTimes.length - 1;
		if (t <= this.enterTimes[0]) {
			return 0;
		}
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (this.enterTimes[mid] <= t) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		return lo;
	}

	/** Path position (x, z) about `distance` blocks ahead of path time t in a straight line, scanning at most maxTicks ahead - the point chunk sending is sorted around (see the ChunkDataSender mixin). */
	static ChunkPos aheadChunk(PathEvaluator evaluator, double t, double distance, int maxTicks) {
		PathEvaluator.Pose here = evaluator.evaluate(t);
		double bestX = here.x();
		double bestZ = here.z();
		double limitSq = distance * distance;
		int last = evaluator.lastTick();
		for (int step = 2; step <= maxTicks && t + step <= last + 1; step += 2) {
			PathEvaluator.Pose ahead = evaluator.evaluate(Math.min(t + step, last));
			double dSq = sq(ahead.x() - here.x()) + sq(ahead.z() - here.z());
			if (dSq > limitSq) {
				break;
			}
			bestX = ahead.x();
			bestZ = ahead.z();
		}
		return new ChunkPos((int) Math.floor(bestX) >> 4, (int) Math.floor(bestZ) >> 4);
	}
}
