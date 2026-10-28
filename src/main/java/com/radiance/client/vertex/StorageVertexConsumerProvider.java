package com.radiance.client.vertex;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.radiance.client.constant.Constants;
import com.radiance.mixins.vulkan_render_integration.RenderSetupAccessor;
import com.radiance.mixins.vulkan_render_integration.RenderTypeAccessor;
import com.radiance.mixins.vulkan_render_integration.TextureBindingAccessor;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Per-entity vertex storage, one buffer per render type. On 1.21.4 this was the
 * {@code MultiBufferSource} vanilla rendered into; 26.3 has no buffer sources, so
 * {@link SubmitCapture} hands out these buffers to the feature renderers instead.
 */
@Environment(EnvType.CLIENT)
public class StorageVertexConsumerProvider {

    /** Lightning has no texture in vanilla; the ray tracer samples Radiance's lightning texture. */
    private static final RenderType TEXTURED_LIGHTNING = RenderType.create("lightning",
        RenderSetup.builder(RenderPipelines.LIGHTNING)
            .withTexture("Sampler0", Identifier.withDefaultNamespace("textures/block/lightning.png"))
            .sortOnUpload()
            .createRenderSetup());

    /** Render types whose {@code Sampler0} is a glint texture, used to give glint vertices its id. */
    private static final RenderType ITEM_GLINT = RenderTypes.patternedShieldGlint();
    private static final RenderType ARMOR_GLINT = RenderTypes.trimmedArmorGlint();

    private static final VertexConsumer DISCARD = new DiscardingVertexConsumer();

    protected final Map<RenderType, VertexConsumer> pending = new HashMap<>();
    protected final Map<RenderType, VertexConsumer> wrapped = new HashMap<>();
    protected final Map<RenderType, ByteBufferBuilder> allocated = new HashMap<>();

    private int size = 0;
    private int entityCoordinate = 0;
    private boolean entityNormalOffset = false;
    private float entityBaseX = 0.0f;
    private float entityBaseY = 0.0f;
    private float entityBaseZ = 0.0f;
    private boolean entityContextChangedAfterPbrWrite = false;

    public StorageVertexConsumerProvider(int size) {
        this.size = size;
    }

    public VertexConsumer getBuffer(RenderType renderLayer) {
        // 26.3 draws trimmed armor and patterned shield glint as a second, coplanar copy of the
        // model. On 1.21.4 glint was folded into the base vertices; a second surface would only
        // z-fight in the ray tracer, so it is dropped.
        if (renderLayer == ITEM_GLINT || renderLayer == ARMOR_GLINT) {
            return DISCARD;
        }
        if (renderLayer == RenderTypes.lightning()) {
            renderLayer = TEXTURED_LIGHTNING;
        }

        VertexConsumer vertexConsumer = this.wrapped.get(renderLayer);
        if (vertexConsumer != null) {
            return vertexConsumer;
        }

        ByteBufferBuilder bufferAllocator = new ByteBufferBuilder(Math.max(size, 256));
        allocated.put(renderLayer, bufferAllocator);

        PrimitiveTopology drawMode = renderLayer.primitiveTopology();
        VertexConsumer storage;
        if (drawMode == PrimitiveTopology.QUADS) {
            PBRVertexConsumer pbrVertexConsumer = new PBRVertexConsumer(bufferAllocator, renderLayer);
            pbrVertexConsumer.setEntityBuildContext(
                entityCoordinate,
                entityNormalOffset,
                entityBaseX,
                entityBaseY,
                entityBaseZ);
            storage = pbrVertexConsumer;
            vertexConsumer = wrapGlint(pbrVertexConsumer, renderLayer);
        } else {
            storage = new BufferBuilder(bufferAllocator, drawMode, nativeFormat(renderLayer.format()));
            vertexConsumer = storage;
        }
        this.pending.put(renderLayer, storage);
        this.wrapped.put(renderLayer, vertexConsumer);
        return vertexConsumer;
    }

    /**
     * 26.3 glint render types draw the item and its glint in one pass, with the glint texture bound
     * as {@code GlintSampler}. Radiance writes both into the same PBR vertices, as on 1.21.4.
     */
    private static VertexConsumer wrapGlint(PBRVertexConsumer delegate, RenderType renderLayer) {
        Identifier glintTexture = glintTexture(renderLayer);
        if (glintTexture == null) {
            return delegate;
        }
        RenderType glintRenderLayer = ItemFeatureRenderer.ENCHANTED_GLINT_ARMOR.equals(glintTexture)
            ? ARMOR_GLINT : ITEM_GLINT;
        if (RenderTypeInfo.name(renderLayer).endsWith("_glint_special")) {
            return new SpecialGlintVertexConsumer(delegate, glintRenderLayer);
        }
        return new PBRVertexConsumer.GLint(delegate, glintRenderLayer);
    }

    private static @Nullable Identifier glintTexture(RenderType renderLayer) {
        Object binding = ((RenderSetupAccessor) (Object) ((RenderTypeAccessor) renderLayer)
            .radiance$getState()).radiance$getTextures().get("GlintSampler");
        return binding == null ? null : ((TextureBindingAccessor) binding).radiance$getLocation();
    }

