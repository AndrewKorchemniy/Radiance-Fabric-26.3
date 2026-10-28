package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Skips vanilla's raster world passes (sky and main pass) while keeping the rest of
 * {@code LevelRenderer.render}: feature submission and preparation (where entity geometry is
 * captured), section compilation and upload, occlusion updates and the callback that ends the
 * loading screen all run after or around the frame graph and must keep running. Cancelling
 * {@code render} itself would leave the game on the loading screen.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererFrameMixins {

    @Redirect(method = "render",
        at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"))
    private void radiance$skipRasterWorldPasses(FrameGraphBuilder frame,
        GraphicsResourceAllocator allocator, FrameGraphBuilder.Inspector inspector) {
    }
}
