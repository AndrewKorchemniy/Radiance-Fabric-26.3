package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.radiance.client.proxy.world.TerrainMesher;
import java.util.Map;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Meshes sections straight into Radiance's PBR vertex format (see {@link TerrainMesher}). The
 * returned layers are not vanilla {@code BLOCK} meshes: {@code SectionCompileTaskMixins} hands them
 * to native before vanilla would upload them. Not applied with Sodium, which replaces vanilla's
 * section builder (see {@code ChunkBuilderMeshingTaskMixins}).
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
        VisGraph visGraph = new VisGraph();
        try {
            Map<ChunkSectionLayer, MeshData> layers = TerrainMesher.mesh(sectionPos, region,
                builders, this.blockModelSet, this.fluidModelSet, this.blockColors,
                this.cutoutLeaves, visGraph,
                blockEntity -> this.handleBlockEntity(results, blockEntity));
            results.renderedLayers.putAll(layers);
        } catch (RuntimeException | Error e) {
            results.release();
            throw e;
        }

        results.visibilitySet = visGraph.resolve();
        cir.setReturnValue(results);
    }
}
