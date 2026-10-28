package com.radiance.client.proxy.vulkan;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import com.radiance.client.RadianceClient;
import com.radiance.client.option.Options;
import com.radiance.client.profiler.MemoryMetricsCollector;
import com.radiance.client.profiler.Profiler;
import com.radiance.mixin_related.extensions.vulkan_render_integration.INativeImageExt;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.lwjgl.system.MemoryUtil;

public class RendererProxy {

    public static native void initFolderPath(String folderPath);

    // Upscaler ids for the extension queries below; native defines the same values.
    public static final int UPSCALER_DLSS_RR = 0;
    public static final int UPSCALER_DLSS_SR = 1;
    public static final int UPSCALER_DLSS_FRAME_GENERATION = 2;
    public static final int UPSCALER_XESS = 3;

    /**
     * The instance extensions an upscaler SDK needs, or null if the SDK cannot tell (for example,
     * without its driver). Safe to call before the renderer exists.
     */
    public static native String[] queryUpscalerInstanceExtensions(int upscaler);

    /**
     * The device extensions an upscaler SDK needs on {@code physicalDevice}, or null if the SDK
     * cannot tell. Safe to call before the device exists.
     */
    public static native String[] queryUpscalerDeviceExtensions(int upscaler, long instance,
        long physicalDevice);

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

    /** Whether native has attached to vanilla's device and presents frames. */
    public static boolean isAttached() {
        return graphicsQueueShared;
    }

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
     * the GUI, hand overlay and screen effects blended on top, which leaves the color premultiplied
     * by the coverage in the alpha channel. Native composites it over the ray-traced world when it
     * presents, as it did with the OpenGL UI surface on 1.21.4. The image is RGBA8 in
     * {@code VK_IMAGE_LAYOUT_GENERAL} and stored bottom-up.
     */
    public static native void setPresentSource(long vkImage, long vkImageView, int width, int height);

    /**
     * Blurs the world under the UI in the next presented frame. Vanilla's menu blur runs on its main
     * target, which only holds the UI, so the world is blurred natively. Lasts one frame.
     */
    public static native void setWorldBlurRadius(float radius);

    /** Captures the next presented frame, world and UI together, for {@link #readScreenshot}. */
    private static native void requestScreenshot();

    /** Width in the high 32 bits and height in the low 32 bits, or 0 while nothing is captured. */
    private static native long screenshotSize();

    /** Copies the captured frame to {@code destination} as top-down RGBA8, waiting for the GPU. */
    private static native boolean readScreenshot(long destination, int width, int height);

    private static native void submitCommand();

    private static native void present();

    public static native int getPresentedFrameRate();

    /** Callbacks for screenshots requested but not yet captured; touched only on the render thread. */
    private static final List<Consumer<NativeImage>> pendingScreenshots = new ArrayList<>();

    /**
     * Takes a screenshot of what native presents: vanilla's own capture reads its main target, which
     * holds only the UI. The callback receives the next presented frame.
     */
    public static void captureScreenshot(Consumer<NativeImage> callback) {
        requestScreenshot();
        pendingScreenshots.add(callback);
    }

    private static void deliverScreenshots() {
        if (pendingScreenshots.isEmpty()) {
            return;
        }
        long size = screenshotSize();
        int width = (int) (size >>> 32);
        int height = (int) size;
        if (width <= 0 || height <= 0) {
            // Not captured yet. Recreating the swapchain drops native's request, so ask again; a
            // repeated request before the capture is harmless.
            requestScreenshot();
            return;
        }
        NativeImage image = new NativeImage(width, height, false);
        long pixels = ((INativeImageExt) (Object) image).radiance$getPointer();
        if (!readScreenshot(pixels, width, height)) {
            image.close();
            requestScreenshot();
            return;
        }
        // Vanilla's screenshots are opaque whatever the target's alpha held.
        for (long offset = 3; offset < (long) width * height * 4; offset += 4) {
            MemoryUtil.memPutByte(pixels + offset, (byte) 0xFF);
        }
        List<Consumer<NativeImage>> callbacks = new ArrayList<>(pendingScreenshots);
        pendingScreenshots.clear();
        for (int i = 0; i < callbacks.size(); i++) {
            // Each callback owns and closes its image.
            NativeImage copy = i == callbacks.size() - 1 ? image : copyOf(image);
            try {
                callbacks.get(i).accept(copy);
            } catch (RuntimeException e) {
                RadianceClient.LOGGER.warn("Screenshot callback failed", e);
            }
        }
    }

    private static NativeImage copyOf(NativeImage image) {
        NativeImage copy = new NativeImage(image.getWidth(), image.getHeight(), false);
        copy.copyFrom(image);
        return copy;
    }

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
            deliverScreenshots();
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    public static native void close();

    public static native void shouldRenderWorld(boolean renderWorld);

}
