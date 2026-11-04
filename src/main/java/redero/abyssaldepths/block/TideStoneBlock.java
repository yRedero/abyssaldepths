package redero.abyssaldepths.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import redero.abyssaldepths.AbyssalDepths;

/**
 * A tide stone: the crest of the rock spine that runs under a tide lake from the shore to its island. It keeps the
 * world's time: once in every {@link #CYCLE} the tide goes out, and the crest comes up out of the water stone after
 * stone from the shore ({@link #STEP} is its place along the way), thalan or, where the gloam grew on it ({@link #GLOAM}),
 * glowing; when the tide comes back in they go under again in the same order, so the way back closes first. Only
 * stones the world placed keep time (they are started with a tick); placed by hand, one is just gloam.
 */
public class TideStoneBlock extends Block {
    public static final IntegerProperty STEP = IntegerProperty.create("step", 0, 15);
    /** Whether what comes up over it is gloam (else thalan). */
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty GLOAM =
            net.minecraft.world.level.block.state.properties.BooleanProperty.create("gloam");
    /** One tide (ticks), when in it the first stone rises and sinks, how much later each next one does. */
    public static final int CYCLE = 2400, RISE = 0, SINK = 900, STEP_TICKS = 10;

    public TideStoneBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(STEP, 0).setValue(GLOAM, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(STEP, GLOAM);
    }

    /** Whether a stone at this step stands out of the water at this time. */
    public static boolean low(long time, int step) {
        final long t = Math.floorMod(time - (long) step * STEP_TICKS, (long) CYCLE);
        return t >= RISE && t < SINK;
    }

    /** Ticks from now until this step's next change (rising or sinking). */
    public static int untilChange(long time, int step) {
        final long t = Math.floorMod(time - (long) step * STEP_TICKS, (long) CYCLE);
        final long next = t < RISE ? RISE : t < SINK ? SINK : CYCLE + RISE;
        return (int) Math.max(1, next - t);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        final int step = state.getValue(STEP);
        final long time = level.getGameTime();
        final BlockPos up = pos.above();
        final BlockState above = level.getBlockState(up);
        final BlockState crest = (state.getValue(GLOAM) ? AbyssalDepths.GLOAM_CRYSTAL : AbyssalDepths.THALAN).get().defaultBlockState();
        if (low(time, step)) {
            if (above.getFluidState().is(FluidTags.WATER) && above.getFluidState().isSource()) {
                level.setBlock(up, crest, Block.UPDATE_ALL);
                level.playSound(null, up, state.getValue(GLOAM) ? SoundEvents.AMETHYST_BLOCK_CHIME : SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT,
                        SoundSource.BLOCKS, 0.6F, 0.6F + random.nextFloat() * 0.2F);
            }
        } else if (above.is(crest.getBlock())) { // only what it raised itself goes back under
            level.setBlock(up, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            level.playSound(null, up, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 0.4F, 0.5F);
        }
        level.scheduleTick(pos, this, untilChange(time, step));
    }
}
