package com.radiance.client.mod_support.voxy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import me.cortex.voxy.client.core.model.ModelQueries;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import org.lwjgl.system.MemoryUtil;

/**
 * Turns Voxy's CPU mesh of a section into Radiance's 40-byte chunk PBR vertices, four per quad.
 *
 * <p>A Voxy quad is one 64-bit word (see {@code quad_format.glsl}):
 * <pre>
 *   bits  0-2   face: axis (0 = Y, 1 = Z, 2 = X) * 2 + side, so down, up, north, south, west, east
 *   bits  3-6   size along the first quad axis minus one
 *   bits  7-10  size along the second quad axis minus one
 *   bits 11-15  z, bits 16-20 y, bits 21-25 x: the corner in section voxels
 *   bits 26-41  model id
 *   bits 46-54  biome id
 *   bits 55-62  light: block light in the high nibble, sky light in the low nibble
 * </pre>
 * Voxy's vertex shader places a quad from the position, the model's per-face word (the covered
 * sub-rectangle and how far the face sits behind the block boundary) and the quad size. This
 * does the same arithmetic on the CPU, with one voxel being {@code 2^lod} blocks.
 */
public class VoxyMesh {
    /** The first axis of a quad runs along X, X, Y for faces on the Y, Z, X axes. */
    private static final int[] FIRST_AXIS = {0, 0, 1};
    /** The second axis runs along Z, Y, Z. */
    private static final int[] SECOND_AXIS = {2, 1, 2};
    private static final int[] NORMALS = {
        0x80010000, 0x7fff0000, 0x7fff7fff, 0, 0x00008001, 0x00007fff
    };
    /** Voxy widens quads slightly so neighbours don't leave cracks. */
    private static final float EPSILON = 0.00005f;
    private static final int MATERIAL_WATER = 12;
    /** Vista's per-vertex flags: colour layer, normals and lightmap are present. */
    private static final int VERTEX_FLAGS = 1 | 16 | 32;
    private static final int ALPHA_OPAQUE = 0, ALPHA_CUTOUT = 1, ALPHA_TRANSPARENT = 2;
    /** One call carries up to 1,048,576 vertices. */
    public static final int MAX_QUADS = 262144;

    /** Cut-outs with less coverage than this are skipped until native honours tile alpha. */
    private static final boolean SPARSE_CUTOUTS = Boolean.getBoolean("radiance.voxy.sparseCutouts");

    public record Result(ByteBuffer vertices, int quads, int skipped) {}

    private VoxyMesh() {}

    /** Null when the section has no geometry. {@code lod} is Voxy's detail level, 0 to 4. */
    public static Result convert(BuiltSection section, int lod, VoxyModels models) {
        if (section.geometryBuffer == null) { return null; }
        int total = (int) Math.min(section.geometryBuffer.size / 8, Integer.MAX_VALUE);
        int capacity = Math.min(total, MAX_QUADS);
        ByteBuffer vertices = MemoryUtil.memAlloc(capacity * 160).order(ByteOrder.nativeOrder());
        long output = MemoryUtil.memAddress(vertices);
        int quads = 0, skipped = 0;
        float scale = 1 << lod;
        for (int i = 0; i < total && quads < capacity; i++) {
            long quad = MemoryUtil.memGetLong(section.geometryBuffer.address + i * 8L);
            if (emit(quad, scale, models, output + quads * 160L)) { quads++; } else { skipped++; }
        }
        if (quads == 0) {
            MemoryUtil.memFree(vertices);
            return null;
        }
        vertices.limit(quads * 160);
        return new Result(vertices, quads, skipped + Math.max(0, total - capacity));
    }

