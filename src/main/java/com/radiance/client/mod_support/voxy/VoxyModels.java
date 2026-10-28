package com.radiance.client.mod_support.voxy;

import com.radiance.client.vista.VistaTileAtlas;
import com.radiance.mixin_related.extensions.voxy.IVoxyBiomeUploadExt;
import com.radiance.mixin_related.extensions.voxy.IVoxyModelFactoryExt;
import com.radiance.mixin_related.extensions.voxy.IVoxyModelUploadExt;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.ModelQueries;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * A CPU copy of what Voxy would have uploaded to its model buffers, and the conversion of its
 * baked 16x16 face images into Vista tiles.
 *
 * <p>Voxy bakes every distinct block model into six faces (down, up, north, south, west, east)
 * laid out 3 wide and 2 tall in a 48x32 image, with the face index selecting the column
 * ({@code face >> 1}) and row ({@code face & 1}). A quad names its model, face and biome; the
 * face word gives the covered area, depth and tint behaviour, and the biome picks a tint colour.
 */
public class VoxyModels {
    private static final int SIZE = ModelFactory.MODEL_TEXTURE_SIZE;
    private static final int ROW = SIZE * 3;

    /** A face as a Vista tile plus the vertex colour it is multiplied with ({@code 0xAABBGGRR}). */
    public record Face(int tile, int colour, float coverage) {}

    private static final Face EMPTY = new Face(0, 0, 0);

    private static final class Model {
        final int[] faceData = new int[6];
        int flags;
        volatile int colourTint;
        final int[] pixels = new int[ROW * SIZE * 2];
    }

    public final ModelBakerySubsystem bakery;
    public final ModelFactory factory;
    private final VistaTileAtlas atlas;
    private final AtomicReferenceArray<Model> models = new AtomicReferenceArray<>(1 << 16);
    private final ConcurrentHashMap<Long, Face> faces = new ConcurrentHashMap<>();
    private final Object drainLock = new Object();
    private volatile int[] biomeColours = new int[0];

    public VoxyModels(ModelBakerySubsystem bakery, VistaTileAtlas atlas) {
        this.bakery = bakery;
        this.factory = bakery.factory;
        this.atlas = atlas;
    }

    /** Copies finished bakes out of Voxy's upload queue. Safe from any thread. */
    public void drain() {
        synchronized (drainLock) {
            Object upload;
            while ((upload = ((IVoxyModelFactoryExt) factory).radiance$pollUpload()) != null) {
                if (upload instanceof IVoxyModelUploadExt model) {
                    accept(model);
                } else if (upload instanceof IVoxyBiomeUploadExt biomes) {
                    accept(biomes);
                }
            }
        }
    }

    private void accept(IVoxyModelUploadExt upload) {
        try {
            Model model = new Model();
            long record = upload.radiance$model().address;
            for (int face = 0; face < 6; face++) {
                model.faceData[face] = MemoryUtil.memGetInt(record + 4L * face);
            }
            model.flags = MemoryUtil.memGetInt(record + 24);
            model.colourTint = MemoryUtil.memGetInt(record + 28);
            MemoryUtil.memIntBuffer(upload.radiance$texture().address, model.pixels.length)
                .get(model.pixels);
            int index = upload.radiance$biomeColourIndex();
            MemoryBuffer colours = upload.radiance$biomeColours();
            if (index != -1 && colours != null) {
                int count = (int) (colours.size / 4);
                int[] table = biomeColours;
                if (table.length < index + count) { table = Arrays.copyOf(table, index + count); }
                MemoryUtil.memIntBuffer(colours.address, count).get(table, index, count);
                biomeColours = table;
            }
            models.set(upload.radiance$modelId(), model);
        } finally {
            upload.radiance$release();
        }
    }

    private void accept(IVoxyBiomeUploadExt upload) {
        try {
            MemoryBuffer colours = upload.radiance$colours();
            int[] table = new int[(int) (colours.size / 4)];
            MemoryUtil.memIntBuffer(colours.address, table.length).get(table);
            biomeColours = table;
            MemoryBuffer pairs = upload.radiance$modelIndices();
            for (long offset = 0; offset < pairs.size; offset += 8) {
                long pair = MemoryUtil.memGetLong(pairs.address + offset);
                Model model = models.get((int) pair);
                if (model != null) { model.colourTint = (int) (pair >>> 32); }
            }
        } finally {
            upload.radiance$release();
        }
    }

    private Model model(int modelId) {
        Model model = models.get(modelId);
        if (model == null) {
            // The bake publishes its model id a moment before its upload is queued.
            for (int attempt = 0; attempt < 50 && model == null; attempt++) {
                drain();
                model = models.get(modelId);
                if (model == null) { Thread.onSpinWait(); }
            }
        }
        return model;
    }

