package com.radiance.client.mod_support.sodium;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.function.BiConsumer;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The block entities of every section Sodium has built. Sodium only hands out the ones in its
 * visible render lists; the ray tracer also sees what is behind the camera or a wall, so entity
 * extraction walks this cache instead (as {@code BlockEntitySectionCache} does for vanilla's
 * sections). Main thread only: written from {@code RenderSection.setInfo}, read during extraction.
 */
public final class SodiumBlockEntities {

    private static final Long2ObjectOpenHashMap<BuiltSectionInfo> SECTIONS = new Long2ObjectOpenHashMap<>();

    private SodiumBlockEntities() {
    }

    public static void set(long sectionNode, BuiltSectionInfo info) {
        if (info == null || (isEmpty(info.culledBlockEntities) && isEmpty(info.globalBlockEntities))) {
            SECTIONS.remove(sectionNode);
        } else {
            SECTIONS.put(sectionNode, info);
        }
    }

    public static void clear() {
        SECTIONS.clear();
    }

    /** Calls {@code consumer} with every block entity and whether Sodium keeps it as a global one. */
    public static void forEach(BiConsumer<BlockEntity, Boolean> consumer) {
        for (BuiltSectionInfo info : SECTIONS.values()) {
            if (info.culledBlockEntities != null) {
                for (BlockEntity blockEntity : info.culledBlockEntities) {
                    consumer.accept(blockEntity, false);
                }
            }
            if (info.globalBlockEntities != null) {
                for (BlockEntity blockEntity : info.globalBlockEntities) {
                    consumer.accept(blockEntity, true);
                }
            }
        }
    }

    private static boolean isEmpty(BlockEntity[] blockEntities) {
        return blockEntities == null || blockEntities.length == 0;
    }
}
