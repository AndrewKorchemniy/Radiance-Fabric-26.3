package com.radiance.client.proxy.vulkan;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import com.radiance.client.constant.Constants;
import com.radiance.client.option.Options;
import com.radiance.client.profiler.MemoryMetricsCollector;
import com.radiance.client.profiler.Profiler;
import com.radiance.mixin_related.extensions.vulkan_render_integration.INativeImageExt;
import net.minecraft.client.Minecraft;

public class RendererProxy {

    public static native void initFolderPath(String folderPath);

    /**
     * Attaches the native renderer to vanilla's Vulkan device instead of letting it create its own.
     * Native keeps its own command pools and VMA allocator on this device, and creates its swapchain
     * on {@code surface}, the {@code VkSurfaceKHR} vanilla made through SDL.
     */
    private static native void initRendererWithVulkanDevice(long instance, long physicalDevice,
        long device, int graphicsQueueFamily, long graphicsQueue, int computeQueueFamily,
        long computeQueue, int transferQueueFamily, long transferQueue, long surface,
        String[] enabledInstanceExtensions, String[] enabledDeviceExtensions);

    public static void initRenderer(VulkanDevice device, long surface,
        String[] enabledDeviceExtensions) {
        if (Profiler.ENABLED) {
            Profiler.enter("java.RendererProxy.initRenderer");
        }
        try {
            VulkanQueue graphics = device.graphicsQueue();
            VulkanQueue compute = device.computeQueue();
            VulkanQueue transfer = device.transferQueue();
            initRendererWithVulkanDevice(
                device.instance().vkInstance().address(),
                device.vkDevice().getPhysicalDevice().address(),
                device.vkDevice().address(),
                graphics.queueFamilyIndex(), graphics.vkQueue().address(),
                compute.queueFamilyIndex(), compute.vkQueue().address(),
                transfer.queueFamilyIndex(), transfer.vkQueue().address(),
                surface,
                device.instance().getEnabledExtensions().toArray(String[]::new),
                enabledDeviceExtensions);
            Options.applyDefaultFrameGeneration();
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    public static native int maxSupportedTextureSize();

    public static native void acquireContext();

    public static native void submitCommand();

    public static native void present();

    public static native int getPresentedFrameRate();

    public static void submitCommandAndPresent() {
        if (Profiler.ENABLED) {
            Profiler.enter("java.RendererProxy.submitCommandAndPresent");
        }
        try {
            if (Profiler.ENABLED) {
                MemoryMetricsCollector.sampleFrame();
            }
            submitCommand();
            present();
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    public static native void close();

    public static native void shouldRenderWorld(boolean renderWorld);

    public static native void takeScreenshot(boolean withUI, int width, int height, int channel,
        long pointer);

    public static NativeImage takeScreenshotWithoutUI() {
        if (Profiler.ENABLED) {
            Profiler.enter("java.RendererProxy.takeScreenshotWithoutUI");
        }
        try {
            Minecraft minecraftClient = Minecraft.getInstance();
            int
                width =
                minecraftClient.getWindow()
                    .getScreenWidth();
            int
                height =
                minecraftClient.getWindow()
                    .getScreenHeight();
            NativeImage nativeImage = new NativeImage(width, height, false);
            ((INativeImageExt) (Object) nativeImage).radiance$loadFromTextureImageWithoutUI(0, true);
            return nativeImage;
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }
}
