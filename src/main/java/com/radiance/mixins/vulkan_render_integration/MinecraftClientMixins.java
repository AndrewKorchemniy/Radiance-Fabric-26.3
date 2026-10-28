package com.radiance.mixins.vulkan_render_integration;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import com.radiance.client.option.Options;
import com.radiance.client.pipeline.Pipeline;
import com.radiance.client.proxy.vulkan.RendererProxy;
import com.radiance.client.proxy.world.ChunkProxy;
import com.radiance.client.texture.AuxiliaryTextureReloader;
import com.radiance.client.vulkan.VulkanBootstrap;
import com.radiance.mixins.vulkan_device.FrontendGpuSurfaceAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.main.GameConfig;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Starts and stops the native renderer around vanilla's own Vulkan device. Per-frame acquire, submit
 * and present go through {@code VulkanGpuSurfaceMixins}, since MCVR owns the swapchain.
 */
@Mixin(Minecraft.class)
public class MinecraftClientMixins {

    @Shadow
    @Final
    private GpuSurface windowSurface;

    @Shadow
    private ReloadableResourceManager resourceManager;

    /**
     * Vanilla creates the window surface right after the device and window, which is the earliest
     * point where native has everything it needs.
     */
    @Inject(method = "<init>(Lnet/minecraft/client/main/GameConfig;)V",
        at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/Minecraft;windowSurface:Lcom/mojang/renderpearl/api/device/GpuSurface;",
            opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER))
    private void radiance$initRenderer(GameConfig gameConfig, CallbackInfo ci) {
        VulkanGpuSurface surface =
            (VulkanGpuSurface) ((FrontendGpuSurfaceAccessor) this.windowSurface).radiance$getBackend();
        // Native renderer initialisation (shader compilation, NRD and upscaler setup) needs far more
        // stack than the main thread has.
        long stackSize = 512L * 1024 * 1024;
        Thread initThread = new Thread(null, () -> {
            VulkanBootstrap.attachRenderer(surface);
            Pipeline.collectNativeModules();
        }, "Radiance renderer init", stackSize);
        initThread.start();
        try {
            initThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        Pipeline.loadPipeline();
        Pipeline.build();
    }

    @Inject(method = "<init>(Lnet/minecraft/client/main/GameConfig;)V",
        at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/Minecraft;resourceManager:Lnet/minecraft/server/packs/resources/ReloadableResourceManager;",
            opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER))
    private void radiance$registerAuxiliaryTextureReloader(GameConfig args, CallbackInfo ci) {
        this.resourceManager.registerReloadListener(new AuxiliaryTextureReloader());
    }

    /** Native limits the frame rate itself, since frame generation decides when frames are shown. */
    @Redirect(method = "renderFrame(Z)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"))
    private void radiance$disableVanillaFramerateLimit(int framerateLimit) {
    }

    @Inject(method = "close()V", at = @At("HEAD"))
    private void radiance$saveSettings(CallbackInfo ci) {
        Pipeline.savePipeline();
        Options.overwriteConfig();
    }

    /**
     * Native must release its swapchain before vanilla destroys the surface it was built on, and all
     * of its resources before vanilla destroys the device.
     */
    @Inject(method = "close()V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;close()V"))
    private void radiance$closeNativeRenderer(CallbackInfo ci) {
        RendererProxy.close();
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("HEAD"))
    private void radiance$resetBuiltChunkCount(Screen screen, boolean keepResourcePacks,
        CallbackInfo ci) {
        ChunkProxy.builtChunkCount = 0;
    }
}
