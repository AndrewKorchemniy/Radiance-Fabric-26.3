package com.radiance.mixins.vulkan_device;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.radiance.client.vulkan.RadianceVulkanFeatures;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Registers Radiance's device features as optional, so they never affect which GPU vanilla picks or
 * whether Vulkan starts at all. Whether ray tracing ended up enabled is checked after the device is
 * created; see {@link RadianceVulkanFeatures#isRayTracingSupported()}.
 */
@Mixin(VulkanFeatureSets.class)
public class VulkanFeatureSetsMixins {

    @Inject(method = "optionalFeatureSets", at = @At("RETURN"), remap = false)
    private static void radiance$addRadianceFeatureSets(CallbackInfoReturnable<Set<FeatureSet>> cir) {
        cir.getReturnValue().addAll(RadianceVulkanFeatures.featureSets());
    }
}
