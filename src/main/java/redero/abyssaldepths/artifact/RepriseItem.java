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
import redero.abyssaldepths.artifact.reprise.Reprise;
import redero.abyssaldepths.artifact.reprise.RepriseRecorder;

/**
 * Reprise: while it is in a player's inventory, the world remembers its last 120 seconds
 * ({@link RepriseRecorder}); hold right click for five seconds and everything (blocks, their contents,
 * creatures, items, every player) goes back to how it was, for everyone. One charge, back after 30
 * minutes. Reprise itself is not rewound.
 */
public class RepriseItem extends ArtifactItem {
    private static final int USE_TICKS = 100;

    public RepriseItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (charge(stack) < 1) {
            player.displayClientMessage(Component.translatable("abyssaldepths.reprise.no_charge"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (!level.isClientSide && Reprise.pending()) {
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

    /** While it is held: sparks drift backwards around the holder, ever faster. */
    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (!level.isClientSide) {
            return;
        }
        final float progress = 1.0F - remaining / (float) USE_TICKS;
        for (int i = 0; i < 1 + (int) (progress * 4); i++) {
            final double a = level.random.nextDouble() * 2 * Math.PI, d = 0.6 + level.random.nextDouble() * 1.6;
            final double x = entity.getX() + Math.cos(a) * d, z = entity.getZ() + Math.sin(a) * d, y = entity.getY() + level.random.nextDouble() * 2.0;
            // tangential and slightly up: the air turns round them, backwards
            level.addParticle(ParticleTypes.WAX_OFF, x, y, z, Math.sin(a) * 0.08 * (1 + progress), 0.02, -Math.cos(a) * 0.08 * (1 + progress));
        }
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!level.isClientSide && entity instanceof ServerPlayer player && charge(stack) >= 1 && Reprise.activate(player.server)) {
            setCharge(stack, charge(stack) - 1);
            level.playSound(null, player.blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.0F, 0.6F);
            rest(player);
        }
        return stack;
    }
}
