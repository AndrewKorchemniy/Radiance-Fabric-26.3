package com.radiance.client.vista;

import com.radiance.client.proxy.world.VistaProxy;
import com.radiance.mixin_related.extensions.vanilla_resource_tracker.ISpriteContentsExt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;

public class ChunkTerrainBuilder {
    private VistaTerrain.Builder builder;
    private final Map<BlockState, Material> materials = new HashMap<>();
    private final BlockStateModelSet blockModels = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
    private final FluidStateModelSet fluidModels = Minecraft.getInstance().getModelManager().getFluidStateModelSet();
    private final List<BlockStateModelPart> parts = new ArrayList<>();
    private final RandomSource random = RandomSource.create(42);

    public void add(BlockAndTintGetter world, BlockPos pos, BlockState state) {
        int x = pos.getX() & 15;
        int z = pos.getZ() & 15;
        if (x != 0 && x != 15 && z != 0 && z != 15) { return; }
        if (builder == null) {
            CardinalLighting lighting = world.cardinalLighting();
            builder = new VistaTerrain.Builder(lighting.west(), lighting.east(), lighting.north(),
                lighting.south());
        }
        if (state.getRenderShape() == RenderShape.MODEL) {
            List<AABB> boxes = state.getOcclusionShape().toAabbs();
            if (!boxes.isEmpty()) {
                Material material = materials.computeIfAbsent(state, this::getMaterial);
                BlockTintSource tintSource = material.tintIndex < 0 ? null
                    : Minecraft.getInstance().getBlockColors().getTintSource(state, material.tintIndex);
                int tint = tintSource == null ? 0xffffff : tintSource.colorInWorld(state, world, pos);
                int color = tint(material.color, tint);
                for (AABB box : boxes) {
                    if ((x != 0 || box.minX > 0) && (x != 15 || box.maxX < 1)
                        && (z != 0 || box.minZ > 0) && (z != 15 || box.maxZ < 1)) { continue; }
                    builder.add(pos.getX(), pos.getZ(), pos.getY() + (float) box.minY,
                        pos.getY() + (float) box.maxY, (float) box.minX, (float) box.maxX,
                        (float) box.minZ, (float) box.maxZ, color, 0,
                        material.sides[0], material.sides[1], material.sides[2], material.sides[3]);
                }
            }
        }
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            boolean water = fluid.is(FluidTags.WATER);
            Fluid type = fluid.getType();
            float center = fluidHeight(world, pos, type);
            float minimum = center;
            float height = center;
            if (center < 1) {
                float north = fluidHeight(world, pos.north(), type);
                float south = fluidHeight(world, pos.south(), type);
                float west = fluidHeight(world, pos.west(), type);
                float east = fluidHeight(world, pos.east(), type);
                float nw = cornerHeight(world, pos.north().west(), type, center, north, west);
                float ne = cornerHeight(world, pos.north().east(), type, center, north, east);
                float sw = cornerHeight(world, pos.south().west(), type, center, south, west);
                float se = cornerHeight(world, pos.south().east(), type, center, south, east);
                minimum = Math.min(Math.min(nw, ne), Math.min(sw, se));
                height = Math.max(Math.max(nw, ne), Math.max(sw, se));
            }
            BlockState above = world.getBlockState(pos.above());
            if (!type.isSame(above.getFluidState().getType())
                && !sideCovered(world, pos.above(), above, Direction.UP, minimum)) {
                height -= 0.001f;
            }
            BlockState below = world.getBlockState(pos.below());
            boolean renderBottom = FluidRenderer.shouldRenderFace(fluid, state,
                Direction.DOWN, below.getFluidState())
                && !sideCovered(world, pos.below(), below, Direction.DOWN, 0.8888889f);
            FluidModel fluidModel = fluidModels.get(fluid);
            TextureAtlasSprite sprite = fluidModel.flowingMaterial().sprite();
            int color = ((ISpriteContentsExt) sprite.contents()).radiance$getAverageColor();
            // The fluid model's tint is the biome water colour for water and none for lava.
            if (fluidModel.tintSource() != null) {
                color = tint(color, fluidModel.tintSource().colorInWorld(state, world, pos));
            }
            int[] sides = materials.computeIfAbsent(fluid.createLegacyBlock(), this::getMaterial).sides;
            builder.add(pos.getX(), pos.getZ(), pos.getY() + (renderBottom ? 0.001f : 0),
                pos.getY() + height,
                0, 1, 0, 1, color, water ? VistaProxy.MATERIAL_WATER : 0,
                sides[0], sides[1], sides[2], sides[3]);
        }
    }

    public VistaTerrain build() { return builder == null ? VistaTerrain.EMPTY : builder.build(); }

    private Material getMaterial(BlockState state) {
        BlockStateModel model = blockModels.get(state);
        BakedQuad quad = firstQuad(model, Direction.NORTH);
        if (quad == null) {
            quad = firstQuad(model, Direction.UP);
        }
        TextureAtlasSprite sprite = quad == null ? model.particleMaterial().sprite()
            : quad.materialInfo().sprite();
        int color = ((ISpriteContentsExt) sprite.contents()).radiance$getAverageColor();
        if (quad != null && quad.materialInfo().layer() == ChunkSectionLayer.SOLID) { color |= 0xff000000; }
        return new Material(color, quad != null ? quad.materialInfo().tintIndex() : -1,
            Vista.sideMaterials(state));
    }

    private BakedQuad firstQuad(BlockStateModel model, Direction direction) {
        random.setSeed(42);
        model.collectParts(random, parts);
        try {
            for (BlockStateModelPart part : parts) {
                List<BakedQuad> quads = part.getQuads(direction);
                if (!quads.isEmpty()) {
                    return quads.get(0);
                }
            }
            return null;
        } finally {
            parts.clear();
        }
    }

    private static int tint(int color, int tint) {
        int red = (color & 255) * ((tint >>> 16) & 255) / 255;
        int green = ((color >>> 8) & 255) * ((tint >>> 8) & 255) / 255;
        int blue = ((color >>> 16) & 255) * (tint & 255) / 255;
        return (color & 0xff000000) | red | (green << 8) | (blue << 16);
    }

    private static float fluidHeight(BlockAndTintGetter world, BlockPos pos, Fluid fluid) {
        BlockState state = world.getBlockState(pos);
        FluidState sample = state.getFluidState();
        if (fluid.isSame(sample.getType())) {
            return fluid.isSame(world.getFluidState(pos.above()).getType()) ? 1 : sample.getOwnHeight();
        }
        return state.isSolid() ? -1 : 0;
    }

    private static float cornerHeight(BlockAndTintGetter world, BlockPos diagonalPos, Fluid fluid,
        float center, float northSouth, float eastWest) {
        if (eastWest >= 1 || northSouth >= 1) { return 1; }
        float total = 0;
        float weight = 0;
        if (eastWest > 0 || northSouth > 0) {
            float diagonal = fluidHeight(world, diagonalPos, fluid);
            if (diagonal >= 1) { return 1; }
            if (diagonal >= 0) {
                float w = diagonal >= 0.8f ? 10 : 1;
                total += diagonal * w;
                weight += w;
            }
        }
        float centerWeight = center >= 0.8f ? 10 : 1;
        total += center * centerWeight;
        weight += centerWeight;
        if (eastWest >= 0) {
            float w = eastWest >= 0.8f ? 10 : 1;
            total += eastWest * w;
            weight += w;
        }
        if (northSouth >= 0) {
            float w = northSouth >= 0.8f ? 10 : 1;
            total += northSouth * w;
            weight += w;
        }
        return total / weight;
    }

    private static boolean sideCovered(BlockAndTintGetter world, BlockPos pos, BlockState state,
        Direction direction, float height) {
        return state.canOcclude() && Shapes.blockOccludes(
            Shapes.box(0, 0, 0, 1, height, 1), state.getOcclusionShape(), direction);
    }

    private record Material(int color, int tintIndex, int[] sides) {}
}
