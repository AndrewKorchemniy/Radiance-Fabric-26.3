package com.radiance.client.mod_support.voxy;

import com.radiance.client.proxy.world.VistaProxy;
import com.radiance.client.vista.VistaTerrain;
import com.radiance.client.vista.VistaTileAtlas;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.commonImpl.VoxyInstance;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Far terrain from one Voxy world (one dimension of one save or server).
 *
 * <p>Voxy keeps a pyramid of 32x32x32 sections: level 0 has one voxel per block and each level up
 * doubles the voxel size, to level 4. Its renderer picks sections on the GPU; here a background
 * thread does that on the CPU, starting from the level 4 sections around the camera and splitting
 * a section into its children while the camera is near enough. A parent stays on screen until all
 * of its children are meshed, so there are no holes while detail streams in.
 *
 * <p>Sections are meshed by Voxy's own {@link RenderGenerationService}; the finished quads are
 * converted to Radiance vertices on the meshing thread and handed to native from the render
 * thread. Changes to a section's voxels remesh it after a short delay.
 */
public class VoxyWorld {
    private static final Logger LOGGER = LoggerFactory.getLogger("Radiance/Voxy");
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final int SUBMITS_PER_FRAME = 16;
    private static final long FRAME_BUDGET_NANOS = 4_000_000L;
    private static final long PASS_MILLIS = 100;
    private static final int EVICT_AFTER_PASSES = 50;
    private static final int RETRY_AFTER_PASSES = 100;
    private static final int MAX_QUEUED = 3000;
    /** Camera distance, in section widths, below which a section is replaced by its children. */
    private static final double DETAIL = Double.parseDouble(
        System.getProperty("radiance.voxy.detail", "4"));
    private static final long NEAR_REMESH_MILLIS = 500, FAR_REMESH_MILLIS = 2000;

    /** What native holds for a section: the ids to draw it with, and what is below it. */
    private record Built(long id, byte children, boolean geometry) {}

    private static final class Entry {
        final long key;
        volatile Built built;
        volatile int touched;
        volatile int requested = -1_000_000;

        Entry(long key) { this.key = key; }
    }

    private record Result(long key, byte children, VoxyMesh.Result mesh, VistaTerrain terrain) {}

    public final long worldVersion;
    private final ClientLevel level;
    private final WorldEngine engine;
    private final ModelBakerySubsystem bakery;
    private final VoxyModels models;
    private final RenderGenerationService generator;
    private final CardinalLighting lighting;
    private final int topRadius;
    private final int minTop, maxTop;

    private final ConcurrentHashMap<Long, Entry> sections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> dirty = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> evicted = new ConcurrentLinkedQueue<>();
    private final Set<Long> live = ConcurrentHashMap.newKeySet();
    /** Ids replaced or evicted, invalidated once a render list without them is in place. */
    private final LongArrayList retiring = new LongArrayList();

    private volatile double cameraX = Double.NaN, cameraY, cameraZ;
    private volatile long[] selection;
    private long[] applied = new long[0];
    private long[] lastPublished = new long[0];
    private volatile boolean running = true;
    private Thread selector;
    private int pass;
    private boolean failedOnce;

    public VoxyWorld(ClientLevel level, WorldEngine engine, VoxyInstance instance,
        VistaTileAtlas atlas, long worldVersion, float renderDistance) {
        this.worldVersion = worldVersion;
        this.level = level;
        this.engine = engine;
        this.lighting = level.cardinalLighting();
        this.topRadius = (int) Math.ceil(renderDistance) + 1;
        // Level 4 sections are 512 blocks tall.
        this.minTop = level.getMinSectionY() >> 5;
        this.maxTop = (level.getMaxSectionY() - 1) >> 5;
        engine.acquireRef();
        try {
            this.bakery = new ModelBakerySubsystem(engine.getMapper());
            this.models = new VoxyModels(bakery, atlas);
            this.generator = new RenderGenerationService(engine, bakery,
                instance.getServiceManager(), false);
            this.generator.setResultConsumer(this::onMesh);
            engine.setDirtyCallback(this::onDirty);
            for (var biome : engine.getMapper().getBiomeEntries()) { bakery.addBiome(biome); }
            engine.getMapper().setBiomeCallback(bakery::addBiome);
        } catch (RuntimeException exception) {
            engine.releaseRef();
            throw exception;
        }
        this.selector = new Thread(this::selectLoop, "Radiance Voxy LOD selection");
        this.selector.setDaemon(true);
        this.selector.start();
    }

    public VoxyModels models() { return models; }

    public ClientLevel level() { return level; }

    /**
     * The tile materials of a block's west, east, north and south faces for the walls native builds
     * where loaded chunks meet far terrain. All zero until Voxy has baked the block's model.
     */
    public int[] sideMaterials(BlockState state) {
        int block = engine.getMapper().getIdForBlockState(state);
        if (block == 0) { return new int[4]; }
        if (!models.factory.hasModelForBlockId(block)) {
            bakery.requestBlockBake(block);
            return new int[4];
        }
        return VoxyColumns.sides(models, models.factory.getModelId(block));
    }

