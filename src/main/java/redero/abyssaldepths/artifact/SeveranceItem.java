package redero.abyssaldepths.artifact;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import redero.abyssaldepths.AbyssalDepths;

/**
 * Severance: right click cuts away every harmful effect on its holder. Three charges, one back every five
 * minutes (sooner with the mastery, {@link #REGEN_SECONDS}); from 50 % mastery it also leaves the holder
 * untouchable by new harmful effects for a while ({@link #IMMUNITY_SECONDS}); at full mastery every effect cut
 * away turns into its good opposite for eight seconds ({@link #OPPOSITES}).
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public class SeveranceItem extends ArtifactItem {
    /** Seconds for a charge to come back, and of immunity afterwards, per milestone (0 .. 4). */
    static final int[] REGEN_SECONDS = {300, 240, 180, 150, 120}, IMMUNITY_SECONDS = {0, 0, 10, 20, 45};
    /** The good opposite of a harmful effect, given at full mastery for {@link #OPPOSITE_TICKS}. */
    private static final Map<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> OPPOSITES = Map.of(
            net.minecraft.world.effect.MobEffects.POISON, net.minecraft.world.effect.MobEffects.REGENERATION,
            net.minecraft.world.effect.MobEffects.WITHER, net.minecraft.world.effect.MobEffects.REGENERATION,
            net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED,
            net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN, net.minecraft.world.effect.MobEffects.DIG_SPEED,
            net.minecraft.world.effect.MobEffects.WEAKNESS, net.minecraft.world.effect.MobEffects.DAMAGE_BOOST,
            net.minecraft.world.effect.MobEffects.BLINDNESS, net.minecraft.world.effect.MobEffects.NIGHT_VISION,
            net.minecraft.world.effect.MobEffects.DARKNESS, net.minecraft.world.effect.MobEffects.NIGHT_VISION,
            net.minecraft.world.effect.MobEffects.HUNGER, net.minecraft.world.effect.MobEffects.SATURATION,
            net.minecraft.world.effect.MobEffects.LEVITATION, net.minecraft.world.effect.MobEffects.SLOW_FALLING,
            net.minecraft.world.effect.MobEffects.UNLUCK, net.minecraft.world.effect.MobEffects.LUCK);
    static final int OPPOSITE_TICKS = 8 * 20;

    /** The opposite of an effect, matched by its name (holders of the same effect need not be the same object). */
    private static net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> opposite(net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
        for (Map.Entry<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> entry : OPPOSITES.entrySet()) {
            if (entry.getKey().is(effect)) {
                return entry.getValue();
            }
        }
        return null;
    }
    /** Server: players immune to harmful effects, until the game time. */
    private static final Map<UUID, Long> IMMUNE = new HashMap<>();

    public SeveranceItem(Properties properties, ArtifactRank rank, ArtifactFrame frame, int maxCharge, int regenTicks) {
        super(properties, rank, frame, maxCharge, regenTicks);
    }

    @Override
    public int regenTicks(ItemStack stack) {
        return REGEN_SECONDS[milestone(stack)] * 20;
    }

    @Override
    public Component powerLine(ItemStack stack) {
        final int m = milestone(stack);
        return IMMUNITY_SECONDS[m] > 0 ? Component.translatable("item.abyssaldepths.severance.power_immunity", REGEN_SECONDS[m] / 60.0F, IMMUNITY_SECONDS[m])
                : Component.translatable("item.abyssaldepths.severance.power", REGEN_SECONDS[m] / 60.0F);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (charge(stack) < 1) {
            player.displayClientMessage(Component.translatable("abyssaldepths.severance.no_charge"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (level.isClientSide || !(player instanceof ServerPlayer server)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        final List<MobEffectInstance> harmful = player.getActiveEffects().stream()
                .filter(effect -> effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL).toList();
        harmful.forEach(effect -> player.removeEffect(effect.getEffect()));
        if (milestone(stack) >= 4) { // mastered: each one cut away turns into its opposite for a while
            for (MobEffectInstance effect : harmful) {
                final net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> opposite = opposite(effect.getEffect());
                if (opposite != null) {
                    player.addEffect(new MobEffectInstance(opposite, OPPOSITE_TICKS, effect.getAmplifier()));
                }
            }
        }
        final int immunity = IMMUNITY_SECONDS[milestone(stack)];
        if (immunity > 0) {
            IMMUNE.put(player.getUUID(), level.getGameTime() + immunity * 20L);
        }
        setCharge(stack, charge(stack) - 1);
        level.playSound(null, player.blockPosition(), SoundEvents.SHEEP_SHEAR, SoundSource.PLAYERS, 1.0F, 0.6F);
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, 0.8F, 1.4F);
        server.serverLevel().sendParticles(ParticleTypes.ENCHANTED_HIT, player.getX(), player.getY() + 1.0, player.getZ(), 24, 0.4, 0.6, 0.4, 0.2);
        rest(player);
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    /** While immune: harmful effects do not take. */
    @SubscribeEvent
    public static void onEffect(MobEffectEvent.Applicable event) {
        if (event.getEntity().level().isClientSide || event.getEffectInstance().getEffect().value().getCategory() != MobEffectCategory.HARMFUL) {
            return;
        }
        final Long until = IMMUNE.get(event.getEntity().getUUID());
        if (until != null) {
            if (until > event.getEntity().level().getGameTime()) {
                event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
            } else {
                IMMUNE.remove(event.getEntity().getUUID());
            }
        }
    }
}
