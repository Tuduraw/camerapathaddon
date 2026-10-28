package com.example.camerapathaddon.client;

import com.example.camerapathaddon.block.CameraControlBlockEntity;
import com.example.camerapathaddon.path.CameraPath;
import com.example.camerapathaddon.path.PathEvaluator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import net.minecraft.world.debug.gizmo.TextGizmo;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws the paths of camera control blocks whose preview is switched on (in their editor), within PREVIEW_RANGE
 * blocks: the curve itself, coloured by how hard it is on chunk loading (see CameraPathClientConfig.warnChunksPerSecond),
 * and at every key a marker with its number, an arrow for the viewing direction and a short orange line for "up"
 * (showing the roll). The key selected in an open editor is drawn larger, in magenta.
 */
public final class PathPreviewRenderer {

	private static final int MAX_SEGMENTS = 4000;
	private static final int COLOR_OK = 0xFF34C759;
	private static final int COLOR_BUSY = 0xFFFFCC00;
	private static final int COLOR_OVER = 0xFFFF3B30;
	private static final int COLOR_KEY = 0xFFFFFFFF;
	private static final int COLOR_SELECTED = 0xFFFF2DFF;
	private static final int COLOR_FORWARD = 0xFF00E5FF;
	private static final int COLOR_UP = 0xFFFF9500;

	private static final double PREVIEW_RANGE = 512.0;

	/** The block whose editor is open, and the key selected there (-1 = none). */
	private static BlockPos selectedBlock;
	private static int selectedKey = -1;
	private static final Map<CameraControlBlockEntity, Cache> CACHE = new WeakHashMap<>();

	private PathPreviewRenderer() {
	}

	public static void setSelection(BlockPos block, int key) {
		selectedBlock = block;
		selectedKey = key;
	}

	public static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || ClientPlaybackState.isCameraActive()) {
			return;
		}
		List<CameraControlBlockEntity> shown = new ArrayList<>();
		for (CameraControlBlockEntity block : CameraControlBlockEntity.clientLoaded()) {
			if (block.isRemoved() || block.getWorld() != client.world || !block.isPreview() || block.getPath().isEmpty()) {
				continue;
			}
			if (client.player.squaredDistanceTo(block.getPos().toCenterPos()) > PREVIEW_RANGE * PREVIEW_RANGE) {
				continue;
			}
			shown.add(block);
		}
		if (shown.isEmpty()) {
			return;
		}
		int viewDistance = client.options.getClampedViewDistance();
		double threshold = CameraPathClientConfig.get().warnChunksPerSecond;
		try (var scope = client.newGizmoScope()) {
			for (CameraControlBlockEntity block : shown) {
				Cache cache = CACHE.get(block);
				if (cache == null || cache.path != block.getPath() || cache.viewDistance != viewDistance || cache.threshold != threshold) {
					cache = build(block.getPath(), viewDistance, threshold);
					CACHE.put(block, cache);
				}
				draw(block, cache);
			}
		}
	}

	private static void draw(CameraControlBlockEntity block, Cache cache) {
		for (Segment segment : cache.segments) {
			GizmoDrawing.line(segment.from, segment.to, segment.color, 2.0f);
		}
		int selected = block.getPos().equals(selectedBlock) ? selectedKey : -1;
		for (int i = 0; i < cache.markers.size(); i++) {
			KeyMarker marker = cache.markers.get(i);
			boolean isSelected = i == selected;
			double size = isSelected ? 0.6 : 0.3;
			int color = isSelected ? COLOR_SELECTED : COLOR_KEY;
			Vec3d p = marker.pos;
			GizmoDrawing.line(p.add(-size, 0, 0), p.add(size, 0, 0), color, 2.0f);
			GizmoDrawing.line(p.add(0, -size, 0), p.add(0, size, 0), color, 2.0f);
			GizmoDrawing.line(p.add(0, 0, -size), p.add(0, 0, size), color, 2.0f);
			GizmoDrawing.arrow(p, p.add(marker.forward.multiply(isSelected ? 3.0 : 2.0)), COLOR_FORWARD, 2.0f);
			GizmoDrawing.line(p, p.add(marker.up.multiply(isSelected ? 1.5 : 1.0)), COLOR_UP, 2.0f);
			GizmoDrawing.text("#" + (i + 1) + "  " + marker.tick + "t", p.add(0, isSelected ? 0.9 : 0.6, 0), TextGizmo.Style.centered(color));
		}
		// The block's name over its first key, so overlapping paths can be told apart.
		if (!cache.markers.isEmpty()) {
			GizmoDrawing.text(block.getLabel().getString(), cache.markers.get(0).pos.add(0, 1.4, 0), TextGizmo.Style.centered(COLOR_FORWARD));
		}
	}

	private static Cache build(CameraPath path, int viewDistance, double threshold) {
		PathEvaluator evaluator = new PathEvaluator(path, 0, 0, 0, 70.0);
		List<Segment> newSegments = new ArrayList<>();
		int first = evaluator.firstTick();
		int last = evaluator.lastTick();
		double step = Math.max(0.25, (last - first) / (double) MAX_SEGMENTS);
		double viewWidthChunks = 2 * viewDistance + 1;
		PathEvaluator.Pose previous = evaluator.evaluate(first);
		for (double t = first + step; t <= last + 1.0e-6; t += step) {
			PathEvaluator.Pose next = evaluator.evaluate(Math.min(t, last));
			Vec3d a = new Vec3d(previous.x(), previous.y(), previous.z());
			Vec3d b = new Vec3d(next.x(), next.y(), next.z());
			double blocksPerTick = a.distanceTo(b) / step;
			double chunksPerSecond = blocksPerTick * 20.0 / 16.0 * viewWidthChunks;
			int color = chunksPerSecond > threshold ? COLOR_OVER : (chunksPerSecond > threshold * 0.5 ? COLOR_BUSY : COLOR_OK);
			if (a.squaredDistanceTo(b) > 1.0e-8) {
				newSegments.add(new Segment(a, b, color));
			}
			previous = next;
		}
		List<KeyMarker> newMarkers = new ArrayList<>(evaluator.keyCount());
		for (int i = 0; i < evaluator.keyCount(); i++) {
			PathEvaluator.Pose pose = evaluator.evaluate(evaluator.keyTick(i));
			Quaternionf rotation = pose.rotation();
			Vector3f forward = rotation.transform(new Vector3f(0, 0, 1));
			Vector3f up = rotation.transform(new Vector3f(0, 1, 0));
			newMarkers.add(new KeyMarker(new Vec3d(pose.x(), pose.y(), pose.z()),
					new Vec3d(forward.x, forward.y, forward.z), new Vec3d(up.x, up.y, up.z), evaluator.keyTick(i)));
		}
		return new Cache(path, viewDistance, threshold, newSegments, newMarkers);
	}

	private record Cache(CameraPath path, int viewDistance, double threshold, List<Segment> segments, List<KeyMarker> markers) {
	}

	private record Segment(Vec3d from, Vec3d to, int color) {
	}

	private record KeyMarker(Vec3d pos, Vec3d forward, Vec3d up, int tick) {
	}
}
