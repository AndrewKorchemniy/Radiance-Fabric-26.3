package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.proxy.world.ChunkProxy;
import net.minecraft.client.SectionUpdateTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells {@link ChunkProxy} which sections went dirty, so it can rebuild the off-screen ones without
 * scanning the whole tracker every frame. Covers new trackers (every entry starts dirty), block
 * changes, and entries moved to a new position as the camera moves.
 */
@Mixin(SectionUpdateTracker.SectionDirtyState.class)
public class SectionDirtyStateMixins {

    @Shadow
    private boolean isDirty;

    @Shadow
    private long sectionNode;

    @Inject(method = "<init>(ZZJ)V", at = @At("RETURN"))
    private void notifyChunkProxyOnCreate(CallbackInfo ci) {
        if (this.isDirty) {
            ChunkProxy.markSectionDirty(this.sectionNode);
        }
    }

    @Inject(method = {"setDirty(Z)V", "setSectionNode(J)V"}, at = @At("RETURN"))
    private void notifyChunkProxy(CallbackInfo ci) {
        if (this.isDirty) {
            ChunkProxy.markSectionDirty(this.sectionNode);
        }
    }
}
