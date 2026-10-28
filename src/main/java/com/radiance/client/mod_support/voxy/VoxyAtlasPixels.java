package com.radiance.client.mod_support.voxy;

import com.mojang.blaze3d.platform.NativeImage;
import com.radiance.mixins.mod_support.voxy.source.TextureAtlasSpritesAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * The block atlas as ABGR ints (OpenGL's RGBA8 byte order), the layout Voxy's software rasteriser
 * samples. 26.3 stitches atlases on the GPU, so the pixels come from each sprite's CPU image at
 * its atlas position. Animated sprites contribute their first frame.
 */
public record VoxyAtlasPixels(int[] pixels, int width, int height) {
    public static VoxyAtlasPixels build() {
        TextureAtlas atlas = (TextureAtlas) Minecraft.getInstance().getTextureManager()
            .getTexture(TextureAtlas.LOCATION_BLOCKS);
        int width = atlas.getTexture().getWidth(0);
        int height = atlas.getTexture().getHeight(0);
        int[] pixels = new int[width * height];
        for (TextureAtlasSprite sprite : ((TextureAtlasSpritesAccessor) atlas).radiance$getSprites()) {
            SpriteContents contents = sprite.contents();
            try {
                NativeImage image = contents.byMipLevel[0];
                int[] source = image.getPixelsABGR();
                // Sprites are stitched with a border of padding texels: UVs (and so Voxy's
                // rasteriser) address the content at u0/v0, not at getX()/getY().
                int originX = Math.round(sprite.getU0() * width);
                int originY = Math.round(sprite.getV0() * height);
                for (int y = 0; y < contents.height(); y++) {
                    int row = originY + y;
                    if (row < 0 || row >= height) { continue; }
                    int columns = Math.min(contents.width(), width - originX);
                    if (columns > 0 && originX >= 0) {
                        System.arraycopy(source, y * image.getWidth(), pixels,
                            row * width + originX, columns);
                    }
                }
            } catch (RuntimeException exception) {
                // A sprite whose image was already released is left transparent.
            }
        }
        return new VoxyAtlasPixels(pixels, width, height);
    }
}
