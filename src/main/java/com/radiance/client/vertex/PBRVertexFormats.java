package com.radiance.client.vertex;

import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_COLOR_LAYER;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_COORDINATE;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_GLINT_TEXTURE;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_GLINT_UV;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_LIGHT_UV;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_NORM;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_OVERLAY_UV;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_POS;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_POST_BASE;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_TEXTURE_ID;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_TEXTURE_UV;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.radiance.client.vertex.PBRVertexFormatElements.Element;
import java.util.Arrays;
import java.util.List;

public final class PBRVertexFormats {

    /** 64 bytes per vertex: entities, block entities, items, particles and other per-frame geometry. */
    public static final PBRVertexFormat ENTITY_PBR_TRIANGLE = new PBRVertexFormat(List.of(
        PBR_POS, PBR_NORM, PBR_TEXTURE_UV, PBR_COLOR_LAYER, PBR_LIGHT_UV, PBR_OVERLAY_UV,
        PBR_GLINT_UV, PBR_TEXTURE_ID, PBR_GLINT_TEXTURE, PBR_POST_BASE, PBR_COORDINATE));

    /** 40 bytes per vertex: terrain sections and far terrain. */
    public static final PBRVertexFormat CHUNK_PBR_TRIANGLE = new PBRVertexFormat(List.of(
        PBR_POS, PBR_NORM, PBR_TEXTURE_UV, PBR_COLOR_LAYER, PBR_LIGHT_UV, PBR_TEXTURE_ID,
        PBR_COORDINATE));

    private PBRVertexFormats() {
    }

    /**
     * A tightly packed layout of {@link Element}s, plus an equivalent vanilla {@link VertexFormat} so
     * Radiance meshes can travel in vanilla's {@code MeshData}.
     */
    public static final class PBRVertexFormat {

        private final int[] offsetsByElementId = new int[PBRVertexFormatElements.ELEMENT_COUNT];
        private final int elementsMask;
        private final int vertexSize;
        private final VertexFormat vanillaFormat;

        private PBRVertexFormat(List<Element> elements) {
            Arrays.fill(this.offsetsByElementId, -1);
            VertexFormat.Builder builder = VertexFormat.builder(0);
            int offset = 0;
            int mask = 0;
            for (Element element : elements) {
                this.offsetsByElementId[element.id()] = offset;
                offset += element.size();
                mask |= element.mask();
                builder.addAttribute(element.name(), element.format());
            }
            this.elementsMask = mask;
            this.vertexSize = offset;
            this.vanillaFormat = builder.build();
            if (this.vanillaFormat.getVertexSize() != this.vertexSize) {
                throw new IllegalStateException("PBR vertex layout differs from vanilla's: "
                    + this.vertexSize + " != " + this.vanillaFormat.getVertexSize());
            }
        }

        public int getVertexSize() {
            return this.vertexSize;
        }

        public int getElementsMask() {
            return this.elementsMask;
        }

        public boolean contains(Element element) {
            return (this.elementsMask & element.mask()) != 0;
        }

        /** Byte offsets indexed by {@link Element#id()}, -1 for elements not in this format. */
        public int[] getOffsetsByElement() {
            return this.offsetsByElementId;
        }

        public VertexFormat vanillaFormat() {
            return this.vanillaFormat;
        }
    }
}
