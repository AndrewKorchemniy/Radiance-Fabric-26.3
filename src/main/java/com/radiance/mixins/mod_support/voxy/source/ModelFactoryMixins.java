package com.radiance.mixins.mod_support.voxy.source;

import com.radiance.mixin_related.extensions.voxy.IVoxyModelFactoryExt;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Voxy bakes block models on the CPU but uploads the results to OpenGL buffers. Radiance never
 * calls {@code processUploads}; it reads the finished bakes straight off the queue instead.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.model.ModelFactory", remap = false)
public class ModelFactoryMixins implements IVoxyModelFactoryExt {
    @Shadow @Final private ConcurrentLinkedDeque<Object> uploadResults;

    @Override
    public Object radiance$pollUpload() { return uploadResults.poll(); }
}
