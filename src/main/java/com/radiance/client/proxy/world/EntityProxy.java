package com.radiance.client.proxy.world;

import static org.lwjgl.system.MemoryUtil.memAddress;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.radiance.client.constant.Constants;
import com.radiance.client.constant.Constants.PostRenderFlags;
import com.radiance.client.constant.Constants.RayTracingFlags;
import com.radiance.client.profiler.Profiler;
import com.radiance.client.proxy.vulkan.BufferProxy;
import com.radiance.client.vertex.PBRVertexConsumer;
import com.radiance.client.vertex.RenderTypeInfo;
import com.radiance.client.vertex.StorageVertexConsumerProvider;
import com.radiance.client.vertex.SubmitCapture;
import com.radiance.client.vertex.TransferredBuildBuffer;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IEntityRenderStateExt;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public class EntityProxy {

    private static final float VILLAGER_LAYER_OFFSET_UNIT = 0.001f / 16.0f;
    private static final String VILLAGER_TEXTURE_PREFIX = "textures/entity/villager/";
    private static final String ZOMBIE_VILLAGER_TEXTURE_PREFIX = "textures/entity/zombie_villager/";
    private static final Identifier WEATHER_RAIN_TEXTURE = Identifier.withDefaultNamespace(
        "textures/environment/rain.png");
    private static final Identifier WEATHER_SNOW_TEXTURE = Identifier.withDefaultNamespace(
        "textures/environment/snow.png");
    private static final String WEATHER_DEFAULT_CONTENT = "/weather/default";
    private static final String WEATHER_RAIN_CONTENT = "/weather/rain";
    private static final String WEATHER_SNOW_CONTENT = "/weather/snow";
    private static final String PARTICLE_DEFAULT_CONTENT = "/particle/default";
    private static final String TEXT_DEFAULT_CONTENT = "/text/default";
    private static final String NAME_TAG_DEFAULT_CONTENT = "/name_tag/default";
    private static final ConcurrentMap<String, ByteBuffer> UTF8_NAME_BUFFERS =
        new ConcurrentHashMap<>();
    private static final long ENTITY_KEY_PAYLOAD_MASK = 0x0fffffffffffffffL;
    private static final long NORMAL_ENTITY_KEY_PREFIX = 0x1000000000000000L;
    private static final long BLOCK_ENTITY_KEY_PREFIX = 0x2000000000000000L;
    private static final long BLOCK_ENTITY_CRUMBLING_KEY_PREFIX = 0x3000000000000000L;
    private static final long BLOCK_DAMAGE_KEY_PREFIX = 0x4000000000000000L;
    private static final long STATIC_ENTITY_KEY_PREFIX = 0x5000000000000000L;
    private static final long TARGET_BLOCK_OUTLINE_KEY_PREFIX = 0x6000000000000000L;

    /** 1.21.4's weather layers, rebuilt on 26.3's weather pipeline with the same textures. */
    private static final RenderType RAIN_RENDER_TYPE = RenderType.create("weather",
        RenderSetup.builder(RenderPipelines.WEATHER)
            .withTexture("Sampler0", WEATHER_RAIN_TEXTURE)
            .useLightmap()
            .createRenderSetup());
    private static final RenderType SNOW_RENDER_TYPE = RenderType.create("weather",
        RenderSetup.builder(RenderPipelines.WEATHER)
            .withTexture("Sampler0", WEATHER_SNOW_TEXTURE)
            .useLightmap()
            .createRenderSetup());

    /** All captures run on the render thread, one at a time. */
    private static final SubmitCapture CAPTURE = new SubmitCapture();

    /** Quad particles by content name, filled while particles are extracted. */
    private static final Map<String, QuadParticleRenderState> PARTICLE_STATES = new LinkedHashMap<>();
    private static boolean particleExtractActive = false;

    private static boolean entityNativeTransferEnabled = true;

    public static boolean isEntityNativeTransferEnabled() {
        return entityNativeTransferEnabled;
    }

    private static long normalEntityKey(Object entity) {
        return NORMAL_ENTITY_KEY_PREFIX |
            (mix64(System.identityHashCode(entity)) & ENTITY_KEY_PAYLOAD_MASK);
    }

    private static long waterMaskEntityKey(long entityKey) {
        long payload = entityKey & ENTITY_KEY_PAYLOAD_MASK;
        return NORMAL_ENTITY_KEY_PREFIX | (mix64(payload ^ 0x6d2b79f5d1f123bbL) & ENTITY_KEY_PAYLOAD_MASK);
    }

    private static long blockEntityKey(BlockPos blockPos) {
        return BLOCK_ENTITY_KEY_PREFIX | (mix64(blockPos.asLong()) & ENTITY_KEY_PAYLOAD_MASK);
    }

    private static long blockEntityCrumblingKey(BlockPos blockPos) {
        return BLOCK_ENTITY_CRUMBLING_KEY_PREFIX | (mix64(blockPos.asLong()) & ENTITY_KEY_PAYLOAD_MASK);
    }

    private static long blockDamageKey(BlockPos blockPos) {
        return BLOCK_DAMAGE_KEY_PREFIX | (mix64(blockPos.asLong()) & ENTITY_KEY_PAYLOAD_MASK);
    }

    private static long targetBlockOutlineKey(BlockPos blockPos) {
        return TARGET_BLOCK_OUTLINE_KEY_PREFIX | (mix64(blockPos.asLong()) & ENTITY_KEY_PAYLOAD_MASK);
    }

    private static long staticEntityKey(long value) {
        return STATIC_ENTITY_KEY_PREFIX | (mix64(value) & ENTITY_KEY_PAYLOAD_MASK);
    }

    private static long mix64(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    private static ByteBuffer getUtf8NameBuffer(String name) {
        String normalizedName = name == null ? "" : name;
        return UTF8_NAME_BUFFERS.computeIfAbsent(normalizedName,
            value -> MemoryUtil.memUTF8(value, true));
    }

    /**
     * Records the entity's key and ray tracing flag on its render state. 26.3 render states keep no
     * reference to their entity, so this runs at extraction (see {@code EntityRendererMixins}).
     */
    public static void tagEntityRenderState(Entity entity, EntityRenderState state) {
        IEntityRenderStateExt stateExt = (IEntityRenderStateExt) state;
        Constants.RayTracingFlags rayTracingFlag = Constants.RayTracingFlags.WORLD;
        if (entity == Minecraft.getInstance().getCameraEntity()) {
            rayTracingFlag = Constants.RayTracingFlags.PLAYER;
        } else if (entity instanceof FishingHook) {
            rayTracingFlag = Constants.RayTracingFlags.FISHING_BOBBER;
        }
        stateExt.radiance$setEntityKey(normalEntityKey(entity));
        stateExt.radiance$setRayTracingFlag(rayTracingFlag.getValue());
        stateExt.radiance$setTextDisplay(entity instanceof Display.TextDisplay);
    }

    public static void processWorldEntityRenderData(
        StorageVertexConsumerProvider storageVertexConsumerProvider,
        long entityKey,
        double entityPosX,
        double entityPosY,
        double entityPosZ,
        Constants.RayTracingFlags rayTracingFlag,
        boolean reflect,
        EntityRenderDataList entityRenderDataList) {
        processWorldEntityRenderData(storageVertexConsumerProvider, entityKey, entityPosX,
            entityPosY, entityPosZ, rayTracingFlag.getValue(), reflect, entityRenderDataList);
    }

    private static void processWorldEntityRenderData(
        StorageVertexConsumerProvider storageVertexConsumerProvider,
        long entityKey,
        double entityPosX,
        double entityPosY,
        double entityPosZ,
        int rayTracingFlag,
        boolean reflect,
        EntityRenderDataList entityRenderDataList) {
        if (!isEntityNativeTransferEnabled()) {
            return;
        }

        processEntityRenderData(storageVertexConsumerProvider,
            entityKey,
            entityPosX,
            entityPosY,
            entityPosZ,
            rayTracingFlag,
            0,
            -1,
            reflect,
            null,
            false,
            entityRenderDataList);
    }

    public static void processPostEntityRenderData(
        StorageVertexConsumerProvider storageVertexConsumerProvider,
        long entityKey,
        double entityPosX,
        double entityPosY,
        double entityPosZ,
        Constants.PostRenderFlags postRenderFlag,
        EntityRenderDataList entityRenderDataList) {
        if (!isEntityNativeTransferEnabled()) {
            return;
        }

        processEntityRenderData(storageVertexConsumerProvider,
            entityKey,
            entityPosX,
            entityPosY,
            entityPosZ,
            0,
            postRenderFlag.getValue(),
            -1,
            false,
            renderLayer -> defaultPostContentName(postRenderFlag),
            true,
            entityRenderDataList);
    }

    public static void processPostEntityRenderData(
        StorageVertexConsumerProvider storageVertexConsumerProvider,
        long entityKey,
        double entityPosX,
        double entityPosY,
        double entityPosZ,
        Constants.PostRenderFlags postRenderFlag,
        String contentName,
        EntityRenderDataList entityRenderDataList) {
        if (!isEntityNativeTransferEnabled()) {
            return;
        }

        processEntityRenderData(storageVertexConsumerProvider,
            entityKey,
            entityPosX,
            entityPosY,
            entityPosZ,
            0,
            postRenderFlag.getValue(),
            -1,
            false,
            renderLayer -> contentName,
            true,
            entityRenderDataList);
    }

    public static void processPostEntityRenderData(
        StorageVertexConsumerProvider storageVertexConsumerProvider,
        long entityKey,
        double entityPosX,
        double entityPosY,
        double entityPosZ,
        Constants.PostRenderFlags postRenderFlag,
        Function<RenderType, String> contentNameResolver,
        EntityRenderDataList entityRenderDataList) {
        if (!isEntityNativeTransferEnabled()) {
            return;
        }

        processEntityRenderData(storageVertexConsumerProvider,
            entityKey,
            entityPosX,
            entityPosY,
            entityPosZ,
            0,
            postRenderFlag.getValue(),
            -1,
            false,
            contentNameResolver,
            true,
            entityRenderDataList);
    }

    private static void processEntityRenderData(
        StorageVertexConsumerProvider storageVertexConsumerProvider,
        long entityKey,
        double entityPosX,
        double entityPosY,
        double entityPosZ,
        int rayTracingFlag,
        int postRenderFlag,
        int prebuiltBLAS,
        boolean reflect,
        Function<RenderType, String> contentNameResolver,
        boolean isPostRender,
        EntityRenderDataList entityRenderDataList) {
        if (Profiler.ENABLED) {
            Profiler.enter("java.EntityProxy.processEntityRenderData");
        }
        try {
            Map<RenderType, VertexConsumer> layerBuffers = storageVertexConsumerProvider.getLayers();
            EntityRenderData
                entityRenderData =
                new EntityRenderData(entityKey, entityPosX, entityPosY,
                    entityPosZ,
                    rayTracingFlag, postRenderFlag, prebuiltBLAS, isPostRender);
            EntityRenderData
                waterMaskRenderData =
                new EntityRenderData(waterMaskEntityKey(entityKey), entityPosX, entityPosY,
                    entityPosZ,
                    RayTracingFlags.BOAT_WATER_MASK.getValue(), 0, prebuiltBLAS, isPostRender);
            for (Map.Entry<RenderType, VertexConsumer> layerBuffer : layerBuffers.entrySet()) {
                RenderType layer = layerBuffer.getKey();
                MeshData buffer = null;

                VertexConsumer vertexConsumer = layerBuffer.getValue();
                if (vertexConsumer instanceof BufferBuilder bufferBuilder) {
                    buffer = bufferBuilder.build();
                } else if (vertexConsumer instanceof PBRVertexConsumer pbrVertexConsumer) {
                    buffer = pbrVertexConsumer.endNullable();
                }

                if (buffer == null) {
                    continue;
                }
                // 26.3 has no LINE_STRIP; native also never learnt the other topologies.
                PrimitiveTopology mode = buffer.drawState()
                    .primitiveTopology();
                if ((mode != PrimitiveTopology.QUADS && mode != PrimitiveTopology.TRIANGLE_STRIP
                    && mode != PrimitiveTopology.LINES)
                    || !StorageVertexConsumerProvider.isNativeVertexFormat(buffer.drawState()
                    .format())) {
                    buffer.close();
                    continue;
                }

                String contentName = contentNameResolver == null
                    ? ""
                    : Objects.requireNonNullElse(contentNameResolver.apply(layer), "");
                if (RenderTypeInfo.name(layer)
                    .contains("water_mask")) {
                    waterMaskRenderData.add(
                        new EntityRenderLayer(layer, buffer, storageVertexConsumerProvider, reflect, contentName));
                } else {
                    entityRenderData.add(
                        new EntityRenderLayer(layer, buffer, storageVertexConsumerProvider, reflect, contentName));
                }
            }

            if (!entityRenderData.isEmpty()) {
                entityRenderDataList.add(entityRenderData);
            }
            if (!waterMaskRenderData.isEmpty()) {
                entityRenderDataList.add(waterMaskRenderData);
            }
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    /**
     * Entities, from the render states vanilla extracted this frame. Each one is submitted at the
     * origin into Radiance's capture and tessellated into its own storage, with its position sent
     * separately, as on 1.21.4.
     */
    public static void queueEntitiesBuild(LevelRenderState levelRenderState,
        EntityRenderDispatcher entityRenderDispatcher) {
        if (!isEntityNativeTransferEnabled()) {
            return;
        }

        if (Profiler.ENABLED) {
            Profiler.enter("java.EntityProxy.queueEntitiesBuild");
        }
        try {
            PoseStack matrixStack = new PoseStack();
            CameraRenderState cameraState = levelRenderState.cameraRenderState;
            List<StorageVertexConsumerProvider> entityStorageVertexConsumerProviders = new ArrayList<>();
            EntityRenderDataList entityRenderDataList = new EntityRenderDataList();

            for (EntityRenderState state : levelRenderState.entityRenderStates) {
                IEntityRenderStateExt stateExt = (IEntityRenderStateExt) state;
                long entityKey = stateExt.radiance$getEntityKey();
                if (entityKey == 0L) {
                    entityKey = normalEntityKey(state);
                }
                int rayTracingFlag = stateExt.radiance$getRayTracingFlag();
                if (rayTracingFlag == 0) {
                    rayTracingFlag = Constants.RayTracingFlags.WORLD.getValue();
                }

                double entityPosX = state.x;
                double entityPosY = state.y;
                double entityPosZ = state.z;

                StorageVertexConsumerProvider entityStorageVertexConsumerProvider = new StorageVertexConsumerProvider(
                    786432);
                entityStorageVertexConsumerProvider.setEntityBuildContext(
                    Constants.Coordinates.WORLD.getValue(), false, 0, 0, 0);
                entityStorageVertexConsumerProviders.add(entityStorageVertexConsumerProvider);

                if (stateExt.radiance$isTextDisplay()) {
                    entityStorageVertexConsumerProvider.setEntityBuildContext(
                        Constants.Coordinates.WORLD.getValue(), false,
                        entityPosX, entityPosY, entityPosZ);
                    entityRenderDispatcher.submit(state, cameraState, 0, 0, 0, matrixStack,
                        CAPTURE.collector());
                    CAPTURE.flush(entityStorageVertexConsumerProvider::getBuffer);
                    processPostEntityRenderData(entityStorageVertexConsumerProvider,
                        entityKey,
                        entityPosX,
                        entityPosY,
                        entityPosZ,
                        PostRenderFlags.TEXT,
                        entityRenderDataList);
                    continue;
                }

                StorageVertexConsumerProvider postTextStorageVertexConsumerProvider = new StorageVertexConsumerProvider(
                    16384);
                postTextStorageVertexConsumerProvider.setEntityBuildContext(
                    Constants.Coordinates.WORLD.getValue(), false,
                    entityPosX, entityPosY, entityPosZ);
                CAPTURE.separateNameTags(true);
                try {
                    entityRenderDispatcher.submit(state, cameraState, 0, 0, 0, matrixStack,
                        CAPTURE.collector());
                } finally {
                    CAPTURE.separateNameTags(false);
                }
                CAPTURE.flush(entityStorageVertexConsumerProvider::getBuffer);
                CAPTURE.flushNameTags(postTextStorageVertexConsumerProvider::getBuffer);

                boolean hasPostText = !postTextStorageVertexConsumerProvider.getLayers()
                    .isEmpty();
                if (hasPostText) {
                    entityStorageVertexConsumerProviders.add(postTextStorageVertexConsumerProvider);
                    processPostEntityRenderData(postTextStorageVertexConsumerProvider,
                        entityKey,
                        entityPosX,
                        entityPosY,
                        entityPosZ,
                        PostRenderFlags.NAME_TAG,
                        entityRenderDataList);
                } else {
                    postTextStorageVertexConsumerProvider.close();
                }

                processWorldEntityRenderData(entityStorageVertexConsumerProvider,
                    entityKey,
                    entityPosX,
                    entityPosY,
                    entityPosZ,
                    rayTracingFlag,
                    true,
                    entityRenderDataList);
            }

            queueBuild(entityStorageVertexConsumerProviders, entityRenderDataList);
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    /**
     * Block entities, their crumbling overlays, and the crumbling overlay of plain blocks. Crumbling
     * is kept in its own storage with a normal offset so it sits just above the surface it cracks.
     */
    public static void queueBlockEntitiesRebuild(LevelRenderState levelRenderState,
        BlockEntityRenderDispatcher blockEntityRenderDispatcher) {
        if (!isEntityNativeTransferEnabled()) {
            return;
        }

        if (Profiler.ENABLED) {
            Profiler.enter("java.EntityProxy.queueBlockEntitiesRebuild");
        }
        try {
            PoseStack matrixStack = new PoseStack();
            CameraRenderState cameraState = levelRenderState.cameraRenderState;
            List<StorageVertexConsumerProvider> entityStorageVertexConsumerProviders = new ArrayList<>();
            EntityRenderDataList entityRenderDataList = new EntityRenderDataList();
            List<StorageVertexConsumerProvider> crumblingStorageVertexConsumerProviders = new ArrayList<>();
            EntityRenderDataList crumblingRenderDataList = new EntityRenderDataList();
            LongSet queuedBlockEntityKeys = new LongOpenHashSet();

            for (BlockEntityRenderState state : levelRenderState.blockEntityRenderStates) {
                BlockPos blockPos = state.blockPos;
                long entityKey = blockEntityKey(blockPos);
                if (!queuedBlockEntityKeys.add(entityKey)) {
                    continue;
                }

                double entityPosX = blockPos.getX();
                double entityPosY = blockPos.getY();
                double entityPosZ = blockPos.getZ();

                StorageVertexConsumerProvider entityStorageVertexConsumerProvider = new StorageVertexConsumerProvider(
                    786432);
                entityStorageVertexConsumerProvider.setEntityBuildContext(
                    Constants.Coordinates.WORLD.getValue(), false, 0, 0, 0);
                entityStorageVertexConsumerProviders.add(entityStorageVertexConsumerProvider);
                StorageVertexConsumerProvider crumblingStorageVertexConsumerProvider = new StorageVertexConsumerProvider(
                    0);
                crumblingStorageVertexConsumerProvider.setEntityBuildContext(
                    Constants.Coordinates.WORLD.getValue(), true, 0, 0, 0);
                crumblingStorageVertexConsumerProviders.add(crumblingStorageVertexConsumerProvider);

                // Vanilla projects the crack texture from the camera-relative block pose; the block
                // is captured at the origin, so project from the origin instead.
                if (state.breakProgress != null) {
                    state.breakProgress = new ModelFeatureRenderer.CrumblingOverlay(
                        state.breakProgress.progress(), matrixStack.last()
                        .copy());
                }

                matrixStack.pushPose();
                blockEntityRenderDispatcher.submit(state, matrixStack, CAPTURE.collector(), cameraState);
                matrixStack.popPose();
                CAPTURE.flush(renderLayer -> isCrumbling(renderLayer)
                    ? crumblingStorageVertexConsumerProvider.getBuffer(renderLayer)
                    : entityStorageVertexConsumerProvider.getBuffer(renderLayer));

                processWorldEntityRenderData(entityStorageVertexConsumerProvider,
                    entityKey,
                    entityPosX,
                    entityPosY,
                    entityPosZ,
                    Constants.RayTracingFlags.WORLD,
                    true,
                    entityRenderDataList);
                processWorldEntityRenderData(crumblingStorageVertexConsumerProvider,
                    blockEntityCrumblingKey(blockPos),
                    entityPosX,
                    entityPosY,
                    entityPosZ,
                    Constants.RayTracingFlags.WORLD,
                    true,
                    crumblingRenderDataList);
            }

            queueBuild(entityStorageVertexConsumerProviders, entityRenderDataList);
            queueCrumblingRebuild(levelRenderState.blockBreakingRenderStates,
                crumblingStorageVertexConsumerProviders, crumblingRenderDataList);
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    private static boolean isCrumbling(RenderType renderLayer) {
        return ModelBakery.DESTROY_TYPES.contains(renderLayer)
            || ModelBakery.DESTROY_TYPES_OIT.contains(renderLayer);
    }

    private static void queueCrumblingRebuild(
        List<BlockBreakingRenderState> blockBreakingRenderStates,
        List<StorageVertexConsumerProvider> crumblingStorageVertexConsumerProviders,
        EntityRenderDataList crumblingRenderDataList) {
        if (Profiler.ENABLED) {
            Profiler.enter("java.EntityProxy.queueCrumblingRebuild");
        }
        try {
            PoseStack matrixStack = new PoseStack();
            List<StorageVertexConsumerProvider> combinedStorageVertexConsumerProviders =
                new ArrayList<>(crumblingStorageVertexConsumerProviders);
            EntityRenderDataList combinedRenderDataList = new EntityRenderDataList();
            combinedRenderDataList.addAll(crumblingRenderDataList);
            List<BlockStateModelPart> parts = new ArrayList<>();
            RandomSource random = RandomSource.createThreadLocalInstance();
            Minecraft client = Minecraft.getInstance();

            // The extractor already dropped blocks more than 32 blocks away, as 1.21.4 did here.
            for (BlockBreakingRenderState state : blockBreakingRenderStates) {
                if (state.blockState()
                    .getRenderShape() != RenderShape.MODEL) {
                    continue;
                }
                BlockPos blockPos = state.blockPos();

                StorageVertexConsumerProvider blockCrumblingStorageVertexConsumerProvider = new StorageVertexConsumerProvider(
                    786432);
                blockCrumblingStorageVertexConsumerProvider.setEntityBuildContext(
                    Constants.Coordinates.WORLD.getValue(), true, 0, 0, 0);
                combinedStorageVertexConsumerProviders.add(blockCrumblingStorageVertexConsumerProvider);

                matrixStack.pushPose();
                matrixStack.translate(state.blockState()
                    .getOffset(blockPos));
                BlockStateModel model = client.getModelManager()
                    .getBlockStateModelSet()
                    .get(state.blockState());
                random.setSeed(state.blockState()
                    .getSeed(blockPos));
                model.collectParts(random, parts);
                CAPTURE.collector()
                    .submitBreakingBlockModel(matrixStack, List.copyOf(parts), state.progress(),
                        model.hasMaterialFlag(1));
                parts.clear();
                matrixStack.popPose();
                CAPTURE.flush(blockCrumblingStorageVertexConsumerProvider::getBuffer);

                processWorldEntityRenderData(blockCrumblingStorageVertexConsumerProvider,
                    blockDamageKey(blockPos),
                    blockPos.getX(),
                    blockPos.getY(),
                    blockPos.getZ(),
                    Constants.RayTracingFlags.WORLD,
                    true,
                    combinedRenderDataList);
            }

            queueBuild(combinedStorageVertexConsumerProviders, combinedRenderDataList, 0.0f,
                Constants.Coordinates.WORLD,
                true);
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    /**
     * Geometry vanilla submits relative to the camera, such as transient blocks. {@code submitter}
     * receives the collector to submit into.
     */
    public static void queueCameraRelativeBuild(Consumer<SubmitNodeCollector> submitter) {
        if (!isEntityNativeTransferEnabled()) {
            return;
        }

        if (Profiler.ENABLED) {
            Profiler.enter("java.EntityProxy.queueCameraRelativeBuild");
        }
        try {
            List<StorageVertexConsumerProvider> storageVertexConsumerProviders = new ArrayList<>();
            EntityRenderDataList renderDataList = new EntityRenderDataList();

            StorageVertexConsumerProvider storageVertexConsumerProvider = new StorageVertexConsumerProvider(
                0);
            storageVertexConsumerProvider.setEntityBuildContext(
                Constants.Coordinates.CAMERA_SHIFT.getValue(), false, 0, 0, 0);
            storageVertexConsumerProviders.add(storageVertexConsumerProvider);

            submitter.accept(CAPTURE.collector());
            CAPTURE.flush(storageVertexConsumerProvider::getBuffer);

            processWorldEntityRenderData(storageVertexConsumerProvider, staticEntityKey(4), 0, 0, 0,
                Constants.RayTracingFlags.WORLD, true, renderDataList);

            queueBuild(storageVertexConsumerProviders, renderDataList, 0.0f,
                Constants.Coordinates.CAMERA_SHIFT, false);
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    /**
     * The first-person hands and held items, traced in camera space. 26.3 extracts them into
     * {@code PlayerRenderState}; {@code GameRendererMixins} calls this instead of vanilla's raster
     * hand pass.
     */
    public static void queueHandRebuild(CameraRenderState cameraState, PlayerRenderState playerState,
        float handProjectionScale) {
        if (!isEntityNativeTransferEnabled()) {
            return;
        }

        if (Profiler.ENABLED) {
            Profiler.enter("java.EntityProxy.queueHandRebuild");
        }
        try {
            Minecraft client = Minecraft.getInstance();
            boolean renderHand = !cameraState.isPanoramicMode && playerState.hasPlayer
                && client.options.getCameraType()
                .isFirstPerson() && !cameraState.entityRenderState.isSleeping
                && !client.gameRenderer.gameRenderState().guiRenderState.isHudHidden
                && client.gameMode != null
                && client.gameMode.getPlayerMode() != GameType.SPECTATOR;
            if (!renderHand) {
                return;
            }

            PoseStack matrixStack = new PoseStack();
            List<StorageVertexConsumerProvider> storageVertexConsumerProviders = new ArrayList<>();
            EntityRenderDataList renderDataList = new EntityRenderDataList();

            StorageVertexConsumerProvider storageVertexConsumerProvider = new StorageVertexConsumerProvider(
                8192);
            storageVertexConsumerProvider.setEntityBuildContext(
                Constants.Coordinates.CAMERA.getValue(), false, 0, 0, 0);
            storageVertexConsumerProviders.add(storageVertexConsumerProvider);

            matrixStack.pushPose();
            matrixStack.scale(handProjectionScale, handProjectionScale, 1.0F);
            client.gameRenderer.firstPersonHandsAndItemsRenderer.submitHandsWithItems(
                cameraState.cameraEntityPartialTicks,
                matrixStack,
                CAPTURE.collector(),
                playerState,
                playerState.firstPersonHandsAndItems);
            matrixStack.popPose();
            CAPTURE.flush(storageVertexConsumerProvider::getBuffer);

            processWorldEntityRenderData(storageVertexConsumerProvider,
                staticEntityKey(Constants.RayTracingFlags.HAND.getValue()),
                0,
                0,
                0,
                Constants.RayTracingFlags.HAND,
                true,
                renderDataList);
            queueBuild(storageVertexConsumerProviders, renderDataList, 0.0f,
                Constants.Coordinates.CAMERA,
                false);
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    /** Called before vanilla extracts particles: starts routing quads by content name. */
    public static void beginParticleExtract() {
        for (QuadParticleRenderState state : PARTICLE_STATES.values()) {
            state.clear();
        }
        particleExtractActive = isEntityNativeTransferEnabled();
    }

    public static void endParticleExtract() {
        particleExtractActive = false;
    }

    /**
     * Where a quad particle with {@code contentName} is extracted to, or null to keep vanilla's
     * state. Each content name gets its own post-render group, as on 1.21.4.
     */
    public static @Nullable QuadParticleRenderState particleRenderState(@Nullable String contentName) {
        if (!particleExtractActive) {
            return null;
        }
        return PARTICLE_STATES.computeIfAbsent(normalizeParticleContentName(contentName),
            key -> new QuadParticleRenderState());
    }

    public static void queueParticleRebuild(ParticlesRenderState particlesRenderState,
        CameraRenderState cameraState) {
        if (!isEntityNativeTransferEnabled()) {
            return;
        }

        if (Profiler.ENABLED) {
            Profiler.enter("java.EntityProxy.queueParticleRebuild");
        }
        try {
            List<StorageVertexConsumerProvider> storageVertexConsumerProviders = new ArrayList<>();
            EntityRenderDataList renderDataList = new EntityRenderDataList();

            Map<String, StorageVertexConsumerProvider> postStorageVertexConsumerProviders = new LinkedHashMap<>();
            Function<String, StorageVertexConsumerProvider> postProvider =
                contentName -> postStorageVertexConsumerProviders.computeIfAbsent(contentName, key -> {
                    StorageVertexConsumerProvider provider = new StorageVertexConsumerProvider(
                        0);
                    provider.setEntityBuildContext(
                        Constants.Coordinates.CAMERA_SHIFT.getValue(), false, 0, 0, 0);
                    storageVertexConsumerProviders.add(provider);
                    return provider;
                });

            for (Map.Entry<String, QuadParticleRenderState> entry : PARTICLE_STATES.entrySet()) {
                QuadParticleRenderState particles = entry.getValue();
                if (particles.isEmpty()) {
                    continue;
                }
                StorageVertexConsumerProvider provider = postProvider.apply(entry.getKey());
                SubmitCapture.buildParticles(particles, false, provider::getBuffer);
                SubmitCapture.buildParticles(particles, true, provider::getBuffer);
            }

            // Particles that are not single quads (item pickups, elder guardians) are models,
            // traced like 1.21.4's custom particles.
            StorageVertexConsumerProvider storageVertexConsumerProvider = new StorageVertexConsumerProvider(
                0);
            storageVertexConsumerProvider.setEntityBuildContext(
                Constants.Coordinates.CAMERA_SHIFT.getValue(), false, 0, 0, 0);
            storageVertexConsumerProviders.add(storageVertexConsumerProvider);

            for (ParticleGroupRenderState particles : particlesRenderState.particles) {
                if (particles instanceof QuadParticleRenderState quadParticles) {
                    // Quads that bypassed the content routing (particles overriding extraction).
                    if (!quadParticles.isEmpty()) {
                        StorageVertexConsumerProvider provider = postProvider.apply(
                            PARTICLE_DEFAULT_CONTENT);
                        SubmitCapture.buildParticles(quadParticles, false, provider::getBuffer);
                        SubmitCapture.buildParticles(quadParticles, true, provider::getBuffer);
                    }
                } else {
                    particles.submit(CAPTURE.collector(), cameraState);
                }
            }
            CAPTURE.flush(storageVertexConsumerProvider::getBuffer);

            for (Map.Entry<String, StorageVertexConsumerProvider> entry : postStorageVertexConsumerProviders.entrySet()) {
                processPostEntityRenderData(entry.getValue(), staticEntityKey(entry.getKey()
                        .hashCode() + 3L), 0, 0, 0,
                    PostRenderFlags.PARTICLE, entry.getKey(), renderDataList);
            }

            processWorldEntityRenderData(storageVertexConsumerProvider, staticEntityKey(1), 0, 0, 0,
                Constants.RayTracingFlags.PARTICLE, true, renderDataList);

            queueBuild(storageVertexConsumerProviders, renderDataList, 0.0f,
                Constants.Coordinates.CAMERA_SHIFT, false);
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    /**
     * The outline of the block the player looks at, built from the extracted outline the same way
     * {@code LevelRenderer.submitBlockOutline} does, but around the block's own origin.
     */
    public static void queueTargetBlockOutlineRebuild(@Nullable BlockOutlineRenderState state,
        boolean useImprovedTransparency, float lineWidth) {
        if (!isEntityNativeTransferEnabled()) {
            return;
        }

        if (Profiler.ENABLED) {
            Profiler.enter("java.EntityProxy.queueTargetBlockOutlineRebuild");
        }
        try {
            List<StorageVertexConsumerProvider> storageVertexConsumerProviders = new ArrayList<>();
            EntityRenderDataList renderDataList = new EntityRenderDataList();

            PoseStack matrixStack = new PoseStack();

            StorageVertexConsumerProvider storageVertexConsumerProvider = new StorageVertexConsumerProvider(
                0);
            storageVertexConsumerProvider.setEntityBuildContext(
                Constants.Coordinates.WORLD.getValue(), false, 0, 0, 0);
            storageVertexConsumerProviders.add(storageVertexConsumerProvider);

            if (state != null) {
                BlockPos blockPos = state.pos();
                SubmitNodeCollector collector = CAPTURE.collector();
                if (state.highContrast()) {
                    collector.submitShapeOutline(matrixStack, state.shape(),
                        RenderTypes.secondaryBlockOutline(), -16777216, 7.0F, state.isTranslucent());
                }

                int color = state.highContrast() ? -11010079 : ARGB.black(102);
                RenderType renderType;
                if (state.highContrast()) {
                    renderType = RenderTypes.linesDepthBias();
                } else if (useImprovedTransparency) {
                    renderType = RenderTypes.linesTranslucentNoDepthWrite();
                } else {
                    renderType = RenderTypes.linesTranslucent();
                }
                collector.submitShapeOutline(matrixStack, state.shape(), renderType, color,
                    lineWidth, state.isTranslucent());
                CAPTURE.flush(storageVertexConsumerProvider::getBuffer);

                processWorldEntityRenderData(storageVertexConsumerProvider,
                    targetBlockOutlineKey(blockPos),
                    blockPos.getX(),
                    blockPos.getY(),
                    blockPos.getZ(),
                    Constants.RayTracingFlags.FISHING_BOBBER,
                    false,
                    renderDataList);
            }

            queueBuild(storageVertexConsumerProviders, renderDataList, 0.0075f,
                Constants.Coordinates.WORLD,
                false);
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    /**
     * Rain and snow, from the columns vanilla extracted this frame, tessellated with vanilla's own
     * column builder relative to the camera.
     */
    public static void queueWeatherBuild(WeatherEffectRenderer weatherRendering,
        WeatherRenderState weatherRenderState, Vec3 cameraPos) {
        if (!isEntityNativeTransferEnabled()) {
            return;
        }

        if (Profiler.ENABLED) {
            Profiler.enter("java.EntityProxy.queueWeatherBuild");
        }
        try {
            List<StorageVertexConsumerProvider> storageVertexConsumerProviders = new ArrayList<>();
            EntityRenderDataList renderDataList = new EntityRenderDataList();

            StorageVertexConsumerProvider storageVertexConsumerProvider = new StorageVertexConsumerProvider(
                0);
            storageVertexConsumerProvider.setEntityBuildContext(
                Constants.Coordinates.CAMERA_SHIFT.getValue(), false, 0, 0, 0);
            storageVertexConsumerProviders.add(storageVertexConsumerProvider);

            if (!weatherRenderState.rainColumns.isEmpty()) {
                weatherRendering.prepareInstances(storageVertexConsumerProvider.getBuffer(RAIN_RENDER_TYPE),
                    weatherRenderState.rainColumns, cameraPos, 1.0F, weatherRenderState.radius,
                    weatherRenderState.intensity);
            }
            if (!weatherRenderState.snowColumns.isEmpty()) {
                weatherRendering.prepareInstances(storageVertexConsumerProvider.getBuffer(SNOW_RENDER_TYPE),
                    weatherRenderState.snowColumns, cameraPos, 0.8F, weatherRenderState.radius,
                    weatherRenderState.intensity);
            }

            processPostEntityRenderData(storageVertexConsumerProvider, staticEntityKey(2), 0, 0, 0,
                PostRenderFlags.WEATHER, EntityProxy::resolveWeatherContentName, renderDataList);

            queueBuild(storageVertexConsumerProviders, renderDataList, 0.0f,
                Constants.Coordinates.CAMERA_SHIFT, false);
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    public static void queueBuild(
        List<StorageVertexConsumerProvider> storageVertexConsumerProviders,
        EntityRenderDataList entityRenderDataList) {
        queueBuild(storageVertexConsumerProviders, entityRenderDataList, 0.0125f,
            Constants.Coordinates.WORLD, false);
    }

    public static void queueBuild(
        List<StorageVertexConsumerProvider> storageVertexConsumerProviders,
        EntityRenderDataList entityRenderDataList,
        float lineWidth,
        Constants.Coordinates coordinate,
        boolean normalOffset) {
        queueBuildInternal(storageVertexConsumerProviders, entityRenderDataList, lineWidth,
            coordinate, normalOffset, true);
    }

    public static void queueBuildWithoutClose(EntityRenderDataList entityRenderDataList) {
        queueBuildWithoutClose(entityRenderDataList, 0.0125f, Constants.Coordinates.WORLD, false);
    }

    public static void queueBuildWithoutClose(EntityRenderDataList entityRenderDataList,
        float lineWidth,
        Constants.Coordinates coordinate,
        boolean normalOffset) {
        queueBuildInternal(null, entityRenderDataList, lineWidth, coordinate, normalOffset, false);
    }

    private static void queueBuildInternal(
        List<StorageVertexConsumerProvider> storageVertexConsumerProviders,
        EntityRenderDataList entityRenderDataList,
        float lineWidth,
        Constants.Coordinates coordinate,
        boolean normalOffset,
        boolean closeAfterBuild) {
        if (!isEntityNativeTransferEnabled()) {
            if (closeAfterBuild) {
                closeUntransferredBuiltBuffers(entityRenderDataList, null);
                closeStorageVertexConsumerProviders(storageVertexConsumerProviders);
            }
            return;
        }

        if (Profiler.ENABLED) {
            Profiler.enter("java.EntityProxy.queueBuildInternal");
        }
        try {
            ByteBuffer entityKeyBuffer = null;
            ByteBuffer entityPosXBuffer = null;
            ByteBuffer entityPosYBuffer = null;
            ByteBuffer entityPosZBuffer = null;
            ByteBuffer entityRayTracingFlagBuffer = null;
            ByteBuffer entityPostRenderFlagBuffer = null;
            ByteBuffer entityPrebuiltBlasBuffer = null;
            ByteBuffer entityPostRenderBuffer = null;
            ByteBuffer entityLayerCountBuffer = null;
            ByteBuffer geometryTypeBuffer = null;
            ByteBuffer geometryGroupNamePointerBuffer = null;
            ByteBuffer geometryContentNamePointerBuffer = null;
            ByteBuffer geometryTextureBuffer = null;
            ByteBuffer geometryNormalOffsetBuffer = null;
            ByteBuffer vertexFormatBuffer = null;
            ByteBuffer indexFormatBuffer = null;
            ByteBuffer vertexCountBuffer = null;
            ByteBuffer vertexPointerBuffer = null;
            TransferredBuildBuffer[] transferredBuffers =
                closeAfterBuild ? new TransferredBuildBuffer[entityRenderDataList.getTotalLayersCount()] : null;
            boolean submittedNative = false;

            try {
                int entityKeySize = entityRenderDataList.getTotalEntityCount() * Long.BYTES;
                entityKeyBuffer = MemoryUtil.memAlloc(entityKeySize);
                long entityKeyAddress = memAddress(entityKeyBuffer);
                int entityKeyByteOffset = 0;

                int entityPosXSize = entityRenderDataList.getTotalEntityCount() * Double.BYTES;
                entityPosXBuffer = MemoryUtil.memAlloc(entityPosXSize);
                long entityPosXAddress = memAddress(entityPosXBuffer);
                int entityPosXByteOffset = 0;

                int entityPosYSize = entityRenderDataList.getTotalEntityCount() * Double.BYTES;
                entityPosYBuffer = MemoryUtil.memAlloc(entityPosYSize);
                long entityPosYAddress = memAddress(entityPosYBuffer);
                int entityPosYByteOffset = 0;

                int entityPosZSize = entityRenderDataList.getTotalEntityCount() * Double.BYTES;
                entityPosZBuffer = MemoryUtil.memAlloc(entityPosZSize);
                long entityPosZAddress = memAddress(entityPosZBuffer);
                int entityPosZByteOffset = 0;

                int entityRayTracingFlagSize = entityRenderDataList.getTotalEntityCount() * Integer.BYTES;
                entityRayTracingFlagBuffer = MemoryUtil.memAlloc(entityRayTracingFlagSize);
                long entityRayTracingFlagAddress = memAddress(entityRayTracingFlagBuffer);
                int entityRayTracingFlagByteOffset = 0;

                int entityPostRenderFlagSize = entityRenderDataList.getTotalEntityCount() * Integer.BYTES;
                entityPostRenderFlagBuffer = MemoryUtil.memAlloc(entityPostRenderFlagSize);
                long entityPostRenderFlagAddress = memAddress(entityPostRenderFlagBuffer);
                int entityPostRenderFlagByteOffset = 0;

                int entityPrebuiltBLASSize = entityRenderDataList.getTotalEntityCount() * Integer.BYTES;
                entityPrebuiltBlasBuffer = MemoryUtil.memAlloc(entityPrebuiltBLASSize);
                long entityPrebuiltBlasAddress = memAddress(entityPrebuiltBlasBuffer);
                int entityPrebuiltBlasByteOffset = 0;

                int entityPostSize = entityRenderDataList.getTotalEntityCount() * Integer.BYTES;
                entityPostRenderBuffer = MemoryUtil.memAlloc(entityPostSize);
                long entityPostRenderAddress = memAddress(entityPostRenderBuffer);
                int entityPostRenderByteOffset = 0;

                int entityLayerCountSize = entityRenderDataList.getTotalEntityCount() * Integer.BYTES;
                entityLayerCountBuffer = MemoryUtil.memAlloc(entityLayerCountSize);
                long entityLayerCountAddress = memAddress(entityLayerCountBuffer);
                int entityLayerCountByteOffset = 0;

                int geometryTypeSize = entityRenderDataList.getTotalLayersCount() * Integer.BYTES;
                geometryTypeBuffer = MemoryUtil.memAlloc(geometryTypeSize);
                long geometryTypeAddress = memAddress(geometryTypeBuffer);
                int geometryTypeByteOffset = 0;

                int geometryGroupNameSize = entityRenderDataList.getTotalLayersCount() * Long.BYTES;
                geometryGroupNamePointerBuffer = MemoryUtil.memAlloc(geometryGroupNameSize);
                long geometryGroupNameAddress = memAddress(geometryGroupNamePointerBuffer);
                int geometryGroupNameByteOffset = 0;

                int geometryContentNameSize = entityRenderDataList.getTotalLayersCount() * Long.BYTES;
                geometryContentNamePointerBuffer = MemoryUtil.memAlloc(geometryContentNameSize);
                long geometryContentNameAddress = memAddress(geometryContentNamePointerBuffer);
                int geometryContentNameByteOffset = 0;

                int geometryTextureSize = entityRenderDataList.getTotalLayersCount() * Integer.BYTES;
                geometryTextureBuffer = MemoryUtil.memAlloc(geometryTextureSize);
                long geometryTextureAddress = memAddress(geometryTextureBuffer);
                int geometryTextureByteOffset = 0;

                int geometryNormalOffsetSize = entityRenderDataList.getTotalLayersCount() * Float.BYTES;
                geometryNormalOffsetBuffer = MemoryUtil.memAlloc(geometryNormalOffsetSize);
                long geometryNormalOffsetAddress = memAddress(geometryNormalOffsetBuffer);
                int geometryNormalOffsetByteOffset = 0;

                int vertexFormatSize = entityRenderDataList.getTotalLayersCount() * Integer.BYTES;
                vertexFormatBuffer = MemoryUtil.memAlloc(vertexFormatSize);
                long vertexFormatAddress = memAddress(vertexFormatBuffer);
                int vertexFormatByteOffset = 0;

                int indexFormatSize = entityRenderDataList.getTotalLayersCount() * Integer.BYTES;
                indexFormatBuffer = MemoryUtil.memAlloc(indexFormatSize);
                long indexFormatAddress = memAddress(indexFormatBuffer);
                int indexFormatByteOffset = 0;

                int vertexCountSize = entityRenderDataList.getTotalLayersCount() * Integer.BYTES;
                vertexCountBuffer = MemoryUtil.memAlloc(vertexCountSize);
                long vertexCountAddress = memAddress(vertexCountBuffer);
                int vertexCountByteOffset = 0;

                int verticesSize = entityRenderDataList.getTotalLayersCount() * Long.BYTES;
                vertexPointerBuffer = MemoryUtil.memAlloc(verticesSize);
                long vertexPointerAddress = memAddress(vertexPointerBuffer);
                int vertexPointerByteOffset = 0;

                int transferBufferIndex = 0;
                for (EntityRenderData entityRenderData : entityRenderDataList) {
                    entityKeyBuffer.putLong(entityKeyByteOffset, entityRenderData.entityKey);
                    entityKeyByteOffset += Long.BYTES;

                    entityPosXBuffer.putDouble(entityPosXByteOffset, entityRenderData.x);
                    entityPosXByteOffset += Double.BYTES;

                    entityPosYBuffer.putDouble(entityPosYByteOffset, entityRenderData.y);
                    entityPosYByteOffset += Double.BYTES;

                    entityPosZBuffer.putDouble(entityPosZByteOffset, entityRenderData.z);
                    entityPosZByteOffset += Double.BYTES;

                    entityRayTracingFlagBuffer.putInt(entityRayTracingFlagByteOffset,
                        entityRenderData.rayTracingFlag);
                    entityRayTracingFlagByteOffset += Integer.BYTES;

                    entityPostRenderFlagBuffer.putInt(entityPostRenderFlagByteOffset,
                        entityRenderData.postRenderFlag);
                    entityPostRenderFlagByteOffset += Integer.BYTES;

                    entityPrebuiltBlasBuffer.putInt(entityPrebuiltBlasByteOffset, entityRenderData.prebuiltBLAS);
                    entityPrebuiltBlasByteOffset += Integer.BYTES;

                    entityPostRenderBuffer.putInt(entityPostRenderByteOffset, entityRenderData.isPostRender ? 1 : 0);
                    entityPostRenderByteOffset += Integer.BYTES;

                    entityLayerCountBuffer.putInt(entityLayerCountByteOffset, entityRenderData.size());
                    entityLayerCountByteOffset += Integer.BYTES;

                    for (EntityRenderLayer entityRenderLayer : entityRenderData) {
                        RenderType renderLayer = entityRenderLayer.renderLayer;
                        MeshData vertexBuffer = entityRenderLayer.builtBuffer;

                        Identifier textureIdentifier = Objects.requireNonNullElse(
                            RenderTypeInfo.mainTexture(renderLayer),
                            MissingTextureAtlasSprite.getLocation());
                        int
                            geometryTypeID =
                            Constants.GeometryTypes.getGeometryType(renderLayer, entityRenderLayer.reflect)
                                .getValue();
                        int geometryTextureID = Math.max(RenderTypeInfo.mainTextureId(renderLayer), 0);
                        float geometryNormalOffset = getGeometryNormalOffset(textureIdentifier);
                        int
                            vertexFormatID =
                            Constants.VertexFormats.getValue(vertexBuffer.drawState()
                                .format());
                        int
                            indexFormatID =
                            Constants.DrawModes.getValue(vertexBuffer.drawState()
                                .primitiveTopology());
                        float externalBaseX = entityRenderData.isPostRender ? (float) entityRenderData.x : 0.0f;
                        float externalBaseY = entityRenderData.isPostRender ? (float) entityRenderData.y : 0.0f;
                        float externalBaseZ = entityRenderData.isPostRender ? (float) entityRenderData.z : 0.0f;
                        boolean canUseExternalPbr = PBRVertexConsumer.canUseExternalPbr(vertexBuffer);
                        boolean hasFinalExternalPbrContext = canUseExternalPbr &&
                            entityRenderLayer.ownerProvider != null &&
                            entityRenderLayer.ownerProvider.hasFinalEntityBuildContext(
                                coordinate.getValue(), normalOffset, externalBaseX, externalBaseY, externalBaseZ);
                        if (canUseExternalPbr && !hasFinalExternalPbrContext) {
                            throw new IllegalStateException(
                                "PBR QUADS entity layer was built before its final build context was set: layer=" +
                                    RenderTypeInfo.name(renderLayer) + ", content=" + entityRenderLayer.contentName() +
                                    ", coordinate=" + coordinate.getValue() +
                                    ", normalOffset=" + normalOffset +
                                    ", postBase=(" + externalBaseX + ", " + externalBaseY + ", " + externalBaseZ + ")");
                        }
                        boolean shouldTransferExternalPbr = transferredBuffers != null && hasFinalExternalPbrContext;

                        TransferredBuildBuffer transferredBuffer = null;
                        long vertexAddress;

                        if (shouldTransferExternalPbr) {
                            transferredBuffer = TransferredBuildBuffer.takeEntity(
                                entityRenderLayer.ownerProvider, renderLayer, vertexBuffer);
                            transferredBuffers[transferBufferIndex] = transferredBuffer;
                            vertexAddress = transferredBuffer.vertexAddress();
                        } else {
                            BufferProxy.BufferInfo vertexBufferInfo = BufferProxy.getBufferInfo(
                                vertexBuffer.vertexBuffer());
                            vertexAddress = vertexBufferInfo.address();
                        }
                        assert vertexBuffer.drawState()
                            .primitiveTopology() != PrimitiveTopology.QUADS
                            || vertexBuffer.drawState()
                            .indexCount() == vertexBuffer.drawState()
                            .vertexCount() / 4 * 6;

                        geometryTypeBuffer.putInt(geometryTypeByteOffset, geometryTypeID);
                        geometryTypeByteOffset += Integer.BYTES;

                        ByteBuffer geometryGroupNameBuffer = getUtf8NameBuffer(
                            RenderTypeInfo.name(renderLayer));
                        geometryGroupNamePointerBuffer.putLong(geometryGroupNameByteOffset,
                            memAddress(geometryGroupNameBuffer));
                        geometryGroupNameByteOffset += Long.BYTES;

                        ByteBuffer geometryContentNameBuffer = getUtf8NameBuffer(
                            entityRenderLayer.contentName());
                        geometryContentNamePointerBuffer.putLong(geometryContentNameByteOffset,
                            memAddress(geometryContentNameBuffer));
                        geometryContentNameByteOffset += Long.BYTES;

                        geometryTextureBuffer.putInt(geometryTextureByteOffset, geometryTextureID);
                        geometryTextureByteOffset += Integer.BYTES;

                        geometryNormalOffsetBuffer.putFloat(geometryNormalOffsetByteOffset, geometryNormalOffset);
                        geometryNormalOffsetByteOffset += Float.BYTES;

                        vertexFormatBuffer.putInt(vertexFormatByteOffset, vertexFormatID);
                        vertexFormatByteOffset += Integer.BYTES;

                        indexFormatBuffer.putInt(indexFormatByteOffset, indexFormatID);
                        indexFormatByteOffset += Integer.BYTES;

                        vertexCountBuffer.putInt(vertexCountByteOffset,
                            vertexBuffer.drawState()
                                .vertexCount());
                        vertexCountByteOffset += Integer.BYTES;

                        vertexPointerBuffer.putLong(vertexPointerByteOffset, vertexAddress);
                        vertexPointerByteOffset += Long.BYTES;

                        transferBufferIndex++;
                    }
                }

                if (Profiler.ENABLED) {
                    Profiler.enter("java.EntityProxy.queueBuildNative");
                }
                try {
                    queueBuildNative(lineWidth,
                        coordinate.getValue(),
                        normalOffset,
                        entityRenderDataList.getTotalEntityCount(),
                        entityKeyAddress,
                        entityPosXAddress,
                        entityPosYAddress,
                        entityPosZAddress,
                        entityRayTracingFlagAddress,
                        entityPostRenderFlagAddress,
                        entityPrebuiltBlasAddress,
                        0,
                        0,
                        entityPostRenderAddress,
                        entityLayerCountAddress,
                        geometryTypeAddress,
                        geometryGroupNameAddress,
                        geometryContentNameAddress,
                        geometryTextureAddress,
                        geometryNormalOffsetAddress,
                        vertexFormatAddress,
                        indexFormatAddress,
                        vertexCountAddress,
                        vertexPointerAddress,
                        transferredBuffers);
                    submittedNative = true;
                } finally {
                    if (Profiler.ENABLED) {
                        Profiler.exit();
                    }
                }
            } finally {
                freeDirectBuffer(entityKeyBuffer);
                freeDirectBuffer(entityPosXBuffer);
                freeDirectBuffer(entityPosYBuffer);
                freeDirectBuffer(entityPosZBuffer);
                freeDirectBuffer(entityRayTracingFlagBuffer);
                freeDirectBuffer(entityPostRenderFlagBuffer);
                freeDirectBuffer(entityPrebuiltBlasBuffer);
                freeDirectBuffer(entityPostRenderBuffer);
                freeDirectBuffer(entityLayerCountBuffer);
                freeDirectBuffer(geometryTypeBuffer);
                freeDirectBuffer(geometryGroupNamePointerBuffer);
                freeDirectBuffer(geometryContentNamePointerBuffer);
                freeDirectBuffer(geometryTextureBuffer);
                freeDirectBuffer(geometryNormalOffsetBuffer);
                freeDirectBuffer(vertexFormatBuffer);
                freeDirectBuffer(indexFormatBuffer);
                freeDirectBuffer(vertexCountBuffer);
                freeDirectBuffer(vertexPointerBuffer);
                if (transferredBuffers != null && !submittedNative) {
                    closeTransferredBuffers(transferredBuffers);
                }
                if (closeAfterBuild && !submittedNative) {
                    closeUntransferredBuiltBuffers(entityRenderDataList, transferredBuffers);
                    closeStorageVertexConsumerProviders(storageVertexConsumerProviders);
                } else if (closeAfterBuild) {
                    closeStorageVertexConsumerProviders(storageVertexConsumerProviders);
                }
            }
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    private static float getGeometryNormalOffset(Identifier identifier) {
        if (!"minecraft".equals(identifier.getNamespace())) {
            return 0.0f;
        }
        String path = identifier.getPath();
        String layerPath;
        if (path.startsWith(VILLAGER_TEXTURE_PREFIX)) {
            layerPath = path.substring(VILLAGER_TEXTURE_PREFIX.length());
        } else if (path.startsWith(ZOMBIE_VILLAGER_TEXTURE_PREFIX)) {
            layerPath = path.substring(ZOMBIE_VILLAGER_TEXTURE_PREFIX.length());
        } else {
            return 0.0f;
        }

        if (layerPath.startsWith("profession_level/")) {
            return VILLAGER_LAYER_OFFSET_UNIT * 3.0f;
        }
        if (layerPath.startsWith("profession/")) {
            return VILLAGER_LAYER_OFFSET_UNIT * 2.0f;
        }
        if (layerPath.startsWith("type/")) {
            return VILLAGER_LAYER_OFFSET_UNIT;
        }
        return 0.0f;
    }

    private static void freeDirectBuffer(ByteBuffer buffer) {
        if (buffer != null) {
            MemoryUtil.memFree(buffer);
        }
    }

    private static void closeTransferredBuffers(TransferredBuildBuffer[] transferredBuffers) {
        for (TransferredBuildBuffer transferredBuffer : transferredBuffers) {
            if (transferredBuffer != null) {
                transferredBuffer.close();
            }
        }
    }

    private static void closeUntransferredBuiltBuffers(
        EntityRenderDataList entityRenderDataList,
        TransferredBuildBuffer[] transferredBuffers) {
        if (entityRenderDataList == null) {
            return;
        }

        for (EntityRenderData entityRenderData : entityRenderDataList) {
            for (EntityRenderLayer entityRenderLayer : entityRenderData) {
                MeshData vertexBuffer = entityRenderLayer.builtBuffer;
                if (isTransferred(vertexBuffer, transferredBuffers)) {
                    continue;
                }
                vertexBuffer.close();
            }
        }
    }

    private static boolean isTransferred(
        MeshData builtBuffer,
        TransferredBuildBuffer[] transferredBuffers) {
        if (transferredBuffers == null) {
            return false;
        }

        for (TransferredBuildBuffer transferredBuffer : transferredBuffers) {
            if (transferredBuffer != null && transferredBuffer.builtBuffer() == builtBuffer) {
                return true;
            }
        }
        return false;
    }

    private static void closeStorageVertexConsumerProviders(
        List<StorageVertexConsumerProvider> storageVertexConsumerProviders) {
        if (storageVertexConsumerProviders == null) {
            return;
        }

        for (StorageVertexConsumerProvider storageVertexConsumerProvider : storageVertexConsumerProviders) {
            storageVertexConsumerProvider.close();
        }
    }

    private static native void queueBuildNative(float lineWidth,
        int coordinate,
        boolean normalOffset,
        int size,
        long entityKeys,
        long entityPosXs,
        long entityPosYs,
        long entityPosZs,
        long entityRayTracingFlags,
        long entityPostRenderFlags,
        long entityPrebuiltBLASs,
        long entityTransforms,
        long entityMaterials,
        long entityPosts,
        long entityLayerCounts,
        long geometryTypes,
        long geometryGroupNames,
        long geometryContentNames,
        long geometryTextures,
        long geometryNormalOffsets,
        long vertexFormats,
        long indexFormats,
        long vertexCounts,
        long vertices,
        TransferredBuildBuffer[] transferredBuffers);

    private static native void buildNative();

    public static void build() {
        if (!isEntityNativeTransferEnabled()) {
            return;
        }

        if (Profiler.ENABLED) {
            Profiler.enter("java.EntityProxy.build");
        }
        try {
            buildNative();
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    private static String defaultPostContentName(Constants.PostRenderFlags postRenderFlag) {
        return switch (postRenderFlag) {
            case WEATHER -> WEATHER_DEFAULT_CONTENT;
            case PARTICLE -> PARTICLE_DEFAULT_CONTENT;
            case TEXT -> TEXT_DEFAULT_CONTENT;
            case NAME_TAG -> NAME_TAG_DEFAULT_CONTENT;
            case WORLD_OVERLAY -> "/world_overlay/default";
        };
    }

    private static String normalizeParticleContentName(String particleContentName) {
        if (particleContentName == null || particleContentName.isBlank()) {
            return PARTICLE_DEFAULT_CONTENT;
        }
        return particleContentName;
    }

    private static String resolveWeatherContentName(RenderType renderLayer) {
        Identifier textureIdentifier = RenderTypeInfo.mainTexture(renderLayer);
        if (WEATHER_RAIN_TEXTURE.equals(textureIdentifier)) {
            return WEATHER_RAIN_CONTENT;
        }
        if (WEATHER_SNOW_TEXTURE.equals(textureIdentifier)) {
            return WEATHER_SNOW_CONTENT;
        }
        return WEATHER_DEFAULT_CONTENT;
    }

    public record EntityRenderLayer(RenderType renderLayer, MeshData builtBuffer,
                                    StorageVertexConsumerProvider ownerProvider,
                                    boolean reflect, String contentName) {

    }

    public static class EntityRenderData extends ArrayList<EntityRenderLayer> {

        private final long entityKey;
        private final int rayTracingFlag;
        private final int postRenderFlag;
        private final int prebuiltBLAS;
        private final boolean isPostRender;
        private double x;
        private double y;
        private double z;

        public EntityRenderData(long entityKey, double x, double y, double z, int rayTracingFlag,
            int postRenderFlag,
            int prebuiltBLAS,
            boolean isPostRender) {
            this.entityKey = entityKey;
            this.x = x;
            this.y = y;
            this.z = z;
            this.rayTracingFlag = rayTracingFlag;
            this.postRenderFlag = postRenderFlag;
            this.prebuiltBLAS = prebuiltBLAS;
            this.isPostRender = isPostRender;
        }

        public double getX() {
            return x;
        }

        public void setX(double x) {
            this.x = x;
        }

        public double getY() {
            return y;
        }

        public void setY(double y) {
            this.y = y;
        }

        public double getZ() {
            return z;
        }

        public void setZ(double z) {
            this.z = z;
        }

        public int getRayTracingFlag() {
            return rayTracingFlag;
        }

        public int getPostRenderFlag() {
            return postRenderFlag;
        }

        public int getPrebuiltBLAS() {
            return prebuiltBLAS;
        }

        public long getEntityKey() {
            return entityKey;
        }

        public boolean isPostRender() {
            return isPostRender;
        }
    }

    public static class EntityRenderDataList extends ArrayList<EntityRenderData> {

        private int totalLayersCount;

        @Override
        public boolean add(EntityRenderData entityRenderData) {
            totalLayersCount += entityRenderData.size();
            return super.add(entityRenderData);
        }

        @Override
        public void add(int index, EntityRenderData entityRenderData) {
            totalLayersCount += entityRenderData.size();
            super.add(index, entityRenderData);
        }

        @Override
        public boolean addAll(Collection<? extends EntityRenderData> entityRenderDataCollection) {
            boolean changed = false;
            for (EntityRenderData entityRenderData : entityRenderDataCollection) {
                changed |= add(entityRenderData);
            }
            return changed;
        }

        @Override
        public boolean addAll(int index, Collection<? extends EntityRenderData> entityRenderDataCollection) {
            int insertIndex = index;
            for (EntityRenderData entityRenderData : entityRenderDataCollection) {
                add(insertIndex, entityRenderData);
                insertIndex++;
            }
            return !entityRenderDataCollection.isEmpty();
        }

        @Override
        public EntityRenderData remove(int index) {
            EntityRenderData removed = super.remove(index);
            totalLayersCount -= removed.size();
            return removed;
        }

        @Override
        public boolean remove(Object object) {
            if (!(object instanceof EntityRenderData entityRenderData)) {
                return false;
            }
            boolean removed = super.remove(object);
            if (removed) {
                totalLayersCount -= entityRenderData.size();
            }
            return removed;
        }

        @Override
        public void clear() {
            totalLayersCount = 0;
            super.clear();
        }

        public int getTotalLayersCount() {
            return totalLayersCount;
        }

        public int getTotalEntityCount() {
            return this.size();
        }
    }
}
