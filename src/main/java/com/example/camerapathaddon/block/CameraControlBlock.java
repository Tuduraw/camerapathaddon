package com.example.camerapathaddon.block;

import com.example.camerapathaddon.network.Payloads;
import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.block.WireOrientation;

/** Camera control block: right-click opens its path editor (see CameraControlBlockEntity). Like tudursvehiclemod's Drone Center, everything - editing, viewing, playback - goes through the block, so no key bindings are needed. */
public class CameraControlBlock extends BlockWithEntity {

	public static final MapCodec<CameraControlBlock> CODEC = createCodec(CameraControlBlock::new);

	public CameraControlBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}

	@Override
	protected BlockRenderType getRenderType(BlockState state) {
		return BlockRenderType.MODEL;
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new CameraControlBlockEntity(pos, state);
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
		super.onPlaced(world, pos, state, placer, itemStack);
		if (world.getBlockEntity(pos) instanceof CameraControlBlockEntity blockEntity) {
			// An item renamed in an anvil names the setup.
			if (itemStack.getCustomName() != null) {
				blockEntity.setName(itemStack.getCustomName().getString());
			}
			if (world instanceof ServerWorld serverWorld) {
				blockEntity.onRedstone(serverWorld, world.isReceivingRedstonePower(pos));
			}
		}
	}

	@Override
	protected void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock, WireOrientation wireOrientation, boolean notify) {
		super.neighborUpdate(state, world, pos, sourceBlock, wireOrientation, notify);
		if (world instanceof ServerWorld serverWorld && world.getBlockEntity(pos) instanceof CameraControlBlockEntity blockEntity) {
			blockEntity.onRedstone(serverWorld, world.isReceivingRedstonePower(pos));
		}
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		return open(world, pos, player);
	}

	@Override
	protected ActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		return open(world, pos, player);
	}

	private static ActionResult open(World world, BlockPos pos, PlayerEntity player) {
		// Sneaking passes, so blocks can still be placed against it.
		if (player.isSneaking()) {
			return ActionResult.PASS;
		}
		if (world.isClient()) {
			return ActionResult.SUCCESS;
		}
		if (player instanceof ServerPlayerEntity serverPlayer && world.getBlockEntity(pos) instanceof CameraControlBlockEntity) {
			ServerPlayNetworking.send(serverPlayer, new Payloads.OpenEditor(pos));
		}
		return ActionResult.SUCCESS;
	}
}
