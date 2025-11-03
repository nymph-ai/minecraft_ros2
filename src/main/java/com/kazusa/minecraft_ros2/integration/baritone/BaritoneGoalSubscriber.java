package com.kazusa.minecraft_ros2.integration.baritone;

import geometry_msgs.msg.Point;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.ros2.rcljava.node.BaseComposableNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import std_msgs.msg.Empty;

/**
 * ROS 2 subscriber that bridges goal messages to Baritone navigation commands.
 */
public class BaritoneGoalSubscriber extends BaseComposableNode {
    private static final Logger LOGGER = LoggerFactory.getLogger(BaritoneGoalSubscriber.class);

    private static final String GOAL_TOPIC = "/minecraft/baritone/goal";
    private static final String CANCEL_TOPIC = "/minecraft/baritone/cancel";

    public BaritoneGoalSubscriber() {
        super("minecraft_baritone_goal_subscriber");

        if (!FMLEnvironment.dist.isClient()) {
            LOGGER.warn("BaritoneGoalSubscriber instantiated on non-client dist; Baritone expects client-side execution.");
        }

        this.node.<Point>createSubscription(Point.class, GOAL_TOPIC, this::handleGoalMessage);
        this.node.<Empty>createSubscription(Empty.class, CANCEL_TOPIC, msg -> handleCancelMessage());

        LOGGER.info("BaritoneGoalSubscriber listening on '{}' for targets and '{}' for cancellations.",
                GOAL_TOPIC, CANCEL_TOPIC);
    }

    private void handleGoalMessage(final Point point) {
        if (point == null) {
            LOGGER.warn("Received null Point message on '{}'; ignoring.", GOAL_TOPIC);
            return;
        }

        LOGGER.debug("Received Baritone goal: x={}, y={}, z={}", point.getX(), point.getY(), point.getZ());
        scheduleOnMainThread(() -> {
            if (!BaritoneIntegration.navigateTo(point.getX(), point.getY(), point.getZ())) {
                LOGGER.warn("Failed to submit Baritone goal to ({}, {}, {}); see logs for details.",
                        point.getX(), point.getY(), point.getZ());
            }
        });
    }

    private void handleCancelMessage() {
        LOGGER.debug("Received Baritone cancel message on '{}'", CANCEL_TOPIC);
        scheduleOnMainThread(() -> {
            if (!BaritoneIntegration.cancelNavigation()) {
                LOGGER.warn("Failed to cancel Baritone navigation; see logs for details.");
            }
        });
    }

    private void scheduleOnMainThread(Runnable runnable) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            LOGGER.warn("Minecraft instance is null; cannot execute Baritone command on main thread.");
            return;
        }

        minecraft.execute(runnable);
    }
}

