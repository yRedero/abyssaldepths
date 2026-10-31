package redero.abyssaldepths.artifact;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/**
 * Firmament: while right click is held, a block of air under its holder's feet bears them
 * ({@link Firmament}): they walk on it, jump to climb, let go to fall. One charge a hold; a hold lasts up
 * to {@link #SECONDS} and the blocks left behind stay for {@link #TRAIL}, both longer with the mastery, and
 * from {@link #SOFT_LANDING} on the fall after letting go does no harm. Five charges, one back a minute.
 */
public class FirmamentItem extends ArtifactItem {
    /** Seconds a hold lasts and ticks a block left behind stays, per milestone (0 .. 4). */
    static final int[] SECONDS = {5, 10, 15, 20, 30}, TRAIL = {20, 30, 40, 50, 60};
    /** The milestone from which the fall after letting go is soft. */
    static final int SOFT_LANDING = 2;

    public FirmamentItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks);
    }

    @Override
    public Component powerLine(ItemStack stack) {
        final int m = milestone(stack);
        return Component.translatable(m >= SOFT_LANDING ? "item.abyssaldepths.firmament.power_soft" : "item.abyssaldepths.firmament.power",
                SECONDS[m], TRAIL[m] / 20.0F);
    }

    @Override
    protected int cooldownTicks() {
        return 20;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (charge(stack) < 1) {
            player.displayClientMessage(Component.translatable("abyssaldepths.firmament.no_charge"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (!level.isClientSide) {
            setCharge(stack, charge(stack) - 1);
            level.playSound(null, player.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.6F, 1.8F);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return SECONDS[milestone(stack)] * 20;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.NONE;
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        end(level, entity);
    }

    /** The hold ran out. */
    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        end(level, entity);
        return stack;
    }

    private void end(Level level, LivingEntity entity) {
        if (!level.isClientSide && entity instanceof Player player) {
            level.playSound(null, player.blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.5F, 1.8F);
            rest(player);
        }
    }
}
