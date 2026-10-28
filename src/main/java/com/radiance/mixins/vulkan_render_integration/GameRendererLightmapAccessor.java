package com.radiance.mixins.vulkan_render_integration;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Lightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The level lightmap, whose inputs Radiance reads (see {@code LightmapTextureManagerMixins}). */
@Mixin(GameRenderer.class)
public interface GameRendererLightmapAccessor {

    @Accessor("lightmap")
    Lightmap radiance$getLightmap();
}
