package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Transparency;
import com.radiance.mixin_related.extensions.vanilla_resource_tracker.INativeImageExt;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MipmapGenerator.class)
public class MipmapHelperMixins {

    @Inject(method = "generateMipLevels", at = @At("RETURN"))
    private static void addIdentifier(Identifier name, NativeImage[] originals, int mipmap,
        MipmapStrategy mipmapStrategy, float alphaCutoffBias, Transparency transparency,
        CallbackInfoReturnable<NativeImage[]> cir) {
        NativeImage[] levels = cir.getReturnValue();
        for (int level = originals.length; level < levels.length; level++) {
            NativeImage generated = levels[level];
            NativeImage source = levels[level - 1];
            if (generated != null && source != null) {
                ((INativeImageExt) (Object) generated).radiance$setIdentifier(
                    ((INativeImageExt) (Object) source).radiance$getIdentifier());
            }
        }
    }
}
