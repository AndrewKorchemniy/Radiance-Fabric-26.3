package com.radiance.client.vulkan;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.radiance.client.proxy.vulkan.RendererProxy;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Puts MCVR's world image into vanilla's main render target, between vanilla's frame clear and the
 * hand, screen effects and GUI it draws on top.
 *
 * <p>Vanilla records the whole frame into one submission that only goes to the GPU at the end of the
 * frame. If native submitted its own work now, it would run before vanilla's already-recorded clear
 * and be wiped. Native therefore records a command buffer, and it is appended to vanilla's encoder
 * here, so it runs in order with vanilla's commands.
 */
public final class WorldComposite {

    private WorldComposite() {
    }

    public static void renderWorldInto(RenderTarget target) {
        VulkanGpuTexture color = (VulkanGpuTexture) target.getColorTexture();
        VulkanGpuTextureView colorView = (VulkanGpuTextureView) target.getColorTextureView();
        if (color == null || colorView == null) {
            return;
        }
        // Native owns this command buffer and keeps it alive until the submission it is appended to
        // has completed (vanilla keeps at most two submissions in flight).
        long commandBuffer = RendererProxy.recordWorldFrame(color.vkImage(), colorView.vkImageView(),
            target.width, target.height);
        if (commandBuffer == 0L) {
            return;
        }
        VulkanCommandEncoder encoder = (VulkanCommandEncoder)
            ((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend();
        encoder.execute(
            new VkCommandBuffer(commandBuffer, VulkanBootstrap.vanillaDevice().vkDevice()));
    }
}
