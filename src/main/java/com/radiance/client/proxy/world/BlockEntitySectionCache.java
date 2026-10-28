package com.radiance.client.proxy.world;

import com.radiance.client.profiler.Profiler;
import java.util.BitSet;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

public final class BlockEntitySectionCache {
    private static final SectionRenderDispatcher.RenderSection[] EMPTY =
        new SectionRenderDispatcher.RenderSection[0];
    private static final BitSet nonempty = new BitSet();
    // ViewArea keeps its sections in a RotatingSectionStorage whose slots never change, so the
    // storage identifies the section set and sections[i].index == i.
    private static RotatingSectionStorage<SectionRenderDispatcher.RenderSection> sourceStorage;
    private static SectionRenderDispatcher.RenderSection[] source;
    private static SectionRenderDispatcher.RenderSection[] snapshot;
    private static boolean indexed;

    private BlockEntitySectionCache() {}

    public static synchronized void clear() {
        sourceStorage = null;
        source = null;
        snapshot = null;
        nonempty.clear();
        indexed = false;
    }

    public static synchronized void changed(SectionRenderDispatcher.RenderSection section) {
        int index = section.index;
        if (source == null || !indexed || index < 0 || index >= source.length
            || source[index] != section) return;
        boolean wasNonempty = nonempty.get(index);
        boolean isNonempty = !section.getSectionMesh().getRenderableBlockEntities().isEmpty();
        nonempty.set(index, isNonempty);
        if (wasNonempty || isNonempty) snapshot = null;
    }

    public static synchronized SectionRenderDispatcher.RenderSection[] sections(ViewArea area) {
        boolean rebuilt = false;
        if (sourceStorage != area.sections) {
            sourceStorage = area.sections;
            source = new SectionRenderDispatcher.RenderSection[sourceStorage.size()];
            int[] next = {0};
            sourceStorage.forEach(section -> source[next[0]++] = section);
            nonempty.clear();
            snapshot = null;
            indexed = true;
            for (int i = 0; i < source.length; i++) {
                var section = source[i];
                if (section == null || section.index != i) {
                    indexed = false;
                    break;
                }
                if (!section.getSectionMesh().getRenderableBlockEntities().isEmpty()) nonempty.set(i);
            }
            rebuilt = true;
        }
        if (!indexed) {
            if (Profiler.ENABLED) {
                Profiler.metric("block_entities.sections_total", source.length);
                Profiler.metric("block_entities.sections_scanned", source.length);
                Profiler.metric("block_entities.section_cache_fallback", 1);
            }
            return source;
        }
        if (snapshot == null) {
            int count = nonempty.cardinality();
            snapshot = count == 0 ? EMPTY : new SectionRenderDispatcher.RenderSection[count];
            int next = 0;
            for (int i = nonempty.nextSetBit(0); i >= 0; i = nonempty.nextSetBit(i + 1)) {
                snapshot[next++] = source[i];
            }
            rebuilt = true;
        }
        if (Profiler.ENABLED) {
            Profiler.metric("block_entities.sections_total", source.length);
            Profiler.metric("block_entities.sections_scanned", snapshot.length);
            Profiler.metric("block_entities.section_cache_rebuilt", rebuilt ? 1 : 0);
            Profiler.metric("block_entities.section_cache_reused", rebuilt ? 0 : 1);
        }
        return snapshot;
    }
}
