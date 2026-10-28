package com.radiance.client.mod_support;

import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;

public class ModSupport {
    private static final String DISTANT_HORIZONS_VERSION = "3.3.4";
    private static final String VOXY_VERSION = "0.2.20-beta";

    public static final boolean DISTANT_HORIZONS = isVersion("distanthorizons", DISTANT_HORIZONS_VERSION);

    /** Voxy at the one version the mixins are written against. */
    public static final boolean VOXY = isVersion("voxy", VOXY_VERSION);

    /**
     * Voxy is the Vista source. Only one source can feed Vista, so Distant Horizons wins when both
     * are installed; Voxy's OpenGL renderer is switched off in that case too, since it can't run on
     * Radiance's Vulkan device.
     */
    public static final boolean VOXY_SOURCE = VOXY && !DISTANT_HORIZONS;

    private static boolean isVersion(String id, String version) {
        return FabricLoader.getInstance().getModContainer(id)
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString().equals(version))
            .orElse(false);
    }

    private static Optional<String> installedVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id)
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString());
    }

    public static void initialize() {
        var distantHorizons = installedVersion("distanthorizons");
        if (distantHorizons.isPresent() && !DISTANT_HORIZONS) {
            throw new IllegalStateException("Radiance Vista supports Distant Horizons "
                + DISTANT_HORIZONS_VERSION + " on Minecraft 26.3; found " + distantHorizons.get()
                + ". Install that official Distant Horizons release or remove Distant Horizons to run Radiance independently.");
        }
        var voxy = installedVersion("voxy");
        if (voxy.isPresent() && !VOXY) {
            throw new IllegalStateException("Radiance Vista supports Voxy " + VOXY_VERSION
                + " on Minecraft 26.3; found " + voxy.get()
                + ". Install that Voxy release or remove Voxy to run Radiance independently.");
        }
    }
}
