package com.radiance.mixins.vulkan_device;

import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla has no getter for the backend behind {@code Minecraft.windowSurface}. */
@Mixin(FrontendGpuSurface.class)
public interface FrontendGpuSurfaceAccessor {

    @Accessor(value = "backend", remap = false)
    GpuSurfaceBackend radiance$getBackend();
}
