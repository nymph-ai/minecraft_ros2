package com.kazusa.minecraft_ros2.block;

import com.kazusa.minecraft_ros2.minecraft_ros2;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import java.util.Set;

public class ModBlockEntities {
    // DeferredRegister for block entities
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, minecraft_ros2.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RedStonePubSubBlockEntity>> RED_STONE_PUB_SUB_BLOCK_ENTITY =
        BLOCK_ENTITIES.register(
            "red_stone_pub_sub_block_entity",
            () -> new BlockEntityType<>(RedStonePubSubBlockEntity::new, Set.of(ModBlocks.REDSTONE_PUB_SUB.get()))
        );

    // Register method to be called in main mod class
    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
    }
}
