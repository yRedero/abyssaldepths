package redero.abyssaldepths.artifact;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/**
 * Oblivion: the reverse of Parallax. Hold right click for a moment and everything alive within
 * {@link #RADIUS} blocks of its user (players and creatures, not the user) is sent into the shadow of this
 * world for {@link #TICKS}: gone for everyone outside, among themselves still alive, fighting what else is
 * there ({@link Shades}). The strong stay less long: creatures with more than {@link #STRONG_HEALTH} health
 * for that much less (a 300 health boss half the time), never under {@link #MIN_TICKS}. Three charges, one
 * back every 30 minutes.
 */
public class OblivionItem extends ArtifactItem {
    public static final double RADIUS = 24.0;
    public static final int TICKS = 30 * 20, MIN_TICKS = 3 * 20;
    public static final float STRONG_HEALTH = 150.0F;
    private static final int USE_TICKS = 30;

    public OblivionItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks);
    }

    /** How long an entity stays in the shadow: the full time, less for the strong. */
    public static int ticksFor(LivingEntity entity) {
        final float health = entity.getMaxHealth();
        return health > STRONG_HEALTH ? Math.max(MIN_TICKS, Math.round(TICKS * STRONG_HEALTH / health)) : TICKS;
    }

    /**
     * Sends everything alive around the user, and lit TNT, into the shadow (not the user, armour stands, spectators);
     * returns who went, with their time, for the test command.
     */
    public static List<String> cast(ServerPlayer user) {
        final ServerLevel level = user.serverLevel();
        final List<String> sent = new ArrayList<>();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, user.getBoundingBox().inflate(RADIUS),
                e -> e != user && e.isAlive() && !(e instanceof ArmorStand) && !e.isSpectator() && e.distanceToSqr(user) <= RADIUS * RADIUS)) {
            final int ticks = ticksFor(entity);
            Shades.sendAway(entity, ticks);
            level.sendParticles(ParticleTypes.SQUID_INK, entity.getX(), entity.getY() + entity.getBbHeight() / 2, entity.getZ(), 12,
                    entity.getBbWidth() / 2, entity.getBbHeight() / 3, entity.getBbWidth() / 2, 0.02);
            sent.add(entity.getType().getDescription().getString() + " " + ticks / 20 + " s");
        }
        // lit TNT goes too: it bursts in the shadow, harming nothing in the world
        for (net.minecraft.world.entity.item.PrimedTnt tnt : level.getEntitiesOfClass(net.minecraft.world.entity.item.PrimedTnt.class,
                user.getBoundingBox().inflate(RADIUS), e -> e.distanceToSqr(user) <= RADIUS * RADIUS)) {
            Shades.sendAway(tnt, TICKS);
            level.sendParticles(ParticleTypes.SQUID_INK, tnt.getX(), tnt.getY() + 0.5, tnt.getZ(), 12, 0.3, 0.3, 0.3, 0.02);
            sent.add("tnt " + TICKS / 20 + " s");
        }
        // a ring running out to where it reaches, and a deep boom
        for (int ring = 1; ring <= 4; ring++) {
            final double r = RADIUS * ring / 4;
            final int points = (int) (r * 6);
            for (int i = 0; i < points; i++) {
                final double a = 2 * Math.PI * i / points;
                level.sendParticles(ParticleTypes.REVERSE_PORTAL, user.getX() + Math.cos(a) * r, user.getY() + 0.3, user.getZ() + Math.sin(a) * r, 1, 0, 0.1, 0, 0.0);
            }
        }
        level.playSound(null, user.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 1.6F, 0.5F);
        return sent;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (charge(stack) < 1) {
            player.displayClientMessage(Component.translatable("abyssaldepths.oblivion.no_charge"), true);
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

    /** While it is held: darkness gathers in towards the holder. */
    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (!level.isClientSide) {
            return;
        }
        final float progress = 1.0F - remaining / (float) USE_TICKS;
        for (int i = 0; i < 2 + (int) (progress * 6); i++) {
            final double a = level.random.nextDouble() * 2 * Math.PI, d = 2.0 + level.random.nextDouble() * 3.0;
            final double x = entity.getX() + Math.cos(a) * d, z = entity.getZ() + Math.sin(a) * d, y = entity.getY() + level.random.nextDouble() * 2.0;
            level.addParticle(ParticleTypes.SQUID_INK, x, y, z, -Math.cos(a) * 0.15 * (1 + progress), 0.0, -Math.sin(a) * 0.15 * (1 + progress));
        }
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!level.isClientSide && entity instanceof ServerPlayer player && charge(stack) >= 1) {
            setCharge(stack, charge(stack) - 1);
            cast(player);
            rest(player);
        }
        return stack;
    }
}
