package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.proxy.world.BlockEntitySectionCache;
import com.radiance.client.texture.RainExposure;
import com.radiance.client.vista.Vista;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What vanilla extracts for the world. The ray tracer sees more than the screen does, so entity
 * and block entity culling is loosened the way 1.21.4 loosened {@code collectVisibleEntities} and
 * walked every built section for block entities.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixins {

    @Inject(method = "setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V", at = @At("RETURN"))
    private void updateVistaWorld(@Nullable ClientLevel world, CallbackInfo ci) {
        Vista.setWorld(world);
        RainExposure.setWorld(world);
    }

    /** The player's own body is traced in first person too (shadows, reflections). */
    @Redirect(method = "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;isDetached()Z"))
    private boolean enablePlayerRendererInFirstPlayer(Camera instance) {
        return true;
    }

    /** Entities close to the camera are kept even outside the frustum. */
    @Redirect(method = "isEntityVisible(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDDFJ)Z",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDDF)Z"))
    private boolean loosenEntityFiltering(EntityRenderDispatcher instance, Entity entity,
        Frustum frustum, double x, double y, double z, float partialTicks) {
        double dx = entity.getX() - x;
        double dy = entity.getY() - y;
        double dz = entity.getZ() - z;
        if (dx * dx + dy * dy + dz * dz < 24.0 * 24.0) {
            return true;
        }
        return instance.shouldRender(entity, frustum, x, y, z, partialTicks);
    }

    /** Block entities of every built section, not just the visible ones. */
    @Redirect(method = "extractVisibleBlockEntities(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> allBlockEntitySections(
        LevelRenderer instance) {
        ViewArea viewArea = instance.viewArea();
        if (viewArea == null) {
            return instance.visibleSections();
        }
        return ObjectArrayList.wrap(BlockEntitySectionCache.sections(viewArea));
    }
}