    private static boolean emit(long quad, float scale, VoxyModels models, long target) {
        int face = (int) (quad & 7);
        if (face > 5) { return false; }
        int axis = face >> 1;
        int side = face & 1;
        int sizeFirst = (int) ((quad >> 3) & 15) + 1;
        int sizeSecond = (int) ((quad >> 7) & 15) + 1;
        int modelId = (int) ((quad >> 26) & 0xffff);
        int biome = (int) ((quad >> 46) & 0x1ff);
        int light = (int) ((quad >> 55) & 0xff);
        VoxyModels.Face info = models.face(modelId, face, biome);
        if (info == null || (info.tile() == 0 && info.colour() == 0)) { return false; }
        int faceData = models.faceData(modelId, face);
        long metadata = models.metadata(modelId);
        boolean translucent = ModelQueries.isTranslucent(metadata);
        boolean cutout = ((faceData >> 22) & 1) != 0 && !translucent;
        if (cutout && info.coverage() < 0.5f && !SPARSE_CUTOUTS) { return false; }

        // The covered sub-rectangle of the face, in sixteenths, and its depth behind the block edge.
        float minFirst = (faceData & 15) / 16.0f - EPSILON;
        float minSecond = ((faceData >> 8) & 15) / 16.0f - EPSILON;
        float sizeOfFirst = ((faceData >> 4) & 15) / 16.0f + 1.0f / 16.0f - minFirst;
        float sizeOfSecond = ((faceData >> 12) & 15) / 16.0f + 1.0f / 16.0f - minSecond;
        int depthBits = (faceData >> 16) & 63;
        float depth = (depthBits + (depthBits == 63 ? 1 : 0)) / 64.0f;
        float along = side == 1 ? 1.0f - depth : depth;
        float spanFirst = sizeOfFirst + sizeFirst - 1;
        float spanSecond = sizeOfSecond + sizeSecond - 1;

        float[] origin = {(quad >> 21) & 31, (quad >> 16) & 31, (quad >> 11) & 31};
        int first = FIRST_AXIS[axis], second = SECOND_AXIS[axis];
        int normalAxis = 3 - first - second;
        origin[first] += minFirst;
        origin[second] += minSecond;
        origin[normalAxis] += along;

        boolean water = models.isWater(metadata);
        int material = water ? MATERIAL_WATER : 0;
        int textureId = 0x80000000 | (material << 16) | info.tile();
        int alphaMode = translucent ? ALPHA_TRANSPARENT : cutout ? ALPHA_CUTOUT : ALPHA_OPAQUE;
        long emission = ModelQueries.lightEmission(metadata);
        int emissionBits = emission == 0 ? 0
            : (Float.floatToFloat16(emission / 15.0f) & 0xffff) << 16;
        int packed = VERTEX_FLAGS | (alphaMode << 8) | emissionBits;
        int lightUv = (light >> 4) * 16 | ((light & 15) * 16) << 16;
        // The corner order is counter-clockwise seen from outside.
        boolean reverse = (axis == 0) == (side == 1);
        for (int corner = 0; corner < 4; corner++) {
            int index = reverse ? (4 - corner) & 3 : corner;
            int cornerFirst = index == 1 || index == 2 ? 1 : 0;
            int cornerSecond = index >= 2 ? 1 : 0;
            float[] position = origin.clone();
            position[first] += spanFirst * cornerFirst;
            position[second] += spanSecond * cornerSecond;
            long vertex = target + corner * 40L;
            MemoryUtil.memPutFloat(vertex, position[0] * scale);
            MemoryUtil.memPutFloat(vertex + 4, position[1] * scale);
            MemoryUtil.memPutFloat(vertex + 8, position[2] * scale);
            MemoryUtil.memPutInt(vertex + 12, NORMALS[face]);
            // Voxy samples the face image directly, repeating once per voxel.
            MemoryUtil.memPutFloat(vertex + 16, minFirst + spanFirst * cornerFirst);
            MemoryUtil.memPutFloat(vertex + 20, minSecond + spanSecond * cornerSecond);
            MemoryUtil.memPutInt(vertex + 24, info.colour());
            MemoryUtil.memPutInt(vertex + 28, lightUv);
            MemoryUtil.memPutInt(vertex + 32, textureId);
            MemoryUtil.memPutInt(vertex + 36, packed);
        }
        return true;
    }
}
