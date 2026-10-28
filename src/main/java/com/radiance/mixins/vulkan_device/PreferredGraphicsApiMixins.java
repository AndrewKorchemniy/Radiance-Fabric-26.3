package com.radiance.mixins.vulkan_device;

import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Radiance renders on vanilla's Vulkan device, so the game must never fall back to OpenGL. Returning
 * only the Vulkan backend also bypasses the reset to OpenGL vanilla applies after an unclean start,
 * and an unsupported GPU gets vanilla's "no supported graphics backend" error instead of a silent
 * OpenGL game that Radiance cannot draw into.
 */
@Mixin(PreferredGraphicsApi.class)
public class PreferredGraphicsApiMixins {

    @Inject(method = "getBackendsToTry", at = @At("HEAD"), cancellable = true)
    private void radiance$vulkanOnly(CallbackInfoReturnable<GpuBackend[]> cir) {
        cir.setReturnValue(new GpuBackend[] {new VulkanBackend()});
    }
}
