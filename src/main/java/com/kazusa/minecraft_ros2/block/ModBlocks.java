package com.kazusa.minecraft_ros2.block;

import com.kazusa.minecraft_ros2.minecraft_ros2;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(Registries.BLOCK, minecraft_ros2.MOD_ID);

    public static final ResourceKey<Block> REDSTONE_PUB_SUB_KEY =
        ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(minecraft_ros2.MOD_ID, "redstone_pub_sub"));

    public static final DeferredHolder<Block, Block> REDSTONE_PUB_SUB =
        BLOCKS.register("redstone_pub_sub", () -> new RedstonePubSubBlock(
            BlockBehaviour.Properties.of()
                .setId(REDSTONE_PUB_SUB_KEY)
                .mapColor(MapColor.WOOD)
                .strength(2.0f, 6.0f)
                .sound(SoundType.WOOD)
        ));
}
