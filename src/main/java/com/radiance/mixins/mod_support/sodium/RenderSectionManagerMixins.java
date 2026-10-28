package com.radiance.mixins.mod_support.sodium;

import com.radiance.client.proxy.world.ChunkProxy;
import com.radiance.mixin_related.extensions.mod_support.sodium.IRenderSectionManagerExt;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium's section lifecycle, as far as native needs it. Sections that get a build task are
 * captured on the worker ({@code ChunkBuilderMeshingTaskMixins}); this covers the ones that never
 * do (nothing but air) and the ones that go away.
 */
@Mixin(RenderSectionManager.class)
public abstract class RenderSectionManagerMixins implements IRenderSectionManagerExt {

    @Shadow
    @Final
    private SectionStorage renderSections;

    @Shadow
    @Final
    private ClientLevel level;

    @Shadow
    public abstract void scheduleRebuild(int x, int y, int z, boolean playerChanged);

    @Inject(method = "onSectionAdded(III)V", at = @At("RETURN"))
    private void radiance$sectionAdded(int x, int y, int z, CallbackInfo ci) {
        if (this.radiance$hasOnlyAir(x, y, z)) {
            ChunkProxy.sodiumInvalidate(x, y, z);
        }
    }

    @Inject(method = "onSectionRemoved(III)V", at = @At("RETURN"))
    private void radiance$sectionRemoved(int x, int y, int z, CallbackInfo ci) {
        ChunkProxy.sodiumRelease(x, y, z);
    }

    /** A rebuild without a task: the section lost all its blocks (or was never anything but air). */
    @Inject(method = "createRebuildTask(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;IZJ)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/tasks/ChunkBuilderMeshingTask;",
        at = @At("RETURN"))
    private void radiance$emptyRebuild(RenderSection render, int frame, boolean blocking,
        long chunkCaptureTime, CallbackInfoReturnable<ChunkBuilderMeshingTask> cir) {
        if (cir.getReturnValue() == null) {
            ChunkProxy.sodiumInvalidate(render.getChunkX(), render.getChunkY(), render.getChunkZ());
        }
    }

    @Override
    public boolean radiance$resync(int x, int y, int z, boolean waitForBuild) {
        RenderSection section = this.renderSections.getConsistent(SectionPos.asLong(x, y, z));
        if (section == null) {
            // Chunk not loaded: its sections report in when Sodium adds them.
            return true;
        }
        if (this.radiance$hasOnlyAir(x, y, z)) {
            ChunkProxy.sodiumInvalidate(x, y, z);
            return true;
        }
        if (section.isBuilt()) {
            this.scheduleRebuild(x, y, z, false);
            return true;
        }
        // Not built yet: the first build, if it has not run, is captured as usual.
        return !waitForBuild;
    }

    @Unique
    private boolean radiance$hasOnlyAir(int x, int y, int z) {
        ChunkAccess chunk = this.level.getChunk(x, z);
        LevelChunkSection section = chunk.getSections()[this.level.getSectionIndexFromSectionY(y)];
        return section == null || section.hasOnlyAir();
    }
}
