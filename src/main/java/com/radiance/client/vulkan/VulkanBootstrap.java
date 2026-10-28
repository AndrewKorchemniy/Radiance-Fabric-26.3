package com.radiance.client.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import com.radiance.client.proxy.vulkan.RendererProxy;
import com.radiance.mixins.vulkan_device.FrontendGpuDeviceAccessor;
import com.radiance.mixins.vulkan_device.VulkanGpuSurfaceAccessor;

/** Attaches the native renderer to the Vulkan device and window surface vanilla created. */
public final class VulkanBootstrap {

    private VulkanBootstrap() {
    }

    public static VulkanDevice vanillaDevice() {
        GpuDeviceBackend backend =
            ((FrontendGpuDeviceAccessor) RenderSystem.getDevice()).radiance$getBackend();
        if (!(backend instanceof VulkanDevice vulkanDevice)) {
            // PreferredGraphicsApiMixins only offers the Vulkan backend, so this means another mod
            // replaced the backend selection.
            throw new IllegalStateException(
                "Radiance requires Minecraft's Vulkan backend, but the game is running on "
                    + backend.getClass().getSimpleName());
        }
        return vulkanDevice;
    }

    /**
     * Called once vanilla has created both its device and its window surface.
     *
     * @throws UnsupportedGpuException if the device does not support hardware ray tracing
     */
    public static void attachRenderer(VulkanGpuSurface surface) {
        if (!RadianceVulkanFeatures.isRayTracingSupported()) {
            throw new UnsupportedGpuException(
                "Radiance needs a GPU with Vulkan hardware ray tracing "
                    + "(VK_KHR_acceleration_structure and VK_KHR_ray_tracing_pipeline).");
        }
        RendererProxy.initRenderer(vanillaDevice(),
            ((VulkanGpuSurfaceAccessor) surface).radiance$getSurface(),
            RadianceVulkanFeatures.enabledDeviceExtensions());
    }

    public static final class UnsupportedGpuException extends RuntimeException {
        public UnsupportedGpuException(String message) {
            super(message);
        }
    }
}
