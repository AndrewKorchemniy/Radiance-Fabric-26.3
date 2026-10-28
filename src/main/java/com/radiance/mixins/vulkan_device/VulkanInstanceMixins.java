package com.radiance.mixins.vulkan_device;

import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import org.lwjgl.vulkan.VK13;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Vanilla creates its instance for Vulkan 1.2; MCVR and XeSS need 1.3. The instance version only caps
 * what the application may use, so raising it does not change vanilla's own behaviour, and the
 * physical device still has to report 1.3 for 1.3 functions to be available.
 *
 * <p>{@code VulkanBackend.checkBackendAvailable()} builds a throwaway instance through the same
 * constructor, so this must stay free of side effects.
 */
@Mixin(VulkanInstance.class)
public class VulkanInstanceMixins {

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/VkApplicationInfo;apiVersion(I)Lorg/lwjgl/vulkan/VkApplicationInfo;"),
        remap = false)
    private int radiance$requestVulkan13(int apiVersion) {
        return Math.max(apiVersion, VK13.VK_API_VERSION_1_3);
    }
}