    // ---- render thread ---------------------------------------------------------------------

    /** Runs once per frame: hands finished meshes to native and applies the latest LOD selection. */
    public void tick(double x, double y, double z) {
        cameraX = x;
        cameraY = y;
        cameraZ = z;
        models.drain();
        long start = System.nanoTime();
        for (int submitted = 0; submitted < SUBMITS_PER_FRAME
            && System.nanoTime() - start < FRAME_BUDGET_NANOS; submitted++) {
            Result result = results.poll();
            if (result == null) { break; }
            if (!submit(result)) {
                results.add(result);
                break;
            }
        }
        for (Long key; (key = evicted.poll()) != null; ) { retiring.add(key); }
        long[] chosen = selection;
        if (chosen != null && chosen != applied && allLive(chosen)) {
            VistaProxy.updateRenderList(worldVersion, chosen);
            applied = chosen;
            retireUnused(chosen);
        }
    }

    private boolean allLive(long[] ids) {
        for (long id : ids) {
            if (!live.contains(id)) { return false; }
        }
        return true;
    }

    private void retireUnused(long[] chosen) {
        for (Iterator<Long> it = retiring.iterator(); it.hasNext(); ) {
            long id = it.next();
            if (Arrays.binarySearch(chosen, id) < 0) {
                live.remove(id);
                VistaProxy.invalidateSingle(worldVersion, id);
                it.remove();
            }
        }
    }

    private boolean submit(Result result) {
        Entry entry = sections.get(result.key);
        if (entry == null) {
            free(result);
            return true;
        }
        Built old = entry.built;
        Built built;
        if (result.mesh == null) {
            built = new Built(0, result.children, false);
        } else {
            long id = NEXT_ID.incrementAndGet();
            int lod = WorldEngine.getLevel(result.key);
            int width = 32 << lod;
            boolean accepted = VistaProxy.rebuildSingle(worldVersion, id,
                WorldEngine.getX(result.key) * width, WorldEngine.getY(result.key) * width,
                WorldEngine.getZ(result.key) * width, width, result.mesh.vertices(), result.terrain);
            if (!accepted) { return false; }
            MemoryUtil.memFree(result.mesh.vertices());
            live.add(id);
            built = new Built(id, result.children, true);
        }
        entry.built = built;
        if (old != null && old.geometry) { retiring.add(old.id); }
        return true;
    }

    private static void free(Result result) {
        if (result.mesh != null) { MemoryUtil.memFree(result.mesh.vertices()); }
    }

