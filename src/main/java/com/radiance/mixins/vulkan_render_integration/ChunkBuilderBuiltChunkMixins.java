package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.proxy.world.BlockEntitySectionCache;
import com.radiance.client.proxy.world.ChunkProxy;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public class ChunkBuilderBuiltChunkMixins {

    @Inject(method = "reset()V", at = @At(value = "TAIL"))
    private void updateBlockEntitySectionsOnReset(CallbackInfo ci) {
        BlockEntitySectionCache.changed((SectionRenderDispatcher.RenderSection) (Object) this);
    }

    @Inject(method = "setSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)Lnet/minecraft/client/renderer/chunk/SectionMesh;",
        at = @At("TAIL"))
    private void updateBlockEntitySections(SectionMesh sectionMesh,
        CallbackInfoReturnable<SectionMesh> cir) {
        BlockEntitySectionCache.changed((SectionRenderDispatcher.RenderSection) (Object) this);
    }

    // The storage moves sections around as the camera moves (and places them once from the
    // constructor). Native drops the old contents under a new generation; compiles of the old
    // position still in flight are discarded by ChunkProxy.
    @Inject(method = "setSectionNode(J)V", at = @At(value = "HEAD"))
    private void beginNativeChunkRelocation(long sectionNode, CallbackInfo ci) {
        SectionRenderDispatcher.RenderSection self = (SectionRenderDispatcher.RenderSection) (Object) this;
        ChunkProxy.beginRelocation(self, SectionPos.sectionToBlockCoord(SectionPos.x(sectionNode)),
            SectionPos.sectionToBlockCoord(SectionPos.y(sectionNode)),
            SectionPos.sectionToBlockCoord(SectionPos.z(sectionNode)));
    }

    @Inject(method = "setSectionNode(J)V", at = @At(value = "TAIL"))
    private void finishNativeChunkRelocation(long sectionNode, CallbackInfo ci) {
        ChunkProxy.finishRelocation((SectionRenderDispatcher.RenderSection) (Object) this);
    }
}
