package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.platform.NativeImage;
import com.radiance.client.proxy.vulkan.TextureProxy;
import com.radiance.mixin_related.extensions.vanilla_resource_tracker.INativeImageExt;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NativeImage.class)
public abstract class NativeImageMixins implements
    com.radiance.mixin_related.extensions.vulkan_render_integration.INativeImageExt {

    @Shadow
    private long pixels;

    @Final
    @Shadow
    private int width;

    @Final
    @Shadow
    private NativeImage.Format format;

    @Final
    @Shadow
    private int height;

    /**
     * Queued auxiliary uploads read straight from this image's pixels (see
     * {@code TextureUploadMixins}), so they must reach native before the memory is freed. The PBR side
     * images attached when the image was read are freed with it.
     */
    @Inject(method = "close()V", at = @At(value = "HEAD"))
    public void closeImage(CallbackInfo ci) {
        TextureProxy.flushUploadBatch();
        INativeImageExt nativeImageExt = (INativeImageExt) this;
        NativeImage specularImage = nativeImageExt.radiance$getSpecularNativeImage();
        NativeImage normalImage = nativeImageExt.radiance$getNormalNativeImage();
        NativeImage flagImage = nativeImageExt.radiance$getFlagNativeImage();
        if (specularImage != null) {
            specularImage.close();
        }
        if (normalImage != null) {
            normalImage.close();
        }
        if (flagImage != null) {
            flagImage.close();
        }
    }

    @Override
    public NativeImage radiance$alignTo(NativeImage source) {
        int targetWidth = source.getWidth();
        int targetHeight = source.getHeight();
        NativeImage.Format targetFormat = source.format();

        if (width == targetWidth && height == targetHeight && format == targetFormat) {
            return (NativeImage) (Object) this;
        }

        NativeImage destinationImage = new NativeImage(targetFormat, targetWidth, targetHeight, false);

        int sourceChannelCount = this.format.components();
        int destinationChannelCount = targetFormat.components();
        int sharedChannelCount = Math.min(sourceChannelCount, destinationChannelCount);

        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                int sampleX = (x < this.width) ? x : (x % this.width);
                int sampleY = (y < this.height) ? y : (y % this.height);

                long sourcePixelPointer =
                    this.pixels + (sampleX + (long) sampleY * this.width) * sourceChannelCount;
                long destinationPixelPointer = ((com.radiance.mixin_related.extensions
                    .vulkan_render_integration.INativeImageExt) (Object) destinationImage).radiance$getPointer()
                    + (long) (x + (long) y * targetWidth) * destinationChannelCount;

                for (int channelIndex = 0; channelIndex < sharedChannelCount; channelIndex++) {
                    byte channelValue = MemoryUtil.memGetByte(sourcePixelPointer + channelIndex);
                    MemoryUtil.memPutByte(destinationPixelPointer + channelIndex, channelValue);
                }

                if (destinationChannelCount > sourceChannelCount) {
                    for (int channelIndex = sourceChannelCount; channelIndex < destinationChannelCount;
                        channelIndex++) {
                        MemoryUtil.memPutByte(destinationPixelPointer + channelIndex, (byte) 0);
                    }
                }
            }
        }
        return destinationImage;
    }

    @Override
    public long radiance$getPointer() {
        return pixels;
    }
}
