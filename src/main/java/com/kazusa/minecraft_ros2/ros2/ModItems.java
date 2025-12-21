package com.kazusa.minecraft_ros2.ros2;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * @deprecated LIDAR items are deprecated in NeoForge 1.21.11.
 * All LIDAR item registrations have been removed.
 */
@Deprecated
public class ModItems {
    public static final String MODID = ModArmorMaterials.MODID;

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, MODID);

    // LIDAR items deprecated and removed for 1.21.11
    // VELODYNE_VLP16, HESAI_XT32, HESAI_FT120, RS_LIDAR_M1, UTM_30LN

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
