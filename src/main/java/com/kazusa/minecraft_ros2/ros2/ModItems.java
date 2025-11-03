package com.kazusa.minecraft_ros2.ros2;

import com.kazusa.minecraft_ros2.items.LidarItem;
import com.kazusa.minecraft_ros2.minecraft_ros2;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.Optional;

public final class ModItems {
    private ModItems() {
    }

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, minecraft_ros2.MOD_ID);

    public static final RegistryObject<Item> VELODYNE_VLP16 = ITEMS.register("velodyne_vlp16",
            () -> createLidarItem("velodyne_vlp16"));
    public static final RegistryObject<Item> HESAI_XT32 = ITEMS.register("hesai_xt32",
            () -> createLidarItem("hesai_xt32"));
    public static final RegistryObject<Item> HESAI_FT120 = ITEMS.register("hesai_ft120",
            () -> createLidarItem("hesai_ft120"));
    public static final RegistryObject<Item> RS_LIDAR_M1 = ITEMS.register("rs_lidar_m1",
            () -> createLidarItem("rs_lidar_m1"));
    public static final RegistryObject<Item> UTM_30LN = ITEMS.register("utm_30ln",
            () -> createLidarItem("utm_30ln"));

    private static LidarItem createLidarItem(String id) {
        Equippable equippable = new Equippable(
                EquipmentSlot.HEAD,
                Holder.direct(SoundEvents.ARMOR_EQUIP_GENERIC),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                false,
                false,
                false,
                false,
                Holder.direct(SoundEvents.ARMOR_EQUIP_GENERIC)
        );

        Item.Properties properties = new Item.Properties()
                .setId(ResourceLocation.fromNamespaceAndPath(minecraft_ros2.MOD_ID, id))
                .stacksTo(1)
                .component(DataComponents.EQUIPPABLE, equippable);

        return new LidarItem(properties);
    }
}
