package com.example.camerapathaddon.registry;

import com.example.camerapathaddon.CameraPathAddon;
import com.example.camerapathaddon.entity.CameraRigEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;

public final class ModEntityTypes {

	public static EntityType<CameraRigEntity> CAMERA_RIG;

	private ModEntityTypes() {
	}

	public static void register() {
		RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE, CameraPathAddon.id("camera_rig"));
		CAMERA_RIG = Registry.register(Registries.ENTITY_TYPE, key,
				EntityType.Builder.create(CameraRigEntity::new, SpawnGroup.MISC)
						.dimensions(0.2f, 0.2f)
						.maxTrackingRange(32)
						// Every tick: the server-side rig is what the riding player's chunks and entity tracking follow.
						.trackingTickInterval(1)
						.disableSummon()
						.build(key));
	}
}
