package com.kazusa.minecraft_ros2.network;

import com.kazusa.minecraft_ros2.minecraft_ros2;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class NetworkHandler {
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(minecraft_ros2.MOD_ID)
            .versioned("1.0")
            .optional();

        registrar.playToServer(
            RenamePacket.TYPE,
            RenamePacket.STREAM_CODEC,
            RenamePacket::handle
        );
    }

    public static void sendToServer(CustomPacketPayload payload) {
        // Not used in 1.21.11 migration stub
    }
}
