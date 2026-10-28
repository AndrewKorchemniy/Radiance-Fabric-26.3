package com.radiance.mixin_related.extensions.voxy;

import me.cortex.voxy.common.util.MemoryBuffer;

/** The whole biome colour table, rebuilt because a biome was registered after some models. */
public interface IVoxyBiomeUploadExt {
    /** One ARGB colour per biome per model that depends on biomes, from index 0. */
    MemoryBuffer radiance$colours();

    /** Pairs of (model id, first colour index) as 8-byte little-endian longs. */
    MemoryBuffer radiance$modelIndices();

    void radiance$release();
}
