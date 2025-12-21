package com.kazusa.minecraft_ros2.ros2;

/**
 * Simplified ModArmorMaterials for NeoForge 1.21.11
 *
 * Note: ArmorMaterial and ArmorItem.Type have been removed in 1.21.5+
 * and replaced with data components. Since the LIDAR functionality
 * using this has been deprecated, we only keep the MODID constant.
 */
public class ModArmorMaterials {
    public static final String MODID = "minecraft_ros2";

    // MYSTIC_MATERIAL removed - ArmorMaterial API no longer exists
    // Use Item.Properties with armor data components instead for future armor implementations
}
