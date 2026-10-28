package com.radiance.mixins.mod_support.voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Voxy wraps the same call to refuse a Vulkan device ("Voxy does not presently support vulkan"),
 * which would leave Radiance with no graphics backend at all. Radiance needs Vulkan, so a Vulkan
 * backend is created directly. The high priority makes this wrapper the outermost one, so
 * Voxy's never sees the call.
 */
@Mixin(value = Minecraft.class, priority = 5000)
public class MinecraftBackendMixins {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;"))
    private static GpuDevice radiance$createVulkanDevice(GpuBackend backend, GpuDebugOptions options,
        Operation<GpuDevice> original) throws BackendCreationException {
        if (backend instanceof VulkanBackend) { return backend.createDevice(options); }
        return original.call(backend, options);
    }
}
