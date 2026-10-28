package com.radiance.client.vertex;

import com.radiance.client.texture.TextureTracker;
import com.radiance.mixins.vulkan_render_integration.RenderSetupAccessor;
import com.radiance.mixins.vulkan_render_integration.RenderTypeAccessor;
import com.radiance.mixins.vulkan_render_integration.TextureBindingAccessor;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * What the ray tracer needs to know about a vanilla material. On 1.21.4 this was read from
 * {@code RenderType.CompositeRenderType}'s state shards; 26.3 keeps it in {@code RenderSetup}.
 */
public final class RenderTypeInfo {

    public static final int ALPHA_MODE_OPAQUE = 0;
    public static final int ALPHA_MODE_CUTOUT = 1;
    public static final int ALPHA_MODE_TRANSPARENT = 2;

    private RenderTypeInfo() {
    }

    public static String name(RenderType renderType) {
        return ((RenderTypeAccessor) renderType).radiance$getName();
    }

    /** The texture bound to {@code Sampler0}, or null if the material is untextured. */
    public static @Nullable Identifier mainTexture(RenderType renderType) {
        Object binding = ((RenderSetupAccessor) (Object) ((RenderTypeAccessor) renderType)
            .radiance$getState()).radiance$getTextures().get("Sampler0");
        return binding == null ? null : ((TextureBindingAccessor) binding).radiance$getLocation();
    }

    /** The native id of the {@code Sampler0} texture; untextured materials use the missing texture. */
    public static int mainTextureId(RenderType renderType) {
        Identifier texture = mainTexture(renderType);
        return TextureTracker.idOf(texture != null ? texture : MissingTextureAtlasSprite.getLocation());
    }

    public static int alphaMode(RenderType renderType) {
        String name = name(renderType);
        if (name.contains("solid")) {
            return ALPHA_MODE_OPAQUE;
        }
        if (name.contains("cutout") || !renderType.hasBlending()) {
            return ALPHA_MODE_CUTOUT;
        }
        return ALPHA_MODE_TRANSPARENT;
    }

    /** Terrain layers are no longer render types in 26.3; all of them sample the block atlas. */
    public static int alphaMode(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> ALPHA_MODE_OPAQUE;
            case CUTOUT -> ALPHA_MODE_CUTOUT;
            case TRANSLUCENT -> ALPHA_MODE_TRANSPARENT;
        };
    }

    public static int blockAtlasTextureId() {
        return TextureTracker.idOf(TextureAtlas.LOCATION_BLOCKS);
    }
}
