package redero.abyssaldepths.artifact;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Repulsion: right click sends a pressure wave out from its holder: everything within {@link #RADIUS}
 * (creatures, players, projectiles, items, lit TNT) is thrown away from them, the living also hurt a
 * little ({@link #DAMAGE}). The mastery widens and strengthens it. Five charges, one back every 30 s.
 */
public class RepulsionItem extends ArtifactItem {
    /** Reach in blocks and force, per milestone (0 .. 4). */
    static final float[] RADIUS = {6.0F, 6.75F, 7.5F, 8.25F, 9.0F}, FORCE = {1.2F, 1.4F, 1.6F, 1.8F, 2.0F};
    static final float DAMAGE = 2.0F;

    public RepulsionItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks);
    }

    @Override
    public Component powerLine(ItemStack stack) {
        final int m = milestone(stack);
        return Component.translatable("item.abyssaldepths.repulsion.power", RADIUS[m], Math.round(FORCE[m] * 100));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (charge(stack) < 1) {
            player.displayClientMessage(Component.translatable("abyssaldepths.repulsion.no_charge"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (level instanceof ServerLevel server) {
            final int m = milestone(stack);
            wave(server, player, RADIUS[m], FORCE[m]);
            setCharge(stack, charge(stack) - 1);
            rest(player);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** Throws everything within the radius away from the user (on the user's side of the shadow). */
    public static int wave(ServerLevel level, Player user, float radius, float force) {
        final Vec3 centre = user.position().add(0, user.getBbHeight() * 0.5, 0);
        int thrown = 0;
        for (Entity entity : level.getEntities(user, user.getBoundingBox().inflate(radius), e -> !e.isSpectator() && !Shades.apart(user, e)
                && !e.hasPassenger(user) && !user.hasPassenger(e) && e.position().add(0, e.getBbHeight() * 0.5, 0).distanceTo(centre) <= radius)) {
            final Vec3 away = entity.position().add(0, entity.getBbHeight() * 0.5, 0).subtract(centre);
            final double d = away.length();
            final Vec3 dir = d < 1.0E-3 ? new Vec3(1, 0, 0) : new Vec3(away.x, 0, away.z).normalize();
            final double strength = force * (1.0 - 0.5 * d / radius);
            if (entity instanceof LivingEntity living) {
                living.hurt(level.damageSources().playerAttack(user), DAMAGE);
            }
            if (AnchorItem.anchored(entity)) {
                continue;
            }
            entity.setDeltaMovement(dir.x * strength, 0.25 + 0.2 * strength, dir.z * strength);
            entity.hasImpulse = true;
            entity.hurtMarked = true; // sent to the clients (a player moves themselves)
            thrown++;
        }
        // the wave: a ring of gusts running out, a burst at the middle
        level.sendParticles(ParticleTypes.GUST_EMITTER_SMALL, centre.x, user.getY() + 0.5, centre.z, 1, 0, 0, 0, 0);
        for (int i = 0; i < 48; i++) {
            final float a = i * Mth.TWO_PI / 48;
            level.sendParticles(ParticleTypes.CLOUD, centre.x + Mth.cos(a) * 0.8, user.getY() + 0.4, centre.z + Mth.sin(a) * 0.8, 0,
                    Mth.cos(a), 0.02, Mth.sin(a), radius * 0.09);
        }
        level.playSound(null, user.blockPosition(), SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 1.2F, 0.7F);
        return thrown;
    }
}
