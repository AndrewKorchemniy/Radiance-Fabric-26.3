package com.radiance.client.texture;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.packs.resources.PreparableReloadListener;

public class AuxiliaryTextureReloader implements PreparableReloadListener {

    @Override
    public CompletableFuture<Void> reload(SharedState currentReload, Executor prepareExecutor,
        PreparationBarrier synchronizer, Executor applyExecutor) {
        return AuxiliaryTextures.decodeImagesAsync(currentReload.resourceManager(), prepareExecutor)
            .thenCompose(synchronizer::wait)
            .thenAcceptAsync(AuxiliaryTextures::applyDecodedImages, applyExecutor);
    }
}
