package com.radiance.mixins.mod_support.voxy.source;

import com.radiance.client.mod_support.voxy.VoxyAtlasPixels;
import me.cortex.voxy.client.core.model.bakery.SoftwareRasterizer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy's software rasteriser reads the block atlas back through OpenGL. The atlas lives on
 * vanilla's Vulkan device here, so it is rebuilt from the sprites' CPU images instead.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.model.bakery.SoftwareModelTextureBakery", remap = false)
public class SoftwareModelTextureBakeryMixins {
    @Shadow @Final private SoftwareRasterizer rasterizer;

    @Inject(method = "setupTexture", at = @At("HEAD"), cancellable = true, require = 1)
    private void readAtlasFromSprites(CallbackInfo ci) {
        VoxyAtlasPixels atlas = VoxyAtlasPixels.build();
        rasterizer.setSamplerTexture(atlas.pixels(), atlas.width(), atlas.height());
        ci.cancel();
    }
}
