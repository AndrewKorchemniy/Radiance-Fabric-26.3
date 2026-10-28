package com.radiance.mixins.vanilla_resource_tracker;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.radiance.client.texture.TextureTracker;
import java.util.function.Supplier;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.3 builds an atlas by uploading each sprite to its own scratch texture and drawing those into the
 * atlas on the GPU. Native only samples the finished atlas, so the scratch textures are not imported.
 */
@Mixin(TextureAtlas.class)
public class SpriteScratchTextureMixins {

    @WrapOperation(method = "uploadInitialContents", at = @At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/renderpearl/api/GpuFormat;IIII)Lcom/mojang/renderpearl/api/textures/GpuTexture;"),
        remap = false)
    private GpuTexture radiance$skipScratchImport(GpuDevice device, Supplier<String> label,
        int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels,
        Operation<GpuTexture> original) {
        return TextureTracker.withoutImport(() -> original.call(device, label, usage, format, width,
            height, depthOrLayers, mipLevels));
    }
}
