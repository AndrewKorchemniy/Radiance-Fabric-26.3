package com.radiance.mixin_related.extensions.vulkan_render_integration;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;

public interface IDrawContextExt {

    void radiance$drawOrientedQuad(RenderPipeline pipeline, float x1, float y1, float x2, float y2,
        float thickness, int color);
}
