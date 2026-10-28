package com.radiance.mixins.vanilla_resource_tracker;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.radiance.client.proxy.vulkan.TextureProxy;
import com.radiance.client.texture.AuxiliaryTextures;
import com.radiance.client.texture.TextureTracker;
import com.radiance.client.texture.TextureTracker.TextureTarget;
import com.radiance.mixin_related.extensions.vanilla_resource_tracker.INativeImageExt;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every {@link NativeImage} upload in 26.3 goes through this overload, and it names the destination
 * texture, so the PBR side textures (specular, normal, flag) and emission data can follow each upload
 * into the matching region of the texture's auxiliary images. Vanilla uploads the image itself; native
 * already samples that through the shared device.
 *
 * <p>The queued regions point into the source image's pixels, which callers usually close right
 * after uploading; {@code NativeImageMixins} flushes the queue before an image is freed.
 */
@Mixin(FrontendCommandEncoder.class)
public class TextureUploadMixins {

    @Inject(method = "writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;IIII)V",
        at = @At("HEAD"), remap = false)
    private void radiance$uploadAuxiliaryRegions(GpuTexture destination, NativeImage source,
        int mipLevel, int depthOrLayer, int destX, int destY, CallbackInfo ci) {
        if (TextureTracker.isUploadTrackingSuppressed()) {
            return;
        }
        TextureTarget target = TextureTracker.targetOf(destination);
        if (target == null || depthOrLayer != 0) {
            return;
        }
        INativeImageExt sourceExt = (INativeImageExt) (Object) source;
        sourceExt.radiance$setTarget(target);
        List<TextureProxy.TextureUploadRegion> auxiliaryRegions =
            AuxiliaryTextures.buildUploadRegions(source, sourceExt, mipLevel, destX, destY, 0, 0,
                source.getWidth(), source.getHeight());
        for (TextureProxy.TextureUploadRegion region : auxiliaryRegions) {
            TextureProxy.queueUploadRegion(region);
        }
    }
}
