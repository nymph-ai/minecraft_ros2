package com.kazusa.minecraft_ros2.events;

import com.kazusa.minecraft_ros2.ros2.ROS2Manager;
import com.kazusa.minecraft_ros2.ros2.ImagePublisher;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber
public class RenderEventHandler {
    private static int renderTickCount = 0;
    private static final int TICKS_BETWEEN_IMAGES = 1; // Publish every render tick for maximum frame rate

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            renderTickCount++;
            if (renderTickCount >= TICKS_BETWEEN_IMAGES) {
                renderTickCount = 0; // Reset the counter

                ROS2Manager ros2 = ROS2Manager.getInstance();
                if (ros2.isInitialized()) {
                    ImagePublisher publisher = ros2.getImagePublisher();
                    if (publisher != null) {
                        publisher.captureAndPublish(); // 3回に1回画像取得
                    } else {
                        org.slf4j.LoggerFactory.getLogger(RenderEventHandler.class).warn("ImagePublisher is null");
                    }
                } else {
                    org.slf4j.LoggerFactory.getLogger(RenderEventHandler.class).warn("ROS2 not initialized");
                }
            }
        }
    }
}
