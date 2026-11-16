package redero.abyssaldepths.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.level.BlockEvent;
import redero.abyssaldepths.AbyssalDepths;

/** An axe strips the dusk stalk and its hyphae, as it does logs. */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class GloamStripping {
    private GloamStripping() {
    }

    @SubscribeEvent
    public static void onToolUse(BlockEvent.BlockToolModificationEvent event) {
        if (event.getItemAbility() != ItemAbilities.AXE_STRIP) {
            return;
        }
        final BlockState state = event.getState();
        final Block stripped = state.is(AbyssalDepths.DUSK_STALK.get()) ? AbyssalDepths.STRIPPED_DUSK_STALK.get()
                : state.is(AbyssalDepths.DUSK_HYPHAE.get()) ? AbyssalDepths.STRIPPED_DUSK_HYPHAE.get() : null;
        if (stripped != null) {
            event.setFinalState(stripped.defaultBlockState().setValue(RotatedPillarBlock.AXIS, state.getValue(RotatedPillarBlock.AXIS)));
        }
    }
}
