package com.kazusa.minecraft_ros2.ros2;

import com.kazusa.minecraft_ros2.config.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.player.Player;
import org.ros2.rcljava.node.BaseComposableNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import geometry_msgs.msg.Twist;

public class TwistSubscriber extends BaseComposableNode {
    private static final Logger LOGGER = LoggerFactory.getLogger(TwistSubscriber.class);
    private static final double MOVEMENT_THRESHOLD = 0.05;
    private static final long CMD_VEL_TIMEOUT_MS = 250L;

    private Minecraft minecraft;
    private Player player;

    private double lastLinearX = 0.0;
    private double lastLinearY = 0.0;
    private double lastLinearZ = 0.0;
    private double lastAngularY = 0.0;
    private double lastAngularZ = 0.0;
    private long lastCmdVelMs = 0L;
    private boolean cmdVelActive = false;

    private final boolean loggingEnabled;
    private final boolean useInputMovement;
    private final boolean debugDirectMove;
    private final boolean debugMovementState;
    private long lastDebugLogMs = 0L;

    public TwistSubscriber() {
        super("minecraft_twist_subscriber");
        this.loggingEnabled = Config.COMMON.enableLogging.get();
        this.useInputMovement = parseEnvBool("MINECRAFT_ROS2_INPUT_MOVEMENT", true);
        this.debugDirectMove = parseEnvBool("MINECRAFT_ROS2_DEBUG_DIRECT_MOVE", false);
        this.debugMovementState = parseEnvBool("MINECRAFT_ROS2_DEBUG_MOVEMENT_STATE", false);
        this.node.<Twist>createSubscription(
            Twist.class, "/cmd_vel", this::twistCallback);
        LOGGER.info(
            "TwistSubscriber initialized and listening on 'cmd_vel' topic (loggingEnabled={}, inputMovement={}, debugDirectMove={}, debugMovementState={})",
            this.loggingEnabled,
            this.useInputMovement,
            this.debugDirectMove,
            this.debugMovementState
        );
    }

    private void twistCallback(final Twist msg) {
        // Store the received twist values
        lastLinearX = msg.getLinear().getX();
        lastLinearY = msg.getLinear().getY();
        lastLinearZ = msg.getLinear().getZ();
        lastAngularY = msg.getAngular().getY();
        lastAngularZ = msg.getAngular().getZ();
        lastCmdVelMs = System.currentTimeMillis();
        cmdVelActive = true;
        
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

            if (useInputMovement) {
                if (isCmdVelFresh()) {
                    applyInputState();
                    if (debugDirectMove) {
                        applyDirectMovement(speedFactor);
                    }
                } else if (cmdVelActive) {
                    clearInputState();
                    cmdVelActive = false;
                }
            } else {
                applyDirectMovement(speedFactor);
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

            if (debugMovementState) {
                logMovementState();
            }
        } else if (useInputMovement) {
            clearInputState();
            cmdVelActive = false;
        }
    }

    private void applyDirectMovement(double speedFactor) {
        if (Math.abs(lastLinearX) <= MOVEMENT_THRESHOLD
                && Math.abs(lastLinearY) <= MOVEMENT_THRESHOLD
                && Math.abs(lastLinearZ) <= MOVEMENT_THRESHOLD) {
            return;
        }

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

    private void applyInputState() {
        Options options = minecraft.options;
        boolean forward = lastLinearX > MOVEMENT_THRESHOLD;
        boolean back = lastLinearX < -MOVEMENT_THRESHOLD;
        boolean left = lastLinearY > MOVEMENT_THRESHOLD;
        boolean right = lastLinearY < -MOVEMENT_THRESHOLD;
        boolean jump = lastLinearZ > 0.1;
        boolean sprint = Math.abs(lastLinearX) > 0.6;

        setKey(options.keyUp, forward);
        setKey(options.keyDown, back);
        setKey(options.keyLeft, left);
        setKey(options.keyRight, right);
        setKey(options.keyJump, jump);
        setKey(options.keySprint, sprint);
    }

    private void clearInputState() {
        Options options = minecraft.options;
        setKey(options.keyUp, false);
        setKey(options.keyDown, false);
        setKey(options.keyLeft, false);
        setKey(options.keyRight, false);
        setKey(options.keyJump, false);
        setKey(options.keySprint, false);
    }

    private void logMovementState() {
        long now = System.currentTimeMillis();
        if (now - lastDebugLogMs < 1000L) {
            return;
        }
        lastDebugLogMs = now;
        String pos = String.format("(%.2f, %.2f, %.2f)", player.getX(), player.getY(), player.getZ());
        var vel = player.getDeltaMovement();
        String velStr = String.format("(%.3f, %.3f, %.3f)", vel.x(), vel.y(), vel.z());
        boolean keyUp = minecraft.options.keyUp.isDown();
        boolean keyDown = minecraft.options.keyDown.isDown();
        boolean keyLeft = minecraft.options.keyLeft.isDown();
        boolean keyRight = minecraft.options.keyRight.isDown();
        boolean keyJump = minecraft.options.keyJump.isDown();
        boolean keySprint = minecraft.options.keySprint.isDown();
        LOGGER.info(
            "Movement state: pos={}, vel={}, keys=[up:{} down:{} left:{} right:{} jump:{} sprint:{}]",
            pos,
            velStr,
            keyUp,
            keyDown,
            keyLeft,
            keyRight,
            keyJump,
            keySprint
        );
    }

    private void setKey(KeyMapping key, boolean pressed) {
        if (key != null) {
            key.setDown(pressed);
        }
    }

    private boolean isCmdVelFresh() {
        return cmdVelActive && (System.currentTimeMillis() - lastCmdVelMs) <= CMD_VEL_TIMEOUT_MS;
    }

    private static boolean parseEnvBool(String name, boolean fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim().toLowerCase();
        return trimmed.equals("1") || trimmed.equals("true") || trimmed.equals("yes") || trimmed.equals("on");
    }
}
