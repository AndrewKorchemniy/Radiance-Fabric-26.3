package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.proxy.world.EntityProxy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixins {

    /** Render states lose their entity; record what the ray tracer needs while it is still known. */
    @Inject(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
        at = @At("RETURN"))
    private void radiance$tagRenderState(Entity entity, float partialTicks,
        CallbackInfoReturnable<EntityRenderState> cir) {
        EntityProxy.tagEntityRenderState(entity, cir.getReturnValue());
    }

    /**
     * The ray tracer casts real shadows, so vanilla's blob shadows are not even extracted (1.21.4
     * cancelled {@code renderShadow}).
     */
    @Inject(method = "extractShadow(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/level/Level;)V",
        at = @At("HEAD"), cancellable = true)
    private void radiance$cancelShadow(EntityRenderState state, Minecraft minecraft, Level level,
        CallbackInfo ci) {
        state.shadowPieces.clear();
        ci.cancel();
    }
}
