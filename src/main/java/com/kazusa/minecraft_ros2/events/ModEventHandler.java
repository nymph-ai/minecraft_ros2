package com.kazusa.minecraft_ros2.events;

import com.kazusa.minecraft_ros2.minecraft_ros2;
import com.kazusa.minecraft_ros2.ros2.ROS2Manager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles client-side NeoForge events for the minecraft_ros2 mod
 */
@EventBusSubscriber(modid = minecraft_ros2.MOD_ID, value = Dist.CLIENT)
public class ModEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModEventHandler.class);
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static boolean loggedDeathScreenFields = false;
    private static boolean respawnRequested = false;

    /**
     * Restore player health to max - called from multiple places to ensure it happens
     */
    private static void restorePlayerHealth(Player player) {
        if (player != null) {
            float currentHealth = player.getHealth();
            float maxHealth = player.getMaxHealth();
            if (currentHealth <= 0.0f || currentHealth < maxHealth) {
                player.setHealth(maxHealth);
            }
        }
    }

    /**
     * Apply ROS2 twist commands every client tick
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ROS2Manager.getInstance().processClientTick();

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (minecraft.screen instanceof DeathScreen && player != null) {
            if (!respawnRequested) {
                respawnRequested = true;
                player.respawn();
            }
        } else {
            respawnRequested = false;
        }

        // Keep ROS2 tick processing; auto-respawn removed for 1.21 API changes
        if (player != null && minecraft.level != null) {
            restorePlayerHealth(player);
        }
    }
    
    /**
     * Handle player respawn/clone event - restore health immediately when player respawns
     * This fires when the player entity is cloned during respawn
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        // Only handle on client side
        if (event.getEntity().level().isClientSide()) {
            Player newPlayer = event.getEntity();
            // Schedule health restoration immediately and with a small delay to catch timing issues
            restorePlayerHealth(newPlayer);
            // Also schedule a delayed check in case the player isn't fully loaded yet
            scheduler.schedule(() -> {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft != null && minecraft.player != null) {
                    restorePlayerHealth(minecraft.player);
                }
            }, 100, TimeUnit.MILLISECONDS); // 100ms = 2 ticks
        }
    }
    
    /**
     * Handle player respawn event - restore health when player respawns
     */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        // Only handle on client side
        if (event.getEntity().level().isClientSide()) {
            Player player = event.getEntity();
            restorePlayerHealth(player);
            // Schedule delayed check
            scheduler.schedule(() -> {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft != null && minecraft.player != null) {
                    restorePlayerHealth(minecraft.player);
                }
            }, 200, TimeUnit.MILLISECONDS); // 200ms = 4 ticks
        }
    }

}
