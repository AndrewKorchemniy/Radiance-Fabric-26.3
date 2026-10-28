package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.radiance.client.proxy.vulkan.RendererProxy;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla screenshots read its main target, which with Radiance holds only the UI; the world is
 * composited in natively when the frame is presented. Screenshots of the main target therefore take
 * the presented frame instead. This covers F2, panoramas and world thumbnails.
 */
@Mixin(Screenshot.class)
public class ScreenshotMixins {

    @Inject(method = "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
        at = @At("HEAD"), cancellable = true)
    private static void radiance$captureComposited(RenderTarget target, int downscaleFactor,
        Consumer<NativeImage> callback, CallbackInfo ci) {
        if (!RendererProxy.isAttached()
            || target != Minecraft.getInstance().gameRenderer.mainRenderTarget()) {
            return;
        }
        ci.cancel();
        if (downscaleFactor <= 1) {
            RendererProxy.captureScreenshot(callback);
            return;
        }
        RendererProxy.captureScreenshot(image -> {
            try (image) {
                callback.accept(radiance$downscale(image, downscaleFactor));
            }
        });
    }

    /** Averages each {@code factor}x{@code factor} block, as vanilla's own capture does. */
    private static NativeImage radiance$downscale(NativeImage image, int factor) {
        int width = image.getWidth() / factor;
        int height = image.getHeight() / factor;
        NativeImage result = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = 0;
                int green = 0;
                int blue = 0;
                for (int j = 0; j < factor; j++) {
                    for (int i = 0; i < factor; i++) {
                        int argb = image.getPixel(x * factor + i, y * factor + j);
                        red += ARGB.red(argb);
                        green += ARGB.green(argb);
                        blue += ARGB.blue(argb);
                    }
                }
                int samples = factor * factor;
                result.setPixel(x, y, ARGB.color(255, red / samples, green / samples, blue / samples));
            }
        }
        return result;
    }
}
