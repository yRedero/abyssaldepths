package redero.abyssaldepths.artifact;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
 * Inner Haven: hold right click for three seconds and the holder fades away into their inner self (their
 * own room in the Inner Self dimension), leaving their body behind; using it there brings them back to
 * it. A crossing costs charges, fewer with the mastery ({@link #cost}: 2 at first, 1 from 50 %, and coming
 * back is free at 100 %); from 75 % the holder flies in their inner self ({@link InnerSelf}).
 */
public class InnerHavenItem extends ArtifactItem {
    /** Charges a crossing costs going in, and coming back, per milestone (0 .. 4). */
    private static final int[] COST_IN = {2, 2, 1, 1, 1}, COST_BACK = {2, 2, 1, 1, 0};
    /** The milestone from which the holder flies in their inner self. */
    public static final int FLIGHT_MILESTONE = 3;

    /** What the crossing the player would make now costs. */
    public static int cost(ItemStack stack, Player player) {
        final int m = milestone(stack);
        return player.level().dimension() == InnerSelf.DIMENSION ? COST_BACK[m] : COST_IN[m];
    }

    @Override
    public Component powerLine(ItemStack stack) {
        final int m = milestone(stack);
        return Component.translatable(m >= FLIGHT_MILESTONE ? "item.abyssaldepths.inner_haven.power_flight" : "item.abyssaldepths.inner_haven.power",
                COST_IN[m], COST_BACK[m]);
    }
    private static final int USE_TICKS = 60;

    public InnerHavenItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (charge(stack) < cost(stack, player)) {
            player.displayClientMessage(Component.translatable("abyssaldepths.inner_haven.no_charge", cost(stack, player)), true);
            return InteractionResultHolder.fail(stack);
        }
        if (!level.isClientSide && InnerSelf.travelling(player)) {
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return USE_TICKS;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.NONE;
    }

    /** While it is held: light gathers around the holder, faster and closer towards the end. */
    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (!level.isClientSide) {
            return;
        }
        final float progress = 1.0F - remaining / (float) USE_TICKS;
        for (int i = 0; i < 1 + (int) (progress * 4); i++) {
            final double a = level.random.nextDouble() * 2 * Math.PI, d = 2.5 - 1.8 * progress;
            final double x = entity.getX() + Math.cos(a) * d, z = entity.getZ() + Math.sin(a) * d;
            final double y = entity.getY() + level.random.nextDouble() * 2.0;
            level.addParticle(ParticleTypes.END_ROD, x, y, z, (entity.getX() - x) * 0.05, 0.01, (entity.getZ() - z) * 0.05);
        }
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!level.isClientSide && entity instanceof ServerPlayer player && charge(stack) >= cost(stack, player) && !InnerSelf.travelling(player)) {
            setCharge(stack, charge(stack) - cost(stack, player));
            level.playSound(null, player.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.8F, 1.6F);
            InnerSelf.travel(player);
            rest(player);
        }
        return stack;
    }
}
