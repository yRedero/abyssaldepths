package redero.abyssaldepths.artifact;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.entity.UndertowEntity;

/**
 * Undertow: right click opens a gravitational anomaly where its holder looks (up to {@link #REACH} blocks):
 * it draws the creatures around it in, then bursts ({@link UndertowEntity}). The mastery makes it pull
 * longer and burst harder ({@link #SECONDS}, {@link #DAMAGE}). Three charges, one back every five minutes.
 */
public class UndertowItem extends ArtifactItem {
    public static final double REACH = 20.0;
    /** Seconds it pulls, and the burst's damage, per milestone (0 .. 4). */
    static final int[] SECONDS = {5, 6, 7, 8, 9};
    static final float[] DAMAGE = {10, 15, 20, 25, 30};

    public UndertowItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks);
    }

    @Override
    public Component powerLine(ItemStack stack) {
        final int m = milestone(stack);
        return Component.translatable("item.abyssaldepths.undertow.power", SECONDS[m], Math.round(DAMAGE[m]));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (charge(stack) < 1) {
            player.displayClientMessage(Component.translatable("abyssaldepths.undertow.no_charge"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (level instanceof ServerLevel server) {
            // where the holder looks: in front of the block hit, or as far as it reaches into the air
            final Vec3 eye = player.getEyePosition(), end = eye.add(player.getLookAngle().scale(REACH));
            final BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            final Vec3 at = hit.getType() == HitResult.Type.MISS ? end : hit.getLocation().subtract(player.getLookAngle().scale(0.8)).add(0, 0.6, 0);
            final UndertowEntity anomaly = new UndertowEntity(AbyssalDepths.UNDERTOW_ENTITY.get(), server);
            anomaly.moveTo(at.x, at.y, at.z);
            final int m = milestone(stack);
            anomaly.start(player, SECONDS[m] * 20, DAMAGE[m]);
            server.addFreshEntity(anomaly);
            setCharge(stack, charge(stack) - 1);
            level.playSound(null, player.blockPosition(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 1.0F, 0.6F);
            rest(player);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
