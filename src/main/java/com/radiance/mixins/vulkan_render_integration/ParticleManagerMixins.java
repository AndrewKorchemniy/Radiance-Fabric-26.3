package com.radiance.mixins.vulkan_render_integration;

import com.llamalad7.mixinextras.sugar.Local;
import com.radiance.client.proxy.world.EntityProxy;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IParticleExt;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleEngine.class)
public class ParticleManagerMixins {

    /** Names each particle after its type, so native can treat particle types differently. */
    @Inject(method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/particle/ParticleEngine;add(Lnet/minecraft/client/particle/Particle;)V",
            shift = At.Shift.BEFORE))
    public void checkParticleCounter(ParticleOptions parameters,
        double x,
        double y,
        double z,
        double velocityX,
        double velocityY,
        double velocityZ,
        CallbackInfoReturnable<Particle> cir,
        @Local Particle particle) {
        Identifier particleId = BuiltInRegistries.PARTICLE_TYPE.getKey(parameters.getType());
        if (particleId != null) {
            ((IParticleExt) particle).radiance$setContentName(
                EntityContentNames.toParticleContentName(particleId));
        }
    }

    /** While vanilla extracts, quad particles are routed into per-content groups. */
    @Inject(method = "extract(Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)V",
        at = @At("HEAD"))
    private void radiance$beginExtract(ParticlesRenderState particlesRenderState, Frustum frustum,
        Camera camera, float partialTickTime, CallbackInfo ci) {
        EntityProxy.beginParticleExtract();
    }

    @Inject(method = "extract(Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)V",
        at = @At("RETURN"))
    private void radiance$endExtract(ParticlesRenderState particlesRenderState, Frustum frustum,
        Camera camera, float partialTickTime, CallbackInfo ci) {
        EntityProxy.endParticleExtract();
    }

    private static final class EntityContentNames {

        private static String toParticleContentName(Identifier particleId) {
            if ("minecraft".equals(particleId.getNamespace())) {
                return "/particle/" + particleId.getPath();
            }
            return "/particle/" + particleId.getNamespace() + "/" + particleId.getPath();
        }
    }
}
