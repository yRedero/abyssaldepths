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
 * it. Each crossing costs {@link #COST} charges.
 */
public class InnerHavenItem extends ArtifactItem {
    public static final int COST = 2;
    private static final int USE_TICKS = 60;

    public InnerHavenItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (charge(stack) < COST) {
            player.displayClientMessage(Component.translatable("abyssaldepths.inner_haven.no_charge", COST), true);
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
        if (!level.isClientSide && entity instanceof ServerPlayer player && charge(stack) >= COST && !InnerSelf.travelling(player)) {
            setCharge(stack, charge(stack) - COST);
            level.playSound(null, player.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.8F, 1.6F);
            InnerSelf.travel(player);
        }
        return stack;
    }
}
