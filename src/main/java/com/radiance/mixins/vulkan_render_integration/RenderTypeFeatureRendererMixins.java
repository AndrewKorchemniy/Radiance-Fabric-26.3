package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.radiance.client.vertex.SubmitCapture;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sends the vertices of Radiance's own feature renderers (see {@link SubmitCapture}) to Radiance
 * storage instead of vanilla's shared staging buffer. Vanilla's renderers are left alone.
 */
@Mixin(RenderTypeFeatureRenderer.class)
public abstract class RenderTypeFeatureRendererMixins {

    @Inject(method = "getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
        at = @At("HEAD"), cancellable = true)
    private void radiance$captureVertices(RenderType renderType,
        CallbackInfoReturnable<VertexConsumer> cir) {
        VertexConsumer captured = SubmitCapture.redirect(this, renderType);
        if (captured != null) {
            cir.setReturnValue(captured);
        }
    }
}
