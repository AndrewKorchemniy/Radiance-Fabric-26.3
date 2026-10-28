package com.radiance.mixins.vulkan_render_integration;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.radiance.client.proxy.vulkan.BufferProxy;
import com.radiance.client.proxy.world.ChunkProxy;
import com.radiance.client.proxy.world.EntityProxy;
import com.radiance.client.proxy.world.PlayerProxy;
import com.radiance.client.texture.TextureTracker;
import com.radiance.client.vista.Vista;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IGameRendererExt;
import com.radiance.mixin_related.extensions.vulkan_render_integration.ILightMapManagerExt;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IOverlayTextureExt;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feeds the ray tracer from {@code LevelRenderer.render}. On 1.21.4 Radiance replaced
 * {@code renderLevel} outright; 26.3 extracts the frame first, so {@code render} keeps running (see
 * {@code LevelRendererFrameMixins}) and Radiance reads the extracted state: uniforms at its start,
 * entity-like geometry where {@code submitFeatures} would hand it to vanilla's feature renderers, and
 * terrain bookkeeping at its end.
 */
@Mixin(value = LevelRenderer.class, priority = 1100)
public abstract class WorldRendererMixins {

    @Unique
    private static final String RENDER =
        "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V";

    @Unique
    private static final String SUBMIT_FEATURES =
        "submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V";

    /** 26.3 stitches the sun into the celestial atlas; native samples the source texture. */
    @Unique
    private static final Identifier SUN_TEXTURE = Identifier.withDefaultNamespace(
        "textures/environment/celestial/sun.png");

    @Final
    @Shadow
    private GameRenderer gameRenderer;

    @Final
    @Shadow
    private EntityRenderDispatcher entityRenderDispatcher;

    @Final
    @Shadow
    private BlockEntityRenderDispatcher blockEntityRenderDispatcher;

    @Final
    @Shadow
    private LevelRenderState levelRenderState;

    @Final
    @Shadow
    private OptionsRenderState optionsRenderState;

    @Final
    @Shadow
    private CloudRenderer cloudRenderer;

    @Final
    @Shadow
    private WeatherEffectRenderer weatherEffectRenderer;

    @Shadow
    private @Nullable ViewArea viewArea;

    @Final
    @Shadow
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Final
    @Shadow
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> nearbyVisibleSections;

    @Inject(method = RENDER, at = @At("HEAD"))
    private void radiance$updateWorld(GraphicsResourceAllocator resourceAllocator,
        boolean renderOutline, CameraRenderState cameraState, GpuBufferSlice terrainFog,
        Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired,
        CallbackInfo ci) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        Camera camera = this.gameRenderer.mainCamera();
        float tickDelta = this.levelRenderState.worldPartialTicks;
        PlayerProxy.setCameraPos(cameraState.pos);

        Matrix4f viewMatrix = new Matrix4f(
            ((IGameRendererExt) this.gameRenderer).radiance$getRotationMatrix());
        Matrix4f effectedViewMatrix = new Matrix4f(cameraState.viewRotationMatrix);
        Matrix4f projectionMatrix = radiance$projectionMatrix(cameraState);
        Vista.render(camera, ((IGameRendererExt) this.gameRenderer).radiance$getRotationMatrix(),
            projectionMatrix, tickDelta);

        // Untracked textures come back as -1; native treats 0 as "no texture".
        int overlayTextureID = Math.max(TextureTracker.idOf(
            ((IOverlayTextureExt) this.gameRenderer.overlayTexture()).radiance$getTexture()), 0);
        int endSkyTextureID = Math.max(
            TextureTracker.idOf(AbstractEndPortalRenderer.END_SKY_LOCATION), 0);
        int endPortalTextureID = Math.max(
            TextureTracker.idOf(AbstractEndPortalRenderer.END_PORTAL_LOCATION), 0);
        ILightMapManagerExt lightMapManagerExt =
            (ILightMapManagerExt) ((GameRendererLightmapAccessor) this.gameRenderer).radiance$getLightmap();
        float gameTime = ((float) (this.levelRenderState.gameTime % 24000L) + tickDelta) / 24000.0F;
        BufferProxy.updateWorldUniform(camera, viewMatrix, effectedViewMatrix, projectionMatrix,
            overlayTextureID, cameraState.fogData, level, gameTime, endSkyTextureID,
            endPortalTextureID, lightMapManagerExt.radiance$getTextureId());

