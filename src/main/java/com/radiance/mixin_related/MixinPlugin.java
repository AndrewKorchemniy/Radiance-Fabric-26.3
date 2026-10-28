package com.radiance.mixin_related;

import com.radiance.client.mod_support.ModSupport;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public class MixinPlugin implements IMixinConfigPlugin {

    public static boolean ENABLED = true;

    /** Capture vanilla's section compile path; not applicable while Sodium is installed. */
    private static final Set<String> VANILLA_TERRAIN_MIXINS = Set.of(
        "com.radiance.mixins.vulkan_render_integration.BuiltChunkStorageMixins",
        "com.radiance.mixins.vulkan_render_integration.ChunkBuilderBuiltChunkMixins",
        "com.radiance.mixins.vulkan_render_integration.LevelExtractorSectionUpdatesMixins",
        "com.radiance.mixins.vulkan_render_integration.SectionBuilderMixins",
        "com.radiance.mixins.vulkan_render_integration.SectionCompileTaskMixins",
        "com.radiance.mixins.vulkan_render_integration.SectionDirtyStateMixins");

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith("com.radiance.mixins.mod_support.sodium.")) {
            return ENABLED && ModSupport.SODIUM;
        }
        // Sodium replaces vanilla's section management (its ViewArea is a stub, sections are built
        // by its own chunk builder), so Radiance captures terrain from Sodium instead.
        if (ModSupport.SODIUM && VANILLA_TERRAIN_MIXINS.contains(mixinClassName)) {
            return false;
        }
        if (mixinClassName.startsWith("com.radiance.mixins.mod_support.distant_horizons.")) {
            return ENABLED && ModSupport.DISTANT_HORIZONS;
        }
        // The Voxy source mixins feed Vista; the rest only keep Voxy's OpenGL renderer from running.
        if (mixinClassName.startsWith("com.radiance.mixins.mod_support.voxy.source.")) {
            return ENABLED && ModSupport.VOXY_SOURCE;
        }
        if (mixinClassName.startsWith("com.radiance.mixins.mod_support.voxy.")) {
            return ENABLED && ModSupport.VOXY;
        }
        return ENABLED;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
        IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
        IMixinInfo mixinInfo) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }
}
