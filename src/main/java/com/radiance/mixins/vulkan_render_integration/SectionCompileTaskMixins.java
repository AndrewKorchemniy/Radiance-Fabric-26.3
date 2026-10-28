package com.radiance.mixins.vulkan_render_integration;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.radiance.client.proxy.world.ChunkProxy;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Routes compiled sections to native instead of vanilla's GPU buffers. {@code SectionBuilderMixins}
 * produces PBR layers; they are taken out of the results before vanilla sees them, so vanilla goes
 * down its empty-section path: no upload, and the mesh is published at once (no section stays
 * {@code UNCOMPILED} waiting for upload callbacks).
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public abstract class SectionCompileTaskMixins {

    // CompileTask is an inner class of RenderSection; javac keeps the owner in this field.
    @Shadow
    @Final
    SectionRenderDispatcher.RenderSection this$1;

    @WrapOperation(method = "doTask(Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection$SectionTask$SectionTaskResult;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionCompiler;compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;"))
    private SectionCompiler.Results sendSectionToNative(SectionCompiler compiler,
        SectionPos sectionPos, RenderSectionRegion region, VertexSorting vertexSorting,
        SectionBufferBuilderPack buffers, Operation<SectionCompiler.Results> original,
        @Local Vec3 cameraPos) {
        SectionCompiler.Results results = original.call(compiler, sectionPos, region, vertexSorting,
            buffers);
        ChunkProxy.onSectionCompiled(this$1, sectionPos, region, results, buffers,
            ((SectionRenderDispatcher.RenderSection.SectionTask) (Object) this).isCancelled,
            cameraPos);
        return results;
    }

    /**
     * Vanilla publishes empty sections as {@code EMPTY}, which every face can be seen through. All
     * sections take that path now, so publish the compiled mesh instead and keep its visibility set
     * for vanilla's occlusion culling. A cancelled task (the section moved) publishes nothing; the
     * caller releases whatever this returns.
     */
    @WrapOperation(method = "doTask(Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection$SectionTask$SectionTaskResult;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;setSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)Lnet/minecraft/client/renderer/chunk/SectionMesh;"))
    private SectionMesh keepVisibilitySet(SectionRenderDispatcher.RenderSection section,
        SectionMesh sectionMesh, Operation<SectionMesh> original,
        @Local CompiledSectionMesh compiledSectionMesh) {
        if (((SectionRenderDispatcher.RenderSection.SectionTask) (Object) this).isCancelled.get()) {
            return compiledSectionMesh;
        }
        return original.call(section, compiledSectionMesh);
    }
}
