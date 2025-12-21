package com.kazusa.minecraft_ros2.items;

import com.kazusa.minecraft_ros2.minecraft_ros2;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import com.kazusa.minecraft_ros2.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

public class BlockItems {
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(Registries.ITEM, minecraft_ros2.MOD_ID);

    public static final ResourceKey<Item> REDSTONE_PUB_SUB_ITEM_KEY =
        ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(minecraft_ros2.MOD_ID, "redstone_pub_sub"));

    public static final DeferredHolder<Item, Item> REDSTONE_PUB_SUB_ITEM =
        ITEMS.register("redstone_pub_sub", () ->
            new BlockItem(ModBlocks.REDSTONE_PUB_SUB.get(),
                new Item.Properties()
                    .setId(REDSTONE_PUB_SUB_ITEM_KEY)
                    .useBlockDescriptionPrefix()
            )
        );
}
