package com.radiance.client.vertex;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererMap;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.LeashFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.feature.ShapeOutlineFeatureRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Turns 26.3 submits into Radiance vertices. On 1.21.4 Radiance handed vanilla its own
 * {@code MultiBufferSource}; 26.3 renderers only submit, and the feature renderers tessellate later
 * into a shared staging buffer. A capture collects one entity's submits in its own
 * {@link SubmitNodeStorage}, then runs a private set of feature renderers whose vertex builders are
 * redirected (see {@code RenderTypeFeatureRendererMixins}) to Radiance storage, so every entity keeps
 * its own geometry and vanilla's frame is never touched.
 */
@Environment(EnvType.CLIENT)
public final class SubmitCapture {

    private static final Set<FeatureRenderer<?>> CAPTURE_RENDERERS =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<SingleQuadParticle.Layer, RenderType> PARTICLE_RENDER_TYPES =
        new IdentityHashMap<>();
    private static @Nullable Function<RenderType, VertexConsumer> activeTarget;

    private final FeatureRendererMap renderers = new FeatureRendererMap();
    private final CaptureStorage storage = new CaptureStorage();
    private final SubmitNodeStorage nameTagStorage = new SubmitNodeStorage();
    private final PhaseOutput output = new PhaseOutput();

    public SubmitCapture() {
        // Shadows and debug gizmos are never traced (1.21.4 cancelled entity shadows too); outlines
        // are skipped when draining.
        register(FlameFeatureRenderer.TYPE, new FlameFeatureRenderer());
        register(ModelFeatureRenderer.TYPE, new ModelFeatureRenderer());
        register(TextFeatureRenderer.TYPE, new TextFeatureRenderer());
        register(LeashFeatureRenderer.TYPE, new LeashFeatureRenderer());
        register(ItemFeatureRenderer.TYPE, new ItemFeatureRenderer());
        register(CustomFeatureRenderer.TYPE, new CustomFeatureRenderer());
        register(BlockModelFeatureRenderer.TYPE, new BlockModelFeatureRenderer());
        register(MovingBlockFeatureRenderer.TYPE, new MovingBlockFeatureRenderer());
        register(ShapeOutlineFeatureRenderer.TYPE, new ShapeOutlineFeatureRenderer());
    }

    private <S extends SubmitNode> void register(FeatureRendererType<S> type,
        FeatureRenderer<S> renderer) {
        this.renderers.put(type, renderer);
        CAPTURE_RENDERERS.add(renderer);
    }

    /**
     * Called from {@code RenderTypeFeatureRenderer.getVertexBuilder}: the Radiance buffer for
     * {@code renderType} while a capture renderer is tessellating, otherwise null.
     */
    public static @Nullable VertexConsumer redirect(Object renderer, RenderType renderType) {
        Function<RenderType, VertexConsumer> target = activeTarget;
        if (target == null || !CAPTURE_RENDERERS.contains(renderer)) {
            return null;
        }
        return target.apply(renderType);
    }

    /** A textured quad render type for a particle layer, so particles carry their atlas and alpha. */
    public static RenderType particleRenderType(SingleQuadParticle.Layer layer) {
        return PARTICLE_RENDER_TYPES.computeIfAbsent(layer, key -> RenderType.create(
            key.translucent() ? "translucent_particle" : "opaque_particle",
            RenderSetup.builder(key.pipeline())
                .withTexture("Sampler0", key.textureAtlasLocation())
                .useLightmap()
                .createRenderSetup()));
    }

    /** The collector to submit into. Submits stay queued until {@link #flush}. */
    public SubmitNodeCollector collector() {
        return this.storage;
    }

    /**
     * Routes name tags into a separate storage, flushed with {@link #flushNameTags}, so they can be
     * post-rendered like 1.21.4's name tag buffer.
     */
    public void separateNameTags(boolean separate) {
        this.storage.nameTagTarget = separate ? this.nameTagStorage : null;
    }

    /** Tessellates everything submitted so far into {@code target} and empties the capture. */
    public void flush(Function<RenderType, VertexConsumer> target) {
        flush(this.storage, target);
    }

    public void flushNameTags(Function<RenderType, VertexConsumer> target) {
        flush(this.nameTagStorage, target);
    }

    /** Drops anything submitted but not flushed, for example after a renderer threw. */
    public void discard() {
        this.storage.getSubmitsPerOrder().clear();
        this.nameTagStorage.getSubmitsPerOrder().clear();
        this.storage.seeThrough().sortInto((submit, strictlyOrdered) -> {
        });
        this.nameTagStorage.seeThrough().sortInto((submit, strictlyOrdered) -> {
        });
    }

