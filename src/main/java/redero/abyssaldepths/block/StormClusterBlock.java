package redero.abyssaldepths.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * A storm crystal of the silt dunes: dull and hard as the rock it grows from, until a dune storm rages; then it is
 * {@link #CHARGED}, glows, draws the storm's lightning, and only then can it be broken off (it drops storm shards).
 * The charge follows the storm ({@link redero.abyssaldepths.StormCrystals}).
 */
public class StormClusterBlock extends AmethystClusterBlock {
    public static final BooleanProperty CHARGED = BooleanProperty.create("charged");

    public StormClusterBlock(Properties properties) {
        super(7.0F, 3.0F, properties);
        registerDefaultState(defaultBlockState().setValue(CHARGED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(CHARGED);
    }

    /** Uncharged, nothing breaks it (in survival): it gives way only while the storm charges it. */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return state.getValue(CHARGED) || player.isCreative() ? super.getDestroyProgress(state, player, level, pos) : 0.0F;
    }
}
