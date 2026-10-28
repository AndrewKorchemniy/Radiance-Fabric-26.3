package com.radiance.client.gui;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

/**
 * An untextured quad with arbitrary corners (given in order around the quad), recorded into the
 * retained-mode GUI render state. Used for the rotated wire segments in the render pipeline
 * editor.
 */
public record OrientedQuadRenderState(
    RenderPipeline pipeline,
    Matrix3x2fc pose,
    float x0, float y0,
    float x1, float y1,
    float x2, float y2,
    float x3, float y3,
    int color,
    @Nullable ScreenRectangle scissorArea,
    @Nullable ScreenRectangle bounds
) implements GuiElementRenderState {

    public OrientedQuadRenderState(RenderPipeline pipeline, Matrix3x2fc pose, float x0, float y0,
        float x1, float y1, float x2, float y2, float x3, float y3, int color,
        @Nullable ScreenRectangle scissorArea) {
        this(pipeline, pose, x0, y0, x1, y1, x2, y2, x3, y3, color, scissorArea,
            getBounds(pose, x0, y0, x1, y1, x2, y2, x3, y3, scissorArea));
    }

    @Override
    public void buildVertices(VertexConsumer vertexConsumer) {
        vertexConsumer.addVertexWith2DPose(pose, x0, y0).setColor(color);
        vertexConsumer.addVertexWith2DPose(pose, x1, y1).setColor(color);
        vertexConsumer.addVertexWith2DPose(pose, x2, y2).setColor(color);
        vertexConsumer.addVertexWith2DPose(pose, x3, y3).setColor(color);
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    private static @Nullable ScreenRectangle getBounds(Matrix3x2fc pose, float x0, float y0,
        float x1, float y1, float x2, float y2, float x3, float y3,
        @Nullable ScreenRectangle scissorArea) {
        int minX = Mth.floor(Math.min(Math.min(x0, x1), Math.min(x2, x3)));
        int minY = Mth.floor(Math.min(Math.min(y0, y1), Math.min(y2, y3)));
        int maxX = Mth.ceil(Math.max(Math.max(x0, x1), Math.max(x2, x3)));
        int maxY = Mth.ceil(Math.max(Math.max(y0, y1), Math.max(y2, y3)));
        ScreenRectangle bounds = new ScreenRectangle(minX, minY, Math.max(1, maxX - minX),
            Math.max(1, maxY - minY)).transformMaxBounds(pose);
        return scissorArea != null ? scissorArea.intersection(bounds) : bounds;
    }
}
