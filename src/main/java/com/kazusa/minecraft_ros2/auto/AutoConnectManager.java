package com.kazusa.minecraft_ros2.auto;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Forces the headless client to auto-connect to the configured server once the title screen is ready.
 * This bypasses the multiplayer warning dialog and keeps retrying until either the join succeeds or
 * a small retry budget is exhausted.
 */
public final class AutoConnectManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoConnectManager.class);
    private static final int RETRY_DELAY_TICKS = 100; // 5 seconds
    private static final int MAX_ATTEMPTS = 12;

    private final Endpoint endpoint;
    private int attempts = 0;
    private int cooldownTicks = 0;
    private boolean skipInjected = false;

    private AutoConnectManager(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    public static void installFromEnv() {
        String raw = System.getenv("MC_SERVER");
        if (raw == null || raw.isBlank()) {
            LOGGER.info("MC_SERVER is not set; auto-connect disabled.");
            return;
        }

        Endpoint endpoint = Endpoint.parse(raw.trim());
        if (endpoint == null) {
            LOGGER.warn("Failed to parse MC_SERVER value '{}'; expected host[:port]. Auto-connect disabled.", raw);
            return;
        }

        LOGGER.info("Enabling auto-connect for {}", endpoint.displayLabel());
        MinecraftForge.EVENT_BUS.register(new AutoConnectManager(endpoint));
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }

        if (minecraft.level != null && minecraft.player != null) {
            LOGGER.info("Auto-connect complete; player is in a world.");
            MinecraftForge.EVENT_BUS.unregister(this);
            return;
        }

        if (attempts >= MAX_ATTEMPTS) {
            LOGGER.warn("Giving up after {} auto-connect attempts.", MAX_ATTEMPTS);
            MinecraftForge.EVENT_BUS.unregister(this);
            return;
        }

        if (minecraft.screen == null || minecraft.screen instanceof ConnectScreen) {
            return;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        ensureSkipWarning(minecraft);
        attempts++;
        cooldownTicks = RETRY_DELAY_TICKS;

        ServerAddress address;
        try {
            address = endpoint.toServerAddress();
        } catch (IllegalArgumentException ex) {
            LOGGER.error("MC_SERVER value '{}' is not a valid address; disabling auto-connect.", endpoint.rawInput(), ex);
            MinecraftForge.EVENT_BUS.unregister(this);
            return;
        }
        ServerData serverData = new ServerData("minecraft_ros2", endpoint.rawInput(), ServerData.Type.OTHER);
        LOGGER.info("Auto-connect attempt {} to {}", attempts, address);
        ConnectScreen.startConnecting(minecraft.screen, minecraft, address, serverData, false, (TransferState) null);
    }

    private void ensureSkipWarning(Minecraft minecraft) {
        if (skipInjected) {
            return;
        }

        try {
            Options options = minecraft.options;
            Field field = Options.class.getDeclaredField("skipMultiplayerWarning");
            field.setAccessible(true);

            if (field.getType() == boolean.class) {
                if (!field.getBoolean(options)) {
                    field.setBoolean(options, true);
                    options.save();
                }
            } else {
                Object option = field.get(options);
                invokeBooleanSetter(option);
                invokeSave(options);
            }
        } catch (Exception ex) {
            LOGGER.debug("Unable to force skip of multiplayer warning", ex);
        } finally {
            skipInjected = true;
        }
    }

    private void invokeBooleanSetter(Object option) {
        if (option == null) {
            return;
        }

        for (String methodName : new String[]{"setValue", "set"}) {
            try {
                Method setter = option.getClass().getMethod(methodName, Object.class);
                setter.invoke(option, Boolean.TRUE);
                return;
            } catch (ReflectiveOperationException ignored) {
                // Try the next candidate
            }
        }
    }

    private void invokeSave(Options options) {
        try {
            options.save();
        } catch (Exception e) {
            try {
                Method saveMethod = Options.class.getMethod("save");
                saveMethod.invoke(options);
            } catch (ReflectiveOperationException ignored) {
                // Nothing else we can do; Minecraft will eventually persist the option anyway.
            }
        }
    }

    private record Endpoint(String rawInput) {
        private static Endpoint parse(String raw) {
            String target = raw.trim();
            if (target.isEmpty()) {
                return null;
            }

            return new Endpoint(target);
        }

        private ServerAddress toServerAddress() {
            return ServerAddress.parseString(rawInput);
        }

        private String displayLabel() {
            return rawInput;
        }
    }
}

