package com.kazusa.minecraft_ros2.auto;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.SubscribeEvent;
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
    private boolean waitingLogged = false;

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
        NeoForge.EVENT_BUS.register(new AutoConnectManager(endpoint));
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null || minecraft.getConnection() != null) {
            return;
        }
        Screen current = minecraft.screen;
        if (current instanceof AccessibilityOnboardingScreen) {
            minecraft.setScreen(new TitleScreen());
            current = minecraft.screen;
        }
        if (current instanceof ConnectScreen) {
            return;
        }
        if (current != null
            && !(current instanceof TitleScreen)
            && !(current instanceof DisconnectedScreen)) {
            if (!waitingLogged) {
                LOGGER.info("Auto-connect waiting for title screen, current screen={}", current.getClass().getSimpleName());
                waitingLogged = true;
            }
            return;
        }
        if (current == null && !waitingLogged) {
            LOGGER.info("Auto-connect waiting for title screen, current screen=null");
            waitingLogged = true;
        }
        if (attempts >= MAX_ATTEMPTS) {
            return;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        ensureSkipWarning(minecraft);

        attempts++;
        cooldownTicks = RETRY_DELAY_TICKS;
        LOGGER.info("Auto-connect attempt {}/{} to {}", attempts, MAX_ATTEMPTS, endpoint.displayLabel());

        Screen parent = current != null ? current : new TitleScreen();
        ServerData serverData = new ServerData("minecraft_ros2", endpoint.displayLabel(), ServerData.Type.OTHER);
        ServerAddress address = endpoint.toServerAddress();
        ConnectScreen.startConnecting(parent, minecraft, address, serverData, false, (TransferState) null);
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
