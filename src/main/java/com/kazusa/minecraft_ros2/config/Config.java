package com.kazusa.minecraft_ros2.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class Config {
    public static final ModConfigSpec COMMON_SPEC;
    public static final CommonConfig COMMON;

    static {
        Pair<CommonConfig, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(CommonConfig::new);
        COMMON_SPEC = specPair.getRight();
        COMMON = specPair.getLeft();
    }

    public static class CommonConfig {
        public final ModConfigSpec.IntValue maxSpeed;
        public final ModConfigSpec.BooleanValue enableLogging;
        public final ModConfigSpec.ConfigValue<String> topicName;
        public final ModConfigSpec.BooleanValue enableDebugDataStreaming;
        public final ModConfigSpec.BooleanValue enableBaritone;

        public CommonConfig(ModConfigSpec.Builder builder) {
            builder.comment("ROS2 Minecraft Mod Configuration").push("general");

            maxSpeed = builder
                    .comment("Maximum speed of movement in Minecraft units per second")
                    .defineInRange("maxSpeed", 10, 1, 100);

            enableLogging = builder
                    .comment("Enable or disable detailed logging")
                    .define("enableLogging", true);
                    
            topicName = builder
                    .comment("ROS2 topic name to subscribe for Twist messages")
                    .define("topicName", "cmd_vel");
                    
            enableDebugDataStreaming = builder
                    .comment("Enable or disable the debug data stream for additional information")
                    .define("enableDebugDataStreaming", false);

            enableBaritone = builder
                    .comment("Enable Baritone integration for ROS2 goal topics")
                    .define("enableBaritone", true);

            builder.pop();
        }
    }
}
