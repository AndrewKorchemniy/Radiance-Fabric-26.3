package com.radiance.mixin_related.extensions.vulkan_render_integration;

import org.joml.Vector3fc;

/**
 * The level lightmap's texture and the inputs it was last rendered from. 26.3 renders the lightmap
 * on the GPU from a {@code LightmapRenderState}; these are that state's values.
 */
public interface ILightMapManagerExt {

    int radiance$getTextureId();

    float radiance$getSkyFactor();

    float radiance$getBlockFactor();

    Vector3fc radiance$getBlockLightTint();

    Vector3fc radiance$getSkyLightColor();

    /** Replaces 1.21.4's ambient light factor and bright lightmap flag. */
    Vector3fc radiance$getAmbientColor();

    float radiance$getNightVisionFactor();

    Vector3fc radiance$getNightVisionColor();

    float radiance$getDarknessScale();

    /** The boss overlay's world darkening, 1.21.4's darken world factor. */
    float radiance$getDarkenWorldFactor();

    float radiance$getBrightnessFactor();
}
