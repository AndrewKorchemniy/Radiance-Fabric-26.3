package com.radiance.mixins.vulkan_device;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import com.radiance.client.proxy.vulkan.RendererProxy;
import org.spongepowered.asm.mixin.Mixin;

/** Vanilla's queue submit takes native's queue lock; see {@link VulkanQueueMixins}. */
@Mixin(VulkanQueue.Submission.class)
public class VulkanQueueSubmissionMixins {

    @WrapMethod(method = "close", remap = false)
    private void radiance$submitLocked(Operation<Void> original) {
        RendererProxy.runWithGraphicsQueue(() -> original.call());
    }
}
