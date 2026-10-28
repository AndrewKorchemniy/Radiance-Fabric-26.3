package com.radiance.mixins.mod_support.voxy.source;

import com.radiance.mixin_related.extensions.voxy.IVoxyBiomeUploadExt;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.model.ModelFactory$BiomeUploadResult", remap = false)
public abstract class BiomeUploadResultMixins implements IVoxyBiomeUploadExt {
    @Shadow @Final private MemoryBuffer biomeColourBuffer;
    @Shadow @Final private MemoryBuffer modelBiomeIndexPairs;

    @Shadow public abstract void free();

    @Override public MemoryBuffer radiance$colours() { return biomeColourBuffer; }
    @Override public MemoryBuffer radiance$modelIndices() { return modelBiomeIndexPairs; }
    @Override public void radiance$release() { free(); }
}
