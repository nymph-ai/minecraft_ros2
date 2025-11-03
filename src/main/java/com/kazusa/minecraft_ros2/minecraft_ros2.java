package com.kazusa.minecraft_ros2;

import com.kazusa.minecraft_ros2.config.Config;
import com.kazusa.minecraft_ros2.items.BlockItems;
import com.kazusa.minecraft_ros2.ros2.CreativeTabEvents;
import com.kazusa.minecraft_ros2.ros2.ModItems;
import com.kazusa.minecraft_ros2.ros2.ROS2Manager;
import com.kazusa.minecraft_ros2.block.ModBlocks;
import com.kazusa.minecraft_ros2.block.ModBlockEntities;
import com.kazusa.minecraft_ros2.menu.ModMenuTypes;
import com.kazusa.minecraft_ros2.models.ModEntities;
import com.kazusa.minecraft_ros2.utils.GeometryApplier;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Mod(minecraft_ros2.MOD_ID)
public class minecraft_ros2 {
    public static final String MOD_ID = "minecraft_ros2";
    private static final Logger LOGGER = LoggerFactory.getLogger(minecraft_ros2.class);

    public minecraft_ros2(FMLJavaModLoadingContext context) throws NoSuchFieldException, IllegalAccessException {
        LOGGER.info("Initializing minecraft_ros2 mod");

        GeometryApplier.initResourcePack();

        var modBusGroup = context.getModBusGroup();

        FMLCommonSetupEvent.getBus(modBusGroup).addListener(this::setup);
        FMLClientSetupEvent.getBus(modBusGroup).addListener(this::clientSetup);

        context.registerConfig(ModConfig.Type.COMMON, Config.COMMON_SPEC);

        ModItems.ITEMS.register(modBusGroup);
        BlockItems.ITEMS.register(modBusGroup);
        ModBlocks.BLOCKS.register(modBusGroup);
        ModBlockEntities.BLOCK_ENTITIES.register(modBusGroup);
        ModMenuTypes.MENUS.register(modBusGroup);
        ModEntities.ENTITIES.register(modBusGroup);
        BuildCreativeModeTabContentsEvent.BUS.addListener(CreativeTabEvents::handle);

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

    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModConfigEvents {
        private ModConfigEvents() {
        }

        @SubscribeEvent
        public static void handleConfig(final ModConfigEvent.Loading event) {
            if (event.getConfig().getSpec() == Config.COMMON_SPEC) {
                LOGGER.info("Loading minecraft_ros2 configuration");
            }
        }
    }
}
