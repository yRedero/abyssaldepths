package redero.abyssaldepths.worldgen;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
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
 * Only the 16x16 columns of the origin chunk are touched.
 */
public class TunnelFeature extends Feature<NoneFeatureConfiguration> {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

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

        boolean placed = false;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                final int x = chunk.getMinBlockX() + dx;
                final int z = chunk.getMinBlockZ() + dz;
                if (!layer.column(x, z, column)) {
                    continue;
                }
                for (int y = column.floor; y <= column.ceiling; y++) {
                    pos.set(x, y, z);
                    level.setBlock(pos, AIR, 2);
                    placed = true;
                }
            }
        }
        return placed;
    }
}
