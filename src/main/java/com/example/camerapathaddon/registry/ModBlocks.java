package com.example.camerapathaddon.registry;

import com.example.camerapathaddon.CameraPathAddon;
import com.example.camerapathaddon.block.CameraControlBlock;
import com.example.camerapathaddon.block.CameraControlBlockEntity;
import com.example.camerapathaddon.screen.CameraControlScreenHandler;
import com.example.tudursvehiclemod.item.ModItemGroups;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.math.BlockPos;

public final class ModBlocks {

	public static Block CAMERA_CONTROL;
	public static Item CAMERA_CONTROL_ITEM;
	public static BlockEntityType<CameraControlBlockEntity> CAMERA_CONTROL_ENTITY;
	public static ExtendedScreenHandlerType<CameraControlScreenHandler, BlockPos> CAMERA_CONTROL_SCREEN;

	private ModBlocks() {
	}

	public static void register() {
		RegistryKey<Block> blockKey = RegistryKey.of(RegistryKeys.BLOCK, CameraPathAddon.id("camera_control"));
		CAMERA_CONTROL = Registry.register(Registries.BLOCK, blockKey, new CameraControlBlock(AbstractBlock.Settings.create()
				.registryKey(blockKey)
				.mapColor(MapColor.IRON_GRAY)
				.strength(4.0f)
				.requiresTool()));
		RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, CameraPathAddon.id("camera_control"));
		CAMERA_CONTROL_ITEM = Registry.register(Registries.ITEM, itemKey,
				new BlockItem(CAMERA_CONTROL, new Item.Settings().registryKey(itemKey).useBlockPrefixedTranslationKey()));
		CAMERA_CONTROL_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, CameraPathAddon.id("camera_control"),
				FabricBlockEntityTypeBuilder.create(CameraControlBlockEntity::new, CAMERA_CONTROL).build());
		CAMERA_CONTROL_SCREEN = Registry.register(Registries.SCREEN_HANDLER, CameraPathAddon.id("camera_control"),
				new ExtendedScreenHandlerType<>(CameraControlScreenHandler::new, BlockPos.PACKET_CODEC.cast()));
		// Next to tudursvehiclemod's own Drone Center in its creative tab.
		ItemGroupEvents.modifyEntriesEvent(ModItemGroups.VEHICLES).register(entries -> entries.add(CAMERA_CONTROL_ITEM));
	}
}
