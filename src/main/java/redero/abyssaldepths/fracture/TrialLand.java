package redero.abyssaldepths.fracture;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The real land, copied into the trials in black and white: each block a grey by its colour's brightness (dark
 * deepslate to white quartz), pieces of it laid down where the trial wants them.
 */
final class TrialLand {
    /** The tones the copy is made of, dark to light. */
    static final Block[] TONES = {Blocks.POLISHED_DEEPSLATE, Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.CALCITE, Blocks.WHITE_CONCRETE,
            Blocks.SMOOTH_QUARTZ};

    private TrialLand() {
    }

    /** A block's tone (by its colour's brightness), steps darker. */
    static BlockState tone(BlockState real, ServerLevel from, BlockPos at, int darker) {
        final int rgb = real.getMapColor(from, at).col;
        final float lum = (0.3F * (rgb >> 16 & 255) + 0.59F * (rgb >> 8 & 255) + 0.11F * (rgb & 255)) / 255.0F;
        final int k = lum < 0.2F ? 0 : lum < 0.33F ? 1 : lum < 0.46F ? 2 : lum < 0.58F ? 3 : lum < 0.72F ? 4 : 5;
        return TONES[Math.max(0, k - darker)].defaultBlockState();
    }

    /** The top block of the real land at x, z (stone if there is none). */
    static BlockState top(ServerLevel from, int x, int z, BlockPos.MutableBlockPos at) {
        at.set(x, from.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
        final BlockState state = from.getBlockState(at);
        return state.isAir() ? Blocks.STONE.defaultBlockState() : state;
    }

    /**
     * A square piece of the real land round (realX, realZ) laid at middle: its top as the floor, two layers under it,
     * the rim a step darker. The blocks set are added to placed (if given).
     */
    static void piece(ServerLevel into, ServerLevel from, int realX, int realZ, BlockPos middle, int half, List<BlockPos> placed) {
        final BlockPos.MutableBlockPos real = new BlockPos.MutableBlockPos();
        for (int dz = -half; dz <= half; dz++) {
            for (int dx = -half; dx <= half; dx++) {
                final BlockState state = top(from, realX + dx, realZ + dz, real);
                final boolean rim = Math.abs(dx) == half || Math.abs(dz) == half;
                for (int down = 0; down < 3; down++) {
                    final BlockPos at = middle.offset(dx, -down, dz);
                    into.setBlock(at, tone(state, from, real, down == 0 ? (rim ? 1 : 0) : down), 2);
                    if (placed != null) {
                        placed.add(at);
                    }
                }
            }
        }
    }
}
