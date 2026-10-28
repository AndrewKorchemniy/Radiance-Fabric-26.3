package com.radiance.mixins.mod_support.voxy.source;

import java.util.List;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TextureAtlas.class)
public interface TextureAtlasSpritesAccessor {
    @Accessor("sprites")
    List<TextureAtlasSprite> radiance$getSprites();
}
