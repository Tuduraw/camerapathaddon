package com.example.camerapathaddon.server;

import com.example.camerapathaddon.block.CameraControlBlockEntity;
import com.example.camerapathaddon.network.Payloads;
import com.example.camerapathaddon.path.CameraPath;
import com.example.camerapathaddon.path.Keyframe;
import com.example.camerapathaddon.path.PathSettings;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/** Server side of every request a camera control block's screens send. Each checks that the player can actually reach the block (packets can be forged). */
public final class BlockRequests {

	/** Same reach as opening the block's slots (vanilla's block interaction range plus this margin). */
	private static final double EXTRA_REACH = 1.0;

	private BlockRequests() {
	}

	/** The block at pos if player may use its screens: within reach, or away on a view playback that came from it (checking a key's view, or paused on its path). */
	private static CameraControlBlockEntity block(ServerPlayerEntity player, BlockPos pos) {
		if (!player.canInteractWithBlockAt(pos, EXTRA_REACH) && !isAwayFrom(player, pos)) {
			return null;
		}
		return player.getEntityWorld().getBlockEntity(pos) instanceof CameraControlBlockEntity block ? block : null;
	}

	private static boolean isAwayFrom(ServerPlayerEntity player, BlockPos pos) {
		GlobalPos source = PlaybackManager.cameraSourceOf(player);
		return source != null && source.pos().equals(pos) && source.dimension() == player.getEntityWorld().getRegistryKey();
	}

	private static GlobalPos source(ServerPlayerEntity player, BlockPos pos) {
		return GlobalPos.create(player.getEntityWorld().getRegistryKey(), pos);
	}

	public static void editKey(ServerPlayerEntity player, BlockPos pos, int index, Keyframe key) {
		CameraControlBlockEntity block = block(player, pos);
		if (block == null) {
			return;
		}
		CameraPath path = block.getPath();
		if (index < 0 || index > path.keys().size()) {
			index = path.keys().size();
		}
		CameraPath.Edit edit = path.setKey(index, key);
		if (edit.index() < 0) {
			player.sendMessage(Text.translatable("message.camerapathaddon.too_many_keys", CameraPath.MAX_KEYS), true);
			return;
		}
		block.setPath(edit.path());
	}

	public static void editSettings(ServerPlayerEntity player, BlockPos pos, PathSettings settings, String name) {
		CameraControlBlockEntity block = block(player, pos);
		if (block == null) {
			return;
		}
		block.setName(name);
		block.setPath(block.getPath().withSettings(settings));
	}

	public static void action(ServerPlayerEntity player, BlockPos pos, Payloads.Action action, int index, double value) {
		CameraControlBlockEntity block = block(player, pos);
		if (block == null) {
			return;
		}
		CameraPath path = block.getPath();
		switch (action) {
			case REMOVE_KEY -> block.setPath(path.removeKey(index));
			case DUPLICATE_KEY -> {
				if (index < 0 || index >= path.keys().size()) {
					return;
				}
				if (path.keys().size() >= CameraPath.MAX_KEYS) {
					player.sendMessage(Text.translatable("message.camerapathaddon.too_many_keys", CameraPath.MAX_KEYS), true);
					return;
				}
				int spacing = path.settings().keySpacing();
				Keyframe source = path.keys().get(index);
				// Open a gap of `spacing` after the copied key, then put the copy in it.
				CameraPath shifted = index + 1 < path.keys().size() ? path.shiftFrom(index + 1, spacing) : path;
				List<Keyframe> keys = new ArrayList<>(shifted.keys());
				keys.add(source.withTick(source.tick() + spacing));
				block.setPath(shifted.withKeys(keys));
			}
			case SHIFT_FROM -> {
				if (Double.isFinite(value)) {
					block.setPath(path.shiftFrom(index, (int) MathHelper.clamp(value, -Keyframe.MAX_TICK, Keyframe.MAX_TICK)));
				}
			}
			case SCALE_TIME -> {
				if (Double.isFinite(value) && value >= 0.01 && value <= 100) {
					block.setPath(path.scaleTime(value));
				}
			}
			case IMPORT_ROUTE -> {
				if (!Double.isFinite(value) || value <= 0) {
					return;
				}
				int count = block.importFromBook(Math.min(value, 1000.0));
				player.sendMessage(count > 0
						? Text.translatable("message.camerapathaddon.imported", count)
						: Text.translatable(block.getBook().isEmpty() ? "message.camerapathaddon.need_book" : "message.camerapathaddon.no_route"), true);
			}
			case CLEAR_KEYS -> block.setPath(path.withKeys(List.of()));
			case TOGGLE_PREVIEW -> block.setPreview(!block.isPreview());
			case OPEN_SLOTS -> {
				if (player.canInteractWithBlockAt(pos, EXTRA_REACH)) {
					player.openHandledScreen(block);
				}
			}
			case PLAY_CAMERA -> PlaybackManager.startCameraFromBlock(block, player, index, false);
			case PLAY_VEHICLE -> PlaybackManager.startVehicleFromBlock(block, player, index);
			case STOP -> {
				int stopped = PlaybackManager.stopFromBlock(player.getEntityWorld(), pos);
				if (stopped == 0) {
					player.sendMessage(Text.translatable("message.camerapathaddon.nothing_playing"), true);
				}
			}
			case INSPECT -> PlaybackManager.startCameraFromBlock(block, player, Math.max(0, index), true);
			case INSPECT_TIME -> PlaybackManager.inspectTime(player, source(player, pos), Math.max(0, index));
			case RESUME_CAMERA -> PlaybackManager.resumeCamera(player, source(player, pos), index);
			case END_CAMERA -> PlaybackManager.endCameraFrom(player, source(player, pos));
			case TOGGLE_PAUSE -> {
				if (!PlaybackManager.mayPlay(player)) {
					player.sendMessage(Text.translatable("message.camerapathaddon.no_permission"), true);
				} else if (PlaybackManager.togglePauseFromBlock(player.getEntityWorld(), pos) == 0) {
					player.sendMessage(Text.translatable("message.camerapathaddon.nothing_playing"), true);
				}
			}
		}
	}
}