    private void flush(SubmitNodeStorage submits, Function<RenderType, VertexConsumer> target) {
        FeatureFrameContext context = createContext();
        Function<RenderType, VertexConsumer> previousTarget = activeTarget;
        activeTarget = target;
        this.output.context = context;
        this.output.target = target;
        try {
            // Orders ascend, as in vanilla. Everything is opaque to the ray tracer, so the phase
            // split only matters for which feature renderer runs.
            for (SubmitNodeCollection collection : submits.getSubmitsPerOrder().values()) {
                for (FeatureRenderPhase<?> phase : collection.allPhases()) {
                    if (phase == collection.outline) {
                        collection.outline.clear();
                        continue;
                    }
                    phase.sortInto(this.output);
                }
            }
            submits.seeThrough().sortInto(this.output);
        } catch (Throwable throwable) {
            submits.getSubmitsPerOrder().clear();
            throw throwable;
        } finally {
            activeTarget = previousTarget;
            this.output.context = null;
            this.output.target = null;
            for (FeatureRenderer<?> renderer : this.renderers.values()) {
                renderer.finishExecute(context);
            }
        }
    }

    private static FeatureFrameContext createContext() {
        Minecraft minecraft = Minecraft.getInstance();
        GameRenderer gameRenderer = minecraft.gameRenderer;
        return new FeatureFrameContext(
            gameRenderer.gameRenderState().optionsRenderState,
            minecraft.font,
            minecraft.getModelManager().getBlockStateModelSet(),
            minecraft.getBlockColors(),
            minecraft.getTextureManager(),
            minecraft.getAtlasManager(),
            gameRenderer.levelLightmap(),
            gameRenderer.renderBuffers().stagedVertexBuffer());
    }

    @SuppressWarnings("unchecked")
    private <S extends SubmitNode> void dispatch(FeatureRendererType<?> featureType,
        List<? extends SubmitNode> submits, boolean strictlyOrdered) {
        if (featureType == QuadParticleFeatureRenderer.TYPE) {
            for (SubmitNode submit : submits) {
                QuadParticleFeatureRenderer.Submit particles = (QuadParticleFeatureRenderer.Submit) submit;
                buildParticles(particles.particles(), particles.translucent(), this.output.target);
            }
            return;
        }

        FeatureRenderer<S> renderer = this.renderers.get((FeatureRendererType<S>) featureType);
        if (renderer == null) {
            return;
        }
        renderer.prepareGroup(this.output.context, (List<S>) submits, strictlyOrdered);
    }

    /** Builds the quads of {@code particles} whose layer matches {@code translucent}. */
    public static void buildParticles(QuadParticleRenderState particles, boolean translucent,
        Function<RenderType, VertexConsumer> target) {
        for (SingleQuadParticle.Layer layer : particles.layers()) {
            if (layer.translucent() == translucent) {
                particles.buildLayer(layer, target.apply(particleRenderType(layer)));
            }
        }
    }

    private final class PhaseOutput implements FeatureRenderPhase.Output {

        private @Nullable FeatureFrameContext context;
        private @Nullable Function<RenderType, VertexConsumer> target;

        @Override
        public void accept(SubmitNode submit, boolean strictlyOrdered) {
            dispatch(submit.featureType(), List.of(submit), strictlyOrdered);
        }

        @Override
        public <Submit extends SubmitNode> void acceptFeatureGroup(
            FeatureRendererType<Submit> featureType, Collection<Submit> submits,
            boolean strictlyOrdered) {
            if (submits.isEmpty()) {
                return;
            }
            dispatch(featureType,
                submits instanceof List<Submit> list ? list : new ArrayList<>(submits),
                strictlyOrdered);
        }
    }

    /** A storage that can send name tags elsewhere; entities submit them through the top level. */
    private static final class CaptureStorage extends SubmitNodeStorage {

        private @Nullable SubmitNodeStorage nameTagTarget;

        @Override
        public void submitNameTag(PoseStack poseStack, @Nullable Vec3 nameTagAttachment, int offset,
            Component name, boolean seeThrough, int lightCoords, CameraRenderState camera) {
            if (this.nameTagTarget != null) {
                this.nameTagTarget.submitNameTag(poseStack, nameTagAttachment, offset, name,
                    seeThrough, lightCoords, camera);
            } else {
                super.submitNameTag(poseStack, nameTagAttachment, offset, name, seeThrough,
                    lightCoords, camera);
            }
        }
    }
}