    /** Stops everything and gives the world back to Voxy. Render thread only. */
    public void close() {
        running = false;
        engine.setDirtyCallback(null);
        try {
            engine.getMapper().setBiomeCallback(null);
            engine.getMapper().setStateCallback(null);
        } catch (RuntimeException exception) {
            LOGGER.warn("Voxy's mapper was already closed", exception);
        }
        try {
            selector.join(2000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        try {
            bakery.shutdown();
            generator.shutdown();
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to shut down Voxy's meshing", exception);
        }
        for (Result result; (result = results.poll()) != null; ) { free(result); }
        try {
            engine.releaseRef();
        } catch (IllegalStateException exception) {
            // Voxy already shut the world down with the session.
        }
        sections.clear();
        live.clear();
    }

    // ---- meshing threads -------------------------------------------------------------------

    private void onMesh(BuiltSection mesh) {
        long key = mesh.position;
        try {
            if (!sections.containsKey(key)) { return; }
            VoxyMesh.Result converted = null;
            VistaTerrain terrain = VistaTerrain.EMPTY;
            if (!mesh.isEmpty()) {
                int lod = WorldEngine.getLevel(key);
                converted = VoxyMesh.convert(mesh, lod, models);
                if (converted != null) {
                    WorldSection section = engine.isLive() ? engine.acquireIfExists(key) : null;
                    if (section != null) {
                        try {
                            terrain = VoxyColumns.build(section, models, lighting);
                        } finally {
                            section.release();
                        }
                    }
                }
            }
            results.add(new Result(key, mesh.childExistence, converted, terrain));
        } catch (Throwable throwable) {
            if (!failedOnce) {
                failedOnce = true;
                LOGGER.error("Failed to convert Voxy section {}", WorldEngine.pprintPos(key), throwable);
            }
            results.add(new Result(key, mesh.childExistence, null, VistaTerrain.EMPTY));
        } finally {
            mesh.free();
        }
    }

    private void onDirty(WorldSection section, int flags, int neighbours) {
        long now = System.currentTimeMillis();
        markDirty(section.key, now);
        if (neighbours != 0) {
            int lod = section.lvl;
            if ((neighbours & 0b000001) != 0) { markDirty(key(lod, section.x, section.y - 1, section.z), now); }
            if ((neighbours & 0b000010) != 0) { markDirty(key(lod, section.x, section.y + 1, section.z), now); }
            if ((neighbours & 0b000100) != 0) { markDirty(key(lod, section.x - 1, section.y, section.z), now); }
            if ((neighbours & 0b001000) != 0) { markDirty(key(lod, section.x + 1, section.y, section.z), now); }
            if ((neighbours & 0b010000) != 0) { markDirty(key(lod, section.x, section.y, section.z - 1), now); }
            if ((neighbours & 0b100000) != 0) { markDirty(key(lod, section.x, section.y, section.z + 1), now); }
        }
    }

    private void markDirty(long key, long now) {
        if (sections.containsKey(key)) { dirty.putIfAbsent(key, now); }
    }

    private static long key(int lod, int x, int y, int z) {
        return WorldEngine.getWorldSectionId(lod, x, y, z);
    }

    // ---- selection thread ------------------------------------------------------------------

    private void selectLoop() {
        while (running) {
            try {
                Thread.sleep(PASS_MILLIS);
                if (!Double.isNaN(cameraX)) { selectPass(); }
            } catch (InterruptedException exception) {
                return;
            } catch (Throwable throwable) {
                LOGGER.error("Voxy LOD selection failed", throwable);
            }
        }
    }

    private void selectPass() {
        pass++;
        double x = cameraX, y = cameraY, z = cameraZ;
        remeshDirty();
        LongArrayList ids = new LongArrayList();
        int centreX = (int) Math.floor(x / 512.0);
        int centreZ = (int) Math.floor(z / 512.0);
        for (int dz = -topRadius; dz <= topRadius; dz++) {
            for (int dx = -topRadius; dx <= topRadius; dx++) {
                if (dx * dx + dz * dz > topRadius * topRadius) { continue; }
                for (int sectionY = minTop; sectionY <= maxTop; sectionY++) {
                    visit(key(4, centreX + dx, sectionY, centreZ + dz), x, y, z, ids);
                }
            }
        }
        for (Iterator<Map.Entry<Long, Entry>> it = sections.entrySet().iterator(); it.hasNext(); ) {
            Entry entry = it.next().getValue();
            if (pass - entry.touched > EVICT_AFTER_PASSES) {
                it.remove();
                dirty.remove(entry.key);
                Built built = entry.built;
                if (built != null && built.geometry) { evicted.add(built.id); }
            }
        }
        long[] sorted = ids.toLongArray();
        Arrays.sort(sorted);
        if (!Arrays.equals(sorted, lastPublished)) {
            lastPublished = sorted;
            selection = sorted;
        }
    }

    /** Adds the ids to draw for a built section or its children. */
    private void visit(long key, double x, double y, double z, LongArrayList out) {
        Entry entry = entry(key);
        Built built = entry.built;
        if (built == null) { return; }
        int lod = WorldEngine.getLevel(key);
        if (lod > 0 && built.children != 0 && wantsSplit(key, lod, x, y, z)) {
            boolean ready = true;
            for (int child = 0; child < 8; child++) {
                if ((built.children & (1 << child)) != 0 && entry(childKey(key, lod, child)).built == null) {
                    ready = false;
                }
            }
            if (ready) {
                for (int child = 0; child < 8; child++) {
                    if ((built.children & (1 << child)) != 0) { visit(childKey(key, lod, child), x, y, z, out); }
                }
                return;
            }
        }
        if (built.geometry) { out.add(built.id); }
    }

    private static long childKey(long key, int lod, int child) {
        return key(lod - 1, WorldEngine.getX(key) * 2 + (child & 1),
            WorldEngine.getY(key) * 2 + ((child >> 2) & 1), WorldEngine.getZ(key) * 2 + ((child >> 1) & 1));
    }

    private boolean wantsSplit(long key, int lod, double x, double y, double z) {
        double width = 32 << lod;
        double minX = WorldEngine.getX(key) * width, minY = WorldEngine.getY(key) * width;
        double minZ = WorldEngine.getZ(key) * width;
        double dx = Math.max(Math.max(minX - x, x - (minX + width)), 0);
        double dy = Math.max(Math.max(minY - y, y - (minY + width)), 0);
        double dz = Math.max(Math.max(minZ - z, z - (minZ + width)), 0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz) < width * DETAIL;
    }

    /** Finds or creates the entry for a section and asks Voxy to mesh it if nothing has yet. */
    private Entry entry(long key) {
        Entry entry = sections.computeIfAbsent(key, Entry::new);
        entry.touched = pass;
        if (entry.built == null && pass - entry.requested > RETRY_AFTER_PASSES
            && generator.getTaskCount() < MAX_QUEUED) {
            entry.requested = pass;
            generator.enqueueTask(key);
        }
        return entry;
    }

    private void remeshDirty() {
        if (dirty.isEmpty()) { return; }
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<Long, Long>> it = dirty.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Long> change = it.next();
            long delay = WorldEngine.getLevel(change.getKey()) == 0 ? NEAR_REMESH_MILLIS : FAR_REMESH_MILLIS;
            if (now - change.getValue() < delay) { continue; }
            it.remove();
            if (sections.containsKey(change.getKey())) { generator.enqueueTask(change.getKey()); }
        }
    }
}
