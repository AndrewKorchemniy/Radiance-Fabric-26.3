package com.radiance.client.mod_support.voxy;

import com.radiance.client.proxy.world.VistaProxy;
import com.radiance.client.vista.VistaTerrain;
import me.cortex.voxy.client.core.model.ModelQueries;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.world.level.CardinalLighting;

/**
 * Native joins far terrain to nearby chunks and to other far sections with walls built from
 * columns of solid spans (the same data {@code ChunkTerrainBuilder} produces for loaded chunks).
 * This reads those columns from a Voxy section: runs of the same fully opaque block, or water,
 * along each column, for the cells that touch a 16-block border.
 */
public class VoxyColumns {
    private VoxyColumns() {}

    public static VistaTerrain build(WorldSection section, VoxyModels models, CardinalLighting lighting) {
        VistaTerrain.Builder builder = new VistaTerrain.Builder(lighting.west(), lighting.east(),
            lighting.north(), lighting.south());
        int lod = section.lvl;
        int cell = 1 << lod;
        int originX = section.x << (5 + lod);
        int originY = section.y << (5 + lod);
        int originZ = section.z << (5 + lod);
        long[] data = section._unsafeGetRawDataArray();
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                int blockX = originX + x * cell;
                int blockZ = originZ + z * cell;
                if (cell < 16 && (blockX & 15) != 0 && ((blockX + cell) & 15) != 0
                    && (blockZ & 15) != 0 && ((blockZ + cell) & 15) != 0) {
                    continue;
                }
                int runModel = 0, runStart = 0, runBiome = 0;
                for (int y = 0; y <= 32; y++) {
                    int model = 0, biome = 0;
                    if (y < 32) {
                        long state = data[WorldSection.getIndex(x, y, z)];
                        int block = Mapper.getBlockId(state);
                        if (block != 0 && models.factory.hasModelForBlockId(block)) {
                            model = models.factory.getModelId(block) + 1;
                            biome = Mapper.getBiomeId(state);
                        }
                    }
                    if (model != runModel) {
                        if (runModel != 0) {
                            emit(builder, models, runModel - 1, runBiome, blockX, blockZ, cell,
                                originY + runStart * cell, originY + y * cell);
                        }
                        runModel = model;
                        runStart = y;
                        runBiome = biome;
                    }
                }
            }
        }
        return builder.build();
    }

    private static void emit(VistaTerrain.Builder builder, VoxyModels models, int modelId, int biome,
        int blockX, int blockZ, int cell, int minY, int maxY) {
        long metadata = models.metadata(modelId);
        boolean water = models.isWater(metadata);
        if (!water && !ModelQueries.isFullyOpaque(metadata)) { return; }
        VoxyModels.Face top = models.face(modelId, 1, biome);
        if (top == null || top.colour() == 0) { return; }
        int material = water ? 12 << 16 : 0;
        builder.add(blockX, blockZ, minY, maxY, 0, cell, 0, cell, top.colour(),
            water ? VistaProxy.MATERIAL_WATER : 0,
            side(models, modelId, 4, biome, material), side(models, modelId, 5, biome, material),
            side(models, modelId, 2, biome, material), side(models, modelId, 3, biome, material));
    }

    private static int side(VoxyModels models, int modelId, int face, int biome, int material) {
        VoxyModels.Face info = models.face(modelId, face, biome);
        return info == null || info.tile() == 0 ? 0 : 0x80000000 | material | info.tile();
    }

    /** The west, east, north and south tile materials of a baked model, in {@code VistaTerrain} order. */
    public static int[] sides(VoxyModels models, int modelId) {
        long metadata = models.metadata(modelId);
        int material = models.isWater(metadata) ? 12 << 16 : 0;
        return new int[]{side(models, modelId, 4, 0, material), side(models, modelId, 5, 0, material),
            side(models, modelId, 2, 0, material), side(models, modelId, 3, 0, material)};
    }
}