    public long metadata(int modelId) { return factory.getModelMetadataFromClientId(modelId); }

    public boolean isWater(long metadata) {
        return ModelQueries.isFluid(metadata) && ModelQueries.isTranslucent(metadata);
    }

    /** The face word: bits 22 and 23 mark alpha cut-outs, 24 and 25 the tint behaviour. */
    public int faceData(int modelId, int face) {
        Model model = model(modelId);
        return model == null ? 0 : model.faceData[face];
    }

    /** The tile and vertex colour for a model face seen in a biome, or null if it isn't baked. */
    public Face face(int modelId, int face, int biome) {
        Model model = model(modelId);
        if (model == null) { return null; }
        int tintState = (model.faceData[face] >> 24) & 3;
        int tint = 0;
        if (tintState != 0) {
            int colour = model.colourTint;
            if ((model.flags & 2) != 0) {
                int[] table = biomeColours;
                int index = colour + biome;
                colour = colour >= 0 && index < table.length ? table[index] : -1;
            }
            // White (-1) is "no tint"; an unwritten table entry is 0.
            tint = colour == -1 || (colour >>> 24) == 0 ? 0 : colour;
        }
        long key = modelId | ((long) face << 16) | ((long) tint << 32);
        Face cached = faces.get(key);
        if (cached == null) {
            cached = bake(model, modelId, face, tintState, tint);
            faces.put(key, cached);
        }
        return cached;
    }

    private Face bake(Model model, int modelId, int face, int tintState, int tint) {
        int[] pixels = new int[SIZE * SIZE];
        int originX = (face >> 1) * SIZE;
        int originY = (face & 1) * SIZE;
        for (int y = 0; y < SIZE; y++) {
            System.arraycopy(model.pixels, (originY + y) * ROW + originX, pixels, y * SIZE, SIZE);
        }
        int tintRed = (tint >>> 16) & 255;
        int tintGreen = (tint >>> 8) & 255;
        int tintBlue = tint & 255;
        if (tintState == 1 && tint != 0) {
            // Partial tint, like grass block sides: only the grey texels take the colour.
            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i];
                int red = pixel & 255, green = (pixel >>> 8) & 255, blue = (pixel >>> 16) & 255;
                if ((pixel >>> 24) != 0 && Math.abs(red - green) <= 5 && Math.abs(green - blue) <= 5) {
                    pixels[i] = (pixel & 0xff000000) | (red * tintRed / 255)
                        | ((green * tintGreen / 255) << 8) | ((blue * tintBlue / 255) << 16);
                }
            }
        }
        long red = 0, green = 0, blue = 0, alpha = 0;
        int covered = 0;
        for (int pixel : pixels) {
            if ((pixel >>> 24) == 0) { continue; }
            red += pixel & 255;
            green += (pixel >>> 8) & 255;
            blue += (pixel >>> 16) & 255;
            alpha += pixel >>> 24;
            covered++;
        }
        if (covered == 0) { return EMPTY; }
        float averageRed = Math.max(red / (float) covered, 1);
        float averageGreen = Math.max(green / (float) covered, 1);
        float averageBlue = Math.max(blue / (float) covered, 1);
        boolean translucent = ModelQueries.isTranslucent(metadata(modelId));
        byte[] tile = new byte[VistaTileAtlas.TILE_BYTES];
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int target = i * 4;
            int pixelAlpha = pixel >>> 24;
            if (pixelAlpha == 0) {
                tile[target] = tile[target + 1] = tile[target + 2] = (byte) 128;
                continue;
            }
            tile[target] = ratio(pixel & 255, averageRed);
            tile[target + 1] = ratio((pixel >>> 8) & 255, averageGreen);
            tile[target + 2] = ratio((pixel >>> 16) & 255, averageBlue);
            // A translucent face carries its opacity in the vertex colour; the tile only marks coverage.
            tile[target + 3] = (byte) (translucent ? 255 : pixelAlpha);
        }
        float outRed = averageRed, outGreen = averageGreen, outBlue = averageBlue;
        if (tintState == 2 && tint != 0) {
            outRed = outRed * tintRed / 255;
            outGreen = outGreen * tintGreen / 255;
            outBlue = outBlue * tintBlue / 255;
        }
        int outAlpha = translucent ? Math.max(1, (int) (alpha / covered)) : 255;
        int colour = (outAlpha << 24) | (clamp(outBlue) << 16) | (clamp(outGreen) << 8) | clamp(outRed);
        return new Face(atlas.add(tile), colour, covered / (float) (SIZE * SIZE));
    }

    private static byte ratio(int value, float average) {
        return (byte) Math.min(255, Math.round(value / average * 127.5f));
    }

    private static int clamp(float value) { return Math.max(0, Math.min(255, Math.round(value))); }
}
