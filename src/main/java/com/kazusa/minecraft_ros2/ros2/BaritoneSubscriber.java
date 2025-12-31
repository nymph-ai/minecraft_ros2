package com.kazusa.minecraft_ros2.ros2;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.ros2.rcljava.node.BaseComposableNode;
import org.ros2.rcljava.qos.QoSProfile;
import org.ros2.rcljava.subscription.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import geometry_msgs.msg.PoseStamped;
import geometry_msgs.msg.PointStamped;
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
    private final Minecraft minecraft;
    private IBaritone baritone;
    private String followTargetName;
    private final double followDistance;
    private final long followUpdateMs;
    private final long followStaleMs;
    private long lastSeenAtMs = 0L;
    private Double lastSeenX = null;
    private Double lastSeenZ = null;
    private long lastMissingLogMs = 0L;

    public BaritoneSubscriber() {
        super("baritone_subscriber");
        this.minecraft = Minecraft.getInstance();

        // Initialize Baritone
        try {
            this.baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            LOGGER.info("Baritone initialized successfully");
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

        followSubscription = this.node.createSubscription(
            std_msgs.msg.String.class,
            "/baritone/follow",
            this::handleFollowTarget,
            QoSProfile.DEFAULT
        );

        this.node.createWallTimer(followUpdateMs, TimeUnit.MILLISECONDS, this::updateFollowGoal);

        LOGGER.info(
            "BaritoneSubscriber initialized - listening on /baritone/goal/pose, /baritone/goal/point, /baritone/follow"
        );
    }

    /**
     * Handle full 3D pose goal (X, Y, Z coordinates)
     */
    private void handlePoseGoal(PoseStamped msg) {
        if (baritone == null || minecraft.player == null) {
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

            // Set Baritone goal
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(targetPos));

            LOGGER.info("Baritone pathfinding started to {}", targetPos);
        } catch (Exception e) {
            LOGGER.error("Error processing Baritone pose goal", e);
        }
    }

    /**
     * Handle 2D point goal (X, Z coordinates only - Y is auto-calculated)
     */
    private void handlePointGoal(PointStamped msg) {
        if (baritone == null || minecraft.player == null) {
            return;
        }

        try {
            // Extract XZ position from message
            double x = msg.getPoint().getX();
            double z = msg.getPoint().getZ();

            LOGGER.info("Received Baritone goal (Point XZ): {} {}", (int) Math.floor(x), (int) Math.floor(z));

            // Use GoalXZ which auto-calculates Y based on terrain
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ((int) Math.floor(x), (int) Math.floor(z)));

            LOGGER.info("Baritone pathfinding started to XZ: {} {}", (int) Math.floor(x), (int) Math.floor(z));
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

    private void updateFollowGoal() {
        if (followTargetName == null || baritone == null || minecraft.player == null || minecraft.level == null) {
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

        baritone.getCustomGoalProcess().setGoalAndPath(
            new GoalXZ((int) Math.floor(goalX), (int) Math.floor(goalZ))
        );
    }

    /**
     * Cancel current pathfinding
     */
    public void cancelPath() {
        if (baritone != null) {
            baritone.getPathingBehavior().cancelEverything();
            LOGGER.info("Baritone pathfinding cancelled");
        }
    }

    /**
     * Check if Baritone is currently pathing
     */
    public boolean isPathing() {
        return baritone != null && baritone.getPathingBehavior().isPathing();
    }

    /**
     * Get current Baritone instance
     */
    public IBaritone getBaritone() {
        return baritone;
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
