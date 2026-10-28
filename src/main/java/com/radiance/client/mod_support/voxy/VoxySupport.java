package com.radiance.client.mod_support.voxy;

import com.radiance.client.mod_support.ModSupport;
import com.radiance.client.proxy.world.VistaProxy;
import com.radiance.client.vista.Vista;
import com.radiance.client.vista.VistaTileAtlas;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Voxy as a source of far terrain for Vista.
 *
 * <p>Voxy itself is left to ingest chunks, store its section pyramid and mesh sections; its
 * OpenGL renderer is never created (see the mixins in {@code mod_support.voxy}). This class takes
 * the place of that renderer: it picks which sections to show and sends their meshes to native.
 */
public class VoxySupport implements Vista.Source {
    private static final Logger LOGGER = LoggerFactory.getLogger("Radiance/Voxy");
    private static boolean initialized;

    private final VistaTileAtlas atlas = new VistaTileAtlas();
    private ClientLevel level;
    private VoxyWorld world;
    private long worldVersion;
    private boolean reloadPending;
    private boolean wasActive;
    private long retryAtNanos;

    /** Called in place of Voxy's own OpenGL setup, once the renderer exists. */
    public static synchronized void initialize() {
        if (initialized) { return; }
        initialized = true;
        if (!ModSupport.VOXY_SOURCE) {
            LOGGER.warn("Voxy is installed together with Distant Horizons; Radiance draws Distant "
                + "Horizons' terrain and leaves Voxy switched off");
            return;
        }
        VoxyCommon.setInstanceFactory(VoxyClientInstance::new);
        Vista.setSource(new VoxySupport());
        LOGGER.info("Voxy far terrain enabled");
    }

    private static boolean enabled() {
        return VoxyConfig.CONFIG.enabled && VoxyConfig.CONFIG.enableRendering;
    }

    @Override
    public int distanceBlocks() {
        return world != null && enabled() ? (int) (VoxyConfig.CONFIG.sectionRenderDistance * 512) : 0;
    }

    @Override public int textureId() { return atlas.id(); }

    @Override
    public int[] sideMaterials(BlockState state) {
        VoxyWorld current = world;
        return current == null ? new int[4] : current.sideMaterials(state);
    }

    @Override
    public void reload() { reloadPending = true; }

    @Override
    public void setWorld(ClientLevel level) {
        if (this.level == level) { return; }
        this.level = level;
        closeWorld();
    }

    @Override
    public void render(Camera camera, Matrix4f view, Matrix4f projection, float tickDelta) {
        boolean active = enabled();
        if (reloadPending) {
            reloadPending = false;
            closeWorld();
            atlas.reset();
        }
        if (!active) {
            if (wasActive) { closeWorld(); }
            wasActive = false;
            return;
        }
        wasActive = true;
        if (world == null && !openWorld()) { return; }
        var position = camera.position();
        world.tick(position.x, position.y, position.z);
        atlas.update();
    }

    private boolean openWorld() {
        if (level == null || System.nanoTime() < retryAtNanos) { return false; }
        VoxyInstance instance = VoxyCommon.getInstance();
        if (instance == null) { return false; }
        try {
            WorldEngine engine = WorldIdentifier.of(level).getOrCreateEngine(true);
            VistaProxy.reset(++worldVersion);
            world = new VoxyWorld(level, engine, instance, atlas, worldVersion,
                VoxyConfig.CONFIG.sectionRenderDistance);
            return true;
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to start Voxy far terrain; retrying in 10 seconds", exception);
            retryAtNanos = System.nanoTime() + 10_000_000_000L;
            return false;
        }
    }

    private void closeWorld() {
        if (world == null) { return; }
        VoxyWorld closing = world;
        world = null;
        closing.close();
        VistaProxy.reset(++worldVersion);
    }
}
