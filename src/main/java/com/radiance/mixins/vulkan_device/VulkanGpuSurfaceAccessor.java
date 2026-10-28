package com.radiance.mixins.vulkan_device;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The {@code VkSurfaceKHR} vanilla created through SDL, which MCVR builds its swapchain on. */
@Mixin(VulkanGpuSurface.class)
public interface VulkanGpuSurfaceAccessor {

    @Accessor(value = "surface", remap = false)
    long radiance$getSurface();
}
