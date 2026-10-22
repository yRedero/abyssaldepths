package redero.abyssaldepths.worldgen;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Carves the {@link TunnelLayer} into the chunk the feature is placed for. It runs once per
 * chunk (empty placement list) in the raw_generation step, before ores and other decoration.
 * Only the 16x16 columns of the origin chunk are carved. Lava next to the new air (ends of vanilla
 * lava tunnels reaching down into the layer) is turned into magma so it does not pour into the
 * cavern; that may touch blocks of the neighbouring chunks, which the region allows.
 */
public class TunnelFeature extends Feature<NoneFeatureConfiguration> {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState MAGMA = Blocks.MAGMA_BLOCK.defaultBlockState();

    public TunnelFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        final WorldGenLevel level = context.level();
        final ChunkPos chunk = new ChunkPos(context.origin());
        final TunnelLayer layer = new TunnelLayer(level.getSeed());
        final TunnelLayer.Column column = new TunnelLayer.Column();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();

        boolean placed = false;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                final int x = chunk.getMinBlockX() + dx;
                final int z = chunk.getMinBlockZ() + dz;
                layer.column(x, z, column);
                for (int y = TunnelLayer.MIN_AIR_Y; y <= TunnelLayer.MAX_AIR_Y; y++) {
                    if (!layer.isAir(x, y, z, column)) {
                        continue;
                    }
                    pos.set(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, AIR, 2);
                        placed = true;
                    }
                    for (Direction dir : Direction.values()) {
                        side.setWithOffset(pos, dir);
                        if (level.getFluidState(side).is(FluidTags.LAVA)) {
                            level.setBlock(side, MAGMA, 2);
                        }
                    }
                }
            }
        }
        return placed;
    }
}
