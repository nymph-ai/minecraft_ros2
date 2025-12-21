package com.kazusa.minecraft_ros2.models;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.constant.dataticket.DataTicket;
import software.bernie.geckolib.renderer.base.GeoRenderState;
import java.util.HashMap;
import java.util.Map;

public class DynamicModelEntityRenderer
    extends GeoEntityRenderer<DynamicModelEntity, DynamicModelEntityRenderer.DynamicRenderState>
{
    public DynamicModelEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new DynamicModelEntityModel());
        this.shadowRadius = 0.5f;  // 影の大きさ
        this.scaleWidth = 1.0f; // モデルのスケール
        this.scaleHeight = 1.0f; // モデルの高さ
    }

    public static class DynamicRenderState extends EntityRenderState implements GeoRenderState {
        private final Map<DataTicket<?>, Object> data = new HashMap<>();

        @Override
        public <D> void addGeckolibData(DataTicket<D> dataTicket, D value) {
            data.put(dataTicket, value);
        }

        @Override
        public boolean hasGeckolibData(DataTicket<?> dataTicket) {
            return data.containsKey(dataTicket);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <D> D getGeckolibData(DataTicket<D> dataTicket) {
            return (D) data.get(dataTicket);
        }

        @Override
        public Map<DataTicket<?>, Object> getDataMap() {
            return data;
        }
    }
}
