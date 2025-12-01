package com.kazusa.minecraft_ros2.events;

import com.kazusa.minecraft_ros2.minecraft_ros2;
import com.kazusa.minecraft_ros2.ros2.ROS2Manager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Handles client-side Forge events for the minecraft_ros2 mod
 */
@Mod.EventBusSubscriber(modid = minecraft_ros2.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ModEventHandler {

    /**
     * Apply ROS2 twist commands every client tick
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ROS2Manager.getInstance().processClientTick();
        }
    }
}
