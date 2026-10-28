package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.proxy.world.EntityProxy;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IParticleExt;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.WhiteAshParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SingleQuadParticle.class)
public abstract class BillboardParticleMixins {

    /**
     * Extracts each quad particle into the group of its content name (see
     * {@code ParticleManagerMixins}), and shrinks white ash to an eighth, as 1.21.4 did.
     */
    @Redirect(method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/state/level/QuadParticleRenderState;add(Lnet/minecraft/client/particle/SingleQuadParticle$Layer;FFFFFFFFFFFFII)V"))
    private void resizeParticle(QuadParticleRenderState state, SingleQuadParticle.Layer layer,
        float x, float y, float z, float xRot, float yRot, float zRot, float wRot, float scale,
        float u0, float u1, float v0, float v1, int color, int lightCoords) {
        if ((Object) this instanceof WhiteAshParticle) {
            scale /= 8.0F;
        }
        QuadParticleRenderState target = EntityProxy.particleRenderState(
            ((IParticleExt) this).radiance$getContentName());
        (target != null ? target : state).add(layer, x, y, z, xRot, yRot, zRot, wRot, scale, u0,
            u1, v0, v1, color, lightCoords);
    }
}
