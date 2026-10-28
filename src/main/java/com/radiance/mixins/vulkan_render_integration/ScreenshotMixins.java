package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.radiance.client.proxy.vulkan.RendererProxy;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla screenshots read its main target, which with Radiance holds only the UI; the world is
 * composited in natively when the frame is presented. Screenshots of the main target therefore take
 * the presented frame instead. This covers F2 and world thumbnails.
 *
 * <p>Downscaled captures are left to vanilla: only the debug panorama uses them, and it renders its
 * six faces without presenting in between, so the presented frame would be wrong for all of them.
 */
@Mixin(Screenshot.class)
public class ScreenshotMixins {

    @Inject(method = "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
        at = @At("HEAD"), cancellable = true)
    private static void radiance$captureComposited(RenderTarget target, int downscaleFactor,
        Consumer<NativeImage> callback, CallbackInfo ci) {
        if (!RendererProxy.isAttached() || downscaleFactor != 1
            || target != Minecraft.getInstance().gameRenderer.mainRenderTarget()) {
            return;
        }
        ci.cancel();
        RendererProxy.captureScreenshot(callback);
    }
}
