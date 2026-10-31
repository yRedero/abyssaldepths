package redero.abyssaldepths.artifact;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import redero.abyssaldepths.AbyssalDepths;

/**
 * Anchor: right click and nothing moves its holder for a while (the Anchored effect,
 * {@link AbyssalDepths#ANCHORED}): no knockback from blows, explosions, or this mod's pushes (Repulsion,
 * Aegis). Longer with the mastery ({@link #SECONDS}). Three charges, one back every two minutes.
 */
public class AnchorItem extends ArtifactItem {
    /** Seconds it holds, per milestone (0 .. 4). */
    static final int[] SECONDS = {10, 15, 20, 25, 30};

    public AnchorItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks);
    }

    /** Whether nothing may push this entity now. */
    public static boolean anchored(net.minecraft.world.entity.Entity entity) {
        return entity instanceof LivingEntity living && living.hasEffect(AbyssalDepths.ANCHORED);
    }

    @Override
    public Component powerLine(ItemStack stack) {
        return Component.translatable("item.abyssaldepths.anchor.power", SECONDS[milestone(stack)]);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (charge(stack) < 1) {
            player.displayClientMessage(Component.translatable("abyssaldepths.anchor.no_charge"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (!level.isClientSide) {
            player.addEffect(new MobEffectInstance(AbyssalDepths.ANCHORED, SECONDS[milestone(stack)] * 20, 0, false, false, true));
            setCharge(stack, charge(stack) - 1);
            level.playSound(null, player.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.4F, 0.5F);
            rest(player);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @EventBusSubscriber(modid = AbyssalDepths.MODID)
    public static final class Events {
        private Events() {
        }

        /** Every knockback, whatever its source (the effect's resistance covers explosions). */
        @SubscribeEvent
        public static void onKnockBack(LivingKnockBackEvent event) {
            if (anchored(event.getEntity())) {
                event.setCanceled(true);
            }
        }
    }
}
