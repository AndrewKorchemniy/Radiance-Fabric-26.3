package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.radiance.client.vertex.PBRVertexConsumer;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla gives every fluid vertex an up normal. The ray tracer needs the real one, so faces written
 * into a PBR consumer get the normal of their quad. On 1.21.4 Radiance rewrote the whole tesselate
 * method for this; 26.3 funnels every fluid face through {@code addFace}, which is enough.
 */
@Mixin(FluidRenderer.class)
public class FluidRendererMixins {

    @Inject(method = "addFace(Lcom/mojang/blaze3d/vertex/VertexConsumer;FFFFFFFFFFFFFFFFFFFFIIZ)V",
        at = @At(value = "HEAD"),
        cancellable = true)
    private void addNormalToFace(VertexConsumer builder,
        float x0, float y0, float z0, float u0, float v0,
        float x1, float y1, float z1, float u1, float v1,
        float x2, float y2, float z2, float u2, float v2,
        float x3, float y3, float z3, float u3, float v3,
        int color, int lightCoords, boolean addBackFace, CallbackInfo ci) {
        if (!(builder instanceof PBRVertexConsumer)) {
            return;
        }

        // Normal from the quad's diagonals; vertices are wound counter-clockwise seen from the front.
        float ax = x2 - x0;
        float ay = y2 - y0;
        float az = z2 - z0;
        float bx = x3 - x1;
        float by = y3 - y1;
        float bz = z3 - z1;
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        if (ny > 0.0F) {
            // 1.21.4 tilted sloped water surfaces twice as far as their geometry; keep that look.
            ny *= 0.5F;
        }
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length <= 1.0E-6F) {
            nx = 0.0F;
            ny = 1.0F;
            nz = 0.0F;
        } else {
            nx /= length;
            ny /= length;
            nz /= length;
        }

        radiance$vertex(builder, x0, y0, z0, color, u0, v0, lightCoords, nx, ny, nz);
        radiance$vertex(builder, x1, y1, z1, color, u1, v1, lightCoords, nx, ny, nz);
        radiance$vertex(builder, x2, y2, z2, color, u2, v2, lightCoords, nx, ny, nz);
        radiance$vertex(builder, x3, y3, z3, color, u3, v3, lightCoords, nx, ny, nz);
        if (addBackFace) {
            // As on 1.21.4, the back of the surface faces down while the backs of the sides keep
            // the outward normal.
            float sign = ny > 0.0F ? -1.0F : 1.0F;
            float bnx = nx * sign;
            float bny = ny * sign;
            float bnz = nz * sign;
            radiance$vertex(builder, x0, y0, z0, color, u0, v0, lightCoords, bnx, bny, bnz);
            radiance$vertex(builder, x3, y3, z3, color, u3, v3, lightCoords, bnx, bny, bnz);
            radiance$vertex(builder, x2, y2, z2, color, u2, v2, lightCoords, bnx, bny, bnz);
            radiance$vertex(builder, x1, y1, z1, color, u1, v1, lightCoords, bnx, bny, bnz);
        }

        ci.cancel();
    }

    @Unique
    private static void radiance$vertex(VertexConsumer builder, float x, float y, float z, int color,
        float u, float v, int lightCoords, float nx, float ny, float nz) {
        builder.addVertex(x, y, z, color, u, v, OverlayTexture.NO_OVERLAY, lightCoords, nx, ny, nz);
    }
}
