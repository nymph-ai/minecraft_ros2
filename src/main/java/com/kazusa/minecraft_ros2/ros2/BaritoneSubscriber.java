package com.kazusa.minecraft_ros2.ros2;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.ros2.rcljava.node.BaseComposableNode;
import org.ros2.rcljava.qos.QoSProfile;
import org.ros2.rcljava.subscription.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import geometry_msgs.msg.PoseStamped;
import geometry_msgs.msg.PointStamped;
import std_msgs.msg.Empty;
import java.util.concurrent.TimeUnit;

/**
 * ROS2 Subscriber for Baritone pathfinding commands.
 * Accepts goal positions via ROS2 topics and uses Baritone to navigate.
 */
public class BaritoneSubscriber extends BaseComposableNode {
    private static final Logger LOGGER = LoggerFactory.getLogger(BaritoneSubscriber.class);

    private final Subscription<PoseStamped> poseGoalSubscription;
    private final Subscription<PointStamped> pointGoalSubscription;
    private final Subscription<std_msgs.msg.String> followSubscription;
    private final Subscription<Empty> cancelSubscription;
    private final Subscription<std_msgs.msg.String> commandSubscription;
    private final org.ros2.rcljava.publisher.Publisher<std_msgs.msg.String> statePublisher;
    private final Minecraft minecraft;
    private IBaritone baritone;
    private final baritone.api.IBaritoneProvider baritoneProvider;
    private String followTargetName;
    private final double followDistance;
    private final long followUpdateMs;
    private final long followStaleMs;
    private long lastSeenAtMs = 0L;
    private Double lastSeenX = null;
    private Double lastSeenZ = null;
    private long lastMissingLogMs = 0L;
    private final boolean debugBaritoneCommand;
    private long lastGoalMs = 0L;

    public BaritoneSubscriber() {
        super("baritone_subscriber");
        this.minecraft = Minecraft.getInstance();
        this.baritoneProvider = BaritoneAPI.getProvider();

        // Initialize Baritone
        try {
            this.baritone = resolveBaritone();
            LOGGER.info("Baritone initialized successfully ({})", describeBaritone());
        } catch (Exception e) {
            LOGGER.error("Failed to initialize Baritone", e);
        }

        // Subscribe to /baritone/goal/pose for full 3D navigation
        poseGoalSubscription = this.node.createSubscription(
            PoseStamped.class,
            "/baritone/goal/pose",
            this::handlePoseGoal,
            QoSProfile.DEFAULT
        );

        // Subscribe to /baritone/goal/point for simpler XZ navigation (Y auto-calculated)
        pointGoalSubscription = this.node.createSubscription(
            PointStamped.class,
            "/baritone/goal/point",
            this::handlePointGoal,
            QoSProfile.DEFAULT
        );

        followDistance = parseEnvDouble("BARITONE_FOLLOW_DISTANCE", 3.0);
        followUpdateMs = parseEnvLong("BARITONE_FOLLOW_UPDATE_MS", 500L);
        followStaleMs = parseEnvLong("BARITONE_FOLLOW_STALE_MS", 30000L);
        debugBaritoneCommand = parseEnvBool("MINECRAFT_ROS2_DEBUG_BARITONE_CMD", false);

        followSubscription = this.node.createSubscription(
            std_msgs.msg.String.class,
            "/baritone/follow",
            this::handleFollowTarget,
            QoSProfile.DEFAULT
        );

        cancelSubscription = this.node.createSubscription(
            Empty.class,
            "/baritone/cancel",
            msg -> cancelPath(),
            QoSProfile.DEFAULT
        );

        commandSubscription = this.node.createSubscription(
            std_msgs.msg.String.class,
            "/baritone/command",
            this::handleBaritoneCommand,
            QoSProfile.DEFAULT
        );

        statePublisher = this.node.createPublisher(std_msgs.msg.String.class, "/baritone/state");
        this.node.createWallTimer(200, TimeUnit.MILLISECONDS, this::publishState);

        this.node.createWallTimer(followUpdateMs, TimeUnit.MILLISECONDS, this::updateFollowGoal);

        LOGGER.info(
            "BaritoneSubscriber initialized - listening on /baritone/goal/pose, /baritone/goal/point, /baritone/follow, /baritone/cancel, /baritone/command"
        );
    }

