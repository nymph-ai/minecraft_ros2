package com.kazusa.minecraft_ros2.events;

import com.kazusa.minecraft_ros2.minecraft_ros2;
import com.kazusa.minecraft_ros2.ros2.ROS2Manager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles client-side Forge events for the minecraft_ros2 mod
 */
@Mod.EventBusSubscriber(modid = minecraft_ros2.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ModEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModEventHandler.class);
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static boolean loggedDeathScreenFields = false;

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
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ROS2Manager.getInstance().processClientTick();
            
            Minecraft minecraft = Minecraft.getInstance();
            Player player = minecraft.player;
            
            // Auto-click respawn button on death screen
            if (minecraft.screen instanceof DeathScreen) {
                DeathScreen deathScreen = (DeathScreen) minecraft.screen;
                boolean buttonClicked = false;

                // Try multiple possible field names for the respawn button
                String[] possibleFieldNames = {
                    "respawnButton",  // Common deobfuscated name
                    "f_95918_",       // Possible obfuscated name
                    "f_95919_",       // Alternative obfuscated name
                    "f_",             // Field prefix pattern
                };

                for (String fieldName : possibleFieldNames) {
                    try {
                        java.lang.reflect.Field[] fields = DeathScreen.class.getDeclaredFields();
                        for (java.lang.reflect.Field field : fields) {
                            // Skip exitToTitleButton - we never want to click it
                            if (field.getName().contains("exitToTitle") || field.getName().contains("titleButton")) {
                                continue;
                            }
                            // Check if field name matches or if it's a Button type field
                            if (field.getName().equals(fieldName) ||
                                (field.getType() == Button.class && fieldName.equals("respawnButton"))) {
                                field.setAccessible(true);
                                Object buttonObj = field.get(deathScreen);
                                if (buttonObj instanceof Button) {
                                    Button button = (Button) buttonObj;
                                    if (button.visible && button.active) {
                                        LOGGER.info("Auto-clicking respawn button via field: {}", field.getName());
                                        button.onPress();
                                        buttonClicked = true;
                                        break;
                                    }
                                }
                            }
                        }
                        if (buttonClicked) break;
                    } catch (Exception e) {
                        // Try next field name
                    }
                }

                // Fallback 1: Try to find all Button fields, but skip exitToTitleButton
                if (!buttonClicked) {
                    try {
                        java.lang.reflect.Field[] allFields = DeathScreen.class.getDeclaredFields();
                        for (java.lang.reflect.Field field : allFields) {
                            // Skip exitToTitleButton - we want respawn, not exit
                            if (field.getName().contains("exitToTitle") || field.getName().contains("titleButton")) {
                                continue;
                            }
                            if (field.getType() == Button.class) {
                                field.setAccessible(true);
                                Object buttonObj = field.get(deathScreen);
                                if (buttonObj instanceof Button) {
                                    Button button = (Button) buttonObj;
                                    if (button.visible && button.active) {
                                        LOGGER.info("Auto-clicking button via field scan: {}", field.getName());
                                        button.onPress();
                                        buttonClicked = true;
                                        break;
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.debug("Failed to find button via field scan", e);
                    }
                }

                // Fallback 2: Try to access the children list and find respawn button by checking message content
                if (!buttonClicked) {
                    try {
                        // Try different possible field names for children
                        String[] childrenFieldNames = {"children", "f_96541_", "renderables"};
                        for (String childFieldName : childrenFieldNames) {
                            try {
                                java.lang.reflect.Field childrenField = net.minecraft.client.gui.screens.Screen.class.getDeclaredField(childFieldName);
                                childrenField.setAccessible(true);
                                Object childrenObj = childrenField.get(deathScreen);
                                if (childrenObj instanceof java.util.List) {
                                    java.util.List<?> children = (java.util.List<?>) childrenObj;
                                    // First pass: try to find respawn button by checking button message
                                    for (Object child : children) {
                                        if (child instanceof Button) {
                                            Button button = (Button) child;
                                            if (button.visible && button.active) {
                                                try {
                                                    // Try to get the button's message/text
                                                    java.lang.reflect.Method getMessageMethod = Button.class.getMethod("getMessage");
                                                    Object messageObj = getMessageMethod.invoke(button);
                                                    String message = messageObj.toString().toLowerCase();
                                                    // Check if this is the respawn button (avoid exit/title buttons)
                                                    if (message.contains("respawn") || (!message.contains("exit") && !message.contains("title"))) {
                                                        LOGGER.info("Auto-clicking respawn button via children list: {}", message);
                                                        button.onPress();
                                                        buttonClicked = true;
                                                        break;
                                                    }
                                                } catch (Exception e) {
                                                    // If we can't check the message, skip this button
                                                }
                                            }
                                        }
                                    }
                                }
                                if (buttonClicked) break;
                            } catch (NoSuchFieldException e) {
                                // Try next field name
                            }
                        }
                    } catch (Exception ex) {
                        LOGGER.debug("Failed to click button via children list", ex);
                    }
                }

                if (!buttonClicked) {
                    // Log all available fields once for debugging
                    if (!loggedDeathScreenFields) {
                        LOGGER.warn("Failed to auto-click respawn button. Available DeathScreen fields:");
                        for (java.lang.reflect.Field field : DeathScreen.class.getDeclaredFields()) {
                            LOGGER.warn("  - {} ({})", field.getName(), field.getType().getSimpleName());
                        }
                        loggedDeathScreenFields = true;
                    }
                }
            }
            
            // Aggressively restore health if player exists and has low/zero health (prevents death loop)
            // This runs every tick as a safety net
            if (player != null && minecraft.level != null) {
                restorePlayerHealth(player);
            }
        }
    }
    
    /**
     * Handle player respawn/clone event - restore health immediately when player respawns
     * This fires when the player entity is cloned during respawn
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        // Only handle on client side
        if (event.getEntity().level().isClientSide) {
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
        if (event.getEntity().level().isClientSide) {
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
