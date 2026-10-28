package com.radiance.mixins.vulkan_render_integration;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.radiance.client.gui.OrientedQuadRenderState;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IDrawContextExt;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(GuiGraphicsExtractor.class)
public class DrawContextMixins implements IDrawContextExt {

    @Final
    @Shadow
    private Matrix3x2fStack pose;

    @Final
    @Shadow
    private GuiRenderState guiRenderState;

    @Final
    @Shadow
    private GuiGraphicsExtractor.ScissorStack scissorStack;

    @Override
    public void radiance$drawOrientedQuad(RenderPipeline pipeline, float x1, float y1, float x2,
        float y2, float thickness, int color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.1f) {
            return;
        }

        float nx = -dy / len * (thickness / 2f);
        float ny = dx / len * (thickness / 2f);

        // GUI drawing is retained now: record the quad, GuiRenderer builds the vertices later
        guiRenderState.addGuiElement(new OrientedQuadRenderState(pipeline,
            new Matrix3x2f(pose),
            x1 + nx, y1 + ny,
            x2 + nx, y2 + ny,
            x2 - nx, y2 - ny,
            x1 - nx, y1 - ny,
            color, scissorStack.peek()));
    }
}
