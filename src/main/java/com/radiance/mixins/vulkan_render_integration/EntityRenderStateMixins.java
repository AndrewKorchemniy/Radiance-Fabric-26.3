package com.radiance.mixins.vulkan_render_integration;

import com.radiance.mixin_related.extensions.vulkan_render_integration.IEntityRenderStateExt;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public class EntityRenderStateMixins implements IEntityRenderStateExt {

    @Unique
    private long radiance$entityKey = 0L;

    @Unique
    private int radiance$rayTracingFlag = 0;

    @Unique
    private boolean radiance$textDisplay = false;

    @Override
    public long radiance$getEntityKey() {
        return radiance$entityKey;
    }

    @Override
    public void radiance$setEntityKey(long entityKey) {
        radiance$entityKey = entityKey;
    }

    @Override
    public int radiance$getRayTracingFlag() {
        return radiance$rayTracingFlag;
    }

    @Override
    public void radiance$setRayTracingFlag(int rayTracingFlag) {
        radiance$rayTracingFlag = rayTracingFlag;
    }

    @Override
    public boolean radiance$isTextDisplay() {
        return radiance$textDisplay;
    }

    @Override
    public void radiance$setTextDisplay(boolean textDisplay) {
        radiance$textDisplay = textDisplay;
    }
}
