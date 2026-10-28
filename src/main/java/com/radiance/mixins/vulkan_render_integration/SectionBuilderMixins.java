package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.radiance.client.vertex.PBRVertexConsumer;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IBlockColorsExt;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Meshes sections straight into Radiance's PBR vertex format. The returned layers are not vanilla
 * {@code BLOCK} meshes: {@code SectionCompileTaskMixins} hands them to native before vanilla would upload
 * them. Like 1.21.4, vanilla ambient occlusion is off; the ray tracer computes its own.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionBuilderMixins {

    @Final
    @Shadow
    private boolean cutoutLeaves;

    @Final
    @Shadow
    private BlockStateModelSet blockModelSet;

    @Final
    @Shadow
    private FluidStateModelSet fluidModelSet;

    @Final
    @Shadow
    private BlockColors blockColors;

    @Shadow
    private <E extends BlockEntity> void handleBlockEntity(SectionCompiler.Results results,
        E blockEntity) {
        throw new AssertionError();
    }

    @Inject(method =
        "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
        at = @At(value = "HEAD"), cancellable = true)
    public void redirectBuild(SectionPos sectionPos,
        RenderSectionRegion region,
        VertexSorting vertexSorting,
        SectionBufferBuilderPack builders,
        CallbackInfoReturnable<SectionCompiler.Results> cir) {
        SectionCompiler.Results results = new SectionCompiler.Results();
        BlockPos minPos = sectionPos.origin();
        BlockPos maxPos = minPos.offset(15, 15, 15);
        VisGraph visGraph = new VisGraph();
        BlockModelLighter.enableCaching();
        ModelBlockRenderer blockRenderer = new ModelBlockRenderer(false, true, this.blockColors);
        FluidRenderer fluidRenderer = new FluidRenderer(this.fluidModelSet);
        Map<ChunkSectionLayer, PBRVertexConsumer> startedLayers = new EnumMap<>(ChunkSectionLayer.class);
        // The quad outputs only get positions; tint emission also needs the block being meshed.
        BlockState[] currentState = new BlockState[1];
        BlockPos[] currentPos = new BlockPos[1];
        BlockQuadOutput quadOutput = (x, y, z, quad, instance) -> this.radiance$putQuad(
            this.radiance$getOrBeginLayer(startedLayers, builders, quad.materialInfo().layer()),
            x, y, z, quad, instance, region, currentState[0], currentPos[0]);
        BlockQuadOutput opaqueQuadOutput = (x, y, z, quad, instance) -> this.radiance$putQuad(
            this.radiance$getOrBeginLayer(startedLayers, builders, ChunkSectionLayer.SOLID),
            x, y, z, quad, instance, region, currentState[0], currentPos[0]);
        FluidRenderer.Output fluidOutput = layer -> this.radiance$getOrBeginLayer(startedLayers,
            builders, layer);

        try {
            for (BlockPos pos : BlockPos.betweenClosed(minPos, maxPos)) {
                BlockState blockState = region.getBlockState(pos);
                if (blockState.isAir()) {
                    continue;
                }

                try {
                    if (blockState.isSolidRender()) {
                        visGraph.setOpaque(pos);
                    }

                    if (blockState.hasBlockEntity()) {
                        BlockEntity blockEntity = region.getBlockEntity(pos);
                        if (blockEntity != null) {
                            this.handleBlockEntity(results, blockEntity);
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
                            ModelBlockRenderer.forceOpaque(this.cutoutLeaves, blockState)
                                ? opaqueQuadOutput : quadOutput,
                            SectionPos.sectionRelative(pos.getX()),
                            SectionPos.sectionRelative(pos.getY()),
                            SectionPos.sectionRelative(pos.getZ()),
                            region,
                            pos,
                            blockState,
                            this.blockModelSet.get(blockState),
                            blockState.getSeed(pos));
                    }
                } catch (Throwable t) {
                    CrashReport report = CrashReport.forThrowable(t, "Tesselating block in world");
                    CrashReportCategory category = report.addCategory("Block being tesselated");
                    CrashReportCategory.populateBlockDetails(category, region, pos, blockState);
                    throw new ReportedException(report);
                }
            }

            // 1.21.4 sent terrain unsorted (no index buffer), so translucent quads are not sorted here.
            for (Map.Entry<ChunkSectionLayer, PBRVertexConsumer> entry : startedLayers.entrySet()) {
                MeshData mesh = entry.getValue().endNullable();
                if (mesh != null) {
                    results.renderedLayers.put(entry.getKey(), mesh);
                }
            }
        } catch (RuntimeException | Error e) {
            results.release();
            throw e;
        } finally {
            BlockModelLighter.clearCache();
        }

        results.visibilitySet = visGraph.resolve();
        cir.setReturnValue(results);
    }

    @Unique
    private PBRVertexConsumer radiance$getOrBeginLayer(
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
    @Unique
    private void radiance$putQuad(PBRVertexConsumer consumer, float x, float y, float z,
        BakedQuad quad, QuadInstance instance, RenderSectionRegion region, BlockState state,
        BlockPos pos) {
        Vector3fc normal = quad.direction().getUnitVec3f();
        int lightEmission = quad.materialInfo().lightEmission();
        int tintIndex = quad.materialInfo().tintIndex();
        float emission = tintIndex != -1 && state != null
            ? ((IBlockColorsExt) this.blockColors).radiance$getEmission(state, region, pos, tintIndex)
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
