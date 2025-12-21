package com.kazusa.minecraft_ros2.ros2;

import com.kazusa.minecraft_ros2.config.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.ros2.rcljava.node.BaseComposableNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import geometry_msgs.msg.Twist;

public class TwistSubscriber extends BaseComposableNode {
    private static final Logger LOGGER = LoggerFactory.getLogger(TwistSubscriber.class);
    private static final double MOVEMENT_THRESHOLD = 0.05;

    private Minecraft minecraft;
    private Player player;

    private double lastLinearX = 0.0;
    private double lastLinearY = 0.0;
    private double lastLinearZ = 0.0;
    private double lastAngularY = 0.0;
    private double lastAngularZ = 0.0;

    private final boolean loggingEnabled;

    public TwistSubscriber() {
        super("minecraft_twist_subscriber");
        this.loggingEnabled = Config.COMMON.enableLogging.get();
        this.node.<Twist>createSubscription(
            Twist.class, "/cmd_vel", this::twistCallback);
        LOGGER.info(
            "TwistSubscriber initialized and listening on 'cmd_vel' topic (loggingEnabled={})",
            this.loggingEnabled
        );
    }

    private void twistCallback(final Twist msg) {
        // Store the received twist values
        lastLinearX = msg.getLinear().getX();
        lastLinearY = msg.getLinear().getY();
        lastLinearZ = msg.getLinear().getZ();
        lastAngularY = msg.getAngular().getY();
        lastAngularZ = msg.getAngular().getZ();
        
        if (loggingEnabled) {
            LOGGER.info(
                "Received twist: linear_x={}, linear_y={}, linear_z={}, angular_y={}, angular_z={}",
                lastLinearX, lastLinearY, lastLinearZ, lastAngularY, lastAngularZ
            );
        } else {
            LOGGER.debug(
                "Received twist: linear_x={}, linear_y={}, linear_z={}, angular_y={}, angular_z={}",
                lastLinearX, lastLinearY, lastLinearZ, lastAngularY, lastAngularZ
            );
        }
    }
    
    /**
     * Apply the stored twist values to move the player
     * Called from the game tick event to ensure movement happens on the client/main thread
     */
    public void applyPlayerMovement() {
        minecraft = Minecraft.getInstance();
        player = minecraft.player;

        if (player != null && !minecraft.isPaused()) {
            double maxSpeed = Config.COMMON.maxSpeed.get();
            double speedFactor = maxSpeed / 20.0;

            if (Math.abs(lastLinearX) > MOVEMENT_THRESHOLD
                    || Math.abs(lastLinearY) > MOVEMENT_THRESHOLD
                    || Math.abs(lastLinearZ) > MOVEMENT_THRESHOLD) {
                float yaw = player.getYRot();
                double yawRad = Math.toRadians(yaw);

                double forward = lastLinearX * speedFactor;
                double strafe = -lastLinearY * speedFactor;

                double dx = -Math.sin(yawRad) * forward - Math.cos(yawRad) * strafe;
                double dz = Math.cos(yawRad) * forward - Math.sin(yawRad) * strafe;

                double dy = player.getDeltaMovement().y(); // preserve current vertical motion

                // Jump processing (jump if not already jumping)
                if (lastLinearZ > 0.1 && player.verticalCollision) {
                    dy = 0.42; // Minecraft jump speed
                }


                // Use deltaMovement for natural movement
                player.setDeltaMovement(dx, dy, dz);
                // hasImpulse field removed in 1.21.11

                if (loggingEnabled) {
                    LOGGER.info(
                        "Applied movement: dx={}, dy={}, dz={}, yaw={}, linear=({}, {}, {})",
                        dx, dy, dz, yaw, lastLinearX, lastLinearY, lastLinearZ
                    );
                }
            }

            // Rotation processing (smooth)
            if (Math.abs(lastAngularZ) > MOVEMENT_THRESHOLD || Math.abs(lastAngularY) > MOVEMENT_THRESHOLD) {
                float rotZ = (float)(-lastAngularZ * speedFactor * 10);
                float rotY = (float)(-lastAngularY * speedFactor * 10);
                player.setYRot(player.getYRot() + rotZ);
                player.setXRot(player.getXRot() + rotY);

                if (loggingEnabled) {
                    LOGGER.info(
                        "Applied rotation: rotZ={}, rotY={}, angular=({}, {})",
                        rotZ, rotY, lastAngularZ, lastAngularY
                    );
                }
            }
        }
    }
}