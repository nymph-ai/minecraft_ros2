package com.kazusa.minecraft_ros2;

import com.kazusa.minecraft_ros2.auto.AutoConnectManager;
import com.kazusa.minecraft_ros2.config.Config;
import com.kazusa.minecraft_ros2.items.BlockItems;
import com.kazusa.minecraft_ros2.network.NetworkHandler;
import com.kazusa.minecraft_ros2.ros2.ModItems;
import com.kazusa.minecraft_ros2.ros2.ROS2Manager;
import com.kazusa.minecraft_ros2.block.ModBlocks;
import com.kazusa.minecraft_ros2.block.ModBlockEntities;
import com.kazusa.minecraft_ros2.menu.ModMenuTypes;
import com.kazusa.minecraft_ros2.models.ModEntities;
import com.kazusa.minecraft_ros2.utils.GeometryApplier;
import net.neoforged.bus.api.IEventBus;
import com.kazusa.minecraft_ros2.graphics.ModelHandler;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Mod(minecraft_ros2.MOD_ID)
public class minecraft_ros2 {
    public static final String MOD_ID = "minecraft_ros2";
    private static final Logger LOGGER = LoggerFactory.getLogger(minecraft_ros2.class);
    private static final String WORLD_CONTENT_ENV = "MINECRAFT_ROS2_ENABLE_WORLD_CONTENT";
    private static final boolean WORLD_CONTENT_ENABLED = Boolean.parseBoolean(
        System.getenv().getOrDefault(WORLD_CONTENT_ENV, "false")
    );

    public static boolean isDeprecatedWorldContentEnabled() {
        return WORLD_CONTENT_ENABLED;
    }

    public minecraft_ros2(IEventBus modBus, ModContainer modContainer) throws NoSuchFieldException, IllegalAccessException {
        LOGGER.info("Initializing minecraft_ros2 mod");

        // Register the setup methods for mod loading
        modBus.addListener(this::setup);
        modBus.addListener(NetworkHandler::register);
        if (WORLD_CONTENT_ENABLED) {
            modBus.addListener(com.kazusa.minecraft_ros2.events.CommonModEventSubscriber::onEntityAttributeCreation);
        } else {
            LOGGER.warn("Deprecated world content is disabled. Set {}=true to re-enable (modded servers only).",
                WORLD_CONTENT_ENV);
        }
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            GeometryApplier.initResourcePack();
            modBus.addListener(this::clientSetup);
            if (WORLD_CONTENT_ENABLED) {
                modBus.addListener(com.kazusa.minecraft_ros2.events.ClientModEventSubscriber::onRegisterRenderers);
                modBus.addListener(com.kazusa.minecraft_ros2.events.ClientModEventSubscriber::onAddPackFinders);
                modBus.addListener(com.kazusa.minecraft_ros2.events.ClientModEventSubscriber::onRegisterMenuScreens);
            }
            ModelHandler.register(modBus);
        }

        // Register the configuration
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.COMMON_SPEC);
        modBus.addListener(this::onConfigLoad);

        // No instance-level NeoForge event listeners are registered here.

        ModItems.register(modBus);
        if (WORLD_CONTENT_ENABLED) {
            BlockItems.ITEMS.register(modBus);
            ModBlocks.BLOCKS.register(modBus);
            ModBlockEntities.register(modBus);
            ModMenuTypes.MENUS.register(modBus);
            ModEntities.register(modBus);
        }

        LOGGER.info("minecraft_ros2 mod initialized");
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("minecraft_ros2 common setup");
        // Performed for both client and server setup
    }


    @net.neoforged.api.distmarker.OnlyIn(Dist.CLIENT)
    private void clientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("minecraft_ros2 client setup");

        event.enqueueWork(() -> {
            try {
                LOGGER.info("Attempting to initialize ROS2 during client setup...");
                ROS2Manager.getInstance().initialize();
                AutoConnectManager.installFromEnv();
            } catch (Exception e) {
                LOGGER.error("Failed to initialize ROS2 during client setup", e);
            }
        });
    }

    private void onConfigLoad(final ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == Config.COMMON_SPEC) {
            LOGGER.info("Loading minecraft_ros2 configuration");
            // Configuration values are accessed directly from Config.COMMON
        }
    }
}
