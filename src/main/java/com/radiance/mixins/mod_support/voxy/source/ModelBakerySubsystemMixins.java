package com.radiance.mixins.mod_support.voxy.source;

import com.radiance.client.UnsafeManager;
import me.cortex.voxy.client.core.model.ModelStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The bakery owns a {@code ModelStore}, which is a set of OpenGL buffers and a texture. Radiance
 * keeps the baked models on the CPU instead, so the store is an empty shell whose constructor
 * (the OpenGL calls) never runs. Nothing reads it: uploads are taken off the factory's queue.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.model.ModelBakerySubsystem", remap = false)
public class ModelBakerySubsystemMixins {
    @Redirect(method = "<init>", at = @At(value = "NEW",
        target = "Lme/cortex/voxy/client/core/model/ModelStore;"), require = 1)
    private ModelStore skipModelStore() {
        return UnsafeManager.INSTANCE.allocateInstance(ModelStore.class);
    }

    @Redirect(method = "shutdown", at = @At(value = "INVOKE",
        target = "Lme/cortex/voxy/client/core/model/ModelStore;free()V"), require = 1)
    private void skipModelStoreFree(ModelStore store) { }
}
