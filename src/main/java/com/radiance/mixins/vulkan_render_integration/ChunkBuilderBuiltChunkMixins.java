package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.proxy.world.ChunkProxy;
import com.radiance.client.proxy.world.BlockEntitySectionCache;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IChunkBuilderBuiltChunkExt;
import java.util.stream.Collector;
import java.util.stream.Stream;
import net.minecraft.client.render.chunk.ChunkBuilder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkBuilder.BuiltChunk.class)
public class ChunkBuilderBuiltChunkMixins implements IChunkBuilderBuiltChunkExt {

    @Shadow
    @Final
    ChunkBuilder field_20833;

    @Unique
    public ChunkBuilder radiance$getChunkBuilder() {
        return field_20833;
    }

    @Redirect(method = "<init>",
        at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;collect(Ljava/util/stream/Collector;)Ljava/lang/Object;"))
    private Object cancelCollect(Stream<?> stream, Collector<?, ?, ?> collector) {
        return null;
    }

    @Inject(method = "clear()V", at = @At(value = "TAIL"))
    private void addToRebuildGridClear(CallbackInfo ci) {
        ChunkBuilder.BuiltChunk self = (ChunkBuilder.BuiltChunk) (Object) this;
        BlockEntitySectionCache.changed(self);
        ChunkProxy.enqueueRebuild(self);
    }

    @Inject(method = "setData(Lnet/minecraft/client/render/chunk/ChunkBuilder$ChunkData;)V",
        at = @At("TAIL"))
    private void updateBlockEntitySections(ChunkBuilder.ChunkData data, CallbackInfo ci) {
        BlockEntitySectionCache.changed((ChunkBuilder.BuiltChunk) (Object) this);
    }

    @Inject(method = "scheduleRebuild(Z)V", at = @At(value = "TAIL"))
    private void addToRebuildGridScheduleRebuild(CallbackInfo ci) {
        ChunkBuilder.BuiltChunk self = (ChunkBuilder.BuiltChunk) (Object) this;
        ChunkProxy.enqueueRebuild(self);
    }

    @Inject(method = "setSectionPos(J)V", at = @At(value = "HEAD"))
    private void beginNativeChunkRelocation(long sectionPos, CallbackInfo ci) {
        ChunkBuilder.BuiltChunk self = (ChunkBuilder.BuiltChunk) (Object) this;
        ChunkProxy.beginRelocation(self, net.minecraft.util.math.ChunkSectionPos.unpackX(sectionPos) << 4,
            net.minecraft.util.math.ChunkSectionPos.unpackY(sectionPos) << 4,
            net.minecraft.util.math.ChunkSectionPos.unpackZ(sectionPos) << 4);
    }

    @Inject(method = "setSectionPos(J)V", at = @At(value = "TAIL"))
    private void finishNativeChunkRelocation(long sectionPos, CallbackInfo ci) {
        ChunkProxy.finishRelocation((ChunkBuilder.BuiltChunk) (Object) this);
    }

    @Inject(method = "delete()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;clear()V",
            shift = At.Shift.AFTER),
        cancellable = true)
    public void cancelVertexConsumerDelete(CallbackInfo ci) {
        ci.cancel();
    }
}
