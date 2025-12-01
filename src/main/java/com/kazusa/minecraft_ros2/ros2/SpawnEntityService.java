package com.kazusa.minecraft_ros2.ros2;

import com.kazusa.minecraft_ros2.models.ModEntities;
import com.kazusa.minecraft_ros2.models.DynamicModelEntity;
import com.kazusa.minecraft_ros2.models.DynamicModelEntityModel;
import com.kazusa.minecraft_ros2.utils.GeometryApplier;
import org.ros2.rcljava.node.BaseComposableNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simulation_interfaces.srv.SpawnEntity;
import simulation_interfaces.srv.SpawnEntity_Request;
import simulation_interfaces.srv.SpawnEntity_Response;
import simulation_interfaces.msg.Result;
import org.ros2.rcljava.service.RMWRequestId;

import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.RegistryObject;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;

public class SpawnEntityService  extends BaseComposableNode {
    public final List<DynamicModelEntity> spawnedEntities = new ArrayList<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(SpawnEntityService.class);

    // モデルの名前の空配列
    private final List<String> jsonFileNames;

    private int current_model_number;

    public SpawnEntityService() {
        super("spawn_entity_service");
        current_model_number = 0;
        // 空のリストを作成！
        jsonFileNames = new ArrayList<>();

        try {
            this.node.<SpawnEntity>createService(
                SpawnEntity.class,
                "spawn_entity",
                (RMWRequestId header, SpawnEntity_Request request,
                    SpawnEntity_Response response)
                    -> this.handleService(header, request, response));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to create service", e);
        }
        LOGGER.info("SpawnEntityService initialized and listening on '/spawn_entity'");
    }

    private void handleService(final RMWRequestId header,
            final SpawnEntity_Request request,
            final SpawnEntity_Response response) {
        Result result;
        try {
            result = spawnEntityInternal(
                request.getName(),
                request.getEntityNamespace(),
                request.getEntityResource().getUri(),
                request.getInitialPose().getPose().getPosition().getX(),
                request.getInitialPose().getPose().getPosition().getY(),
                request.getInitialPose().getPose().getPosition().getZ(),
                request.getAllowRenaming()
            );
        } catch (Exception e) {
            LOGGER.error("Unexpected exception while handling spawn request {}", request, e);
            result = new Result();
            result.setResult(Byte.valueOf((byte) 0));
            result.setErrorMessage("Internal error: " + e.getMessage());
        }
        response.setResult(result);
        response.setEntityName(request.getName());
    }

    public Result spawnViaCommand(String name, String namespace, String uri,
            double x, double y, double z) {
        return spawnEntityInternal(name, namespace, uri, x, y, z, true);
    }

