package com.radiance.mixins.mod_support.voxy;

import com.radiance.client.mod_support.voxy.VoxySupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy sets itself up once the renderer exists: it queries OpenGL capabilities, compiles test
 * shaders and then registers the factory for its client instance. None of the OpenGL part can run
 * on Vulkan, so setup is replaced by Radiance's, which registers the factory and, when Voxy is
 * the Vista source, Vista itself.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.VoxyClient", remap = false)
public class VoxyClientMixins {
    @Inject(method = "initVoxyClient", at = @At("HEAD"), cancellable = true, require = 1)
    private static void radiance$initWithoutOpenGL(CallbackInfo ci) {
        VoxySupport.initialize();
        ci.cancel();
    }
}
