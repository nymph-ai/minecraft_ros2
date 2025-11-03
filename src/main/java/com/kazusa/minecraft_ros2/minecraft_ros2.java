package com.kazusa.minecraft_ros2;

import com.kazusa.minecraft_ros2.config.Config;
import com.kazusa.minecraft_ros2.graphics.ModelHandler;
import com.kazusa.minecraft_ros2.items.BlockItems;
import com.kazusa.minecraft_ros2.ros2.ModItems;
import com.kazusa.minecraft_ros2.ros2.ROS2Manager;
import com.kazusa.minecraft_ros2.block.ModBlocks;
import com.kazusa.minecraft_ros2.block.ModBlockEntities;
import com.kazusa.minecraft_ros2.menu.ModMenuTypes;
import com.kazusa.minecraft_ros2.models.ModEntities;
import com.kazusa.minecraft_ros2.utils.GeometryApplier;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Mod(minecraft_ros2.MOD_ID)
public class minecraft_ros2 {
    public static final String MOD_ID = "minecraft_ros2";
    private static final Logger LOGGER = LoggerFactory.getLogger(minecraft_ros2.class);

    @SuppressWarnings("removal")
    public minecraft_ros2() throws NoSuchFieldException, IllegalAccessException {
        LOGGER.info("Initializing minecraft_ros2 mod");

        GeometryApplier.initResourcePack();

        // Get event bus - suppressing deprecation warning as this remains the correct pattern for Forge 1.21.10
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register the setup methods for mod loading
        modEventBus.addListener(this::setup);
        modEventBus.addListener(this::clientSetup);

        ModEntities.register(modEventBus);

        // Register the configuration - suppressing deprecation warning
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.COMMON_SPEC);
        modEventBus.addListener(this::onConfigLoad);

        // Register this mod to the MinecraftForge event bus
        MinecraftForge.EVENT_BUS.register(this);

        ModItems.register(modEventBus);
        BlockItems.ITEMS.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenuTypes.MENUS.register(modEventBus);
        ModelHandler.register(modEventBus);

        LOGGER.info("minecraft_ros2 mod initialized");
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("minecraft_ros2 common setup");
        // Performed for both client and server setup
    }


    private void clientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("minecraft_ros2 client setup");

        // Initialize ROS2 system on a separate thread to prevent blocking the main thread
        event.enqueueWork(() -> {
            try {
                LOGGER.info("Attempting to initialize ROS2 during client setup...");
                ROS2Manager.getInstance().initialize();
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
