package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.texture.RainExposure;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public class RainExposureChunkMixins {
    @Inject(method = {
        "replaceWithPacketData(IILnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;)V",
        "replaceBiomes(Lnet/minecraft/network/FriendlyByteBuf;)V"}, at = @At("RETURN"))
    private void rainPacketChanged(CallbackInfo ci) {
        RainExposure.changed((LevelChunk) (Object) this);
    }

    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("RETURN"))
    private void rainChanged(BlockPos pos, BlockState state, int flags,
                             CallbackInfoReturnable<BlockState> ci) {
        if (ci.getReturnValue() != null) RainExposure.changed((LevelChunk) (Object) this);
    }
}
