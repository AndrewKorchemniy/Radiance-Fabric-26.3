package com.radiance.client.proxy.vulkan;

import com.radiance.client.vista.Vista;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memSet;

import com.radiance.client.profiler.Profiler;
import com.radiance.client.texture.TextureTracker;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class BufferProxy {

    /** 1.21.4's {@code FogShape} indices, which native still uses. */
    private static final float NO_FOG_END = 1.0E8F;
    private static final int FOG_SHAPE_SPHERE = 0;
    private static final int FOG_SHAPE_CYLINDER = 1;

    public static BufferInfo getBufferInfo(ByteBuffer sourceBuffer) {
        ByteBuffer slicedBuffer = sourceBuffer.slice();

        assert slicedBuffer.isDirect();

        long bufferAddress = memAddress(slicedBuffer);
        int sizeBytes = slicedBuffer.remaining();
        return new BufferInfo(bufferAddress, sizeBytes);
    }

    private static native void updateWorldUniformNative(long uniformAddress);

    /**
     * @param gameTime the day-relative game time vanilla gives its shaders, (time % 24000 +
     *                 partial tick) / 24000
     */
    public static void updateWorldUniform(Camera camera, Matrix4f viewMatrix,
        Matrix4f effectedViewMatrix, Matrix4f projectionMatrix, int overlayTextureID, FogData fog,
        ClientLevel world, float gameTime, int endSkyTextureID, int endPortalTextureID,
        int lightMapTextureID) {
        if (Profiler.ENABLED) {
            Profiler.enter("java.BufferProxy.updateWorldUniform");
        }
        try (var stack = stackPush()) {
            int sizeBytes = 624;
            ByteBuffer uniformBuffer = stack.malloc(sizeBytes);
            long uniformAddress = memAddress(uniformBuffer);
            int byteOffset = 0;

            viewMatrix.get(byteOffset, uniformBuffer);
            byteOffset += Float.BYTES * 16;

            effectedViewMatrix.get(byteOffset, uniformBuffer);
            byteOffset += Float.BYTES * 16;

            projectionMatrix.get(byteOffset, uniformBuffer);
            byteOffset += Float.BYTES * 16;

            byteOffset += Float.BYTES * 16 * 3;
            byteOffset += Float.BYTES * 2;

            uniformBuffer.putFloat(byteOffset, gameTime);
            byteOffset += Float.BYTES;

            byteOffset += Integer.BYTES;

            // The armor glint scroll, as 1.21.4 read it back from setupGlintTexturing(0.16F).
            Matrix4f textureMat = TextureTransform.ARMOR_ENTITY_GLINT_TEXTURING.createMatrix();
            textureMat.get(byteOffset, uniformBuffer);
            byteOffset += Float.BYTES * 16;

            uniformBuffer.putInt(byteOffset, overlayTextureID);
            byteOffset += Integer.BYTES;
            uniformBuffer.putInt(byteOffset, camera.isDetached() ? 0 : 1);
            byteOffset += Integer.BYTES;

            // 26.3 blends an environmental fog (water, lava, blindness...) with a render distance
            // fog. 1.21.4 had a single range: take whichever is nearer, spherical for environmental
            // fog and cylindrical for distance fog, as 1.21.4's FogRenderer chose.
            boolean environmental = fog.environmentalEnd < fog.renderDistanceEnd;
            float fogStart = environmental ? fog.environmentalStart : fog.renderDistanceStart;
            float fogEnd = environmental ? fog.environmentalEnd : fog.renderDistanceEnd;
            if (fogStart >= fogEnd) {
                // Distant Horizons disables vanilla fog by pushing its start to about 4e14 while the
                // end stays at the render distance. Native derives the air's Minecraft fog density
                // from the end - start range, so the inverted range read as a 1 block range and
                // buried everything in haze. Report "no fog" instead.
                fogStart = 0.0F;
                fogEnd = NO_FOG_END;
            }
            uniformBuffer.putFloat(byteOffset, fogStart);
            byteOffset += Float.BYTES;
            uniformBuffer.putFloat(byteOffset, fogEnd);
            byteOffset += Float.BYTES;

            uniformBuffer.putFloat(byteOffset, fog.color.x());
            byteOffset += Float.BYTES;
            uniformBuffer.putFloat(byteOffset, fog.color.y());
            byteOffset += Float.BYTES;
            uniformBuffer.putFloat(byteOffset, fog.color.z());
            byteOffset += Float.BYTES;
            uniformBuffer.putFloat(byteOffset, fog.color.w());
            byteOffset += Float.BYTES;

            uniformBuffer.putInt(byteOffset, environmental ? FOG_SHAPE_SPHERE : FOG_SHAPE_CYLINDER);
            byteOffset += Integer.BYTES;
            uniformBuffer.putInt(byteOffset, world.dimensionType()
                .skybox()
                .ordinal());
            byteOffset += Integer.BYTES;
            uniformBuffer.putInt(byteOffset, world.getMinY());
            byteOffset += Integer.BYTES;
            uniformBuffer.putInt(byteOffset, world.getMinY() + world.getHeight());
            byteOffset += Integer.BYTES;

            byteOffset += Double.BYTES;
            byteOffset += Double.BYTES;
            byteOffset += Double.BYTES;
            byteOffset += Double.BYTES;
            byteOffset += Integer.BYTES;
            byteOffset += Integer.BYTES;
            byteOffset += Integer.BYTES;
            byteOffset += Integer.BYTES;
            byteOffset += Integer.BYTES;
            byteOffset += Integer.BYTES;
            byteOffset += Integer.BYTES;
            byteOffset += Integer.BYTES;

            uniformBuffer.putInt(byteOffset, endSkyTextureID);
            byteOffset += Integer.BYTES;
            uniformBuffer.putInt(byteOffset, endPortalTextureID);
            byteOffset += Integer.BYTES;
            uniformBuffer.putInt(byteOffset, lightMapTextureID);
            byteOffset += Integer.BYTES;
            uniformBuffer.putInt(byteOffset,
                Math.max(16, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16));
            byteOffset += Integer.BYTES;

            uniformBuffer.putInt(byteOffset, Vista.distanceBlocks());
            uniformBuffer.putInt(byteOffset + 4, 0);
            uniformBuffer.putInt(byteOffset + 8, 0);
            uniformBuffer.putInt(byteOffset + 12, Vista.textureId());
            uniformBuffer.putLong(byteOffset + 16, 0);
            uniformBuffer.putLong(byteOffset + 24, 0);
            updateWorldUniformNative(uniformAddress);
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    private static native void updateSkyUniformNative(long uniformAddress);

    public static void updateSkyUniform(float baseColorR, float baseColorG, float baseColorB,
        float horizonColorR, float horizonColorG, float horizonColorB, float horizonColorA,
        Vector3f sunDirection, int skyType, boolean sunRisingOrSetting, boolean skyDark,
        boolean hasBlindnessOrDarkness, int submersionType, int moonPhase, float rainGradient,
        int sunTextureID, int moonTextureID) {
        if (Profiler.ENABLED) {
            Profiler.enter("java.BufferProxy.updateSkyUniform");
        }
        try (var stack = stackPush()) {
            int sizeBytes = 80;
            ByteBuffer uniformBuffer = stack.malloc(sizeBytes);
            long uniformAddress = memAddress(uniformBuffer);
            int byteOffset = 0;

            uniformBuffer.putFloat(byteOffset, baseColorR);
            byteOffset += Float.BYTES;
            uniformBuffer.putFloat(byteOffset, baseColorG);
            byteOffset += Float.BYTES;
            uniformBuffer.putFloat(byteOffset, baseColorB);
            byteOffset += Float.BYTES;
            uniformBuffer.putInt(byteOffset, skyType);
            byteOffset += Integer.BYTES;

            uniformBuffer.putFloat(byteOffset, horizonColorR);
            byteOffset += Float.BYTES;
            uniformBuffer.putFloat(byteOffset, horizonColorG);
            byteOffset += Float.BYTES;
            uniformBuffer.putFloat(byteOffset, horizonColorB);
            byteOffset += Float.BYTES;
            uniformBuffer.putFloat(byteOffset, horizonColorA);
            byteOffset += Float.BYTES;

            uniformBuffer.putFloat(byteOffset, sunDirection.x);
            byteOffset += Float.BYTES;
            uniformBuffer.putFloat(byteOffset, sunDirection.y);
            byteOffset += Float.BYTES;
            uniformBuffer.putFloat(byteOffset, sunDirection.z);
            byteOffset += Float.BYTES;
            uniformBuffer.putInt(byteOffset, sunRisingOrSetting ? 1 : 0);
            byteOffset += Integer.BYTES;

            uniformBuffer.putInt(byteOffset, skyDark ? 1 : 0);
            byteOffset += Integer.BYTES;
            uniformBuffer.putInt(byteOffset, hasBlindnessOrDarkness ? 1 : 0);
            byteOffset += Integer.BYTES;
            uniformBuffer.putInt(byteOffset, submersionType);
            byteOffset += Integer.BYTES;
            uniformBuffer.putInt(byteOffset, moonPhase);
            byteOffset += Integer.BYTES;
            uniformBuffer.putFloat(byteOffset, rainGradient);
            byteOffset += Float.BYTES;
            uniformBuffer.putInt(byteOffset, sunTextureID);
            byteOffset += Integer.BYTES;
            uniformBuffer.putInt(byteOffset, moonTextureID);
            byteOffset += Integer.BYTES;
            var minecraft = Minecraft.getInstance();
            Vec3 camera = minecraft.gameRenderer.mainCamera()
                .position();
            uniformBuffer.putInt(byteOffset, com.radiance.client.texture.RainExposure.update(
                minecraft.level, camera.x, camera.z));

            updateSkyUniformNative(uniformAddress);
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    private static native void updateMappingNative(long mappingAddress);

    public static void updateMapping() {
        if (Profiler.ENABLED) {
            Profiler.enter("java.BufferProxy.updateMapping");
        }
        try (var stack = stackPush()) {
            final int elementCount = 4096;
            int sizeBytes = elementCount * Integer.BYTES * 3;
            ByteBuffer mappingBuffer = stack.malloc(sizeBytes);
            long mappingAddress = memAddress(mappingBuffer);
            memSet(mappingAddress, -1, sizeBytes);
            IntBuffer intView = mappingBuffer.asIntBuffer();

            writeTextureMappings(intView, elementCount, TextureTracker.GLID2SpecularGLID, 0,
                "Specular");
            writeTextureMappings(intView, elementCount, TextureTracker.GLID2NormalGLID, 1,
                "Normal");
            writeTextureMappings(intView, elementCount, TextureTracker.GLID2FlagGLID, 2,
                "Flag");

            updateMappingNative(mappingAddress);
        } finally {
            if (Profiler.ENABLED) {
                Profiler.exit();
            }
        }
    }

    private static void writeTextureMappings(IntBuffer destination, int elementCount,
        Map<Integer, TextureTracker.TextureBinding> bindings, int component, String name) {
        for (Map.Entry<Integer, TextureTracker.TextureBinding> entry : bindings.entrySet()) {
            int sourceId = entry.getKey();
            TextureTracker.TextureBinding binding = entry.getValue();
            if (!TextureTracker.isCurrent(binding)) {
                bindings.remove(sourceId, binding);
                continue;
            }
            int targetId = binding.auxiliary().id();
            if (sourceId < 0 || sourceId >= elementCount || targetId < 0 || targetId >= elementCount) {
                throw new IllegalStateException(name + " mapping " + sourceId + " -> " + targetId
                    + " out of index [0, " + (elementCount - 1) + "]");
            }
            destination.put(sourceId * 3 + component, targetId);
        }
    }

    public record BufferInfo(long address, int sizeBytes) {

    }
}
