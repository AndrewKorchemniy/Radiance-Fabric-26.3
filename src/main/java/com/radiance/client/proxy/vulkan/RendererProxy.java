package com.radiance.client.proxy.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import com.radiance.client.option.Options;
import com.radiance.client.profiler.MemoryMetricsCollector;
import com.radiance.client.profiler.Profiler;

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

    /**
     * (Re)creates native's swapchain on vanilla's surface. Called on startup, on resize and when vsync
     * changes. {@code presentMode} is a {@code GpuSurface.PresentMode} ordinal.
     *
     * @return false if the swapchain could not be created; vanilla retries on a later frame
     */
    public static native boolean configureSwapchain(int width, int height, int presentMode);

    public static native boolean isSwapchainSuboptimal();

    /**
     * Acquires the next swapchain image and begins native's frame.
     *
     * @return false if no image could be acquired, which makes vanilla reconfigure the surface
     */
    public static native boolean acquireContext();

    /**
     * The image holding this frame's final picture (the world composited under vanilla's UI), in
     * {@code VK_IMAGE_LAYOUT_GENERAL} and stored bottom-up.
     */
    public static native void setPresentSource(long vkImage, long vkImageView, int width, int height);

    /**
     * Records this frame's world rendering, ending with the world written into {@code vkImage}
     * (vanilla's main color target, in {@code VK_IMAGE_LAYOUT_GENERAL}, stored bottom-up).
     *
     * @return the recorded, ended {@code VkCommandBuffer} handle, or 0 when no world is rendered this
     *     frame
     */
    public static native long recordWorldFrame(long vkImage, long vkImageView, int width, int height);

    private static native void submitCommand();

    private static native void present();

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

}
