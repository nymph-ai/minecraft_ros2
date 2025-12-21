package com.kazusa.minecraft_ros2.events;

import com.kazusa.minecraft_ros2.ros2.ROS2Manager;
import com.kazusa.minecraft_ros2.ros2.ImagePublisher;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(value = Dist.CLIENT)
public class RenderEventHandler {
    private static int renderTickCount = 0;
    private static final int TICKS_BETWEEN_IMAGES = 1; // Publish every tick for maximum frame rate

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        renderTickCount++;
        if (renderTickCount >= TICKS_BETWEEN_IMAGES) {
            renderTickCount = 0; // Reset the counter

            ROS2Manager ros2 = ROS2Manager.getInstance();
            if (ros2.isInitialized()) {
                ImagePublisher publisher = ros2.getImagePublisher();
                if (publisher != null) {
                    publisher.captureAndPublish();
                } else {
                    org.slf4j.LoggerFactory.getLogger(RenderEventHandler.class).warn("ImagePublisher is null");
                }
            } else {
                org.slf4j.LoggerFactory.getLogger(RenderEventHandler.class).warn("ROS2 not initialized");
            }
        }
    }
}
