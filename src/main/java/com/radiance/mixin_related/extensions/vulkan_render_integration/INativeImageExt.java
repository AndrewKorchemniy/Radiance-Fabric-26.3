package com.radiance.mixin_related.extensions.vulkan_render_integration;

import com.mojang.blaze3d.platform.NativeImage;

public interface INativeImageExt {

    NativeImage radiance$alignTo(NativeImage template);

    long radiance$getPointer();
}
