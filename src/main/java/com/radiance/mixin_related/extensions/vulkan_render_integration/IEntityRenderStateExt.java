package com.radiance.mixin_related.extensions.vulkan_render_integration;

/**
 * What Radiance needs to know about the entity behind a render state. 26.3 render states hold no
 * reference to their entity, so this is recorded when the state is extracted.
 */
public interface IEntityRenderStateExt {

    /** A key that stays the same for the entity across frames, or 0 if the state was not extracted. */
    long radiance$getEntityKey();

    void radiance$setEntityKey(long entityKey);

    /** The {@code Constants.RayTracingFlags} value for the entity. */
    int radiance$getRayTracingFlag();

    void radiance$setRayTracingFlag(int rayTracingFlag);

    /** Text displays are post-rendered as text, like 1.21.4. */
    boolean radiance$isTextDisplay();

    void radiance$setTextDisplay(boolean textDisplay);
}
