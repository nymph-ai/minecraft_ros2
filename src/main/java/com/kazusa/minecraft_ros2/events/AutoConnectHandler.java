package com.kazusa.minecraft_ros2.events;

import com.kazusa.minecraft_ros2.minecraft_ros2;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod.EventBusSubscriber(modid = minecraft_ros2.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class AutoConnectHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoConnectHandler.class);
    private static boolean hasConnected = false;
    private static boolean initialized = false;

    private static int tickCount = 0;
    private static final int TICKS_BEFORE_CONNECT = 100; // Wait ~5 seconds (20 ticks/sec)

    static {
        LOGGER.info("[AutoConnect] AutoConnectHandler class loaded and registered");
    }

    @SuppressWarnings("removal")
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        // Use tick event as backup if screen event doesn't fire
        if (event.phase != TickEvent.Phase.END || hasConnected) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null && minecraft.screen != null) {
            tickCount++;

            if (tickCount == 1) {
                LOGGER.info("[AutoConnect] Client tick detected, waiting for title screen...");
            }

            if (tickCount >= TICKS_BEFORE_CONNECT) {
                LOGGER.info("[AutoConnect] Tick threshold reached, attempting auto-connect via tick event");
                hasConnected = true;
                attemptConnection(minecraft, minecraft.screen);
            }
        }
    }

    @SubscribeEvent
    public static void onScreenOpen(ScreenEvent.Opening event) {
        if (!initialized) {
            LOGGER.info("[AutoConnect] First screen event detected: {}", event.getScreen().getClass().getName());
            initialized = true;
        }

        // Only auto-connect once, when the title screen first appears
        if (!hasConnected && event.getScreen() instanceof TitleScreen) {
            hasConnected = true;
            LOGGER.info("[AutoConnect] Title screen detected! Attempting to connect to server via screen event");
            attemptConnection(Minecraft.getInstance(), event.getScreen());
        }
    }

    private static void attemptConnection(Minecraft minecraft, net.minecraft.client.gui.screens.Screen currentScreen) {
        // Get server address from environment variable or use default
        final String serverAddress = System.getenv("MINECRAFT_SERVER_ADDRESS");
        final String finalAddress = (serverAddress == null || serverAddress.isEmpty())
            ? "minecraft-server:25565"
            : serverAddress;

        LOGGER.info("[AutoConnect] Connecting to server: {}", finalAddress);

        // Create server data
        ServerData serverData = new ServerData("Auto-Connect Server", finalAddress, ServerData.Type.OTHER);

        // Schedule connection on the main thread
        minecraft.execute(() -> {
            try {
                LOGGER.info("[AutoConnect] Initiating connection...");
                net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                    currentScreen,
                    minecraft,
                    ServerAddress.parseString(finalAddress),
                    serverData,
                    false,
                    null
                );
            } catch (Exception e) {
                LOGGER.error("[AutoConnect] Failed to connect to server: {}", finalAddress, e);
            }
        });
    }
}
