package com.kazusa.minecraft_ros2.models;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import software.bernie.geckolib.constant.dataticket.DataTicket;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.base.GeoRenderState;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

public class DynamicModelEntityModel extends GeoModel<DynamicModelEntity> {
    public static final int MAX_MODEL_COUNT = 10; // 動的モデルの数
    // geometry.json のパス
    private static final Identifier DEFAULT_GEO =
        Identifier.fromNamespaceAndPath("minecraft_ros2", "geo/custom_entity.geo.json");
    private static final DataTicket<Integer> MODEL_ID_TICKET =
        DataTicket.create("minecraft_ros2:model_id", Integer.class);
    private static final List<Identifier> CUSTOM_GEO_LIST = List.of(
        Identifier.fromNamespaceAndPath("runtime_geo", "geo/dynamic_model_0.geo.json"),
        Identifier.fromNamespaceAndPath("runtime_geo", "geo/dynamic_model_1.geo.json"),
        Identifier.fromNamespaceAndPath("runtime_geo", "geo/dynamic_model_2.geo.json"),
        Identifier.fromNamespaceAndPath("runtime_geo", "geo/dynamic_model_3.geo.json"),
        Identifier.fromNamespaceAndPath("runtime_geo", "geo/dynamic_model_4.geo.json"),
        Identifier.fromNamespaceAndPath("runtime_geo", "geo/dynamic_model_5.geo.json"),
        Identifier.fromNamespaceAndPath("runtime_geo", "geo/dynamic_model_6.geo.json"),
        Identifier.fromNamespaceAndPath("runtime_geo", "geo/dynamic_model_7.geo.json"),
        Identifier.fromNamespaceAndPath("runtime_geo", "geo/dynamic_model_8.geo.json"),
        Identifier.fromNamespaceAndPath("runtime_geo", "geo/dynamic_model_9.geo.json")
    );

    // テクスチャのパス（お好きなものを）
    private static final Identifier TEX =
        Identifier.fromNamespaceAndPath("minecraft_ros2", "textures/entity/custom_entity.png");
    // アニメーションファイルが無ければ同じパスで空ファイル or 無視して OK
    private static final Identifier ANIM = null;

    // Geckolib 1.21 signatures
    @Override
    public Identifier getModelResource(GeoRenderState state) {
        int modelId = state.getOrDefaultGeckolibData(MODEL_ID_TICKET, 0);
        int clampedId = Mth.clamp(modelId, 0, CUSTOM_GEO_LIST.size() - 1);
        Identifier selected = CUSTOM_GEO_LIST.get(clampedId);
        return resourceExists(selected) ? selected : DEFAULT_GEO;
    }

    @Override
    public Identifier getTextureResource(GeoRenderState state) {
        return TEX;
    }

    @Override
    public Identifier getAnimationResource(DynamicModelEntity animatable) {
        return ANIM;
    }

    @Override
    public void addAdditionalStateData(
        DynamicModelEntity animatable,
        Object instanceData,
        GeoRenderState state
    ) {
        int clampedId = Mth.clamp(animatable.getModelId(), 0, CUSTOM_GEO_LIST.size() - 1);
        state.addGeckolibData(MODEL_ID_TICKET, clampedId);
    }


    public static boolean resourceExists(Identifier loc) {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
    
        // Forgeの実装では hasResource(ResourceLocation) があるので、あればそれを使う
        if (rm instanceof ReloadableResourceManager rrm) {
            Optional<Resource> opt = rrm.getResource(loc);
            if (opt.isPresent()) {
                Resource res = opt.get();
                // try-with は InputStream に対して
                try (InputStream in = res.open()) {
                    // リソースが存在する場合は
                    return true;
                } catch (IOException e) {
                    // 例外が発生した場合はリソースが存在しないと判断
                    return false;
                }
            }
        }
        return false; // ReloadableResourceManager でなければ、またはリソースが見つからない場合は false
    }
}
