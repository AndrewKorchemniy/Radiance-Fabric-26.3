package com.radiance.mixins.vulkan_device;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.radiance.client.vulkan.RadianceVulkanFeatures;
import java.util.Set;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla does not keep the feature set it created the device with, and native needs it to pick code
 * paths, so it is recorded here. {@code checkBackendAvailable()} probes without reaching this method,
 * so only the real device is recorded.
 *
 * <p>The DLSS and XeSS SDKs report their device extensions per GPU, so their feature sets are added
 * once vanilla has picked the physical device and before it walks the optional sets.
 */
@Mixin(VulkanBackend.class)
public class VulkanBackendMixins {

    @Inject(method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
        at = @At("HEAD"), remap = false)
    private static void radiance$recordEnabledFeatures(FeatureSet enabledFeatures,
        VulkanPhysicalDevice physicalDevice, CallbackInfoReturnable<VkDevice> cir) {
        RadianceVulkanFeatures.recordEnabled(enabledFeatures);
    }

    // The optional sets are the third Set<FeatureSet> local; the physical device is the argument of
    // the extension enumeration that precedes the loop over them.
    @Inject(method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
        at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanUtils;enumerateExtensions(Lorg/lwjgl/vulkan/VkPhysicalDevice;)Ljava/util/Set;"),
        remap = false)
    private void radiance$addUpscalerFeatureSets(CallbackInfoReturnable<?> cir,
        @Local(ordinal = 2) Set<FeatureSet> optionalFeatureSets,
        @Local VulkanPhysicalDevice physicalDevice) {
        VkPhysicalDevice vkPhysicalDevice = physicalDevice.vkPhysicalDevice();
        optionalFeatureSets.addAll(RadianceVulkanFeatures.upscalerFeatureSets(vkPhysicalDevice));
    }
}
