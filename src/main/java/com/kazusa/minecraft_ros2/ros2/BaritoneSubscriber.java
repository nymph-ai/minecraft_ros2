package com.kazusa.minecraft_ros2.ros2;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.ros2.rcljava.node.BaseComposableNode;
import org.ros2.rcljava.qos.QoSProfile;
import org.ros2.rcljava.subscription.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import geometry_msgs.msg.PoseStamped;
import geometry_msgs.msg.PointStamped;

/**
 * ROS2 Subscriber for Baritone pathfinding commands.
 * Accepts goal positions via ROS2 topics and uses Baritone to navigate.
 */
public class BaritoneSubscriber extends BaseComposableNode {
    private static final Logger LOGGER = LoggerFactory.getLogger(BaritoneSubscriber.class);

    private final Subscription<PoseStamped> poseGoalSubscription;
    private final Subscription<PointStamped> pointGoalSubscription;
    private final Minecraft minecraft;
    private IBaritone baritone;

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

        LOGGER.info("BaritoneSubscriber initialized - listening on /baritone/goal/pose and /baritone/goal/point");
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
}
