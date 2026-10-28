package com.radiance.client.mod_support.sodium;

import com.mojang.blaze3d.vertex.MeshData;
import com.radiance.client.proxy.world.ChunkProxy;
import com.radiance.client.proxy.world.TerrainMesher;
import com.radiance.mixin_related.extensions.mod_support.sodium.IRenderSectionManagerExt;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Near terrain capture for Sodium.
 *
 * <p>Sodium replaces vanilla's section management: sections live in its own {@code RenderSectionManager},
 * are meshed by its own chunk builder and are drawn by its own renderer, none of which the vanilla
 * terrain mixins ever see. Instead of Sodium's mesh, Radiance wants its 40-byte PBR vertices, so the
 * chunk builder's tasks are extended: right after Sodium meshed a section on its worker, the same
 * worker meshes it again with {@link TerrainMesher} from the very same block snapshot
 * ({@code LevelSlice}) and publishes the result to native through {@link ChunkProxy}. Sodium's own
 * mesh is still produced and uploaded (it is simply never drawn, as the frame graph is skipped).
 *
 * <p>Native addresses sections in a fixed ring around the camera, which Sodium does not have, so
 * {@link ChunkProxy} keeps that ring; this class feeds it from Sodium's section lifecycle:
 * <ul>
 *   <li>{@code setupTerrain} (main thread, every frame, before Sodium schedules anything):
 *       {@link #beginFrame} moves the ring to the camera's section;</li>
 *   <li>sections added/removed/empty: {@code RenderSectionManagerMixins};</li>
 *   <li>mesh results: {@link #onSectionMeshed} on the worker;</li>
 *   <li>block entities: {@link SodiumBlockEntities}.</li>
 * </ul>
 * Sections that enter the ring (the camera moved) need a mesh Sodium already made while they were
 * outside of it, so they are rebuilt through Sodium's own {@code scheduleRebuild}.
 */
public final class SodiumTerrain {

    private static final Logger LOGGER = LoggerFactory.getLogger("Radiance/Sodium");

    /**
     * Radiance wants the sections behind walls and under the ground too (reflections, shadows,
     * bounce lighting), so Sodium's occlusion culling is switched off unless
     * {@code -Dradiance.sodium.occlusionCulling=true}. Sodium then builds every section in range.
     */
    public static final boolean OCCLUSION_CULLING = Boolean.getBoolean(
        "radiance.sodium.occlusionCulling");

    private static final double IMPORTANT_DISTANCE_SQUARED = 768.0;
    // Sections whose mesh was dropped (outside the ring when it finished) wait for Sodium to mark
    // them built, then get rebuilt; give up after this many frames.
    private static final int DROPPED_RETRY_FRAMES = 600;

    private static boolean centerKnown;
    private static int centerX;
    private static int centerY;
    private static int centerZ;
    private static volatile double cameraX;
    private static volatile double cameraY;
    private static volatile double cameraZ;

    private static final ConcurrentLinkedQueue<Long> DROPPED_QUEUE = new ConcurrentLinkedQueue<>();
    private static final Long2IntOpenHashMap DROPPED = new Long2IntOpenHashMap();

    // One 9 MiB pack of vertex builders per Sodium worker (like vanilla's per-worker packs).
    // Packs are tied to an epoch so a reload can close all of them at once.
    private static final ConcurrentLinkedQueue<SectionBufferBuilderPack> ALL_PACKS = new ConcurrentLinkedQueue<>();
    private static final ThreadLocal<PackHolder> PACKS = new ThreadLocal<>();
    private static volatile int packEpoch;

    private SodiumTerrain() {
    }

    private record PackHolder(int epoch, SectionBufferBuilderPack pack) {
    }

    // ---- Lifecycle (main thread) --------------------------------------------------------------

    /** Sodium (re)created its section manager: size native's grid for it. */
    public static void onRendererInit(ClientLevel level, int renderDistance,
        IRenderSectionManagerExt manager) {
        ChunkProxy.sodiumInit(renderDistance, level.getMinSectionY(), level.getMaxSectionY());
        // A reload in the middle of a session keeps the camera, so the ring can be placed at once;
        // otherwise (joining a world) the first frame places it, before any task runs.
        if (centerKnown) {
            reposition(manager);
        }
    }

    /** Sodium tore its section manager down (workers are stopped by now). */
    public static void onRendererDestroy() {
        packEpoch++;
        SectionBufferBuilderPack pack;
        while ((pack = ALL_PACKS.poll()) != null) {
            pack.close();
        }
        DROPPED_QUEUE.clear();
        DROPPED.clear();
        SodiumBlockEntities.clear();
        ChunkProxy.clear();
    }

    /** Start of {@code SodiumWorldRenderer.setupTerrain}: before Sodium reads chunk events or schedules builds. */
    public static void beginFrame(Vec3 cameraPos, IRenderSectionManagerExt manager) {
        cameraX = cameraPos.x;
        cameraY = cameraPos.y;
        cameraZ = cameraPos.z;
        SectionPos section = SectionPos.of(cameraPos);
        centerKnown = true;
        centerX = section.x();
        centerY = section.y();
        centerZ = section.z();
        reposition(manager);
        retryDropped(manager);
    }

    private static void reposition(IRenderSectionManagerExt manager) {
        for (long node : ChunkProxy.sodiumReposition(centerX, centerY, centerZ)) {
            manager.radiance$resync(SectionPos.x(node), SectionPos.y(node), SectionPos.z(node),
                false);
        }
    }

    private static void retryDropped(IRenderSectionManagerExt manager) {
        Long node;
        while ((node = DROPPED_QUEUE.poll()) != null) {
            DROPPED.putIfAbsent(node.longValue(), 0);
        }
        if (DROPPED.isEmpty()) {
            return;
        }
        LongIterator iterator = DROPPED.keySet().iterator();
        while (iterator.hasNext()) {
            long sectionNode = iterator.nextLong();
            int x = SectionPos.x(sectionNode);
            int y = SectionPos.y(sectionNode);
            int z = SectionPos.z(sectionNode);
            // Out of the ring: if it comes back, entering it triggers a resync anyway.
            if (!ChunkProxy.sodiumInWindow(x, y, z)
                || manager.radiance$resync(x, y, z, true)) {
                iterator.remove();
                continue;
            }
            int age = DROPPED.get(sectionNode) + 1;
            if (age > DROPPED_RETRY_FRAMES) {
                iterator.remove();
            } else {
                DROPPED.put(sectionNode, age);
            }
        }
    }

    /** Recompiles every section in the ring (e.g. after chunk emission was toggled). */
    public static void rebuildAll() {
        int[] window = ChunkProxy.sodiumWindow();
        SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
        if (window == null || renderer == null) {
            return;
        }
        renderer.scheduleRebuildForChunks(window[0], ChunkProxy.sodiumMinSectionY(), window[1],
            window[2], ChunkProxy.sodiumMaxSectionY(), window[3], false);
    }

    // ---- Chunk builder worker -----------------------------------------------------------------

    /**
     * Runs on a Sodium worker (or the main thread, for tasks it steals) right after Sodium built
     * {@code output}; {@code context.cache}'s block snapshot is still the section's.
     */
    public static void onSectionMeshed(ChunkBuildContext context, ChunkBuildOutput output,
        CancellationToken cancellation) {
        SectionPos pos = output.section.getPosition();
        long generation = ChunkProxy.sodiumBegin(pos.x(), pos.y(), pos.z());
        if (generation < 0) {
            DROPPED_QUEUE.add(pos.asLong());
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        SectionBufferBuilderPack pack = pack();
        LevelSlice slice = context.cache.getWorldSlice();
        try {
            Map<ChunkSectionLayer, MeshData> layers;
            try {
                layers = TerrainMesher.mesh(pos, slice, pack,
                    minecraft.getModelManager().getBlockStateModelSet(),
                    minecraft.getModelManager().getFluidStateModelSet(),
                    minecraft.getBlockColors(), minecraft.options.cutoutLeaves().get(), null,
                    null);
            } catch (RuntimeException e) {
                // Don't take Sodium's worker (and the game) down for a block Radiance can't mesh.
                LOGGER.error("Could not mesh section {} for the ray tracer", pos, e);
                return;
            }

            double dx = pos.minBlockX() + 8 - cameraX;
            double dy = pos.minBlockY() + 8 - cameraY;
            double dz = pos.minBlockZ() + 8 - cameraZ;
            // Sodium's blocking tasks are the ones it wants on screen this frame (player edits, nearby).
            boolean important = output.blockingTask
                || dx * dx + dy * dy + dz * dz < IMPORTANT_DISTANCE_SQUARED;
            ChunkProxy.sodiumPublish(pos, generation, slice, layers, pack,
                cancellation::isCancelled, important);
        } finally {
            pack.clearAll();
        }
    }

    private static SectionBufferBuilderPack pack() {
        PackHolder holder = PACKS.get();
        int epoch = packEpoch;
        if (holder == null || holder.epoch() != epoch) {
            holder = new PackHolder(epoch, new SectionBufferBuilderPack());
            ALL_PACKS.add(holder.pack());
            PACKS.set(holder);
        }
        return holder.pack();
    }
}
