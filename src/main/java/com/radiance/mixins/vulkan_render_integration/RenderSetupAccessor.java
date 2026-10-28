package com.radiance.mixins.vulkan_render_integration;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.Map;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {

    @Accessor("pipeline")
    RenderPipeline radiance$getPipeline();

    /**
     * Sampler name ("Sampler0", ...) to binding. The values are the package-private
     * {@code RenderSetup.TextureBinding}; read them through {@link TextureBindingAccessor}.
     */
    @Accessor("textures")
    Map<String, Object> radiance$getTextures();

    @Accessor("useLightmap")
    boolean radiance$usesLightmap();

    @Accessor("useOverlay")
    boolean radiance$usesOverlay();
}