        SkyRenderState skyState = this.levelRenderState.skyRenderState;
        Vector3fc baseColor = skyState.skyColor != null ? skyState.skyColor : new Vector3f();
        Vector4fc horizonColor = skyState.sunriseAndSunsetColor != null
            ? skyState.sunriseAndSunsetColor : new Vector4f();

        PoseStack matrixStack = new PoseStack();
        matrixStack.pushPose();
        matrixStack.rotateDegrees(Axis.YP, -90.0F);
        matrixStack.rotate(Axis.XP, skyState.sunAngle);
        Vector3f sunDirection = matrixStack.last()
            .pose()
            .transformPosition(0, 1, 0, new Vector3f())
            .normalize();
        matrixStack.popPose();

        // 1.21.4's FogType had no ATMOSPHERIC; native counts LAVA, WATER, POWDER_SNOW, NONE.
        int submersionType = switch (cameraState.fogType) {
            case LAVA -> 0;
            case WATER -> 1;
            case POWDER_SNOW -> 2;
            default -> 3;
        };

        int sunTextureID = Math.max(TextureTracker.idOf(SUN_TEXTURE), 0);
        // 1.21.4 had one moon_phases.png with all phases; 26.3 keeps each phase in its own texture.
        int moonTextureID = Math.max(TextureTracker.idOf(Identifier.withDefaultNamespace(
            "textures/environment/celestial/moon/" + skyState.moonPhase.getSerializedName()
                + ".png")), 0);

        BufferProxy.updateSkyUniform(baseColor.x(), baseColor.y(), baseColor.z(),
            horizonColor.x(), horizonColor.y(), horizonColor.z(), horizonColor.w(), sunDirection,
            skyState.skybox.ordinal(), horizonColor.w() > 0.001F, skyState.shouldRenderDarkDisc,
            cameraState.entityRenderState.doesMobEffectBlockSky, submersionType,
            skyState.moonPhase.index(), level.getRainLevel(tickDelta), sunTextureID, moonTextureID);

        BufferProxy.updateMapping();

        EntityProxy.queueWeatherBuild(this.weatherEffectRenderer,
            this.levelRenderState.weatherRenderState, cameraState.pos);

