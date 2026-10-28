package com.radiance.client.proxy.world;

import static org.lwjgl.system.MemoryUtil.memAddress;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.radiance.client.constant.Constants;
import com.radiance.client.mod_support.ModSupport;
import com.radiance.client.mod_support.sodium.SodiumTerrain;
import com.radiance.client.option.Options;
import com.radiance.client.profiler.Profiler;
import com.radiance.client.vertex.RenderTypeInfo;
import com.radiance.client.vertex.TransferredBuildBuffer;
import com.radiance.client.vista.ChunkTerrainBuilder;
import com.radiance.client.vista.Vista;
import com.radiance.client.vista.VistaTerrain;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

/**
 * Terrain capture for the ray tracer.
 *
 * <p>On 1.21.4 Radiance replaced vanilla's section scheduling with its own executors. 26.3 compiles
 * sections from {@code LevelRenderer.compileSections} on vanilla's workers, so Radiance rides along:
 * {@code SectionBuilderMixins} meshes each section into PBR vertices, and the compile task hands
 * them to native through {@link #onSectionCompiled} instead of uploading them to vanilla's buffers.
 * Vanilla only recompiles dirty sections that are visible; reflections and shadows need the rest
 * too, so {@link #collectOffscreenSectionUpdates} queues dirty off-screen sections as well.
 */
public class ChunkProxy {

    private static final Object chunkLifecycleLock = new Object();
    private static final Map<Integer, ChunkSlot> chunkSlots = new HashMap<>();
    // Sections whose current native data has geometry, so readiness depends on native's upload.
    private static final BitSet nativeGeometry = new BitSet();
    private static long nextChunkGeneration;
    // Section nodes that went dirty; checked lazily against the section update tracker.
    private static final LongOpenHashSet pendingSections = new LongOpenHashSet();
    private static ViewArea currentStorage = null;
    private static boolean pendingRebuildAll = false;
    private static final double IMPORTANT_DISTANCE_SQUARED = 768.0;
    private static final int RAY_TRACING_NEAR_HORIZONTAL_RADIUS_SECTIONS = 4;
    private static final int RAY_TRACING_NEAR_VERTICAL_RADIUS_SECTIONS = 2;
    public static int builtChunkCount = 0;

    public static native void initNative(int numChunks, int sizeX, int sizeY, int sizeZ,
        int bottomSectionCoord);

    public static native void updateSectionPosNative(int sectionX, int sectionY, int sectionZ);

