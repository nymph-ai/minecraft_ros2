package com.kazusa.minecraft_ros2.events;

import com.kazusa.minecraft_ros2.minecraft_ros2;
import com.kazusa.minecraft_ros2.models.DynamicModelEntity;
import com.kazusa.minecraft_ros2.models.ModEntities;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.bus.api.SubscribeEvent;

public class CommonModEventSubscriber {

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(
            ModEntities.CUSTOM_ENTITY.get(),
            DynamicModelEntity.createAttributes().build()
        );
    }
}


