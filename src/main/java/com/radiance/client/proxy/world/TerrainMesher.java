package com.radiance.client.proxy.world;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.radiance.client.vertex.PBRVertexConsumer;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IBlockColorsExt;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;
import org.joml.Vector3fc;

/**
 * Meshes one section straight into Radiance's PBR chunk vertex format. Shared by the vanilla
 * terrain path ({@code SectionBuilderMixins}, region = vanilla's {@code RenderSectionRegion}) and
 * the Sodium path (region = Sodium's {@code LevelSlice}); both are {@link BlockAndTintGetter}s.
 * Like 1.21.4, vanilla ambient occlusion is off; the ray tracer computes its own.
 */
public final class TerrainMesher {

    private TerrainMesher() {
    }

    /**
     * @param visGraph      receives opaque blocks when non-null (vanilla's occlusion culling input)
     * @param blockEntities receives every block entity of the section when non-null
     * @return the finished layers (unsorted: 1.21.4 sent terrain without an index buffer). The
     *     caller owns the meshes and the byte buffers in {@code builders} they were built from.
     */
    public static Map<ChunkSectionLayer, MeshData> mesh(SectionPos sectionPos,
        BlockAndTintGetter region, SectionBufferBuilderPack builders,
        BlockStateModelSet blockModelSet, FluidStateModelSet fluidModelSet, BlockColors blockColors,
        boolean cutoutLeaves, @Nullable VisGraph visGraph,
        @Nullable Consumer<BlockEntity> blockEntities) {
        BlockPos minPos = sectionPos.origin();
        BlockPos maxPos = minPos.offset(15, 15, 15);
        BlockModelLighter.enableCaching();
        ModelBlockRenderer blockRenderer = new ModelBlockRenderer(false, true, blockColors);
        FluidRenderer fluidRenderer = new FluidRenderer(fluidModelSet);
        Map<ChunkSectionLayer, PBRVertexConsumer> startedLayers = new EnumMap<>(
            ChunkSectionLayer.class);
        // The quad outputs only get positions; tint emission also needs the block being meshed.
        BlockState[] currentState = new BlockState[1];
        BlockPos[] currentPos = new BlockPos[1];
        BlockQuadOutput quadOutput = (x, y, z, quad, instance) -> putQuad(
            getOrBeginLayer(startedLayers, builders, quad.materialInfo().layer()),
            x, y, z, quad, instance, region, blockColors, currentState[0], currentPos[0]);
        BlockQuadOutput opaqueQuadOutput = (x, y, z, quad, instance) -> putQuad(
            getOrBeginLayer(startedLayers, builders, ChunkSectionLayer.SOLID),
            x, y, z, quad, instance, region, blockColors, currentState[0], currentPos[0]);
        FluidRenderer.Output fluidOutput = layer -> getOrBeginLayer(startedLayers, builders,
            layer);

        Map<ChunkSectionLayer, MeshData> layers = new EnumMap<>(ChunkSectionLayer.class);
        try {
            for (BlockPos pos : BlockPos.betweenClosed(minPos, maxPos)) {
                BlockState blockState = region.getBlockState(pos);
                if (blockState.isAir()) {
                    continue;
                }

                try {
                    if (visGraph != null && blockState.isSolidRender()) {
                        visGraph.setOpaque(pos);
                    }

                    if (blockEntities != null && blockState.hasBlockEntity()) {
                        BlockEntity blockEntity = region.getBlockEntity(pos);
                        if (blockEntity != null) {
                            blockEntities.accept(blockEntity);
                        }
                    }

                    FluidState fluidState = blockState.getFluidState();
                    if (!fluidState.isEmpty()) {
                        fluidRenderer.tesselate(region, pos, fluidOutput, blockState, fluidState);
                    }

                    if (blockState.getRenderShape() == RenderShape.MODEL) {
                        currentState[0] = blockState;
                        currentPos[0] = pos;
                        blockRenderer.tesselateBlock(
                            ModelBlockRenderer.forceOpaque(cutoutLeaves, blockState)
                                ? opaqueQuadOutput : quadOutput,
                            SectionPos.sectionRelative(pos.getX()),
                            SectionPos.sectionRelative(pos.getY()),
                            SectionPos.sectionRelative(pos.getZ()),
                            region,
                            pos,
                            blockState,
                            blockModelSet.get(blockState),
                            blockState.getSeed(pos));
                    }
                } catch (Throwable t) {
                    CrashReport report = CrashReport.forThrowable(t,
                        "Tesselating block in world");
                    CrashReportCategory category = report.addCategory(
                        "Block being tesselated");
                    CrashReportCategory.populateBlockDetails(category, region, pos, blockState);
                    throw new ReportedException(report);
                }
            }

            // 1.21.4 sent terrain unsorted (no index buffer), so translucent quads are not sorted here.
            for (Map.Entry<ChunkSectionLayer, PBRVertexConsumer> entry : startedLayers.entrySet()) {
                MeshData mesh = entry.getValue().endNullable();
                if (mesh != null) {
                    layers.put(entry.getKey(), mesh);
                }
            }
        } catch (RuntimeException | Error e) {
            layers.values().forEach(MeshData::close);
            throw e;
        } finally {
            BlockModelLighter.clearCache();
        }
        return layers;
    }

    private static PBRVertexConsumer getOrBeginLayer(
        Map<ChunkSectionLayer, PBRVertexConsumer> startedLayers,
        SectionBufferBuilderPack builders, ChunkSectionLayer layer) {
        PBRVertexConsumer consumer = startedLayers.get(layer);
        if (consumer == null) {
            ByteBufferBuilder buffer = builders.buffer(layer);
            consumer = PBRVertexConsumer.chunk(buffer, layer);
            startedLayers.put(layer, consumer);
        }

        return consumer;
    }

    /** {@code VertexConsumer.putBlockBakedQuad}, plus the albedo emission of tinted quads. */
    private static void putQuad(PBRVertexConsumer consumer, float x, float y, float z,
        BakedQuad quad, QuadInstance instance, BlockAndTintGetter region, BlockColors blockColors,
        BlockState state, BlockPos pos) {
        Vector3fc normal = quad.direction().getUnitVec3f();
        int lightEmission = quad.materialInfo().lightEmission();
        int tintIndex = quad.materialInfo().tintIndex();
        float emission = tintIndex != -1 && state != null
            ? ((IBlockColorsExt) blockColors).radiance$getEmission(state, region, pos, tintIndex)
            : 0.0F;

        for (int vertex = 0; vertex < 4; vertex++) {
            Vector3fc position = quad.position(vertex);
            long packedUv = quad.packedUV(vertex);
            consumer.addVertex(position.x() + x, position.y() + y, position.z() + z,
                instance.getColor(vertex), UVPair.unpackU(packedUv), UVPair.unpackV(packedUv),
                instance.overlayCoords(), instance.getLightCoordsWithEmission(vertex, lightEmission),
                normal.x(), normal.y(), normal.z());
            if (emission != 0.0F) {
                consumer.albedoEmission(emission);
            }
        }
    }
}
