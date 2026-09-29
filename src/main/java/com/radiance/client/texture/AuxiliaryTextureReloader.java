package com.radiance.client.texture;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceReloader;

public class AuxiliaryTextureReloader implements ResourceReloader {

    @Override
    public CompletableFuture<Void> reload(Synchronizer synchronizer, ResourceManager resourceManager,
        Executor prepareExecutor, Executor applyExecutor) {
        return AuxiliaryTextures.decodeImagesAsync(resourceManager, prepareExecutor)
            .thenCompose(synchronizer::whenPrepared)
            .thenAcceptAsync(AuxiliaryTextures::applyDecodedImages, applyExecutor);
    }
}
