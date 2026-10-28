package com.radiance.mixins.mod_support.voxy.source;

import com.radiance.mixin_related.extensions.voxy.IVoxyModelUploadExt;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.model.ModelFactory$ModelBakeResultUpload", remap = false)
public abstract class ModelBakeResultUploadMixins implements IVoxyModelUploadExt {
    @Shadow @Final private MemoryBuffer model;
    @Shadow @Final private MemoryBuffer texture;
    @Shadow public int modelId;
    @Shadow public int biomeUploadIndex;
    @Shadow public MemoryBuffer biomeUpload;

    @Shadow public abstract void free();

    @Override public int radiance$modelId() { return modelId; }
    @Override public MemoryBuffer radiance$model() { return model; }
    @Override public MemoryBuffer radiance$texture() { return texture; }
    @Override public int radiance$biomeColourIndex() { return biomeUploadIndex; }
    @Override public MemoryBuffer radiance$biomeColours() { return biomeUpload; }
    @Override public void radiance$release() { free(); }
}
