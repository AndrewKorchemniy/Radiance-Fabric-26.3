package com.radiance.client.texture;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.radiance.client.constant.VulkanConstants;
import com.radiance.client.proxy.vulkan.TextureProxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

public class TextureTracker {

    private static final AtomicLong nextTextureGeneration = new AtomicLong();

    /**
     * Native ids of the textures vanilla allocated, which native samples directly on the shared device.
     * Keyed by identity: vanilla textures don't override equals, and a closed texture must not match a
     * new one.
     */
    private static final Map<GpuTexture, TextureTarget> vanillaTextures =
        Collections.synchronizedMap(new IdentityHashMap<>());

    public static Map<Integer, Texture> GLID2Texture = new ConcurrentHashMap<>();
    public static Map<Integer, TextureBinding> GLID2SpecularGLID = new ConcurrentHashMap<>();
    public static Map<Integer, TextureBinding> GLID2NormalGLID = new ConcurrentHashMap<>();
    public static Map<Integer, TextureBinding> GLID2FlagGLID = new ConcurrentHashMap<>();

    private static final List<Map<Integer, TextureBinding>> auxiliaryBindings = List.of(
        GLID2SpecularGLID, GLID2NormalGLID, GLID2FlagGLID);

    public record TextureTarget(int id, long generation) {
    }

    public record TextureBinding(TextureTarget base, TextureTarget auxiliary) {
    }

