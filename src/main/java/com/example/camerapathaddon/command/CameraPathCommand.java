package com.example.camerapathaddon.command;

import com.example.camerapathaddon.block.CameraControlBlockEntity;
import com.example.camerapathaddon.server.PlaybackManager;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.Collection;
import java.util.List;

/**
 * /camerapath (operators, level 2) - starts and stops the paths of camera control blocks from commands, so command
 * blocks and functions can start several playbacks (views and vehicles, from one or more blocks) on the same tick:
 * <ul>
 *   <li>play &lt;pos&gt; camera &lt;players&gt; - the block's path on the players' views</li>
 *   <li>play &lt;pos&gt; vehicle [&lt;entities&gt;] - on the given vehicles, or on the vehicle of the block's stick</li>
 *   <li>stop [&lt;pos&gt;] - the playbacks of that block, or all of them</li>
 *   <li>pause &lt;pos&gt; - pauses the playbacks of that block (or resumes them when all are paused)</li>
 *   <li>list - running playbacks</li>
 * </ul>
 */
public final class CameraPathCommand {

	private CameraPathCommand() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("camerapath")
				.requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
				.then(CommandManager.literal("play").then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
						.then(CommandManager.literal("camera").then(CommandManager.argument("players", EntityArgumentType.players())
								.executes(CameraPathCommand::playCamera)))
						.then(CommandManager.literal("vehicle")
								.executes(context -> playVehicle(context, null))
								.then(CommandManager.argument("vehicles", EntityArgumentType.entities())
										.executes(context -> playVehicle(context, EntityArgumentType.getEntities(context, "vehicles")))))))
				.then(CommandManager.literal("stop")
						.executes(CameraPathCommand::stopAll)
						.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos()).executes(CameraPathCommand::stopBlock)))
				.then(CommandManager.literal("pause").then(CommandManager.argument("pos", BlockPosArgumentType.blockPos()).executes(CameraPathCommand::pauseBlock)))
				.then(CommandManager.literal("list").executes(CameraPathCommand::list)));
	}

	private static CameraControlBlockEntity block(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		BlockPos pos = BlockPosArgumentType.getLoadedBlockPos(context, "pos");
		if (context.getSource().getWorld().getBlockEntity(pos) instanceof CameraControlBlockEntity block) {
			return block;
		}
		context.getSource().sendError(Text.translatable("command.camerapathaddon.not_a_block"));
		return null;
	}

	private static int playCamera(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		CameraControlBlockEntity block = block(context);
		if (block == null) {
			return 0;
		}
		int started = 0;
		for (ServerPlayerEntity player : EntityArgumentType.getPlayers(context, "players")) {
			if (player.getEntityWorld() != block.getWorld()) {
				continue;
			}
			Text error = PlaybackManager.startCamera(player, block.getPath(),
					net.minecraft.util.math.GlobalPos.create(block.getWorld().getRegistryKey(), block.getPos()));
			if (error == null) {
				started++;
			} else {
				context.getSource().sendError(Text.literal(player.getName().getString() + ": ").append(error));
			}
		}
		int count = started;
		context.getSource().sendFeedback(() -> Text.translatable("command.camerapathaddon.started", count), true);
		return started;
	}

	private static int playVehicle(CommandContext<ServerCommandSource> context, Collection<? extends Entity> targets) throws CommandSyntaxException {
		CameraControlBlockEntity block = block(context);
		if (block == null) {
			return 0;
		}
		Collection<? extends Entity> vehicles = targets;
		if (vehicles == null) {
			Entity stickTarget = block.getTargetVehicleId() == null ? null
					: ((ServerWorld) block.getWorld()).getEntity(block.getTargetVehicleId());
			if (stickTarget == null) {
				context.getSource().sendError(Text.translatable("message.camerapathaddon.target_not_found"));
				return 0;
			}
			vehicles = List.of(stickTarget);
		}
		int started = 0;
		for (Entity entity : vehicles) {
			if (!(entity instanceof AbstractVehicleEntity vehicle)) {
				continue;
			}
			Text error = PlaybackManager.startVehicle(vehicle, block.getPath(), context.getSource().getPlayer(),
					net.minecraft.util.math.GlobalPos.create(block.getWorld().getRegistryKey(), block.getPos()));
			if (error == null) {
				started++;
			} else {
				context.getSource().sendError(error);
			}
		}
		int count = started;
		context.getSource().sendFeedback(() -> Text.translatable("command.camerapathaddon.started", count), true);
		return started;
	}

	private static int stopBlock(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		BlockPos pos = BlockPosArgumentType.getBlockPos(context, "pos");
		int stopped = PlaybackManager.stopFromBlock(context.getSource().getWorld(), pos);
		context.getSource().sendFeedback(() -> Text.translatable("command.camerapathaddon.stopped", stopped), true);
		return stopped;
	}

	private static int pauseBlock(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		BlockPos pos = BlockPosArgumentType.getBlockPos(context, "pos");
		int changed = PlaybackManager.togglePauseFromBlock(context.getSource().getWorld(), pos);
		context.getSource().sendFeedback(() -> Text.translatable("command.camerapathaddon.paused", changed), true);
		return changed;
	}

	private static int stopAll(CommandContext<ServerCommandSource> context) {
		int stopped = PlaybackManager.describeActive().size();
		PlaybackManager.stopAll(context.getSource().getServer());
		context.getSource().sendFeedback(() -> Text.translatable("command.camerapathaddon.stopped", stopped), true);
		return stopped;
	}

	private static int list(CommandContext<ServerCommandSource> context) {
		List<String> lines = PlaybackManager.describeActive();
		context.getSource().sendFeedback(() -> Text.translatable("command.camerapathaddon.list", lines.size()), false);
		for (String line : lines) {
			context.getSource().sendFeedback(() -> Text.literal(line), false);
		}
		return lines.size();
	}
}
