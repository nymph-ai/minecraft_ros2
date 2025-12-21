package com.kazusa.minecraft_ros2.ros2;

import com.kazusa.minecraft_ros2.items.BlockItems;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.bus.api.SubscribeEvent;

public class CreativeTabEvents {

    @SubscribeEvent
    public static void onBuildCreativeModeTabContents(BuildCreativeModeTabContentsEvent event) {
        // LIDAR items deprecated and removed for 1.21.11
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(BlockItems.REDSTONE_PUB_SUB_ITEM.get());
        }
    }
}
