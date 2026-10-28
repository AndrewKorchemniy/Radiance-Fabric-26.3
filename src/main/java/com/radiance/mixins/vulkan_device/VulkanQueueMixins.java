package com.radiance.mixins.vulkan_device;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import com.radiance.client.proxy.vulkan.RendererProxy;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Vulkan requires external synchronisation of a queue. Vanilla only touches its graphics queue from the
 * render thread and never locks it, but native also submits to that queue from worker threads when the
 * device has no separate compute queue. Vanilla's queue operations therefore take native's queue lock:
 * waits here, submits in {@link VulkanQueueSubmissionMixins}. Presentation already goes through native
 * ({@code VulkanGpuSurfaceMixins}).
 */
@Mixin(VulkanQueue.class)
public class VulkanQueueMixins {

    @WrapMethod(method = "waitIdle", remap = false)
    private void radiance$waitIdleLocked(Operation<Void> original) {
        RendererProxy.runWithGraphicsQueue(() -> original.call());
    }
}
