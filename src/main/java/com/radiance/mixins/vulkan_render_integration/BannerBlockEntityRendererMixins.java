package com.radiance.mixins.vulkan_render_integration;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.world.item.DyeColor;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Banner and shield pattern layers are coplanar, which vanilla resolves with draw order. The ray
 * tracer needs real depth, so each layer is pushed slightly off the canvas, and banners skip the
 * bare canvas under the base pattern layer, as on 1.21.4.
 */
@Mixin(BannerRenderer.class)
public class BannerBlockEntityRendererMixins {

    @Unique
    private static final float radiance$layerDepthStep = 0.002F;

    @WrapOperation(method = "submitBanner",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;IIILnet/minecraft/client/resources/model/sprite/SpriteId;Lnet/minecraft/client/resources/model/sprite/SpriteGetter;I)V",
            ordinal = 1))
    private static void cancelSolidRender(SubmitNodeCollector collector, Model<?> model,
        Object state, PoseStack poseStack, int lightCoords, int overlayCoords, int tintedColor,
        SpriteId sprite, SpriteGetter sprites, int outlineColor, Operation<Void> original) {
    }

    @WrapOperation(method = "submitPatterns",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BannerRenderer;submitPatternLayer(Lnet/minecraft/client/resources/model/sprite/SpriteGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;IILnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/client/resources/model/sprite/SpriteId;Lnet/minecraft/world/item/DyeColor;)V",
            ordinal = 0))
    private static void expandBaseLayer(SpriteGetter sprites, PoseStack poseStack,
        OrderedSubmitNodeCollector collector, int lightCoords, int overlayCoords, Model<?> model,
        Object state, SpriteId sprite, DyeColor color, Operation<Void> original) {
        radiance$pushCanvasLayer(poseStack, model, state, 1);
        original.call(sprites, poseStack, collector, lightCoords, overlayCoords, model, state, sprite,
            color);
        poseStack.popPose();
    }

    @WrapOperation(method = "submitPatterns",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BannerRenderer;submitPatternLayer(Lnet/minecraft/client/resources/model/sprite/SpriteGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;IILnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/client/resources/model/sprite/SpriteId;Lnet/minecraft/world/item/DyeColor;)V",
            ordinal = 1))
    private static void expandPatternLayer(SpriteGetter sprites, PoseStack poseStack,
        OrderedSubmitNodeCollector collector, int lightCoords, int overlayCoords, Model<?> model,
        Object state, SpriteId sprite, DyeColor color, Operation<Void> original,
        @Local(ordinal = 2) int maskIndex) {
        radiance$pushCanvasLayer(poseStack, model, state, maskIndex + 2);
        original.call(sprites, poseStack, collector, lightCoords, overlayCoords, model, state, sprite,
            color);
        poseStack.popPose();
    }

    /** Offsets along the canvas normal; the canvas is the banner's flag or the shield's plate. */
    @Unique
    @SuppressWarnings("unchecked")
    private static void radiance$pushCanvasLayer(PoseStack matrices, Model<?> model, Object state,
        int depthIndex) {
        matrices.pushPose();
        // The flag swings in setupAnim, which vanilla only runs when tessellating.
        ((Model<Object>) model).setupAnim(state);
        ModelPart root = model.root();
        ModelPart canvas = root.hasChild("flag") ? root.getChild("flag") : root;
        Vector3f offset = new Vector3f(0.0F, 0.0F, -radiance$layerDepthStep * depthIndex);
        new Quaternionf().rotationZYX(canvas.zRot, canvas.yRot, canvas.xRot)
            .transform(offset);
        matrices.translate(offset.x, offset.y, offset.z);
    }
}
