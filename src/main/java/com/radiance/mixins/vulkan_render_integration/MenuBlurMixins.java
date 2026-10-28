package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.proxy.vulkan.RendererProxy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla blurs whatever its main target holds behind blurred menus. With Radiance that target holds
 * only the UI drawn so far, so vanilla's blur still softens the HUD, and native blurs the world it
 * composites underneath with the same radius.
 */
@Mixin(GameRenderer.class)
public class MenuBlurMixins {

    @Inject(method = "processBlurEffect", at = @At("HEAD"))
    private void radiance$blurWorld(CallbackInfo ci) {
        if (RendererProxy.isAttached()) {
            RendererProxy.setWorldBlurRadius(
                Minecraft.getInstance().options.getMenuBackgroundBlurriness());
        }
    }
}
