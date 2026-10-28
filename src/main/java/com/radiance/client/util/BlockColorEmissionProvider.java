package com.radiance.client.util;

import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A block tint source that also makes the tinted texels emissive. 26.3 registers one
 * {@link BlockTintSource} per tint index, so the index is no longer passed in.
 */
public interface BlockColorEmissionProvider extends BlockTintSource {

    /** {@code net.minecraft.util.Tuple} is gone in 26.3. */
    record ColorEmission(int color, float emission) {
    }

    ColorEmission getColorEmission(BlockState state, @Nullable BlockAndTintGetter world,
        @Nullable BlockPos pos);

    @Override
    default int color(BlockState state) {
        return getColorEmission(state, null, null).color();
    }

    @Override
    default int colorInWorld(BlockState state, BlockAndTintGetter world, BlockPos pos) {
        return getColorEmission(state, world, pos).color();
    }

    default float getEmission(BlockState state, @Nullable BlockAndTintGetter world,
        @Nullable BlockPos pos) {
        return getColorEmission(state, world, pos).emission();
    }
}
