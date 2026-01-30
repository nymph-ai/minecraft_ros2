package com.kazusa.minecraft_ros2.ros2;

import com.kazusa.minecraft_ros2.models.DynamicModelEntity;
import com.kazusa.minecraft_ros2.minecraft_ros2;
import com.kazusa.minecraft_ros2.config.Config;
import com.kazusa.minecraft_ros2.block.RedstonePubSubBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.ros2.rcljava.RCLJava;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simulation_interfaces.msg.Result;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;

/**
 * Manages ROS2 initialization, execution, and shutdown
 */
public final class ROS2Manager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ROS2Manager.class);
    private static ROS2Manager instance;
    
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private ExecutorService executorService;
    private TwistSubscriber twistSubscriber;
    private CommandSubscriber commandSubscriber;
    private InputSubscriber inputSubscriber;
    private ImagePublisher imagePublisher;
    private PointCloudPublisher pointCloudPublisher;
    private IMUPublisher imuPublisher;
    private GroundTruthPublisher groundTruthPublisher;
    private SurroundingBlockArrayPublisher surroundingBlockArrayPublisher;
    private LivingEntitiesPublisher livingEntitiesPublisher;
    private PlayerStatusPublisher playerStatusPublisher;
    private BaritoneSubscriber baritoneSubscriber;
    private BaritonePathPublisher baritonePathPublisher;

    private SpawnEntityService spawnEntityService;
    private DigBlockService digBlockService;
    
    private final boolean isClientEnvironment;
    private final String baritoneEnabledEnv;
    private final String playerStatusEnabledEnv;

    private ROS2Manager() {
        // Private constructor for singleton
        this.isClientEnvironment = FMLEnvironment.getDist() == Dist.CLIENT;
        this.baritoneEnabledEnv = System.getenv("MINECRAFT_ROS2_ENABLE_BARITONE");
        this.playerStatusEnabledEnv = System.getenv("MINECRAFT_ROS2_PLAYER_STATUS_ENABLED");
    }

    public ImagePublisher getImagePublisher() {
        return imagePublisher;
    }

    public BaritoneSubscriber getBaritoneSubscriber() {
        return baritoneSubscriber;
    }


    /**
     * Get the singleton instance of the ROS2Manager
     */
    public static ROS2Manager getInstance() {
        if (instance == null) {
            synchronized (ROS2Manager.class) {
                if (instance == null) {
                    instance = new ROS2Manager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Initialize ROS2 system
     */
    public void initialize() {
        if (!isClientEnvironment) {
            LOGGER.info("Skipping ROS2 initialization on dedicated server.");
            return;
        }
        if (!initialized.getAndSet(true)) {
            try {
                LOGGER.info("Initializing ROS2...");

                RCLJava.rclJavaInit();
                
                // Create subscriber
                commandSubscriber = new CommandSubscriber();
                inputSubscriber = new InputSubscriber();
                if (minecraft_ros2.isDeprecatedWorldContentEnabled()) {
                    spawnEntityService = new SpawnEntityService();
                    digBlockService = new DigBlockService();
                } else {
                    LOGGER.warn("Spawn entity and redstone pub/sub features are deprecated and disabled.");
                }
                twistSubscriber = new TwistSubscriber();
                boolean baritoneEnabled = baritoneEnabledEnv != null
                        ? Boolean.parseBoolean(baritoneEnabledEnv)
                        : Config.COMMON.enableBaritone.get();
                if (baritoneEnabled) {
                    baritoneSubscriber = new BaritoneSubscriber();
                    baritonePathPublisher = new BaritonePathPublisher(
                            baritoneSubscriber.getBaritone()
                    );
                } else {
                    LOGGER.info("Baritone integration disabled. Set enableBaritone=true in config or MINECRAFT_ROS2_ENABLE_BARITONE=true to enable.");
                }
                imagePublisher = new ImagePublisher();
                pointCloudPublisher = new PointCloudPublisher();
                imuPublisher = new IMUPublisher();
                groundTruthPublisher = new GroundTruthPublisher();
                surroundingBlockArrayPublisher = new SurroundingBlockArrayPublisher();

                boolean playerStatusEnabled = playerStatusEnabledEnv != null
                        ? Boolean.parseBoolean(playerStatusEnabledEnv)
                        : true;
                if (playerStatusEnabled) {
                    playerStatusPublisher = new PlayerStatusPublisher();
                } else {
                    LOGGER.info("Player status publisher disabled by env");
                }

                if (Config.COMMON.enableDebugDataStreaming.get()) {
                    LOGGER.info("Debug data stream enabled");
                    livingEntitiesPublisher = new LivingEntitiesPublisher();
                } else {
                    LOGGER.info("Debug data stream disabled");
                }


                // Create and start executor thread for ROS2 spin
                executorService = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "ROS2-Executor");
                    t.setDaemon(true); // Make it a daemon thread so it doesn't prevent game exit
                    return t;
                });
                
                executorService.submit(() -> {
                    LOGGER.info("ROS2 spin thread started");
                    try {
                        while (!Thread.currentThread().isInterrupted() && RCLJava.ok()) {
                            if (twistSubscriber != null) {
                                RCLJava.spinSome(twistSubscriber);
                            }
                            if (commandSubscriber != null) {
                                RCLJava.spinSome(commandSubscriber);
                            }
                            if (inputSubscriber != null) {
                                RCLJava.spinSome(inputSubscriber);
                            }
                            if (baritoneSubscriber != null) {
                                RCLJava.spinSome(baritoneSubscriber);
                            }
                            if (baritonePathPublisher != null) {
                                RCLJava.spinSome(baritonePathPublisher);
                            }
                            if (imagePublisher != null) {
                                RCLJava.spinSome(imagePublisher);
                            }
                            if (pointCloudPublisher != null) {
                                RCLJava.spinSome(pointCloudPublisher);
                            }
                            if (imuPublisher != null) {
                                RCLJava.spinSome(imuPublisher);
                            }
                            if (groundTruthPublisher != null) {
                                RCLJava.spinSome(groundTruthPublisher);
                            }
                            if (surroundingBlockArrayPublisher != null) {
                                RCLJava.spinSome(surroundingBlockArrayPublisher);
                            }

                            if (spawnEntityService != null) {
                                RCLJava.spinSome(spawnEntityService);
                                if (spawnEntityService.spawnedEntities != null) {
                                    for (DynamicModelEntity entity : spawnEntityService.spawnedEntities) {
                                        if (entity.getRobotTwistSubscriber() != null) {
                                            RCLJava.spinSome(entity.getRobotTwistSubscriber());
                                        }
                                    }
                                }
                            }
                            if (digBlockService != null) {
                                RCLJava.spinSome(digBlockService);
                            }

                            if (minecraft_ros2.isDeprecatedWorldContentEnabled()) {
                                Level world = Minecraft.getInstance().level;
                                if (world != null) {
                                    Set<BlockPos> all = RedstonePubSubBlock.getAllInstances();
                                    for (BlockPos pos : all) {
                                        BlockState state = world.getBlockState(pos);
                                        Block block = state.getBlock();
                                        if (block instanceof RedstonePubSubBlock redstoneBlock) {
                                            BlockIntPublisher publisher = redstoneBlock.getPublisher();
                                            if (publisher != null) {
                                                RCLJava.spinSome(publisher);
                                            }
                                            BlockBoolSubscriber subscriber = redstoneBlock.getSubscriber();
                                            if (subscriber != null) {
                                                RCLJava.spinSome(subscriber);
                                            }
                                        }
                                    }
                                }
                            }

                            if (livingEntitiesPublisher != null) {
                                RCLJava.spinSome(livingEntitiesPublisher);
                            }
                            if (playerStatusPublisher != null) {
                                RCLJava.spinSome(playerStatusPublisher);
                            }
                            try {
                                Thread.sleep(5); // Don't hog CPU
                            } catch (InterruptedException e) {
                                LOGGER.info("ROS2 spin thread interrupted");
                                Thread.currentThread().interrupt();
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.error("Error in ROS2 spin thread", e);
                    }
                    LOGGER.info("ROS2 spin thread exiting");
                });
                
                LOGGER.info("ROS2 initialized successfully");
            } catch (Exception e) {
                LOGGER.error("Failed to initialize ROS2", e);
                shutdown();
            }
        }
    }
    
    /**
     * Shutdown ROS2 system
     */
    public void shutdown() {
        if (initialized.getAndSet(false)) {
            LOGGER.info("Shutting down ROS2...");
            
            if (executorService != null) {
                executorService.shutdownNow();
                executorService = null;
            }
            
            try {
                RCLJava.shutdown();
                LOGGER.info("ROS2 shutdown complete");
            } catch (Exception e) {
                LOGGER.error("Error during ROS2 shutdown", e);
            }
            
            twistSubscriber = null;
            commandSubscriber = null;
            inputSubscriber = null;
        }
    }
    
    /**
     * Apply player movement based on the latest twist message
     * Called every game tick
     */
    public void processClientTick() {
        if (!initialized.get()) {
            return; // ROS2 not initialized, skip processing
        }
        if (twistSubscriber != null) {
            twistSubscriber.applyPlayerMovement();
        }
    }

    /**
     * Process movement updates for server-managed entities
     */
    public void processServerTick() {
        if (!initialized.get() || spawnEntityService == null) {
            return;
        }
        if (spawnEntityService.spawnedEntities != null) {
            for (DynamicModelEntity entity : spawnEntityService.spawnedEntities) {
                if (entity.getRobotTwistSubscriber() != null) {
                    entity.getRobotTwistSubscriber().applyEntityMovement();
                }
            }
        }
    }
    
    /**
     * Check if ROS2 is currently initialized
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    /**
     * Exposed entry point for Forge commands to spawn entities.
     */
    public boolean spawnEntityFromCommand(String name, String namespace, String uri,
                                          double x, double y, double z) {
        LOGGER.warn("Spawn entity via command is not supported in client-only ROS configuration.");
        return false;
    }

}
