package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.entity.LightningBoltRenderer;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives each lightning quad texture coordinates, so the ray tracer can sample Radiance's lightning
 * texture (swapped in by {@code StorageVertexConsumerProvider}). Vanilla's untextured format
 * ignores them.
 */
@Mixin(LightningBoltRenderer.class)
public class LightningEntityRendererMixins {

    @Inject(method = "quad(Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/vertex/VertexConsumer;FFFFIFFZZZZ)V",
        at = @At(value = "HEAD"),
        cancellable = true)
    private static void redirectDrawBranch(Matrix4fc pose,
        VertexConsumer buffer,
        float segmentStartX,
        float segmentStartZ,
        float segmentEndX,
        float segmentEndZ,
        int currentSegment,
        float topRadius,
        float bottomRadius,
        boolean rightXPositive,
        boolean rightZPositive,
        boolean leftXPositive,
        boolean leftZPositive,
        CallbackInfo ci) {
        buffer.addVertex(pose, segmentStartX + (rightXPositive ? bottomRadius : -bottomRadius),
                (float) (currentSegment * 16),
                segmentStartZ + (rightZPositive ? bottomRadius : -bottomRadius))
            .setColor(0.45F, 0.45F, 0.5F, 0.3F)
            .setUv(0.0F, 0.0F);

        buffer.addVertex(pose, segmentEndX + (rightXPositive ? topRadius : -topRadius),
                (float) ((currentSegment + 1) * 16),
                segmentEndZ + (rightZPositive ? topRadius : -topRadius))
            .setColor(0.45F, 0.45F, 0.5F, 0.3F)
            .setUv(1.0F, 0.0F);

        buffer.addVertex(pose, segmentEndX + (leftXPositive ? topRadius : -topRadius),
                (float) ((currentSegment + 1) * 16),
                segmentEndZ + (leftZPositive ? topRadius : -topRadius))
            .setColor(0.45F, 0.45F, 0.5F, 0.3F)
            .setUv(1.0F, 1.0F);

        buffer.addVertex(pose, segmentStartX + (leftXPositive ? bottomRadius : -bottomRadius),
                (float) (currentSegment * 16),
                segmentStartZ + (leftZPositive ? bottomRadius : -bottomRadius))
            .setColor(0.45F, 0.45F, 0.5F, 0.3F)
            .setUv(0.0F, 1.0F);

        ci.cancel();
    }
}
