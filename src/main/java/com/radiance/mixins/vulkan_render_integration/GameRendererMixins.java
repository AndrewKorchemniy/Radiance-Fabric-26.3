package com.radiance.mixins.vulkan_render_integration;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.radiance.client.proxy.vulkan.RendererProxy;
import com.radiance.client.proxy.world.EntityProxy;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IGameRendererExt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixins implements IGameRendererExt {

    @Final
    @Shadow
    private Minecraft minecraft;
    @Final
    @Shadow
    private GameRenderState gameRenderState;
    @Unique
    private final Matrix4f radiance$viewMatrix = new Matrix4f();

    @Inject(method = "render()V", at = @At("HEAD"))
    private void radiance$shouldRenderWorld(CallbackInfo ci) {
        RendererProxy.shouldRenderWorld(this.gameRenderState.shouldRenderLevel
            && this.minecraft.isGameLoadFinished() && this.minecraft.level != null);
    }

    /**
     * Vanilla folds view bobbing and hurt shake into the projection (P * B). The ray tracer needs a
     * plain projection, so B moves into the view matrix instead (B * V) and the projection is left
     * alone. The portal and nausea warp applied afterwards stays in the projection, as on 1.21.4.
     */
    @WrapOperation(method = "renderLevel()V",
        at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;mul(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;",
            ordinal = 0, remap = false))
    private Matrix4f radiance$moveBobIntoView(Matrix4f projection, Matrix4fc bob,
        Operation<Matrix4f> original, @Local CameraRenderState cameraState) {
        this.radiance$viewMatrix.set(bob).mul(cameraState.viewRotationMatrix);
        return projection;
    }

    /**
     * After vanilla's level pass (which now only extracts and compiles, see
     * {@code LevelRendererFrameMixins}), build this frame's entities. The world itself is not drawn
     * into the main target: vanilla leaves it cleared to transparent and draws the 3D HUD and GUI
     * there, and native composites that over the ray-traced world when it presents (see
     * {@code VulkanGpuSurfaceMixins}).
     */
    @Inject(method = "renderLevel()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
    private void radiance$buildEntities(CallbackInfo ci, @Local CameraRenderState cameraState,
        @Local PlayerRenderState playerState) {
        // Radiance traces the first-person hand and held items as part of the world, so it is queued
        // here, before this frame's entity build, rather than in vanilla's hand pass (which runs later
        // and is skipped). The hand uses its own FOV; the ratio lets native draw it with the world's
        // projection.
        if (EntityProxy.isEntityNativeTransferEnabled()) {
            // m11 of a perspective projection is 1 / tan(fovY / 2).
            float worldTanHalfFov = 1.0F / cameraState.projectionMatrix.m11();
            float handTanHalfFov = (float) Math.tan(Math.toRadians(cameraState.hudFov * 0.5F));
            EntityProxy.queueHandRebuild(cameraState, playerState,
                worldTanHalfFov / handTanHalfFov);
        }
        EntityProxy.build();
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void radiance$skipRasterHand(CameraRenderState cameraState, PlayerRenderState playerState,
        GpuTextureView depthTextureView, CallbackInfo ci) {
        ci.cancel();
    }

    @Override
    public Matrix4f radiance$getRotationMatrix() {
        return this.radiance$viewMatrix;
    }
}
