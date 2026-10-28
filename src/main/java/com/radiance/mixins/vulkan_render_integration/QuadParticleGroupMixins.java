package com.radiance.mixins.vulkan_render_integration;

import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Particles off screen still show up in reflections, so none are culled (1.21.4 culled none). */
@Mixin(QuadParticleGroup.class)
public class QuadParticleGroupMixins {

    @Redirect(method = "extractRenderState(Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)Lnet/minecraft/client/renderer/state/level/ParticleGroupRenderState;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"))
    private boolean radiance$keepOffscreenParticles(Frustum frustum, double x, double y, double z) {
        return true;
    }
}
