package com.radiance.mixins.vanilla_resource_tracker;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.Identifier;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Radiance looks up glyphs off the render thread while building text geometry, so font set lookups
 * from other threads must not race a resource reload. 26.3's {@code apply} invalidates its own font
 * provider caches, so only the locking is needed.
 */
@Mixin(FontManager.class)
public abstract class FontManagerMixins {

    @WrapMethod(method = "apply(Lnet/minecraft/client/gui/font/FontManager$Preparation;Lnet/minecraft/util/profiling/ProfilerFiller;)V")
    private void synchronizeFontReload(@Coerce Object preparation, ProfilerFiller profiler,
        Operation<Void> original) {
        synchronized (this) {
            original.call(preparation, profiler);
        }
    }

    @WrapMethod(method = "getFontSetRaw")
    private FontSet synchronizeBackgroundFontLookup(Identifier font,
        Operation<FontSet> original) {
        if (RenderSystem.isOnRenderThread()) {
            return original.call(font);
        }
        synchronized (this) {
            return original.call(font);
        }
    }
}
