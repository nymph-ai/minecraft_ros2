package com.kazusa.minecraft_ros2.network;

import com.kazusa.minecraft_ros2.menu.RedStonePubSubBlockContainer;
import com.kazusa.minecraft_ros2.minecraft_ros2;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RenamePacket(BlockPos pos, String name) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RenamePacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(minecraft_ros2.MOD_ID, "rename"));

    public static final StreamCodec<FriendlyByteBuf, RenamePacket> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, RenamePacket::pos,
        new StreamCodec<FriendlyByteBuf, String>() {
            @Override
            public String decode(FriendlyByteBuf buf) {
                return buf.readUtf(32767);
            }

            @Override
            public void encode(FriendlyByteBuf buf, String value) {
                buf.writeUtf(value);
            }
        }, RenamePacket::name,
        RenamePacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RenamePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                if (player.containerMenu instanceof RedStonePubSubBlockContainer menu) {
                    menu.onRename(packet.name(), player);
                }
            }
        });
    }
}
