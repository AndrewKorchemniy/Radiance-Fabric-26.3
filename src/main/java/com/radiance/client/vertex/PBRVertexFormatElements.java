package com.radiance.client.vertex;

import com.mojang.renderpearl.api.GpuFormat;

/**
 * The attributes of Radiance's PBR vertex formats.
 *
 * <p>1.21.4 registered these in vanilla's global element table and let {@code VertexFormat} work out
 * offsets and masks. 26.3's elements are plain (name, offset, format) records with no ids or masks,
 * and {@code BufferBuilder} only writes vanilla's eight attribute names, so Radiance keeps its own
 * small element model; {@link PBRVertexFormats} lays the elements out.
 */
public final class PBRVertexFormatElements {

    public record Element(int id, String name, GpuFormat format) {

        public int mask() {
            return 1 << this.id;
        }

        public int size() {
            return this.format.blockSize();
        }
    }

    public static final Element PBR_POS = new Element(0, "Pos", GpuFormat.RGB32_FLOAT);
    public static final Element PBR_NORM = new Element(1, "NormalOct", GpuFormat.R32_UINT);
    public static final Element PBR_COLOR_LAYER = new Element(2, "ColorRGBA8", GpuFormat.R32_UINT);
    public static final Element PBR_TEXTURE_UV = new Element(3, "TextureUV", GpuFormat.RG32_FLOAT);
    public static final Element PBR_OVERLAY_UV = new Element(4, "OverlayUV", GpuFormat.R32_UINT);
    public static final Element PBR_TEXTURE_ID = new Element(5, "TextureID", GpuFormat.R32_UINT);
    public static final Element PBR_GLINT_UV = new Element(6, "GlintUVHalf", GpuFormat.R32_UINT);
    public static final Element PBR_GLINT_TEXTURE = new Element(7, "GlintTexture", GpuFormat.R32_UINT);
    public static final Element PBR_LIGHT_UV = new Element(8, "LightUV", GpuFormat.R32_UINT);
    public static final Element PBR_COORDINATE = new Element(9, "PackedData", GpuFormat.R32_UINT);
    public static final Element PBR_POST_BASE = new Element(10, "PostBase", GpuFormat.RGB32_FLOAT);

    public static final int ELEMENT_COUNT = 11;

    private PBRVertexFormatElements() {
    }
}
