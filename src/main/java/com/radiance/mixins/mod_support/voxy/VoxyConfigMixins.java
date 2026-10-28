package com.radiance.mixins.mod_support.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Voxy builds its OpenGL render system on level load and bends fog for it only while rendering
 * is enabled. Both are skipped by reporting rendering as off; Radiance reads the config's
 * {@code enabled} and {@code enableRendering} flags itself.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.config.VoxyConfig", remap = false)
public class VoxyConfigMixins {
    @Inject(method = "isRenderingEnabled", at = @At("HEAD"), cancellable = true, require = 1)
    private void radiance$neverRenderWithOpenGL(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
