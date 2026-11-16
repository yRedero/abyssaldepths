package redero.abyssaldepths.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * A block of storm crystal: the dunes' storm crystals stand in pillars of them, 3 to 5 high, landmarks out on the
 * silt. Like the small clusters round them, dull and unbreakable until a dune storm charges them
 * ({@link StormClusterBlock#CHARGED}); then they glow (the light climbing up the pillar as it wakes) and draw the
 * storm's lightning.
 */
public class StormCrystalBlock extends Block {
    public StormCrystalBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(StormClusterBlock.CHARGED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(StormClusterBlock.CHARGED);
    }

    /** Uncharged, nothing breaks it (in survival): it gives way only while the storm charges it. */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return state.getValue(StormClusterBlock.CHARGED) || player.isCreative() ? super.getDestroyProgress(state, player, level, pos) : 0.0F;
    }
}
