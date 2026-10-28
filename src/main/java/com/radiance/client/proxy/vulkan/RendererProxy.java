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
            graphicsQueueShared = true;
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
     * Vanilla and native share the graphics queue, and native may submit to it from worker threads,
     * so vanilla's queue submits and waits take native's queue lock too (see {@code VulkanQueueMixins}).
     * Before native is initialised these do nothing.
     */
    private static native void lockGraphicsQueue();

    private static native void unlockGraphicsQueue();

    /** Set once native is attached; vanilla submits before that need no lock (and may precede loading). */
    private static volatile boolean graphicsQueueShared = false;

    public static void runWithGraphicsQueue(Runnable action) {
        if (!graphicsQueueShared) {
            action.run();
            return;
        }
        lockGraphicsQueue();
        try {
            action.run();
        } finally {
            unlockGraphicsQueue();
        }
    }

    /**
     * Acquires the next swapchain image and begins native's frame.
     *
     * @return false if no image could be acquired, which makes vanilla reconfigure the surface
     */
    public static native boolean acquireContext();

    /**
     * The image vanilla drew this frame's UI into: its main target, cleared to transparent black, with
     * the GUI, hand overlay and screen effects blended on top (straight alpha, coverage in the alpha
     * channel). Native composites it over the ray-traced world when it presents, as it did with the
     * OpenGL UI surface on 1.21.4. The image is RGBA8 in {@code VK_IMAGE_LAYOUT_GENERAL} and stored
     * bottom-up.
     */
    public static native void setPresentSource(long vkImage, long vkImageView, int width, int height);

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
