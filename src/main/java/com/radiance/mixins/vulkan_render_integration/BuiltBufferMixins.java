package com.radiance.mixins.vulkan_render_integration;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import com.radiance.client.vertex.PBRVertexFormatElements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MeshData.class)
public class BuiltBufferMixins {

    /**
     * Quad sorting finds positions by the attribute name "Position"; Radiance's PBR formats call it
     * {@link PBRVertexFormatElements#PBR_POS}, so fall back to that.
     */
    @WrapOperation(method = "decodeQuadCentroids(Ljava/nio/ByteBuffer;ILcom/mojang/renderpearl/api/vertex/VertexFormat;Lcom/mojang/blaze3d/vertex/CompactVectorArray;I)V",
        at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/vertex/VertexFormat;getElement(Ljava/lang/String;)Lcom/mojang/renderpearl/api/vertex/VertexFormatElement;"))
    private static VertexFormatElement addPBRPosition(VertexFormat format, String attributeName,
        Operation<VertexFormatElement> original) {
        VertexFormatElement element = original.call(format, attributeName);
        if (element == null) {
            element = original.call(format, PBRVertexFormatElements.PBR_POS.name());
        }
        return element;
    }
}
