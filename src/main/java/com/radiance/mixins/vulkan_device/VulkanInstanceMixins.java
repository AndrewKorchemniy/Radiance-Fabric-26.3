package com.radiance.mixins.vulkan_device;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import com.radiance.client.vulkan.RadianceVulkanFeatures;
import java.util.Set;
import org.lwjgl.vulkan.VK13;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla creates its instance for Vulkan 1.2; MCVR and XeSS need 1.3. The instance version only caps
 * what the application may use, so raising it does not change vanilla's own behaviour, and the
 * physical device still has to report 1.3 for 1.3 functions to be available.
 *
 * <p>The instance also gets the extensions the DLSS and XeSS SDKs ask for, where the loader has them.
 *
 * <p>{@code VulkanBackend.checkBackendAvailable()} builds a throwaway instance through the same
 * constructor, so this must stay free of side effects.
 */
@Mixin(VulkanInstance.class)
public class VulkanInstanceMixins {

    @Shadow
    @Final
    private Set<String> enabledExtensions;

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/vulkan/VkApplicationInfo;apiVersion(I)Lorg/lwjgl/vulkan/VkApplicationInfo;"),
        remap = false)
    private int radiance$requestVulkan13(int apiVersion) {
        return Math.max(apiVersion, VK13.VK_API_VERSION_1_3);
    }

    // Right after the SDL surface extensions are added, before the debug extensions and the create call.
    @Inject(method = "<init>", at = @At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;"),
        remap = false)
    private void radiance$enableUpscalerExtensions(int debugVerbosity, boolean wantsDebugLabels,
        boolean validation, CallbackInfo ci, @Local(ordinal = 0) Set<String> availableExtensions) {
        for (String extension : RadianceVulkanFeatures.upscalerInstanceExtensions()) {
            if (availableExtensions.contains(extension)) {
                this.enabledExtensions.add(extension);
            }
        }
    }
}
