package com.radiance.mixins.vulkan_device;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla has no getter for the backend behind {@code RenderSystem.getDevice()}. */
@Mixin(FrontendGpuDevice.class)
public interface FrontendGpuDeviceAccessor {

    @Accessor(value = "backend", remap = false)
    GpuDeviceBackend radiance$getBackend();
}
