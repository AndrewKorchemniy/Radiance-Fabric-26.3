package com.radiance.mixins.vulkan_render_integration;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.radiance.client.texture.TextureTracker;
import com.radiance.mixin_related.extensions.vulkan_render_integration.ILightMapManagerExt;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * On 1.21.4 Radiance recomputed the lightmap on the CPU to learn its inputs. 26.3 renders it on
 * the GPU from a render state, so the inputs are read from that state and the texture itself is
 * vanilla's, sampled through the texture tracker.
 */
@Mixin(Lightmap.class)
public abstract class LightmapTextureManagerMixins implements ILightMapManagerExt {

    @Final
    @Shadow
    private GpuTexture texture;

    @Unique
    private float radiance$skyFactor = 0;
    @Unique
    private float radiance$blockFactor = 0;
    @Unique
    private Vector3fc radiance$blockLightTint = LightmapRenderStateExtractor.WHITE;
    @Unique
    private Vector3fc radiance$skyLightColor = LightmapRenderStateExtractor.WHITE;
    @Unique
    private Vector3fc radiance$ambientColor = LightmapRenderStateExtractor.WHITE;
    @Unique
    private float radiance$nightVisionFactor = 0;
    @Unique
    private Vector3fc radiance$nightVisionColor = LightmapRenderStateExtractor.WHITE;
    @Unique
    private float radiance$darknessScale = 0;
    @Unique
    private float radiance$darkenWorldFactor = 0;
    @Unique
    private float radiance$brightnessFactor = 0;

    @Inject(method = "render(Lnet/minecraft/client/renderer/state/LightmapRenderState;)V",
        at = @At("HEAD"))
    private void radiance$captureInputs(LightmapRenderState renderState, CallbackInfo ci) {
        if (!renderState.needsUpdate) {
            return;
        }
        this.radiance$skyFactor = renderState.skyFactor;
        this.radiance$blockFactor = renderState.blockFactor;
        this.radiance$blockLightTint = renderState.blockLightTint;
        this.radiance$skyLightColor = renderState.skyLightColor;
        this.radiance$ambientColor = renderState.ambientColor;
        this.radiance$nightVisionFactor = renderState.nightVisionEffectIntensity;
        this.radiance$nightVisionColor = renderState.nightVisionColor;
        this.radiance$darknessScale = renderState.darknessEffectScale;
        this.radiance$darkenWorldFactor = renderState.bossOverlayWorldDarkening;
        this.radiance$brightnessFactor = renderState.brightness;
    }

    @Override
    public int radiance$getTextureId() {
        return Math.max(TextureTracker.idOf(this.texture), 0);
    }

    @Override
    public float radiance$getSkyFactor() {
        return radiance$skyFactor;
    }

    @Override
    public float radiance$getBlockFactor() {
        return radiance$blockFactor;
    }

    @Override
    public Vector3fc radiance$getBlockLightTint() {
        return radiance$blockLightTint;
    }

    @Override
    public Vector3fc radiance$getSkyLightColor() {
        return radiance$skyLightColor;
    }

    @Override
    public Vector3fc radiance$getAmbientColor() {
        return radiance$ambientColor;
    }

    @Override
    public float radiance$getNightVisionFactor() {
        return radiance$nightVisionFactor;
    }

    @Override
    public Vector3fc radiance$getNightVisionColor() {
        return radiance$nightVisionColor;
    }

    @Override
    public float radiance$getDarknessScale() {
        return radiance$darknessScale;
    }

    @Override
    public float radiance$getDarkenWorldFactor() {
        return radiance$darkenWorldFactor;
    }

    @Override
    public float radiance$getBrightnessFactor() {
        return radiance$brightnessFactor;
    }
}
