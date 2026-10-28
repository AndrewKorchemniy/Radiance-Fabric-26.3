package com.radiance.client.vista;

import com.radiance.client.constant.VulkanConstants;
import com.radiance.client.proxy.vulkan.TextureProxy;
import com.radiance.client.texture.TextureTracker;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/**
 * A growable atlas of 16x16 RGBA8 tiles that native samples for far terrain
 * ({@code vista_material.glsl}): 256 tiles per row, tile {@code n} at column {@code n % 256}, row
 * {@code n / 256}, five mip levels. Tile 0 means "no texture", so ids start at 1 and the tile in
 * the first slot stays empty.
 *
 * <p>The tile pixels are ratios against the face's average colour (a byte of 127.5 means "same as
 * the average"), because native multiplies the vertex colour by twice the tile. Alpha is the
 * texture's coverage. Identical tiles share one id. Any thread may add tiles; {@link #update}
 * uploads the new ones from the render thread.
 */
public class VistaTileAtlas {
    public static final int TILE_SIZE = 16;
    public static final int TILE_BYTES = TILE_SIZE * TILE_SIZE * 4;
    public static final int MAX_TILES = 65536;
    private static final int MIP_LEVELS = 5;

    private final List<byte[]> tiles = new ArrayList<>();
    private final Map<Key, Integer> ids = new HashMap<>();
    private int texture;
    private int rows;
    private int uploaded;

    private record Key(byte[] pixels) {
        @Override public boolean equals(Object other) {
            return other instanceof Key key && Arrays.equals(pixels, key.pixels);
        }

        @Override public int hashCode() { return Arrays.hashCode(pixels); }
    }

    public VistaTileAtlas() {
        tiles.add(new byte[TILE_BYTES]);
    }

    /** The tile id for 16x16 RGBA bytes (row-major, row 0 first), or 0 when the atlas is full. */
    public synchronized int add(byte[] pixels) {
        if (pixels.length != TILE_BYTES) { throw new IllegalArgumentException("Tiles are 16x16 RGBA8"); }
        Key key = new Key(pixels);
        Integer existing = ids.get(key);
        if (existing != null) { return existing; }
        if (tiles.size() >= MAX_TILES) { return 0; }
        int id = tiles.size();
        tiles.add(pixels);
        ids.put(key, id);
        return id;
    }

    public synchronized int tileCount() { return tiles.size(); }

    /** The native texture id, or 0 until {@link #update} has run with tiles to show. */
    public int id() { return texture; }

    /** Allocates the native texture and uploads tiles added since the last call. Render thread only. */
    public synchronized void update() {
        int count = tiles.size();
        if (count <= 1) { return; }
        if (texture == 0) { texture = TextureProxy.generateTextureId(); }
        if (rows == 0 || count > rows * 256) {
            int size = Math.max(16, rows);
            while (count > size * 256) { size *= 2; }
            rows = size;
            TextureProxy.prepareImage(texture, MIP_LEVELS, 4096, rows * TILE_SIZE,
                VulkanConstants.VkFormat.VK_FORMAT_R8G8B8A8_UNORM.getValue());
            TextureProxy.setFilter(texture, 1, 1);
            TextureProxy.setClamp(texture, 2);
            uploaded = 1;
        }
        if (uploaded >= count) { return; }
        long generation = TextureTracker.captureTarget(texture).generation();
        ByteBuffer pixels = MemoryUtil.memAlloc(TILE_BYTES);
        try {
            for (int id = uploaded; id < count; id++) {
                byte[] mip = tiles.get(id);
                for (int level = 0, size = TILE_SIZE; level < MIP_LEVELS; level++, size >>= 1) {
                    pixels.clear().put(mip).flip();
                    TextureProxy.queueUploadRegion(new TextureProxy.TextureUploadRegion(
                        MemoryUtil.memAddress(pixels), mip.length, size, texture, 0, 0,
                        (id % 256) * size, (id / 256) * size, size, size, level,
                        TextureProxy.UPLOAD_KIND_MAIN, 4, generation));
                    if (size == 1) { break; }
                    mip = nextMip(mip, size);
                }
            }
            uploaded = count;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    /** Box filter that weights colour by alpha so cut-out texels don't bleed into the average. */
    private static byte[] nextMip(byte[] mip, int size) {
        byte[] next = new byte[size * size];
        int half = size / 2;
        for (int y = 0; y < half; y++) {
            for (int x = 0; x < half; x++) {
                int[] sums = new int[4];
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int source = ((y * 2 + dy) * size + x * 2 + dx) * 4;
                        for (int channel = 0; channel < 4; channel++) {
                            sums[channel] += Byte.toUnsignedInt(mip[source + channel])
                                * (channel == 3 ? 1 : Byte.toUnsignedInt(mip[source + 3]));
                        }
                    }
                }
                int target = (y * half + x) * 4;
                for (int channel = 0; channel < 4; channel++) {
                    next[target + channel] = (byte) (channel == 3 ? (sums[channel] + 2) / 4
                        : sums[3] == 0 ? 128 : (sums[channel] + sums[3] / 2) / sums[3]);
                }
            }
        }
        return next;
    }

    /** Forgets every tile; the native texture is kept and refilled. */
    public synchronized void reset() {
        tiles.clear();
        ids.clear();
        tiles.add(new byte[TILE_BYTES]);
        rows = 0;
        uploaded = 0;
    }

    public synchronized void close() {
        if (texture != 0) { TextureProxy.releaseTexture(texture); }
        texture = 0;
        reset();
    }
}
