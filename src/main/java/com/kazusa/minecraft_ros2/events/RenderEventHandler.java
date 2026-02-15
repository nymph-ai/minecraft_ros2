package com.kazusa.minecraft_ros2.events;

import com.kazusa.minecraft_ros2.minecraft_ros2;
import com.kazusa.minecraft_ros2.ros2.ROS2Manager;
import com.kazusa.minecraft_ros2.ros2.ImagePublisher;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = minecraft_ros2.MOD_ID, value = Dist.CLIENT)
public class RenderEventHandler {
    private static int renderFrameCount = 0;
    private static final int FRAMES_BETWEEN_IMAGES =
            parseEnvInt("MINECRAFT_ROS2_FRAMES_BETWEEN_IMAGES", 1); // 1 = every frame

    // Fallback path: if render capture doesn't run for a while, allow tick capture.
    private static int tickCount = 0;
    private static final int TICKS_BETWEEN_IMAGES =
            parseEnvInt("MINECRAFT_ROS2_TICKS_BETWEEN_IMAGES", 1); // legacy; 1 = every tick
    private static final boolean TICK_FALLBACK_ENABLED =
            parseEnvBool("MINECRAFT_ROS2_TICK_FALLBACK_ENABLED", true);
    private static final long TICK_FALLBACK_STALE_MS =
            parseEnvLong("MINECRAFT_ROS2_TICK_FALLBACK_STALE_MS", 750L);

    private static volatile long lastCaptureAtMs = 0L;
    private static final boolean SKIP_WHEN_SCREEN_OPEN =
            parseEnvBool("MINECRAFT_ROS2_SKIP_WHEN_SCREEN_OPEN", true);
    private static final boolean FORCE_HIDE_GUI =
            parseEnvBool("MINECRAFT_ROS2_FORCE_HIDE_GUI", false);

    @SubscribeEvent
    public static void onAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        // Fix #2: capture from the render pipeline after world render, before GUI.
        renderFrameCount++;
        if (renderFrameCount < FRAMES_BETWEEN_IMAGES) {
            return;
        }
        renderFrameCount = 0;
        maybeCapture("render_after_level");
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // Fix #1 fallback: if render capture is not running, tick capture will keep frames flowing.
        if (!TICK_FALLBACK_ENABLED) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCaptureAtMs < TICK_FALLBACK_STALE_MS) {
            return;
        }
        tickCount++;
        if (tickCount < TICKS_BETWEEN_IMAGES) {
            return;
        }
        tickCount = 0;
        maybeCapture("tick_fallback");
    }

    private static void maybeCapture(String source) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        // Avoid capturing inventory/menus/tooltips that render via Screen overlays.
        if (SKIP_WHEN_SCREEN_OPEN && mc.screen != null) {
            return;
        }
        // Optional: force a HUD-less render for "raw" world frames.
        if (FORCE_HIDE_GUI && mc.options != null && !mc.options.hideGui) {
            mc.options.hideGui = true;
        }
        // Avoid publishing before the game is actually in-world.
        if (mc.level == null || mc.player == null) {
            return;
        }

        ROS2Manager ros2 = ROS2Manager.getInstance();
        if (!ros2.isInitialized()) {
            return;
        }
        ImagePublisher publisher = ros2.getImagePublisher();
        if (publisher == null) {
            org.slf4j.LoggerFactory.getLogger(RenderEventHandler.class)
                    .warn("ImagePublisher is null (source={})", source);
            return;
        }
        publisher.captureAndPublish();
        lastCaptureAtMs = System.currentTimeMillis();
    }

    private static boolean parseEnvBool(String name, boolean fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim().toLowerCase();
        return trimmed.equals("1") || trimmed.equals("true") || trimmed.equals("yes") || trimmed.equals("on");
    }

    private static long parseEnvLong(String name, long fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseEnvInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
