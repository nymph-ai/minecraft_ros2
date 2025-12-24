package com.kazusa.minecraft_ros2.ros2;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.utils.BetterBlockPos;
import geometry_msgs.msg.Pose;
import geometry_msgs.msg.PoseArray;
import net.minecraft.client.Minecraft;
import org.ros2.rcljava.node.BaseComposableNode;
import org.ros2.rcljava.publisher.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Publishes the active Baritone path as a PoseArray for tethering.
 */
public class BaritonePathPublisher extends BaseComposableNode {
    private static final Logger LOGGER = LoggerFactory.getLogger(BaritonePathPublisher.class);

    private static final long PERIOD_MS = 200;

    private final Publisher<PoseArray> publisher;
    private final Minecraft minecraft;
    private final IBaritone baritone;

    public BaritonePathPublisher(IBaritone baritone) {
        super("baritone_path_publisher");
        this.minecraft = Minecraft.getInstance();
        this.baritone = baritone;
        this.publisher = this.node.createPublisher(PoseArray.class, "/baritone/path");
        this.node.createWallTimer(PERIOD_MS, TimeUnit.MILLISECONDS, this::publishPath);
        LOGGER.info("BaritonePathPublisher initialized (period={}ms)", PERIOD_MS);
    }

    private void publishPath() {
        if (baritone == null || minecraft.player == null) {
            return;
        }

        PoseArray msg = new PoseArray();
        msg.getHeader().setFrameId("map");

        Optional<IPath> pathOpt = baritone.getPathingBehavior().getPath();
        if (pathOpt.isPresent()) {
            List<BetterBlockPos> positions = pathOpt.get().positions();
            for (BetterBlockPos pos : positions) {
                Pose pose = new Pose();
                // Align with /player/ground_truth: x=z, y=x, z=y.
                pose.getPosition().setX(pos.getZ());
                pose.getPosition().setY(pos.getX());
                pose.getPosition().setZ(pos.getY());
                pose.getOrientation().setW(1.0);
                msg.getPoses().add(pose);
            }
        }

        publisher.publish(msg);
    }
}