    public static void init(int numChunks, int sizeX, int sizeY, int sizeZ,
        int bottomSectionCoord) {
        if (Profiler.ENABLED) {
            Profiler.enter("java.ChunkProxy.init");
        }
        try {
            clear();
            synchronized (chunkLifecycleLock) {
                initNative(numChunks, sizeX, sizeY, sizeZ, bottomSectionCoord);
            }
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    public static void updateSectionPos(SectionPos sectionPos) {
        if (Profiler.ENABLED) {
            Profiler.enter("java.ChunkProxy.updateSectionPos");
        }
        try {
            updateSectionPosNative(sectionPos.x(), sectionPos.y(),
                sectionPos.z());
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    public static void setStorage(ViewArea storage) {
        currentStorage = storage;
        if (currentStorage != null && pendingRebuildAll) {
            pendingRebuildAll = false;
            queueRebuildAll(currentStorage);
        }
    }

    public static void clear() {
        if (Profiler.ENABLED) {
            Profiler.enter("java.ChunkProxy.clear");
        }
        try {
            // Compiles still running on vanilla's workers find no slot and drop their results.
            synchronized (chunkLifecycleLock) {
                chunkSlots.clear();
                nativeGeometry.clear();
                BlockEntitySectionCache.clear();
                sodiumSizeXZ = 0;
                sodiumCenterValid = false;
            }
            currentStorage = null;
            pendingRebuildAll = false;
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    /** Recompiles every section, keeping the current data until the new one arrives. */
    public static void rebuildAll() {
        if (Profiler.ENABLED) {
            Profiler.enter("java.ChunkProxy.rebuildAll");
        }
        try {
            if (ModSupport.SODIUM) {
                SodiumTerrain.rebuildAll();
                return;
            }
            if (currentStorage == null) {
                pendingRebuildAll = true;
                return;
            }

            queueRebuildAll(currentStorage);
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    private static void queueRebuildAll(ViewArea storage) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            pendingRebuildAll = true;
            return;
        }

        // Dirtiness lives in vanilla's section update tracker now; marking a section there gets it
        // recompiled whether or not it is on screen.
        LevelExtractor levelExtractor = client.levelExtractor;
        storage.sections.forEach(section -> {
            long sectionNode = section.getSectionNode();
            levelExtractor.setSectionDirty(SectionPos.x(sectionNode), SectionPos.y(sectionNode),
                SectionPos.z(sectionNode));
        });
    }

    /**
     * Per-frame hook from the frame flow. 26.3 schedules compiles during extraction (see
     * {@link #collectOffscreenSectionUpdates}), so this only flushes a deferred {@link #rebuildAll}.
     */
    public static void rebuild(Camera camera) {
        if (pendingRebuildAll && currentStorage != null) {
            pendingRebuildAll = false;
            queueRebuildAll(currentStorage);
        }

        if (Profiler.ENABLED) {
            synchronized (pendingSections) {
                Profiler.metric("java.chunk_rebuild.pending_sections", pendingSections.size());
            }
        }
    }

    public static void rebuild(Camera camera, List<SectionRenderDispatcher.RenderSection> visibleChunks,
        List<SectionRenderDispatcher.RenderSection> nearbyChunks) {
        rebuild(camera);
    }

    /** Rebuilds run on vanilla's section workers on 26.3, so there is nothing to wait for. */
    public static void waitImportantChunkRebuild() {
    }

    /** Called whenever a section update tracker entry becomes dirty. */
    public static void markSectionDirty(long sectionNode) {
        synchronized (pendingSections) {
            pendingSections.add(sectionNode);
        }
    }

    /**
     * Adds compile requests for dirty sections vanilla skipped because they are not visible. Runs
     * on the main thread during extraction, after vanilla queued the visible ones.
     */
    public static void collectOffscreenSectionUpdates(ClientLevel level, SectionUpdateTracker tracker,
        LevelRenderer levelRenderer, Vec3 cameraPos, List<SectionUpdateRenderState> output) {
        ViewArea viewArea = levelRenderer.viewArea();
        SectionRenderDispatcher dispatcher = levelRenderer.sectionRenderDispatcher();
        if (viewArea == null || dispatcher == null) {
            return;
        }
        if (Profiler.ENABLED) {
            Profiler.enter("java.ChunkProxy.collectOffscreenSectionUpdates");
        }
        try {
            RenderRegionCache cache = new RenderRegionCache();
            int batchSize = Math.max(1, Options.chunkBuildingBatchSize);
            SectionPos cameraSection = SectionPos.of(cameraPos);
            // With a far terrain source, whole columns matter more than height (1.21.4 behaviour).
            boolean completeColumns = Vista.distanceBlocks() > 0;

            // Sections around the camera feed the ray tracer's nearby lighting, so they go first
            // whatever the queue looks like, like 1.21.4's important rebuilds.
            int nearbySubmitted = 0;
            for (int dx = -RAY_TRACING_NEAR_HORIZONTAL_RADIUS_SECTIONS;
                dx <= RAY_TRACING_NEAR_HORIZONTAL_RADIUS_SECTIONS && nearbySubmitted < batchSize; dx++) {
                for (int dz = -RAY_TRACING_NEAR_HORIZONTAL_RADIUS_SECTIONS;
                    dz <= RAY_TRACING_NEAR_HORIZONTAL_RADIUS_SECTIONS && nearbySubmitted < batchSize; dz++) {
                    for (int dy = -RAY_TRACING_NEAR_VERTICAL_RADIUS_SECTIONS;
                        dy <= RAY_TRACING_NEAR_VERTICAL_RADIUS_SECTIONS && nearbySubmitted < batchSize; dy++) {
                        long sectionNode = SectionPos.asLong(cameraSection.x() + dx,
                            cameraSection.y() + dy, cameraSection.z() + dz);
                        if (trySubmit(level, tracker, viewArea, cache, sectionNode, output)) {
                            nearbySubmitted++;
                        }
                    }
                }
            }

            // Everything else waits until vanilla's queue has room, nearest first.
            int queueTarget = Math.max(1, Options.chunkBuildingThreads) * 2;
            int budget = Math.min(batchSize, queueTarget - dispatcher.getCompileQueueSize());
            int backgroundSubmitted = 0;
            int pendingCount;
            if (budget > 0) {
                // Leave room for candidates whose neighbours are not loaded yet.
                int candidateLimit = budget * 2;
                long[] candidates = new long[candidateLimit];
                double[] priorities = new double[candidateLimit];
                int candidateCount = 0;
                synchronized (pendingSections) {
                    LongIterator iterator = pendingSections.iterator();
                    while (iterator.hasNext()) {
                        long sectionNode = iterator.nextLong();
                        SectionUpdateTracker.SectionDirtyState dirtyState = tracker.getDirtyState(sectionNode);
                        if (dirtyState == null || !dirtyState.isDirty()
                            || dirtyState.getSectionNode() != sectionNode) {
                            iterator.remove();
                            continue;
                        }

                        double dx = SectionPos.sectionToBlockCoord(SectionPos.x(sectionNode)) + 8 - cameraPos.x;
                        double dy = SectionPos.sectionToBlockCoord(SectionPos.y(sectionNode)) + 8 - cameraPos.y;
                        double dz = SectionPos.sectionToBlockCoord(SectionPos.z(sectionNode)) + 8 - cameraPos.z;
                        double priority = completeColumns ? dx * dx + dz * dz : dx * dx + dy * dy + dz * dz;
                        if (candidateCount == candidateLimit && priority >= priorities[candidateCount - 1]) {
                            continue;
                        }

                        int insertIndex = candidateCount < candidateLimit ? candidateCount++ : candidateCount - 1;
                        while (insertIndex > 0 && priorities[insertIndex - 1] > priority) {
                            priorities[insertIndex] = priorities[insertIndex - 1];
                            candidates[insertIndex] = candidates[insertIndex - 1];
                            insertIndex--;
                        }
                        priorities[insertIndex] = priority;
                        candidates[insertIndex] = sectionNode;
                    }
                    pendingCount = pendingSections.size();
                }

                for (int i = 0; i < candidateCount && backgroundSubmitted < budget; i++) {
                    if (trySubmit(level, tracker, viewArea, cache, candidates[i], output)) {
                        backgroundSubmitted++;
                    }
                }
            } else {
                synchronized (pendingSections) {
                    pendingCount = pendingSections.size();
                }
            }

            if (Profiler.ENABLED) {
                Profiler.metric("java.chunk_rebuild.offscreen_nearby_submitted", nearbySubmitted);
                Profiler.metric("java.chunk_rebuild.offscreen_background_submitted", backgroundSubmitted);
                Profiler.metric("java.chunk_rebuild.offscreen_budget", Math.max(0, budget));
                Profiler.metric("java.chunk_rebuild.vanilla_queue_size", dispatcher.getCompileQueueSize());
                Profiler.metric("java.chunk_rebuild.pending_sections", pendingCount);
            }
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    /** Queues one dirty section the way {@code LevelExtractor.extract} queues visible ones. */
    private static boolean trySubmit(ClientLevel level, SectionUpdateTracker tracker, ViewArea viewArea,
        RenderRegionCache cache, long sectionNode, List<SectionUpdateRenderState> output) {
        SectionUpdateTracker.SectionDirtyState dirtyState = tracker.getDirtyState(sectionNode);
        if (dirtyState == null || !dirtyState.isDirty()) {
            return false;
        }
        SectionRenderDispatcher.RenderSection section = viewArea.getRenderSection(sectionNode);
        if (section == null || section.getSectionNode() != sectionNode) {
            return false;
        }
        if (level.getChunkSource().getChunk(SectionPos.x(sectionNode), SectionPos.z(sectionNode),
            ChunkStatus.FULL, false) == null) {
            return false;
        }
        if (section.sectionMesh.get() == CompiledSectionMesh.UNCOMPILED
            && !tracker.hasAllNeighbors(level, sectionNode)) {
            return false;
        }

        output.add(new SectionUpdateRenderState(sectionNode, dirtyState.isDirtyFromPlayer(),
            cache.createRegion(level, sectionNode)));
        dirtyState.setNotDirty();
        return true;
    }

    private static final class ChunkSlot {
        /** What the slot belongs to: vanilla's {@code RenderSection}, or {@link #SODIUM_OWNER}. */
        private final Object owner;
        private final BlockPos origin;
        private final long generation;
        private boolean relocating = true;

        private ChunkSlot(Object owner, BlockPos origin, long generation) {
            this.owner = owner;
            this.origin = origin;
            this.generation = generation;
        }
    }

    public static void beginRelocation(SectionRenderDispatcher.RenderSection builtChunk, int x, int y, int z) {
        synchronized (chunkLifecycleLock) {
            ChunkSlot slot = new ChunkSlot(builtChunk, new BlockPos(x, y, z), ++nextChunkGeneration);
            chunkSlots.put(builtChunk.index, slot);
            nativeGeometry.clear(builtChunk.index);
            relocateSingle(builtChunk.index, x, y, z, slot.generation);
        }
    }

    public static void finishRelocation(SectionRenderDispatcher.RenderSection builtChunk) {
        synchronized (chunkLifecycleLock) {
            ChunkSlot slot = chunkSlots.get(builtChunk.index);
            if (slot != null && slot.owner == builtChunk
                && slot.origin.equals(builtChunk.getRenderOrigin())) {
                slot.relocating = false;
            }
        }
    }

    /** The slot a compile of {@code origin} may publish to, or null if the section moved on. */
    private static ChunkSlot currentSlot(int index, Object owner, BlockPos origin, long generation,
        BooleanSupplier cancelled) {
        ChunkSlot slot = chunkSlots.get(index);
        if (slot == null || slot.owner != owner || slot.relocating
            || !slot.origin.equals(origin) || (generation >= 0 && slot.generation != generation)
            || cancelled.getAsBoolean()) {
            return null;
        }
        return slot;
    }

    /**
     * Sends a freshly compiled section to native. Runs inside vanilla's compile task, on a section
     * worker or (for synchronous compiles) the main thread. Takes every layer out of
     * {@code results}, so vanilla sees an empty section and uploads nothing.
     */
    public static void onSectionCompiled(SectionRenderDispatcher.RenderSection builtChunk,
        SectionPos sectionPos, RenderSectionRegion region, SectionCompiler.Results results,
        SectionBufferBuilderPack storage, AtomicBoolean cancelled, Vec3 cameraPos) {
        Map<ChunkSectionLayer, MeshData> buffers = new EnumMap<>(ChunkSectionLayer.class);
        buffers.putAll(results.renderedLayers);
        results.renderedLayers.clear();

        BlockPos origin = sectionPos.origin();
        double dx = origin.getX() + 8 - cameraPos.x;
        double dy = origin.getY() + 8 - cameraPos.y;
        double dz = origin.getZ() + 8 - cameraPos.z;
        // Synchronous compiles are the ones vanilla prioritises (nearby or player-caused).
        boolean important = dx * dx + dy * dy + dz * dz < IMPORTANT_DISTANCE_SQUARED
            || RenderSystem.isOnRenderThread();
        publishCompiled(builtChunk.index, builtChunk, origin, -1L, region, buffers, storage,
            cancelled::get, important);
    }

    /**
     * Hands a meshed section to native if {@code owner} still holds the slot at {@code origin}.
     * Takes ownership of {@code buffers} (and, per layer, of the matching allocator in
     * {@code storage}). Shared by the vanilla compile task and Sodium's chunk builder.
     */
    private static void publishCompiled(int index, Object owner, BlockPos origin, long generation,
        BlockAndTintGetter region, Map<ChunkSectionLayer, MeshData> buffers,
        SectionBufferBuilderPack storage, BooleanSupplier cancelled, boolean important) {
        boolean current;
        synchronized (chunkLifecycleLock) {
            current = currentSlot(index, owner, origin, generation, cancelled) != null;
        }
        if (!current) {
            closeUntransferredBuiltBuffers(buffers, null);
            return;
        }

        VistaTerrain terrain;
        try {
            terrain = buildTerrain(region, origin);
        } catch (RuntimeException | Error e) {
            closeUntransferredBuiltBuffers(buffers, null);
            throw e;
        }

        if (buffers.isEmpty()) {
            try (VistaTerrain.Upload upload = terrain.upload()) {
                synchronized (chunkLifecycleLock) {
                    ChunkSlot slot = currentSlot(index, owner, origin, generation, cancelled);
                    if (slot != null) {
                        invalidateSingle(index, origin.getX(), origin.getY(), origin.getZ(),
                            slot.generation, upload.count, upload.address);
                        nativeGeometry.clear(index);
                        builtChunkCount++;
                    }
                }
            }
            return;
        }

        int size = buffers.size();
        ByteBuffer geometryTypeBB = null;
        ByteBuffer geometryGroupNameBB = null;
        ByteBuffer geometryTextureBB = null;
        ByteBuffer vertexFormatBB = null;
        ByteBuffer indexTypeBB = null;
        ByteBuffer vertexCountBB = null;
        ByteBuffer verticesBB = null;
        ByteBuffer indicesBB = null;
        List<ByteBuffer> geometryGroupNameBuffers = new ArrayList<>(size);
        TransferredBuildBuffer[] transferredBuffers = new TransferredBuildBuffer[size];
        boolean submittedNative = false;

        try {
            geometryTypeBB = MemoryUtil.memAlloc(size * Integer.BYTES);
            geometryGroupNameBB = MemoryUtil.memAlloc(size * Long.BYTES);
            geometryTextureBB = MemoryUtil.memAlloc(size * Integer.BYTES);
            vertexFormatBB = MemoryUtil.memAlloc(size * Integer.BYTES);
            indexTypeBB = MemoryUtil.memAlloc(size * Integer.BYTES);
            vertexCountBB = MemoryUtil.memAlloc(size * Integer.BYTES);
            verticesBB = MemoryUtil.memAlloc(size * Long.BYTES);
            indicesBB = MemoryUtil.memAlloc(size * Long.BYTES);

            // Every terrain layer samples the block atlas.
            int geometryTextureID = RenderTypeInfo.blockAtlasTextureId();
            int geometryIndex = 0;
            for (Map.Entry<ChunkSectionLayer, MeshData> entry : buffers.entrySet()) {
                ChunkSectionLayer layer = entry.getKey();
                MeshData vertexBuffer = entry.getValue();
                TransferredBuildBuffer transferredBuffer = TransferredBuildBuffer.takeChunk(storage,
                    layer, vertexBuffer);
                transferredBuffers[geometryIndex] = transferredBuffer;

                // As on 1.21.4, only the solid layer is opaque to rays.
                int geometryTypeID = (layer == ChunkSectionLayer.SOLID
                    ? Constants.GeometryTypes.WORLD_SOLID
                    : Constants.GeometryTypes.WORLD_TRANSPARENT).getValue();
                int vertexFormatID = Constants.VertexFormats.getValue(
                    vertexBuffer.drawState().format());

                geometryTypeBB.putInt(geometryIndex * Integer.BYTES, geometryTypeID);

                ByteBuffer geometryGroupNameBuffer = MemoryUtil.memUTF8(layer.label(), true);
                geometryGroupNameBuffers.add(geometryGroupNameBuffer);
                geometryGroupNameBB.putLong(geometryIndex * Long.BYTES,
                    memAddress(geometryGroupNameBuffer));

                geometryTextureBB.putInt(geometryIndex * Integer.BYTES, geometryTextureID);
                vertexFormatBB.putInt(geometryIndex * Integer.BYTES, vertexFormatID);
                indexTypeBB.putInt(geometryIndex * Integer.BYTES,
                    Constants.IndexTypes.getValue(vertexBuffer.drawState().indexType()));
                vertexCountBB.putInt(geometryIndex * Integer.BYTES,
                    vertexBuffer.drawState().vertexCount());
                verticesBB.putLong(geometryIndex * Long.BYTES, transferredBuffer.vertexAddress());
                indicesBB.putLong(geometryIndex * Long.BYTES, transferredBuffer.sortedIndexAddress());
                geometryIndex++;
            }

            try (VistaTerrain.Upload upload = terrain.upload()) {
                synchronized (chunkLifecycleLock) {
                    ChunkSlot slot = currentSlot(index, owner, origin, generation, cancelled);
                    if (slot != null) {
                        rebuildSingleNative(origin.getX(), origin.getY(), origin.getZ(),
                            index, size, memAddress(geometryTypeBB),
                            memAddress(geometryGroupNameBB), memAddress(geometryTextureBB),
                            memAddress(vertexFormatBB), memAddress(indexTypeBB),
                            memAddress(vertexCountBB), memAddress(verticesBB), memAddress(indicesBB),
                            transferredBuffers, important, slot.generation, upload.count,
                            upload.address);
                        submittedNative = true;
                        nativeGeometry.set(index);
                        builtChunkCount++;
                    }
                }
            }
        } finally {
            MemoryUtil.memFree(geometryTypeBB);
            MemoryUtil.memFree(geometryGroupNameBB);
            MemoryUtil.memFree(geometryTextureBB);
            MemoryUtil.memFree(vertexFormatBB);
            MemoryUtil.memFree(indexTypeBB);
            MemoryUtil.memFree(vertexCountBB);
            MemoryUtil.memFree(verticesBB);
            MemoryUtil.memFree(indicesBB);
            for (ByteBuffer geometryGroupNameBuffer : geometryGroupNameBuffers) {
                MemoryUtil.memFree(geometryGroupNameBuffer);
            }
            if (!submittedNative) {
                // Native owns transferred buffers once submitted; otherwise free them here.
                for (TransferredBuildBuffer transferredBuffer : transferredBuffers) {
                    if (transferredBuffer != null) {
                        transferredBuffer.close();
                    }
                }
                closeUntransferredBuiltBuffers(buffers, transferredBuffers);
            }
        }
    }

    /** The far-terrain columns along the section's border, when a far terrain source is loaded. */
    private static VistaTerrain buildTerrain(BlockAndTintGetter region, BlockPos origin) {
        if (!Vista.isAvailable()) {
            return VistaTerrain.EMPTY;
        }
        ChunkTerrainBuilder terrain = new ChunkTerrainBuilder();
        for (BlockPos pos : BlockPos.betweenClosed(origin, origin.offset(15, 15, 15))) {
            if ((pos.getX() & 15) == 0 || (pos.getX() & 15) == 15
                || (pos.getZ() & 15) == 0 || (pos.getZ() & 15) == 15) {
                terrain.add(region, pos, region.getBlockState(pos));
            }
        }
        return terrain.build();
    }

    private static void closeUntransferredBuiltBuffers(Map<ChunkSectionLayer, MeshData> buffers,
        TransferredBuildBuffer[] transferredBuffers) {
        for (MeshData builtBuffer : buffers.values()) {
            if (isTransferred(builtBuffer, transferredBuffers)) {
                continue;
            }
            builtBuffer.close();
        }
    }

    private static boolean isTransferred(MeshData builtBuffer,
        TransferredBuildBuffer[] transferredBuffers) {
        if (transferredBuffers == null) {
            return false;
        }

        for (TransferredBuildBuffer transferredBuffer : transferredBuffers) {
            if (transferredBuffer != null && transferredBuffer.builtBuffer() == builtBuffer) {
                return true;
            }
        }
        return false;
    }

    private static native void rebuildSingleNative(int originX, int originY, int originZ, long index,
        int size, long geometryTypes, long geometryGroupNames, long geometryTextures,
        long vertexFormats, long indexTypes, long vertexCounts, long vertices, long indices,
        TransferredBuildBuffer[] transferredBuffers, boolean important, long generation,
        int terrainCount, long terrainAddress);

    public static native boolean isChunkReady(long index);

    public static boolean isChunkReady(SectionRenderDispatcher.RenderSection builtChunk) {
        return isChunkReady(builtChunk.index);
    }

    /**
     * Whether the section's terrain is in the ray tracer: compiled by vanilla and, if it has any
     * geometry, uploaded by native. Replaces 1.21.4's {@code compiled == PROCESSED} check.
     */
    public static boolean isSectionReady(SectionRenderDispatcher.RenderSection builtChunk) {
        if (builtChunk.sectionMesh.get() == CompiledSectionMesh.UNCOMPILED) {
            return false;
        }
        synchronized (chunkLifecycleLock) {
            if (!nativeGeometry.get(builtChunk.index)) {
                return true;
            }
        }
        return isChunkReady(builtChunk.index);
    }

    /**
     * Vanilla counts sections with uploaded layers, which Radiance sections never have; count the
     * ones whose geometry went to native instead (F3, metrics and the world icon screenshot use it).
     */
    public static int countRenderedSections(List<SectionRenderDispatcher.RenderSection> sections) {
        int count = 0;
        synchronized (chunkLifecycleLock) {
            for (SectionRenderDispatcher.RenderSection section : sections) {
                if (nativeGeometry.get(section.index)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static native void invalidateSingle(long index, int originX, int originY, int originZ,
        long generation, int terrainCount, long terrainAddress);

    private static native void relocateSingle(long index, int originX, int originY, int originZ,
        long generation);

    // ------------------------------------------------------------------------------------------
    // Sodium. Sodium keeps its sections in a hash map keyed by position and has no fixed grid, so
    // the ring native expects (the vanilla ViewArea layout: index = (z * sizeY + y) * sizeXZ + x with
    // x and z taken modulo the grid width) is kept here instead: a (2R+1)^2 column window around
    // the camera section. Sodium's meshing workers publish through sodiumPublish; sections that
    // were not meshed (nothing but air, unloaded) are reported through sodiumInvalidate/Release.
    // ------------------------------------------------------------------------------------------

    /** Owner of every slot of Sodium's ring (there is one section manager at a time). */
    private static final Object SODIUM_OWNER = new Object();
    private static final long[] NO_SECTIONS = new long[0];
    private static int sodiumSizeXZ;
    private static int sodiumSizeY;
    private static int sodiumMinSectionY;
    private static int sodiumRadius;
    private static boolean sodiumCenterValid;
    private static int sodiumCenterX;
    private static int sodiumCenterY;
    private static int sodiumCenterZ;

    /** Sizes native's section grid for a Sodium section manager with this render distance. */
    public static void sodiumInit(int radius, int minSectionY, int maxSectionY) {
        clear();
        int sizeXZ = radius * 2 + 1;
        int sizeY = maxSectionY - minSectionY + 1;
        synchronized (chunkLifecycleLock) {
            sodiumRadius = radius;
            sodiumSizeXZ = sizeXZ;
            sodiumSizeY = sizeY;
            sodiumMinSectionY = minSectionY;
            sodiumCenterValid = false;
            initNative(sizeXZ * sizeXZ * sizeY, sizeXZ, sizeY, sizeXZ, minSectionY);
        }
    }

    private static int sodiumIndex(int sectionX, int sectionY, int sectionZ) {
        return (Math.floorMod(sectionZ, sodiumSizeXZ) * sodiumSizeY + (sectionY - sodiumMinSectionY))
            * sodiumSizeXZ + Math.floorMod(sectionX, sodiumSizeXZ);
    }

    private static boolean sodiumInWindowLocked(int sectionX, int sectionY, int sectionZ) {
        return sodiumSizeXZ != 0 && sodiumCenterValid
            && Math.abs(sectionX - sodiumCenterX) <= sodiumRadius
            && Math.abs(sectionZ - sodiumCenterZ) <= sodiumRadius
            && sectionY >= sodiumMinSectionY && sectionY < sodiumMinSectionY + sodiumSizeY;
    }

    /** Whether native has a slot for this section, i.e. it is within the camera's column window. */
    public static boolean sodiumInWindow(int sectionX, int sectionY, int sectionZ) {
        synchronized (chunkLifecycleLock) {
            return sodiumInWindowLocked(sectionX, sectionY, sectionZ);
        }
    }

    /**
     * Moves the window to the camera's section. Slots that now stand for another section are
     * relocated in native (dropping their data and any publish still in flight for the old
     * section). Returns the nodes ({@link SectionPos#asLong}) of the sections that entered the
     * window: the caller has to get them meshed again.
     */
    public static long[] sodiumReposition(int centerX, int centerY, int centerZ) {
        synchronized (chunkLifecycleLock) {
            if (sodiumSizeXZ == 0) {
                return NO_SECTIONS;
            }
            if (sodiumCenterValid && centerX == sodiumCenterX && centerY == sodiumCenterY
                && centerZ == sodiumCenterZ) {
                return NO_SECTIONS;
            }
            boolean columnsMoved = !sodiumCenterValid || centerX != sodiumCenterX
                || centerZ != sodiumCenterZ;
            sodiumCenterValid = true;
            sodiumCenterX = centerX;
            sodiumCenterY = centerY;
            sodiumCenterZ = centerZ;
            updateSectionPosNative(centerX, centerY, centerZ);
            if (!columnsMoved) {
                return NO_SECTIONS;
            }

            LongArrayList entered = new LongArrayList();
            int baseX = centerX - sodiumRadius;
            int baseZ = centerZ - sodiumRadius;
            for (int gridZ = 0; gridZ < sodiumSizeXZ; gridZ++) {
                int sectionZ = baseZ + Math.floorMod(gridZ - baseZ, sodiumSizeXZ);
                for (int gridX = 0; gridX < sodiumSizeXZ; gridX++) {
                    int sectionX = baseX + Math.floorMod(gridX - baseX, sodiumSizeXZ);
                    // Columns move as a whole: if the lowest slot is right, so are the others.
                    ChunkSlot bottom = chunkSlots.get(
                        sodiumIndex(sectionX, sodiumMinSectionY, sectionZ));
                    if (bottom != null && bottom.origin.getX() == (sectionX << 4)
                        && bottom.origin.getZ() == (sectionZ << 4)) {
                        continue;
                    }
                    for (int gridY = 0; gridY < sodiumSizeY; gridY++) {
                        int sectionY = sodiumMinSectionY + gridY;
                        sodiumRelocateLocked(sectionX, sectionY, sectionZ);
                        entered.add(SectionPos.asLong(sectionX, sectionY, sectionZ));
                    }
                }
            }
            return entered.isEmpty() ? NO_SECTIONS : entered.toLongArray();
        }
    }

    /** Resets the slot for this section (new generation, no data). Call with the lock held. */
    private static void sodiumRelocateLocked(int sectionX, int sectionY, int sectionZ) {
        int index = sodiumIndex(sectionX, sectionY, sectionZ);
        ChunkSlot slot = new ChunkSlot(SODIUM_OWNER,
            new BlockPos(sectionX << 4, sectionY << 4, sectionZ << 4), ++nextChunkGeneration);
        slot.relocating = false;
        chunkSlots.put(index, slot);
        nativeGeometry.clear(index);
        relocateSingle(index, sectionX << 4, sectionY << 4, sectionZ << 4, slot.generation);
    }

    /**
     * Called by a Sodium worker before it meshes a section: the generation of the slot the result
     * will go to, or -1 if the section is not in the window (nothing to mesh for).
     */
    public static long sodiumBegin(int sectionX, int sectionY, int sectionZ) {
        synchronized (chunkLifecycleLock) {
            if (!sodiumInWindowLocked(sectionX, sectionY, sectionZ)) {
                return -1L;
            }
            ChunkSlot slot = chunkSlots.get(sodiumIndex(sectionX, sectionY, sectionZ));
            return slot == null ? -1L : slot.generation;
        }
    }

    /**
     * Publishes a section meshed on a Sodium worker. Takes ownership of {@code layers}; dropped if
     * the slot was relocated or released since {@link #sodiumBegin} returned {@code generation}
     * (or {@code cancelled}). {@code region} is the worker's block view, used for the
     * far-terrain border columns.
     */
    public static void sodiumPublish(SectionPos sectionPos, long generation,
        BlockAndTintGetter region, Map<ChunkSectionLayer, MeshData> layers,
        SectionBufferBuilderPack storage, BooleanSupplier cancelled, boolean important) {
        int index;
        synchronized (chunkLifecycleLock) {
            if (!sodiumInWindowLocked(sectionPos.x(), sectionPos.y(), sectionPos.z())) {
                closeUntransferredBuiltBuffers(layers, null);
                return;
            }
            index = sodiumIndex(sectionPos.x(), sectionPos.y(), sectionPos.z());
        }
        publishCompiled(index, SODIUM_OWNER, sectionPos.origin(), generation, region, layers,
            storage, cancelled, important);
    }

    /**
     * The section has nothing to draw (only air, or its geometry vanished): tells native the slot
     * is empty, with no far-terrain border columns.
     */
    public static void sodiumInvalidate(int sectionX, int sectionY, int sectionZ) {
        synchronized (chunkLifecycleLock) {
            if (!sodiumInWindowLocked(sectionX, sectionY, sectionZ)) {
                return;
            }
            int index = sodiumIndex(sectionX, sectionY, sectionZ);
            ChunkSlot slot = chunkSlots.get(index);
            if (slot == null || slot.owner != SODIUM_OWNER || slot.relocating
                || slot.origin.getX() != (sectionX << 4) || slot.origin.getY() != (sectionY << 4)
                || slot.origin.getZ() != (sectionZ << 4)) {
                return;
            }
            try (VistaTerrain.Upload upload = VistaTerrain.EMPTY.upload()) {
                invalidateSingle(index, slot.origin.getX(), slot.origin.getY(), slot.origin.getZ(),
                    slot.generation, upload.count, upload.address);
            }
            nativeGeometry.clear(index);
            builtChunkCount++;
        }
    }

    /** The section is gone (chunk unloaded): drops its data and any publish still in flight. */
    public static void sodiumRelease(int sectionX, int sectionY, int sectionZ) {
        synchronized (chunkLifecycleLock) {
            if (sodiumInWindowLocked(sectionX, sectionY, sectionZ)) {
                sodiumRelocateLocked(sectionX, sectionY, sectionZ);
            }
        }
    }

    /**
     * Whether the section's terrain is in the ray tracer, for a section Sodium reports as built:
     * sections outside the window or without geometry count as ready, like in vanilla's path.
     */
    public static boolean sodiumSectionReady(int sectionX, int sectionY, int sectionZ) {
        int index;
        synchronized (chunkLifecycleLock) {
            if (!sodiumInWindowLocked(sectionX, sectionY, sectionZ)) {
                return true;
            }
            index = sodiumIndex(sectionX, sectionY, sectionZ);
            if (!nativeGeometry.get(index)) {
                return true;
            }
        }
        return isChunkReady(index);
    }

    /** The window as [minX, minZ, maxX, maxZ] in section coordinates, or null before it exists. */
    public static int[] sodiumWindow() {
        synchronized (chunkLifecycleLock) {
            if (sodiumSizeXZ == 0 || !sodiumCenterValid) {
                return null;
            }
            return new int[]{sodiumCenterX - sodiumRadius, sodiumCenterZ - sodiumRadius,
                sodiumCenterX + sodiumRadius, sodiumCenterZ + sodiumRadius};
        }
    }

    public static int sodiumMinSectionY() {
        return sodiumMinSectionY;
    }

    public static int sodiumMaxSectionY() {
        return sodiumMinSectionY + sodiumSizeY - 1;
    }
}
