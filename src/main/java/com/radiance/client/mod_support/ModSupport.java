package com.radiance.client.mod_support;

import net.fabricmc.loader.api.FabricLoader;

public class ModSupport {
    public static final boolean DISTANT_HORIZONS = FabricLoader.getInstance()
        .getModContainer("distanthorizons")
        .map(mod -> mod.getMetadata().getVersion().getFriendlyString().equals("3.3.4"))
        .orElse(false);

    public static void initialize() {
        var distantHorizons = FabricLoader.getInstance().getModContainer("distanthorizons");
        if (distantHorizons.isPresent() && !DISTANT_HORIZONS) {
            throw new IllegalStateException("Radiance Vista supports Distant Horizons 3.3.4 on Minecraft 26.3; found "
                + distantHorizons.get().getMetadata().getVersion().getFriendlyString()
                + ". Install that official Distant Horizons release or remove Distant Horizons to run Radiance independently.");
        }
    }
}
