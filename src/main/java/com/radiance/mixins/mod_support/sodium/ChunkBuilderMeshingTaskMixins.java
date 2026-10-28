package com.radiance.mixins.mod_support.sodium;

import com.radiance.client.mod_support.sodium.SodiumTerrain;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium's section mesh task. When it is done with a section its block snapshot is still alive on
 * the worker, so Radiance meshes the same snapshot into PBR vertices and sends them to native.
 */
@Mixin(ChunkBuilderMeshingTask.class)
public abstract class ChunkBuilderMeshingTaskMixins {

    @Inject(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
        at = @At("RETURN"))
    private void radiance$captureSection(ChunkBuildContext buildContext,
        CancellationToken cancellationToken, CallbackInfoReturnable<ChunkBuildOutput> cir) {
        // A cancelled task returns null.
        ChunkBuildOutput output = cir.getReturnValue();
        if (output != null && !cancellationToken.isCancelled()) {
            SodiumTerrain.onSectionMeshed(buildContext, output, cancellationToken);
        }
    }
}
