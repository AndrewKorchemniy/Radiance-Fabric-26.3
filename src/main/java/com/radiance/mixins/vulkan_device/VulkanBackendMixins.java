package com.radiance.mixins.vulkan_device;

import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.radiance.client.vulkan.RadianceVulkanFeatures;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla does not keep the feature set it created the device with, and native needs it to pick code
 * paths, so it is recorded here. {@code checkBackendAvailable()} probes without reaching this method,
 * so only the real device is recorded.
 */
@Mixin(VulkanBackend.class)
public class VulkanBackendMixins {

    @Inject(method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
        at = @At("HEAD"), remap = false)
    private static void radiance$recordEnabledFeatures(FeatureSet enabledFeatures,
        VulkanPhysicalDevice physicalDevice, CallbackInfoReturnable<VkDevice> cir) {
        RadianceVulkanFeatures.recordEnabled(enabledFeatures);
    }
}
