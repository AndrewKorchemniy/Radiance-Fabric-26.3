package com.radiance.client.mod_support.sodium;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

/**
 * Sodium replaces the video settings screen that Radiance's settings live on, so with Sodium
 * installed they were unreachable. This adds a Radiance entry to Sodium's settings sidebar that
 * opens them: Radiance's video settings (upscaler, frame generation, ...) and, from there, the
 * render pipeline and shader pack settings.
 */
public final class SodiumConfigMenu implements ConfigEntryPoint {

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        String version = FabricLoader.getInstance().getModContainer("radiance")
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
            .orElse("");
        builder.registerModOptions("radiance", "Radiance", version)
            .addPage(builder.createExternalPage()
                .setName(Component.translatable("radiance.sodium.settings_page"))
                .setScreenConsumer(parent -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    minecraft.gui.setScreen(new VideoSettingsScreen(parent, minecraft, minecraft.options));
                }));
    }
}
