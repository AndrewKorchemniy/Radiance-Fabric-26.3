package com.radiance.mixins.vanilla_resource_tracker;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.mojang.blaze3d.platform.NativeImage;
import com.radiance.client.proxy.vulkan.TextureProxy;
import com.radiance.client.texture.AuxiliaryTextures;
import com.radiance.client.texture.TextureTracker;
import com.radiance.client.texture.TextureTracker.TextureTarget;
import com.radiance.mixin_related.extensions.vanilla_resource_tracker.INativeImageExt;
import java.util.List;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 26.3 builds atlases on the GPU: each sprite is uploaded into a scratch texture and drawn into the
 * atlas, and animations are drawn by a shader. The upload hook therefore never sees atlas positions,
 * so the PBR side textures are filled here instead, one region per sprite at its atlas position.
 *
 * <p>Animated sprites only get their first frame's PBR data, since their frames never pass through
 * the CPU after this point.
 */
@Mixin(TextureAtlas.class)
public abstract class SpriteAtlasTextureMixins extends AbstractTexture {

    @Shadow
    private List<TextureAtlasSprite> sprites;

    @WrapMethod(method = "uploadInitialContents")
    private void radiance$uploadAuxiliaryRegions(Operation<Void> original) {
        TextureProxy.beginUploadBatch();
        try {
            // The per-sprite scratch textures are throwaway, so their uploads must not allocate
            // auxiliary images of their own.
            TextureTracker.suppressUploadTracking(() -> original.call());

            TextureTarget atlasTarget = TextureTracker.targetOf(this.texture);
            if (atlasTarget == null) {
                return;
            }
            int atlasWidth = this.texture.getWidth(0);
            int atlasHeight = this.texture.getHeight(0);
            for (TextureAtlasSprite sprite : this.sprites) {
                SpriteContents contents = sprite.contents();
                NativeImage image = contents.byMipLevel[0];
                INativeImageExt imageExt = (INativeImageExt) (Object) image;
                imageExt.radiance$setTarget(atlasTarget);
                // 26.3 pads sprites in the atlas: getX()/getY() is the padded cell, while the
                // sprite's pixels (and its UVs) start at u0/v0.
                int x = Math.round(sprite.getU0() * atlasWidth);
                int y = Math.round(sprite.getV0() * atlasHeight);
                for (TextureProxy.TextureUploadRegion region : AuxiliaryTextures.buildUploadRegions(
                    image, imageExt, 0, x, y, 0, 0, contents.width(), contents.height())) {
                    TextureProxy.queueUploadRegion(region);
                }
            }
        } finally {
            TextureProxy.endUploadBatch();
        }
    }
}
