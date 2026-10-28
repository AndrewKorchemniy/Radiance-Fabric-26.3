package com.radiance.mixins.vulkan_render_integration;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderType.class)
public interface RenderTypeAccessor {

    @Accessor("name")
    String radiance$getName();

    @Accessor("state")
    RenderSetup radiance$getState();
}
