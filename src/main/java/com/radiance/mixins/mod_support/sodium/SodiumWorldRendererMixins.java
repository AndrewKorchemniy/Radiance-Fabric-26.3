package com.radiance.mixins.mod_support.sodium;

import com.mojang.blaze3d.vertex.PoseStack;
import com.radiance.client.mod_support.sodium.SodiumBlockEntities;
import com.radiance.client.mod_support.sodium.SodiumTerrain;
import com.radiance.client.proxy.world.ChunkProxy;
import com.radiance.mixin_related.extensions.mod_support.sodium.IRenderSectionManagerExt;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.SortedSet;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium's per-level renderer: where its terrain is set up each frame (from
 * {@code LevelExtractor.extract}, so before the frame graph that Radiance skips), recreated and
 * torn down. Radiance follows all three, and replaces what Sodium extracts for the ray tracer:
 * block entities of every built section and entities regardless of Sodium's occlusion culling.
 */
@Mixin(SodiumWorldRenderer.class)
public abstract class SodiumWorldRendererMixins {

    @Shadow
    private ClientLevel level;

    @Shadow
    private int renderDistance;

    @Shadow
    private RenderSectionManager renderSectionManager;

    @Shadow
    private void extractBlockEntity(BlockEntity blockEntity, PoseStack poseStack, Camera camera,
        float tickDelta, Long2ObjectMap<SortedSet<BlockDestructionProgress>> progression,
        LevelRenderState levelRenderState, boolean global) {
        throw new AssertionError();
    }

    /** Sodium (re)created its section manager (level load, render distance change, reload). */
    @Inject(method = "initRenderer()V", at = @At("RETURN"))
    private void radiance$initTerrain(CallbackInfo ci) {
        SodiumTerrain.onRendererInit(this.level, this.renderDistance,
            (IRenderSectionManagerExt) this.renderSectionManager);
    }

    /** {@code deleteRendererState} stops the chunk builder's workers, then destroys the manager. */
    @Inject(method = "deleteRendererState()V", at = @At("RETURN"))
    private void radiance$releaseTerrain(CallbackInfo ci) {
        SodiumTerrain.onRendererDestroy();
    }

    /** First thing in Sodium's per-frame terrain update: move native's section ring to the camera. */
    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void radiance$beginFrame(Camera camera, Viewport viewport,
        FogParameters fogParameters, boolean useOcclusionCulling,
        boolean updateChunksImmediately, Matrix4f cullMatrix, CallbackInfo ci) {
        if (this.renderSectionManager != null) {
            SodiumTerrain.beginFrame(camera.position(),
                (IRenderSectionManagerExt) this.renderSectionManager);
        }
    }

    /**
     * Build sections that are hidden from the camera too: the ray tracer needs them. (The first
     * boolean argument is {@code useOcclusionCulling}.)
     */
    @ModifyVariable(method = "setupTerrain", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean radiance$keepHiddenSections(boolean useOcclusionCulling) {
        return SodiumTerrain.OCCLUSION_CULLING && useOcclusionCulling;
    }

    /** A section counts as ready for entities and the loading screen once native has its terrain. */
    @Inject(method = "isSectionReady(III)Z", at = @At("RETURN"), cancellable = true)
    private void radiance$waitForNative(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && !ChunkProxy.sodiumSectionReady(x, y, z)) {
            cir.setReturnValue(false);
        }
    }

    /** Entities are only frustum-culled, like with vanilla terrain: the ray tracer sees behind walls. */
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private <T extends Entity, S extends EntityRenderState>
    void radiance$allEntitiesVisible(EntityRenderer<T, S> renderer, T entity, float partialTicks,
        CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }

    /** The block entities of every built section, not just the visible ones. */
    @Inject(method = "extractBlockEntities", at = @At("HEAD"), cancellable = true)
    private void radiance$extractAllBlockEntities(Camera camera, float tickDelta,
        Long2ObjectMap<SortedSet<BlockDestructionProgress>> progression,
        LevelRenderState levelRenderState, CallbackInfo ci) {
        ci.cancel();
        PoseStack poseStack = new PoseStack();
        SodiumBlockEntities.forEach((blockEntity, global) -> this.extractBlockEntity(blockEntity,
            poseStack, camera, tickDelta, progression, levelRenderState, global));
    }
}