    /**
     * Handle full 3D pose goal (X, Y, Z coordinates)
     */
    private void handlePoseGoal(PoseStamped msg) {
        if (!ensureBaritoneReady() || minecraft.player == null) {
            return;
        }

        try {
            // Extract position from message
            double x = msg.getPose().getPosition().getX();
            double y = msg.getPose().getPosition().getY();
            double z = msg.getPose().getPosition().getZ();

            // Convert to BlockPos
            BlockPos targetPos = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));

            LOGGER.info("Received Baritone goal (Pose): {} {} {}", targetPos.getX(), targetPos.getY(), targetPos.getZ());

            // Schedule on main thread - Baritone requires main thread execution
            minecraft.execute(() -> {
                try {
                    logClientState("pose");
                    var customGoalProcess = baritone.getCustomGoalProcess();
                    customGoalProcess.setGoalAndPath(new GoalBlock(targetPos));
                    lastGoalMs = System.currentTimeMillis();
                    LOGGER.info("Baritone pathfinding started to {}", targetPos);
                    logPathState("pose");
                    schedulePathStateLog("pose", 500);
                    scheduleDebugFallback("pose", targetPos.getX(), targetPos.getZ(), 750);
                } catch (Exception e) {
                    LOGGER.error("Error executing Baritone pose goal on main thread", e);
                }
            });
        } catch (Exception e) {
            LOGGER.error("Error processing Baritone pose goal", e);
        }
    }

    /**
     * Handle 2D point goal (X, Z coordinates only - Y is auto-calculated)
     */
    private void handlePointGoal(PointStamped msg) {
        if (!ensureBaritoneReady() || minecraft.player == null) {
            return;
        }

        try {
            // Extract XZ position from message
            double x = msg.getPoint().getX();
            double z = msg.getPoint().getZ();
            final int goalX = (int) Math.floor(x);
            final int goalZ = (int) Math.floor(z);

            LOGGER.info("Received Baritone goal (Point XZ): {} {}", goalX, goalZ);

            // Schedule on main thread - Baritone requires main thread execution
            minecraft.execute(() -> {
                try {
                    logClientState("point");
                    var customGoalProcess = baritone.getCustomGoalProcess();
                    var pathingBehavior = baritone.getPathingBehavior();

                    LOGGER.info("Baritone state before: isActive={}, isPathing={}, goal={}",
                        customGoalProcess.isActive(),
                        pathingBehavior.isPathing(),
                        customGoalProcess.getGoal());

                    customGoalProcess.setGoalAndPath(new GoalXZ(goalX, goalZ));
                    lastGoalMs = System.currentTimeMillis();

                    LOGGER.info("Baritone state after: isActive={}, isPathing={}, goal={}",
                        customGoalProcess.isActive(),
                        pathingBehavior.isPathing(),
                        customGoalProcess.getGoal());

                    LOGGER.info("Baritone pathfinding executed on main thread to XZ: {} {}", goalX, goalZ);
                    logPathState("point");
                    schedulePathStateLog("point", 500);
                    scheduleDebugFallback("point", goalX, goalZ, 750);
                } catch (Exception e) {
                    LOGGER.error("Error executing Baritone point goal on main thread", e);
                }
            });
        } catch (Exception e) {
            LOGGER.error("Error processing Baritone point goal", e);
        }
    }

    private void handleFollowTarget(std_msgs.msg.String msg) {
        if (msg == null) {
            return;
        }
        java.lang.String target = msg.getData();
        if (target == null || target.trim().isEmpty()) {
            followTargetName = null;
            return;
        }
        java.lang.String trimmed = target.trim();
        if (trimmed.equalsIgnoreCase("off") || trimmed.equalsIgnoreCase("stop")) {
            followTargetName = null;
            cancelPath();
            return;
        }
        followTargetName = trimmed;
        LOGGER.info("Baritone follow target set to {}", followTargetName);
    }

    private void handleBaritoneCommand(std_msgs.msg.String msg) {
        if (msg == null || msg.getData() == null) {
            return;
        }
        String command = msg.getData().trim();
        if (command.isEmpty()) {
            return;
        }
        if (!ensureBaritoneReady()) {
            return;
        }
        if (command.startsWith("#")) {
            command = command.substring(1);
        }
        final String commandFinal = command;
        minecraft.execute(() -> {
            try {
                boolean ok = baritone.getCommandManager().execute(commandFinal);
                LOGGER.info("Baritone command '{}' -> {}", commandFinal, ok);
            } catch (Exception e) {
                LOGGER.error("Baritone command failed: {}", commandFinal, e);
            }
        });
    }

    private void updateFollowGoal() {
        if (followTargetName == null || !ensureBaritoneReady() || minecraft.player == null || minecraft.level == null) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        Player target = null;
        for (Player player : minecraft.level.players()) {
            if (player == null || player == minecraft.player) {
                continue;
            }
            if (player.getName().getString().equalsIgnoreCase(followTargetName)) {
                target = player;
                break;
            }
        }
        if (target == null) {
            if (nowMs - lastMissingLogMs > 5000L) {
                String names = minecraft.level.players().stream()
                    .filter(p -> p != null && p != minecraft.player)
                    .map(p -> p.getName().getString())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(none)");
                LOGGER.info("Follow target '{}' not visible. Nearby players: {}", followTargetName, names);
                lastMissingLogMs = nowMs;
            }
            if (lastSeenX == null || lastSeenZ == null || nowMs - lastSeenAtMs > followStaleMs) {
                return;
            }
        }

        double botX = minecraft.player.getX();
        double botZ = minecraft.player.getZ();
        double targetX;
        double targetZ;
        if (target != null) {
            targetX = target.getX();
            targetZ = target.getZ();
            lastSeenX = targetX;
            lastSeenZ = targetZ;
            lastSeenAtMs = nowMs;
        } else {
            targetX = lastSeenX;
            targetZ = lastSeenZ;
        }

        double dx = targetX - botX;
        double dz = targetZ - botZ;
        double dist = Math.hypot(dx, dz);

        double goalX = targetX;
        double goalZ = targetZ;
        if (dist > 0.001 && followDistance > 0.0) {
            double scale = followDistance / dist;
            goalX = targetX - dx * scale;
            goalZ = targetZ - dz * scale;
        }

        final int finalGoalX = (int) Math.floor(goalX);
        final int finalGoalZ = (int) Math.floor(goalZ);
        minecraft.execute(() -> {
            baritone.getCustomGoalProcess().setGoalAndPath(
                new GoalXZ(finalGoalX, finalGoalZ)
            );
        });
    }

    private void publishState() {
        if (!ensureBaritoneReady()) {
            return;
        }
        var pathingBehavior = baritone.getPathingBehavior();
        boolean isPathing = pathingBehavior.isPathing();
        boolean hasPath = pathingBehavior.getPath().isPresent();
        String goalStr = String.valueOf(pathingBehavior.getGoal());
        String msg = String.format(
            "{\"is_pathing\":%s,\"has_path\":%s,\"goal\":%s,\"last_goal_ms\":%d}",
            isPathing,
            hasPath,
            goalStr == null ? "null" : "\"" + goalStr.replace("\"", "'") + "\"",
            lastGoalMs
        );
        std_msgs.msg.String out = new std_msgs.msg.String();
        out.setData(msg);
        statePublisher.publish(out);
    }

    /**
     * Cancel current pathfinding
     */
    public void cancelPath() {
        if (ensureBaritoneReady()) {
            minecraft.execute(() -> {
                baritone.getPathingBehavior().cancelEverything();
                LOGGER.info("Baritone pathfinding cancelled");
            });
        }
    }

    /**
     * Check if Baritone is currently pathing
     */
    public boolean isPathing() {
        return ensureBaritoneReady() && baritone.getPathingBehavior().isPathing();
    }

    /**
     * Get current Baritone instance
     */
    public IBaritone getBaritone() {
        return baritone;
    }

    private boolean ensureBaritoneReady() {
        if (baritone == null) {
            baritone = resolveBaritone();
            return baritone != null;
        }
        try {
            if (baritone.getPlayerContext().world() == null) {
                IBaritone refreshed = resolveBaritone();
                if (refreshed != null && refreshed != baritone) {
                    baritone = refreshed;
                    LOGGER.info("Baritone context refreshed ({})", describeBaritone());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Baritone context check failed: {}", e.toString());
        }
        return baritone != null;
    }

    private IBaritone resolveBaritone() {
        if (baritoneProvider == null) {
            return null;
        }
        if (minecraft.player != null) {
            IBaritone byPlayer = baritoneProvider.getBaritoneForPlayer(minecraft.player);
            if (byPlayer != null) {
                return byPlayer;
            }
        }
        IBaritone byMinecraft = baritoneProvider.getBaritoneForMinecraft(minecraft);
        if (byMinecraft != null) {
            return byMinecraft;
        }
        return baritoneProvider.getPrimaryBaritone();
    }

    private String describeBaritone() {
        if (baritone == null) {
            return "none";
        }
        Object world = null;
        try {
            world = baritone.getPlayerContext().world();
        } catch (Exception e) {
            return "error";
        }
        return world == null ? "world=null" : "world=ok";
    }

    private void logClientState(String source) {
        boolean paused = minecraft.isPaused();
        Screen screen = minecraft.screen;
        String screenName = screen == null ? "none" : screen.getClass().getSimpleName();
        boolean hasLevel = minecraft.level != null;
        boolean hasConnection = minecraft.getConnection() != null;
        Player player = minecraft.player;
        String pos = player == null
            ? "(unknown)"
            : String.format("(%.2f, %.2f, %.2f)", player.getX(), player.getY(), player.getZ());
        LOGGER.info(
            "Baritone {} goal client state: paused={}, screen={}, level={}, connection={}, player={}",
            source,
            paused,
            screenName,
            hasLevel,
            hasConnection,
            pos
        );
    }

    private void logPathState(String source) {
        if (baritone == null) {
            return;
        }
        var pathingBehavior = baritone.getPathingBehavior();
        boolean hasPath = pathingBehavior.getPath().isPresent();
        String worldDesc;
        try {
            Object world = baritone.getPlayerContext().world();
            worldDesc = world == null ? "null" : world.getClass().getSimpleName();
        } catch (Throwable t) {
            worldDesc = "error:" + t.getClass().getSimpleName();
        }
        LOGGER.info("Baritone {} goal path state: isPathing={}, hasPath={}, goal={}, processGoal={}",
            source,
            pathingBehavior.isPathing(),
            hasPath,
            pathingBehavior.getGoal(),
            baritone.getCustomGoalProcess().getGoal());
        LOGGER.info("Baritone {} world state: {}", source, worldDesc);
    }

    private void schedulePathStateLog(String source, long delayMs) {
        if (baritone == null) {
            return;
        }
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            minecraft.execute(() -> logPathState(source + "+" + delayMs + "ms"));
        }, "baritone-path-log").start();
    }

    private void scheduleDebugFallback(String source, int goalX, int goalZ, long delayMs) {
        if (baritone == null || !debugBaritoneCommand) {
            return;
        }
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            minecraft.execute(() -> {
                var pathingBehavior = baritone.getPathingBehavior();
                if (pathingBehavior.isPathing() || pathingBehavior.getPath().isPresent()) {
                    return;
                }
                String command = String.format("goto %d %d", goalX, goalZ);
                boolean ok = baritone.getCommandManager().execute(command);
                LOGGER.warn("Baritone {} debug fallback: execute '{}' -> {}", source, command, ok);
            });
        }, "baritone-debug-fallback").start();
    }

    private static boolean parseEnvBool(String name, boolean fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim().toLowerCase();
        return trimmed.equals("1") || trimmed.equals("true") || trimmed.equals("yes") || trimmed.equals("on");
    }

    private static double parseEnvDouble(String name, double fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
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
}