    /**
     * Imports a texture vanilla just created. Only sampleable color textures are imported: native never
     * reads vanilla's depth targets, and formats native has no mapping for are skipped.
     */
    public static void onVanillaTextureCreated(VulkanGpuTexture texture) {
        if (importSuppressed.get()) {
            return;
        }
        GpuFormat format = texture.getFormat();
        if ((texture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0 || !format.hasColorAspect()) {
            return;
        }
        VulkanConstants.VkFormat vkFormat;
        try {
            vkFormat = VulkanConstants.VkFormat.fromValue(VulkanConst.toVk(format));
        } catch (IllegalArgumentException unsupported) {
            return;
        }
        int id = TextureProxy.generateTextureId();
        TextureProxy.importVulkanImage(id, texture.vkImage(), vkFormat, texture.getWidth(0),
            texture.getHeight(0), texture.getMipLevels(), texture.getDepthOrLayers());
        vanillaTextures.put(texture, captureTarget(id));
    }

    /** Called when vanilla actually frees the image, two submissions after it was closed. */
    public static void onVanillaTextureDestroyed(VulkanGpuTexture texture) {
        TextureTarget target = vanillaTextures.remove(texture);
        if (target != null) {
            TextureProxy.releaseTexture(target.id());
        }
    }

    private static final ThreadLocal<Boolean> importSuppressed = ThreadLocal.withInitial(() -> false);

    /**
     * Creates textures in {@code action} without importing them. For textures native never samples:
     * 26.3 stitches atlases on the GPU from one scratch texture per sprite, thousands at once, which
     * would otherwise exhaust native's 4096 texture slots.
     */
    public static <T> T withoutImport(java.util.function.Supplier<T> action) {
        boolean previous = importSuppressed.get();
        importSuppressed.set(true);
        try {
            return action.get();
        } finally {
            importSuppressed.set(previous);
        }
    }

    private static final ThreadLocal<Boolean> uploadTrackingSuppressed =
        ThreadLocal.withInitial(() -> false);

    /** Runs {@code action} without following its uploads into auxiliary textures. */
    public static void suppressUploadTracking(Runnable action) {
        boolean previous = uploadTrackingSuppressed.get();
        uploadTrackingSuppressed.set(true);
        try {
            action.run();
        } finally {
            uploadTrackingSuppressed.set(previous);
        }
    }

    public static boolean isUploadTrackingSuppressed() {
        return uploadTrackingSuppressed.get();
    }

    /** The native target of a vanilla texture, or null if it was not imported. */
    public static TextureTarget targetOf(GpuTexture texture) {
        return texture == null ? null : vanillaTextures.get(texture);
    }

    /** The native id of a vanilla texture, or -1 if it was not imported. */
    public static int idOf(GpuTexture texture) {
        TextureTarget target = targetOf(texture);
        return target == null ? -1 : target.id();
    }

    /** The native id of a texture registered with vanilla's texture manager, or -1. */
    public static int idOf(AbstractTexture texture) {
        return texture == null ? -1 : idOf(texture.getTexture());
    }

    /** The native id of the texture vanilla registered under {@code location}, or -1. */
    public static int idOf(Identifier location) {
        return idOf(Minecraft.getInstance().getTextureManager().getTexture(location));
    }

    public static TextureTarget captureTarget(int id) {
        Texture texture = GLID2Texture.get(id);
        if (texture == null) {
            throw new IllegalStateException("Texture " + id + " was not prepared before upload");
        }
        return new TextureTarget(id, texture.generation());
    }

    public static Texture currentTexture(TextureTarget target) {
        if (target == null) {
            return null;
        }
        Texture texture = GLID2Texture.get(target.id());
        return texture != null && texture.generation() == target.generation() ? texture : null;
    }

    public static boolean isCurrent(TextureTarget target) {
        return currentTexture(target) != null;
    }

    public static boolean isCurrent(TextureBinding binding) {
        return binding != null && isCurrent(binding.base()) && isCurrent(binding.auxiliary());
    }

    public static synchronized void registerTexture(TextureTarget target, Texture texture) {
        if (target == null || texture == null) {
            throw new IllegalArgumentException("Texture target and metadata are required");
        }
        if (target.id() < 0) {
            throw new IllegalArgumentException("Invalid texture target id: " + target.id());
        }
        if (target.generation() != texture.generation()) {
            throw new IllegalArgumentException(
                "Texture target generation does not match its metadata: " + target.generation()
                    + " != " + texture.generation());
        }

        Texture previous = GLID2Texture.put(target.id(), texture);
        if (previous != null && previous.generation() != texture.generation()) {
            removeBindingsForId(target.id());
        }
    }

    public static synchronized void releaseTexture(TextureTarget target) {
        if (target == null) {
            return;
        }
        Texture current = GLID2Texture.get(target.id());
        if (current == null || current.generation() != target.generation()) {
            return;
        }
        GLID2Texture.remove(target.id(), current);
        removeBindingsForTarget(target);
    }

    public static synchronized void bindAuxiliary(Map<Integer, TextureBinding> bindings,
        TextureTarget base, TextureTarget auxiliary) {
        if (base == null) {
            return;
        }
        if (!isCurrent(base) || !isCurrent(auxiliary)) {
            bindings.remove(base.id());
            return;
        }
        bindings.put(base.id(), new TextureBinding(base, auxiliary));
    }

    private static void removeBindingsForId(int id) {
        for (Map<Integer, TextureBinding> bindings : auxiliaryBindings) {
            bindings.entrySet().removeIf(entry -> entry.getKey() == id
                || entry.getValue().base().id() == id
                || entry.getValue().auxiliary().id() == id);
        }
    }

    private static void removeBindingsForTarget(TextureTarget target) {
        for (Map<Integer, TextureBinding> bindings : auxiliaryBindings) {
            bindings.entrySet().removeIf(entry -> entry.getValue().base().equals(target)
                || entry.getValue().auxiliary().equals(target));
        }
    }

    public record Texture(int width, int height, int channel, VulkanConstants.VkFormat format,
                          int maxLayer, long generation) {

        public Texture {
            if (width <= 0 || height <= 0 || channel <= 0 || maxLayer < 0) {
                throw new IllegalArgumentException(
                    "Invalid texture width, height, channel, or maxLayer: " + width + ", " + height
                        + ", " + channel + ", " + maxLayer);
            }
        }

        public Texture(int width, int height, int channel, VulkanConstants.VkFormat format,
            int maxLayer) {
            this(width, height, channel, format, maxLayer,
                nextTextureGeneration.incrementAndGet());
        }
    }
}
