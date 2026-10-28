package com.radiance.mixins.vanilla_resource_tracker;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.radiance.client.texture.TextureTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every texture vanilla allocates lives on the device native shares, so native samples vanilla's
 * images directly instead of keeping copies.
 */
@Mixin(VulkanGpuTexture.class)
public class VulkanGpuTextureMixins {

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void radiance$importIntoNative(CallbackInfo ci) {
        TextureTracker.onVanillaTextureCreated((VulkanGpuTexture) (Object) this);
    }

    /**
     * {@code close()} only marks the texture; vanilla frees the image two submissions later, so the
     * native id stays valid until then.
     */
    @Inject(method = "destroy", at = @At("HEAD"), remap = false)
    private void radiance$releaseFromNative(CallbackInfo ci) {
        TextureTracker.onVanillaTextureDestroyed((VulkanGpuTexture) (Object) this);
    }
}
