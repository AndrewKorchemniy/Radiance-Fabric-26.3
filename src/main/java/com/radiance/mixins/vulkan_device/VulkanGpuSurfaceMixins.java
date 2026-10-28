package com.radiance.mixins.vulkan_device;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.radiance.client.proxy.vulkan.RendererProxy;
import com.radiance.client.proxy.vulkan.TextureProxy;
import com.radiance.client.proxy.world.ChunkProxy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands vanilla's swapchain to MCVR. Frame generation (DLSS, FSR, XeSS), vsync and Radiance's frame
 * rate limiter decide when and how often frames reach the screen, so native must own presentation.
 *
 * <p>Vanilla's {@code FrontendGpuSurface} keeps its own acquire/blit/present bookkeeping and only
 * forwards to this backend, so {@code Minecraft.renderFrame} runs unchanged. Vanilla never creates a
 * swapchain of its own: {@code configure} is the only place it would.
 *
 * <p>Order within a frame: vanilla submits its command encoder (world composite plus UI in the main
 * target) before calling {@code present}, and native submits after that on the same queue, so its
 * work starts after vanilla's. Native brackets its work with full memory barriers, since vanilla
 * keeps every image in {@code VK_IMAGE_LAYOUT_GENERAL} and relies on global barriers too.
 */
@Mixin(VulkanGpuSurface.class)
public class VulkanGpuSurfaceMixins {

    @Inject(method = "configure", at = @At("HEAD"), cancellable = true, remap = false)
    private void radiance$configureNativeSwapchain(GpuSurface.Configuration config,
        CallbackInfo ci) throws SurfaceException {
        if (!RendererProxy.configureSwapchain(config.width(), config.height(),
            config.presentMode().ordinal())) {
            throw new SurfaceException("Radiance could not configure its swapchain to " + config);
        }
        ci.cancel();
    }

    @Inject(method = "isSuboptimal", at = @At("HEAD"), cancellable = true, remap = false)
    private void radiance$isNativeSwapchainSuboptimal(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(RendererProxy.isSwapchainSuboptimal());
    }

    @Inject(method = "acquireNextTexture", at = @At("HEAD"), cancellable = true, remap = false)
    private void radiance$acquireNativeSwapchainImage(CallbackInfo ci) throws SurfaceException {
        if (!RendererProxy.acquireContext()) {
            // Vanilla marks the surface for reconfiguration and skips presenting this frame.
            throw new SurfaceException("Radiance could not acquire a swapchain image");
        }
        ci.cancel();
    }

    /**
     * Instead of blitting, tell native which image holds the finished frame (world plus UI). It is
     * stored bottom-up, as vanilla flips Y when blitting to the swapchain.
     */
    @Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true, remap = false)
    private void radiance$setPresentSource(CommandEncoderBackend commandEncoder,
        GpuTextureView textureView, CallbackInfo ci) {
        VulkanGpuTexture texture = (VulkanGpuTexture) textureView.texture();
        RendererProxy.setPresentSource(texture.vkImage(),
            ((VulkanGpuTextureView) textureView).vkImageView(), texture.getWidth(0),
            texture.getHeight(0));
        ci.cancel();
    }

    @Inject(method = "present", at = @At("HEAD"), cancellable = true, remap = false)
    private void radiance$submitAndPresent(CallbackInfo ci) {
        ChunkProxy.waitImportantChunkRebuild();
        synchronized (TextureProxy.class) {
            RendererProxy.submitCommandAndPresent();
        }
        ci.cancel();
    }
}
