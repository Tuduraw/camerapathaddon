package com.example.camerapathaddon.screen;

import com.example.camerapathaddon.block.CameraControlBlockEntity;
import com.example.camerapathaddon.registry.ModBlocks;
import com.example.tudursvehiclemod.item.DroneControlStickItem;
import com.example.tudursvehiclemod.item.DroneRouteBookItem;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;

/** The camera control block's two slots: route book (slot 0) and drone control stick (slot 1). */
public class CameraControlScreenHandler extends ScreenHandler {

	public static final int BOOK_X = 62;
	public static final int BOOK_Y = 24;
	public static final int STICK_X = 62;
	public static final int STICK_Y = 52;

	private final Inventory inventory;
	private final BlockPos blockPos;

	/** Client side: the block position comes with the open packet (see ModBlocks' ExtendedScreenHandlerType). */
	public CameraControlScreenHandler(int syncId, PlayerInventory playerInventory, BlockPos blockPos) {
		this(syncId, playerInventory, new SimpleInventory(2), blockPos);
	}

	public CameraControlScreenHandler(int syncId, PlayerInventory playerInventory, Inventory inventory, BlockPos blockPos) {
		super(ModBlocks.CAMERA_CONTROL_SCREEN, syncId);
		this.inventory = inventory;
		this.blockPos = blockPos;
		inventory.onOpen(playerInventory.player);
		this.addSlot(new Slot(inventory, CameraControlBlockEntity.SLOT_BOOK, BOOK_X, BOOK_Y) {
			@Override
			public boolean canInsert(ItemStack stack) {
				return stack.getItem() instanceof DroneRouteBookItem;
			}
		});
		this.addSlot(new Slot(inventory, CameraControlBlockEntity.SLOT_STICK, STICK_X, STICK_Y) {
			@Override
			public boolean canInsert(ItemStack stack) {
				return stack.getItem() instanceof DroneControlStickItem;
			}
		});
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
			}
		}
		for (int col = 0; col < 9; col++) {
			this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
		}
	}

	public BlockPos getBlockPos() {
		return this.blockPos;
	}

	@Override
	public boolean canUse(PlayerEntity player) {
		return this.inventory.canPlayerUse(player);
	}

	@Override
	public ItemStack quickMove(PlayerEntity player, int index) {
		Slot slot = this.slots.get(index);
		if (slot == null || !slot.hasStack()) {
			return ItemStack.EMPTY;
		}
		ItemStack original = slot.getStack();
		ItemStack result = original.copy();
		if (index < 2) {
			if (!this.insertItem(original, 2, this.slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else if (original.getItem() instanceof DroneRouteBookItem) {
			if (!this.insertItem(original, 0, 1, false)) {
				return ItemStack.EMPTY;
			}
		} else if (original.getItem() instanceof DroneControlStickItem) {
			if (!this.insertItem(original, 1, 2, false)) {
				return ItemStack.EMPTY;
			}
		} else {
			return ItemStack.EMPTY;
		}
		if (original.isEmpty()) {
			slot.setStack(ItemStack.EMPTY);
		} else {
			slot.markDirty();
		}
		return result;
	}
}
