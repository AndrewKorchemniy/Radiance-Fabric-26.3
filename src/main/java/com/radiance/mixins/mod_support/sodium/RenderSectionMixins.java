package com.radiance.mixins.mod_support.sodium;

import com.radiance.client.mod_support.sodium.SodiumBlockEntities;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderSection.class)
public abstract class RenderSectionMixins {

    /** The main-thread moment a build result (or its removal, with null) takes effect. */
    @Inject(method = "setInfo(Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;)I",
        at = @At("RETURN"))
    private void radiance$trackBlockEntities(BuiltSectionInfo info,
        CallbackInfoReturnable<Integer> cir) {
        SodiumBlockEntities.set(((RenderSection) (Object) this).getPosition().asLong(), info);
    }
}