    /**
     * 26.3 lines carry a per-vertex width that native does not decode. The widthless layouts are
     * byte-for-byte prefixes of the same data, so they are written instead.
     */
    private static VertexFormat nativeFormat(VertexFormat format) {
        if (format == DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH) {
            return DefaultVertexFormat.POSITION_COLOR_NORMAL;
        }
        if (format == DefaultVertexFormat.POSITION_COLOR_LINE_WIDTH) {
            return DefaultVertexFormat.POSITION_COLOR;
        }
        return format;
    }

    /** Whether native knows how to decode {@code format}; other layouts are skipped. */
    public static boolean isNativeVertexFormat(VertexFormat format) {
        for (Constants.VertexFormats vertexFormat : Constants.VertexFormats.values()) {
            if (vertexFormat.getVertexFormat() == format) {
                return true;
            }
        }
        return false;
    }

    public StorageVertexConsumerProvider setEntityBuildContext(
        int coordinate,
        boolean normalOffset,
        double baseX,
        double baseY,
        double baseZ) {
        float nextBaseX = (float) baseX;
        float nextBaseY = (float) baseY;
        float nextBaseZ = (float) baseZ;
        if (entityCoordinate == coordinate && entityNormalOffset == normalOffset &&
            Float.compare(entityBaseX, nextBaseX) == 0 && Float.compare(entityBaseY, nextBaseY) == 0 &&
            Float.compare(entityBaseZ, nextBaseZ) == 0) {
            return this;
        }

        for (VertexConsumer vertexConsumer : pending.values()) {
            if (vertexConsumer instanceof PBRVertexConsumer pbrVertexConsumer) {
                if (pbrVertexConsumer.getVertexCount() > 0) {
                    entityContextChangedAfterPbrWrite = true;
                }
                pbrVertexConsumer.setEntityBuildContext(
                    coordinate,
                    normalOffset,
                    nextBaseX,
                    nextBaseY,
                    nextBaseZ);
            }
        }

        entityCoordinate = coordinate;
        entityNormalOffset = normalOffset;
        entityBaseX = nextBaseX;
        entityBaseY = nextBaseY;
        entityBaseZ = nextBaseZ;
        return this;
    }

    public boolean hasFinalEntityBuildContext(
        int coordinate,
        boolean normalOffset,
        float baseX,
        float baseY,
        float baseZ) {
        return !entityContextChangedAfterPbrWrite &&
            entityCoordinate == coordinate &&
            entityNormalOffset == normalOffset &&
            Float.compare(entityBaseX, baseX) == 0 &&
            Float.compare(entityBaseY, baseY) == 0 &&
            Float.compare(entityBaseZ, baseZ) == 0;
    }

    /** The storage consumer of each render type: a {@link PBRVertexConsumer} or a {@code BufferBuilder}. */
    public Map<RenderType, VertexConsumer> getLayers() {
        return this.pending;
    }

    boolean detachTransferredAllocator(RenderType renderLayer, ByteBufferBuilder allocator) {
        ByteBufferBuilder current = this.allocated.get(renderLayer);
        if (current != allocator) {
            return false;
        }

        this.allocated.remove(renderLayer);
        return true;
    }

    public void close() {
        for (Map.Entry<RenderType, ByteBufferBuilder> entry : this.allocated.entrySet()) {
            entry.getValue()
                .close();
        }
        this.allocated.clear();
        this.pending.clear();
        this.wrapped.clear();
    }

    /**
     * Item glint on special-cased items (compasses, clocks) is projected from the item's pose rather
     * than taken from its UVs. Vanilla passes that pose per quad, so the overlay is built per quad.
     */
    private static final class SpecialGlintVertexConsumer extends ForwardingVertexConsumer {

        private final PBRVertexConsumer pbrDelegate;
        private final RenderType glintRenderLayer;

        private SpecialGlintVertexConsumer(PBRVertexConsumer delegate, RenderType glintRenderLayer) {
            super(new PBRVertexConsumer.GLint(delegate, glintRenderLayer));
            this.pbrDelegate = delegate;
            this.glintRenderLayer = glintRenderLayer;
        }

        @Override
        public void putBakedQuadWithGlint(PoseStack.Pose pose, BakedQuad quad, QuadInstance instance,
            PoseStack.Pose sheetedDecalPose) {
            new PBRVertexConsumer.GLintOverlay(this.pbrDelegate, this.glintRenderLayer,
                sheetedDecalPose, ItemFeatureRenderer.SPECIAL_FOIL_TEXTURE_SCALE)
                .putBakedQuad(pose, quad, instance);
        }
    }

    private static class ForwardingVertexConsumer implements VertexConsumer {

        private final VertexConsumer delegate;

        private ForwardingVertexConsumer(VertexConsumer delegate) {
            this.delegate = delegate;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            delegate.setColor(color);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            delegate.setUv3(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            delegate.setLineWidth(width);
            return this;
        }
    }

    private static final class DiscardingVertexConsumer implements VertexConsumer {

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
