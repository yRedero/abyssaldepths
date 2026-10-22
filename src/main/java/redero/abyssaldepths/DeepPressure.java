package redero.abyssaldepths;

import java.util.Set;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.EffectCure;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import redero.abyssaldepths.worldgen.TunnelLayer;

/**
 * "Deep Pressure": after a minute in the thalan layer the effect shows up and its timer counts up
 * for as long as the player stays down there. Back above, it counts down like any other effect.
 * Going down again while it still runs continues counting up right away, without the minute.
 * Milk does not cure it.
 * <p>
 * The timer is the pressure. It sets the stage, which is the level of the effect (Deep Pressure I
 * to IV). The debuffs are part of the effect itself: attribute modifiers by level, hunger in
 * {@link #applyEffectTick}, and at level IV short bursts of hidden vanilla nausea (for the screen
 * warp). Tunnel vision, muffled hearing, heartbeat, ringing and hallucinations are client side
 * ({@code client.DeepPressureClient}).
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class DeepPressure extends MobEffect {
    /** Time in the layer before the effect appears. */
    public static final int GRACE_TICKS = 60 * 20;
    /** Timer value at which stage 2, 3 and 4 begin; stage 1 starts with the effect. */
    public static final int[] STAGE_TICKS = {5 * 60 * 20, 10 * 60 * 20, 15 * 60 * 20};
    /** Timer value of the full pressure (last stage). */
    public static final int MAX_TICKS = STAGE_TICKS[STAGE_TICKS.length - 1];
    // per level I .. IV
    private static final double[] MINING = {-0.4, -0.5, -0.65, -0.75}; // like mining fatigue, multiplies
    private static final double[] MOVEMENT = {0.0, -0.15, -0.15, -0.3}; // like slowness I / II
    private static final double[] ATTACK = {0.0, 0.0, -3.0, -4.0};      // like weakness
    private static final float[] EXHAUSTION = {0.0F, 0.005F, 0.005F, 0.0075F}; // per tick, hunger I = 0.005
    /** Level IV: chance per second of a 7 second nausea burst (vanilla nausea, hidden: no icon). */
    private static final float NAUSEA_CHANCE = 1.0F / 50.0F;

    public DeepPressure() {
        super(MobEffectCategory.HARMFUL, 0x15151c);
        addAttributeModifier(Attributes.BLOCK_BREAK_SPEED, id("mining"), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                amplifier -> MINING[level(amplifier)]);
        addAttributeModifier(Attributes.MOVEMENT_SPEED, id("movement"), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                amplifier -> MOVEMENT[level(amplifier)]);
        addAttributeModifier(Attributes.ATTACK_DAMAGE, id("attack"), AttributeModifier.Operation.ADD_VALUE,
                amplifier -> ATTACK[level(amplifier)]);
    }

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "deep_pressure_" + name);
    }

    private static int level(int amplifier) {
        return Math.max(0, Math.min(STAGE_TICKS.length, amplifier));
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity instanceof Player player && EXHAUSTION[level(amplifier)] > 0.0F) {
            player.causeFoodExhaustion(EXHAUSTION[level(amplifier)]);
        }
        return true;
    }

    @Override
    public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance effectInstance) {
        // no cures: the pressure only goes away by going up
    }

    /** 0 without the effect, else 1 to 4 (its level). */
    public static int stage(LivingEntity entity) {
        final MobEffectInstance effect = entity.getEffect(AbyssalDepths.DEEP_PRESSURE);
        return effect == null ? 0 : effect.getAmplifier() + 1;
    }

    /** Amplifier the timer asks for. */
    private static int amplifierFor(int duration) {
        int amplifier = 0;
        for (int start : STAGE_TICKS) {
            if (duration >= start) {
                amplifier++;
            }
        }
        return amplifier;
    }

    /** 0 without the effect, 1 at full pressure. */
    public static float pressure(LivingEntity entity) {
        final MobEffectInstance effect = entity.getEffect(AbyssalDepths.DEEP_PRESSURE);
        return effect == null ? 0.0F : Math.min(1.0F, effect.getDuration() / (float) MAX_TICKS);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        final Player player = event.getEntity();
        if (player.level().isClientSide() || player.isSpectator()) {
            return;
        }
        final MobEffectInstance current = player.getEffect(AbyssalDepths.DEEP_PRESSURE);
        if (current != null && current.getAmplifier() >= STAGE_TICKS.length && player.tickCount % 20 == 0
                && player.getRandom().nextFloat() < NAUSEA_CHANCE) {
            player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 7 * 20, 0, true, false, false));
        }
        if (player.getBlockY() > TunnelLayer.LAYER_TOP_Y) {
            if (player.getData(AbyssalDepths.TICKS_BELOW) != 0) {
                player.setData(AbyssalDepths.TICKS_BELOW, 0);
            }
            // the timer counts down on its own; drop a level once it falls below the stage.
            // A lower level does not replace a running effect, so it is removed and added again.
            if (current != null && amplifierFor(current.getDuration()) < current.getAmplifier()) {
                player.removeEffect(AbyssalDepths.DEEP_PRESSURE);
                player.addEffect(new MobEffectInstance(AbyssalDepths.DEEP_PRESSURE, current.getDuration(),
                        amplifierFor(current.getDuration()), false, false, true));
            }
            return;
        }
        if (current == null) {
            final int below = player.getData(AbyssalDepths.TICKS_BELOW) + 1;
            player.setData(AbyssalDepths.TICKS_BELOW, below);
            if (below < GRACE_TICKS) {
                return;
            }
        }
        // the instance already ticked down by one this tick: +2 makes the timer count up by one.
        // A new one starts at a full second, an effect at 0 ticks would be removed on its next tick.
        final int duration = current == null ? 20 : current.getDuration() + 2;
        player.addEffect(new MobEffectInstance(AbyssalDepths.DEEP_PRESSURE, duration, amplifierFor(duration), false, false, true));
    }
}
