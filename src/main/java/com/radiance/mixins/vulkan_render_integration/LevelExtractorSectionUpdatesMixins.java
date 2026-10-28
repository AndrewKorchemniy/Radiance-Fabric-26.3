package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.proxy.world.ChunkProxy;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla only queues dirty sections that are visible. The ray tracer also sees what is behind the
 * camera or behind walls, so right after vanilla's section updates, queue dirty off-screen ones too
 * (1.21.4 rebuilt every dirty section).
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorSectionUpdatesMixins {

    @Shadow
    @Final
    private LevelRenderer levelRenderer;

    @Shadow
    private @Nullable ClientLevel level;

    @Shadow
    private @Nullable SectionUpdateTracker sectionUpdateTracker;

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"))
    private void queueOffscreenSectionUpdates(DeltaTracker deltaTracker, Camera camera,
        float worldPartialTicks, CallbackInfo ci) {
        if (this.sectionUpdateTracker != null && this.level != null) {
            ChunkProxy.collectOffscreenSectionUpdates(this.level, this.sectionUpdateTracker,
                this.levelRenderer, camera.position(), this.levelRenderState.sectionUpdateRenderStates);
        }
    }

    /** Radiance sections never upload layers to vanilla, so count them from ChunkProxy instead. */
    @Inject(method = "countRenderedSections()I", at = @At(value = "HEAD"), cancellable = true)
    private void countRadianceSections(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ChunkProxy.countRenderedSections(this.levelRenderer.visibleSections()));
    }
}
