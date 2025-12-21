package com.kazusa.minecraft_ros2.menu;

import com.kazusa.minecraft_ros2.minecraft_ros2;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(Registries.MENU, minecraft_ros2.MOD_ID);

    // RedStonePubSub 用 ScreenHandler を登録
    public static final DeferredHolder<MenuType<?>, MenuType<RedStonePubSubBlockContainer>> REDSTONE_PUB_SUB_BLOCK_MENU =
        MENUS.register("red_stone_pub_sub_block_menu",
            () -> IMenuTypeExtension.create(RedStonePubSubBlockContainer::new)
        );
}
