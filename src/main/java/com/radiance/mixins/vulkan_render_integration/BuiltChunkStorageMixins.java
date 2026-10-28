package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.proxy.world.ChunkProxy;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ViewArea.class)
public class BuiltChunkStorageMixins {

    @Inject(method = "releaseAllBuffers()V", at = @At(value = "HEAD"))
    public void clearChunkProxy(CallbackInfo ci) {
        ChunkProxy.clear();
    }

    /**
     * Sizes native's section grid before the storage creates (and places) its sections. The
     * storage indexes sections as {@code (z * sizeY + y) * sizeXZ + x}, the 1.21.4 layout.
     */
    @ModifyArg(method = "<init>(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;IIIIILnet/minecraft/client/renderer/SectionOcclusionGraph;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/RotatingSectionStorage;<init>(IIILnet/minecraft/client/RotatingSectionStorage$ValueCreator;)V"),
        index = 0)
    private int initChunkRebuildGrid(int radius, int minSectionY, int maxSectionY,
        RotatingSectionStorage.ValueCreator<?> valueCreator) {
        int sizeXZ = radius * 2 + 1;
        int sizeY = maxSectionY - minSectionY + 1;
        ChunkProxy.init(sizeXZ * sizeXZ * sizeY, sizeXZ, sizeY, sizeXZ, minSectionY);
        return radius;
    }

    @Inject(method = "<init>(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;IIIIILnet/minecraft/client/renderer/SectionOcclusionGraph;)V",
        at = @At(value = "RETURN"))
    private void setChunkProxyStorage(CallbackInfo ci) {
        ChunkProxy.setStorage((ViewArea) (Object) this);
    }

    @Inject(method = "repositionCamera(Lnet/minecraft/core/SectionPos;)Z",
        at = @At(value = "HEAD"))
    private void updateChunkStorageSectionPos(SectionPos sectionPos,
        CallbackInfoReturnable<Boolean> cir) {
        ChunkProxy.updateSectionPos(sectionPos);
    }
}
