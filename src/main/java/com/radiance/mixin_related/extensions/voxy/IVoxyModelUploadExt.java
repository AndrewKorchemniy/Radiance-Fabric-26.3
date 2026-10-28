package com.radiance.mixin_related.extensions.voxy;

import me.cortex.voxy.common.util.MemoryBuffer;

/** A baked block model waiting to be uploaded to Voxy's model buffers. */
public interface IVoxyModelUploadExt {
    int radiance$modelId();

    /** The 64-byte model record: six face words, flags, tint, custom id. */
    MemoryBuffer radiance$model();

    /** The 48x32 RGBA8 face atlas for the model, level 0 first, followed by its mips. */
    MemoryBuffer radiance$texture();

    /** Where {@link #radiance$biomeColours} go in the biome colour table, or -1 for none. */
    int radiance$biomeColourIndex();

    MemoryBuffer radiance$biomeColours();

    /** Frees every native buffer the upload owns. */
    void radiance$release();
}