    private Result spawnEntityInternal(String modelName, String namespace, String rawUri,
            double x, double y, double z, boolean allowRenaming) {
        Result result = new Result();

        if (modelName == null || modelName.isEmpty() || rawUri == null || rawUri.isEmpty()) {
            LOGGER.error("Invalid spawn request: missing name or uri");
            result.setResult(Byte.valueOf((byte) 0));
            result.setErrorMessage("Invalid request: modelName or modelUri is empty");
            return result;
        }

        String modelUri = rawUri;
        if (modelUri.startsWith("file://")) {
            modelUri = modelUri.replace("file://", "");
        }

        LOGGER.info("SpawnEntityService request name={} uri={} namespace={} position=({}, {}, {})",
                modelName, modelUri, namespace, x, y, z);

        if (modelUri.startsWith("http://") || modelUri.startsWith("https://")) {
            LOGGER.error("Remote URIs are not supported: {}", modelUri);
            result.setResult(Byte.valueOf((byte) 0));
            result.setErrorMessage("Remote URIs are not supported: " + modelUri);
            return result;
        }

        if (!modelUri.toLowerCase().endsWith(".geo.json")) {
            LOGGER.error("Invalid model URI, must end with .geo.json: {}", modelUri);
            result.setResult(Byte.valueOf((byte) 0));
            result.setErrorMessage("Invalid model URI, must end with .geo.json: " + modelUri);
            return result;
        }

        Path jsonPath = Paths.get(modelUri);
        if (!Files.exists(jsonPath)) {
            LOGGER.error("Model URI does not exist: {}", modelUri);
            result.setResult(Byte.valueOf((byte) 0));
            result.setErrorMessage("Model URI does not exist: " + modelUri);
            return result;
        }
        try {
            String content = Files.readString(jsonPath);
            if (!content.contains("\"format_version\"") || !content.contains("\"minecraft:geometry\"")) {
                LOGGER.error("Invalid geometry JSON format: {}", modelUri);
                result.setResult(Byte.valueOf((byte) 0));
                result.setErrorMessage("Invalid geometry JSON format: " + modelUri);
                return result;
            }
        } catch (IOException e) {
            LOGGER.error("Failed to read model URI: {}", modelUri, e);
            result.setResult(Byte.valueOf((byte) 0));
            result.setErrorMessage("Failed to read model URI: " + modelUri);
            return result;
        }
        LOGGER.info("SpawnEntityService validated geometry {}", modelUri);

        if (!allowRenaming) {
            for (DynamicModelEntity entity : spawnedEntities) {
                if (entity.getCustomName() != null && entity.getCustomName().getString().equals(modelName)) {
                    LOGGER.error("Entity with name '{}' already exists.", modelName);
                    result.setResult(Byte.valueOf((byte) 0));
                    result.setErrorMessage("Entity with name '" + modelName + "' already exists.");
                    return result;
                }
            }
        }

        String jsonFileName = modelUri.substring(modelUri.lastIndexOf('/') + 1, modelUri.lastIndexOf('.'));
        int jsonIndex = jsonFileNames.indexOf(jsonFileName);
        LOGGER.info("SpawnEntityService using geometry {} (known={})", jsonFileName, jsonIndex >= 0);
        if (jsonIndex < 0) {
            if (current_model_number >= DynamicModelEntityModel.MAX_MODEL_COUNT) {
                LOGGER.error("Maximum model number reached, overwriting existing models.");
                result.setResult(Byte.valueOf((byte) 0));
                result.setErrorMessage("Maximum model number reached.");
                return result;
            }
            jsonIndex = current_model_number;
            jsonFileNames.add(jsonFileName);
            LOGGER.info("SpawnEntityService assigned geometry {} to slot {}", jsonFileName, jsonIndex);
            if (FMLEnvironment.dist == Dist.CLIENT) {
                LOGGER.info("SpawnEntityService applying geometry {} into slot {}", jsonFileName, jsonIndex);
                try {
                    GeometryApplier.applyJson(Paths.get(modelUri), "runtime_geo", jsonIndex);
                    LOGGER.info("SpawnEntityService applied geometry {} into slot {}", jsonFileName, jsonIndex);
                } catch (Exception e) {
                    LOGGER.error("Failed to apply geometry {} at {}", jsonFileName, modelUri, e);
                    result.setResult(Byte.valueOf((byte) 0));
                    result.setErrorMessage("Failed to apply geometry: " + e.getMessage());
                    return result;
                }
            } else {
                LOGGER.warn("Skipping geometry application for {} on dedicated server. Ensure clients have this resource pack.", jsonFileName);
            }
            current_model_number++;
        }

        LOGGER.info("SpawnEntityService acquiring server instance");
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            LOGGER.error("MinecraftServer reference is null");
            result.setResult(Byte.valueOf((byte) 0));
            result.setErrorMessage("Server is not available");
            return result;
        }
        LOGGER.info("SpawnEntityService obtained server instance");
        ServerLevel world = server.getLevel(Level.OVERWORLD);
        if (world == null) {
            LOGGER.error("Overworld is not available");
            result.setResult(Byte.valueOf((byte) 0));
            result.setErrorMessage("World is not available");
            return result;
        }
        LOGGER.info("SpawnEntityService obtained overworld reference");

        RegistryObject<EntityType<DynamicModelEntity>> ro = ModEntities.CUSTOM_ENTITY;
        EntityType<DynamicModelEntity> type = ro.get();
        String customName = namespace != null && !namespace.isEmpty() ? namespace : modelName;
        DynamicModelEntity robot = type.create(world);
        if (robot == null) {
            LOGGER.error("Failed to create entity of type: {}", type);
            result.setResult(Byte.valueOf((byte) 0));
            result.setErrorMessage("Failed to create entity of type: " + type);
            return result;
        }
        LOGGER.info("SpawnEntityService created entity instance for {}", customName);
        robot.setCustomName(Component.literal(customName));
        robot.initRobotTwistSubscriber();

        spawnedEntities.add(robot);
        LOGGER.info("SpawnEntityService tracked entity {}, total robots={}", customName, spawnedEntities.size());

        robot.setModelId(jsonIndex);
        LOGGER.info("SpawnEntityService assigned model id {} for {}", jsonIndex, customName);

        robot.moveTo(x, y, z, 0.0F, 0.0F);
        LOGGER.info("SpawnEntityService moving entity {} to ({}, {}, {})", customName, x, y, z);
        world.addFreshEntity(robot);
        LOGGER.info("SpawnEntityService added entity {} to world", customName);
        robot.setModelDimensions();
        LOGGER.info("SpawnEntityService set model dimensions for {}", customName);

        result.setResult(Byte.valueOf((byte) 1));
        result.setErrorMessage("");
        LOGGER.info("SpawnEntityService completed name={} uri={} code=1", customName, modelUri);
        return result;
    }

}