        // Vanilla prepares clouds inside the skipped main pass; CloudRendererMixins turns the call
        // into Radiance geometry.
        CloudStatus cloudStatus = this.optionsRenderState.cloudStatus;
        if (cloudStatus != CloudStatus.OFF && ARGB.alpha(this.levelRenderState.cloudColor) > 0) {
            this.cloudRenderer.prepare(this.levelRenderState.cloudColor, cloudStatus,
                this.levelRenderState.cloudHeight, this.optionsRenderState.cloudRange,
                cameraState.pos, this.levelRenderState.gameTime, tickDelta);
        }
    }

    /**
     * The projection native traces with: vanilla's, plus the portal and nausea warp, but without view
     * bobbing, which {@code GameRendererMixins} moved into the view matrix.
     */
    @Unique
    private Matrix4f radiance$projectionMatrix(CameraRenderState cameraState) {
        Matrix4f projectionMatrix = new Matrix4f(cameraState.projectionMatrix);
        PlayerRenderState playerState = this.levelRenderState.playerRenderState;
        float screenEffectScale = this.optionsRenderState.screenEffectScale;
        float spinningEffectIntensity = Math.max(playerState.portalEffectIntensity,
            playerState.nauseaEffectIntensity) * (screenEffectScale * screenEffectScale);
        if (spinningEffectIntensity > 0.0F) {
            float skew = 5.0F / (spinningEffectIntensity * spinningEffectIntensity + 5.0F)
                - spinningEffectIntensity * 0.04F;
            skew *= skew;
            Vector3f axis = new Vector3f(0.0F, Mth.SQRT_OF_TWO / 2.0F, Mth.SQRT_OF_TWO / 2.0F);
            float angle = playerState.spinningEffectAngle * (float) (Math.PI / 180.0);
            projectionMatrix.rotate(angle, axis);
            projectionMatrix.scale(1.0F / skew, 1.0F, 1.0F);
            projectionMatrix.rotate(-angle, axis);
        }
        return projectionMatrix;
    }

    @WrapOperation(method = SUBMIT_FEATURES, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;submitEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"))
    private void radiance$traceEntities(LevelRenderer instance, PoseStack poseStack,
        LevelRenderState levelRenderState, SubmitNodeCollector output, Operation<Void> original) {
        EntityProxy.queueEntitiesBuild(levelRenderState, this.entityRenderDispatcher);
    }

    @WrapOperation(method = SUBMIT_FEATURES, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;submitBlockEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"))
    private void radiance$traceBlockEntities(LevelRenderer instance, PoseStack poseStack,
        LevelRenderState levelRenderState, SubmitNodeCollector output, Operation<Void> original) {
        EntityProxy.queueBlockEntitiesRebuild(levelRenderState, this.blockEntityRenderDispatcher);
    }

    /** Block crumbling is traced together with block entities, above. */
    @WrapOperation(method = SUBMIT_FEATURES, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;submitBlockDestroyAnimation(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"))
    private void radiance$skipBlockDestroyAnimation(LevelRenderer instance, PoseStack poseStack,
        SubmitNodeCollector output, LevelRenderState levelRenderState, Operation<Void> original) {
    }

    @WrapOperation(method = SUBMIT_FEATURES, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;submitTransientBlocks(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"))
    private void radiance$traceTransientBlocks(LevelRenderer instance, PoseStack poseStack,
        SubmitNodeCollector output, LevelRenderState levelRenderState, Operation<Void> original) {
        EntityProxy.queueCameraRelativeBuild(
            collector -> original.call(instance, poseStack, collector, levelRenderState));
    }

    @WrapOperation(method = SUBMIT_FEATURES, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private void radiance$traceParticles(ParticlesRenderState instance, SubmitNodeCollector output,
        CameraRenderState camera, Operation<Void> original) {
        EntityProxy.queueParticleRebuild(instance, camera);
    }

    @WrapOperation(method = SUBMIT_FEATURES, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;submitBlockOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"))
    private void radiance$traceBlockOutline(LevelRenderer instance, PoseStack poseStack,
        SubmitNodeCollector output, LevelRenderState levelRenderState, Operation<Void> original) {
        EntityProxy.queueTargetBlockOutlineRebuild(levelRenderState.blockOutlineRenderState,
            this.gameRenderer.useImprovedTransparency(),
            this.gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth);
    }

    /** After vanilla compiled sections and updated occlusion, as 1.21.4 did at the end of its frame. */
    @Inject(method = RENDER, at = @At("TAIL"))
    private void radiance$rebuildChunks(GraphicsResourceAllocator resourceAllocator,
        boolean renderOutline, CameraRenderState cameraState, GpuBufferSlice terrainFog,
        Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired,
        CallbackInfo ci) {
        if (this.viewArea == null) {
            return;
        }
        ChunkProxy.setStorage(this.viewArea);
        ChunkProxy.rebuild(this.gameRenderer.mainCamera(), this.visibleSections,
            this.nearbyVisibleSections);
    }

    /**
     * A section only counts as compiled once its terrain is in the ray tracer, so entities in it and
     * the loading screen wait for native, as 1.21.4's {@code isSectionCompiled} override did.
     */
    @Inject(method = "isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;J)Z",
        at = @At("HEAD"), cancellable = true)
    private void radiance$waitForNativeSection(BlockPos blockPos, long chunkFadeDuration,
        CallbackInfoReturnable<Boolean> cir) {
        if (this.viewArea == null) {
            return;
        }
        SectionRenderDispatcher.RenderSection section = this.viewArea.getRenderSectionAt(blockPos);
        if (section != null && !ChunkProxy.isSectionReady(section)) {
            cir.setReturnValue(false);
        }
    }
}
