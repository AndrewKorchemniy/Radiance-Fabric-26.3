package com.radiance.mixins.vanilla_resource_tracker;

import com.radiance.mixin_related.extensions.vanilla_resource_tracker.INativeImageExt;
import com.radiance.mixin_related.extensions.vanilla_resource_tracker.ISpriteContentsExt;
import com.radiance.client.texture.TextureTracker.TextureTarget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SpriteContents.class)
public class SpriteContentsMixins implements ISpriteContentsExt {

    @Shadow @Final private NativeImage image;
    @Unique private volatile boolean radiance$hasAverageColor;
    @Unique private int radiance$averageColor;

    @Unique
    private TextureTarget target;

    @Override
    public TextureTarget radiance$getTarget() {
        return target;
    }

    @Override
    public void radiance$setTarget(TextureTarget target) {
        this.target = target;
    }

    @Override
    public int radiance$getAverageColor() {
        if (!radiance$hasAverageColor) {
            synchronized (this) {
                if (!radiance$hasAverageColor) {
                    long red = 0, green = 0, blue = 0, weight = 0;
                    for (int y = 0; y < image.getHeight(); y++) {
                        for (int x = 0; x < image.getWidth(); x++) {
                            int color = image.getColorArgb(x, y);
                            int alpha = color >>> 24;
                            red += ((color >>> 16) & 255) * alpha;
                            green += ((color >>> 8) & 255) * alpha;
                            blue += (color & 255) * alpha;
                            weight += alpha;
                        }
                    }
                    int alpha = (int) (weight / ((long) image.getWidth() * image.getHeight()));
                    radiance$averageColor = weight == 0 ? 0 : (alpha << 24)
                        | (int) (red / weight) | ((int) (green / weight) << 8)
                        | ((int) (blue / weight) << 16);
                    radiance$hasAverageColor = true;
                }
            }
        }
        return radiance$averageColor;
    }

    @Redirect(method = "upload(IIII[Lnet/minecraft/client/texture/NativeImage;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/texture/NativeImage;upload(IIIIIIIZ)V"))
    public void setImageTargetIDBeforeUpload(NativeImage image, int level,
        int offsetX, int offsetY, int unpackSkipPixels, int unpackSkipRows,
        int width, int height, boolean blur) {
        ((INativeImageExt) (Object) image).radiance$setTarget(this.target);
        image.upload(level, offsetX, offsetY, unpackSkipPixels, unpackSkipRows,
            width, height, blur);
    }
}
