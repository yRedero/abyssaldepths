package redero.abyssaldepths.artifact;

import java.util.ArrayList;
import java.util.List;

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
 * for that much less (a 300 health boss half the time), never under {@link #MIN_TICKS}. Meanwhile they are held in
 * the circle it reached: nothing sent away can leave it till it comes back. The way back takes its toll: they return
 * {@link #PARALYZED_TICKS} paralyzed, then dazed for {@link #DAZED_TICKS} (slow, weak, their sight darkening), the
 * strong for less. Three charges, one back every 30 minutes.
 */
public class OblivionItem extends ArtifactItem {
    public static final double RADIUS = 24.0;
    public static final int TICKS = 30 * 20, MIN_TICKS = 3 * 20;
    public static final float STRONG_HEALTH = 150.0F;
    private static final int USE_TICKS = 30;
    public static final int PARALYZED_TICKS = 3 * 20, DAZED_TICKS = 8 * 20;

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
            Shades.sendAway(entity, ticks, user.position(), RADIUS);
            sent.add(entity.getType().getDescription().getString() + " " + ticks / 20 + " s");
        }
        // lit TNT goes too: it bursts in the shadow, harming nothing in the world
        for (net.minecraft.world.entity.item.PrimedTnt tnt : level.getEntitiesOfClass(net.minecraft.world.entity.item.PrimedTnt.class,
                user.getBoundingBox().inflate(RADIUS), e -> e.distanceToSqr(user) <= RADIUS * RADIUS)) {
            Shades.sendAway(tnt, TICKS, user.position(), RADIUS);
            sent.add("tnt " + TICKS / 20 + " s");
        }
        // a deep boom (the mark on the ground shows how far it reached)
        level.playSound(null, user.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 1.6F, 0.5F);
        return sent;
    }

    /** Back from the shadow Oblivion sent it to: paralyzed a moment, then dazed (the strong for less). */
    public static void returned(LivingEntity entity) {
        final float share = entity.getMaxHealth() > STRONG_HEALTH ? STRONG_HEALTH / entity.getMaxHealth() : 1.0F;
        final int paralyzed = Math.max(10, Math.round(PARALYZED_TICKS * share)), dazed = Math.max(40, Math.round(DAZED_TICKS * share));
        entity.addEffect(new net.minecraft.world.effect.MobEffectInstance(redero.abyssaldepths.AbyssalDepths.PARALYZED, paralyzed, 0, false, true, true));
        entity.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, paralyzed + dazed, 1, false, true, true));
        entity.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, paralyzed + dazed, 0, false, true, true));
        if (entity instanceof Player) {
            entity.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.DARKNESS, paralyzed + dazed, 0, false, false, true));
        }
        if (entity.level() instanceof ServerLevel level) {
            level.playSound(null, entity.blockPosition(), SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 0.5F, 1.6F);
        }
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

    /** Ticks it is held before it is cast (its charging up is drawn by the clients, {@code client.OblivionView}). */
    public static int useTicks() {
        return USE_TICKS;
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
