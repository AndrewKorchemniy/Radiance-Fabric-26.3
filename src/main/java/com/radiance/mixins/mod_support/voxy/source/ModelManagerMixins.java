package com.radiance.mixins.mod_support.voxy.source;

import com.radiance.client.vista.Vista;
import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Resource reloads change every baked model, so Voxy's models are baked again. */
@Mixin(ModelManager.class)
public class ModelManagerMixins {
    @Inject(method = "apply", at = @At("TAIL"))
    private void reloadVista(CallbackInfo ci) { Vista.reload(); }
}
