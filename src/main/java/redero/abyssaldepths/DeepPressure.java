package redero.abyssaldepths;

import java.util.Set;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
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
 * No gameplay effect yet; milk does not cure it.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID)
public final class DeepPressure extends MobEffect {
    /** Time in the layer before the effect appears. */
    public static final int GRACE_TICKS = 60 * 20;

    public DeepPressure() {
        super(MobEffectCategory.HARMFUL, 0x15151c);
    }

    @Override
    public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance effectInstance) {
        // no cures: the pressure only goes away by going up
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        final Player player = event.getEntity();
        if (player.level().isClientSide() || player.isSpectator()) {
            return;
        }
        if (player.getBlockY() > TunnelLayer.LAYER_TOP_Y) {
            if (player.getData(AbyssalDepths.TICKS_BELOW) != 0) {
                player.setData(AbyssalDepths.TICKS_BELOW, 0);
            }
            return; // the effect counts down on its own
        }
        final MobEffectInstance current = player.getEffect(AbyssalDepths.DEEP_PRESSURE);
        if (current == null) {
            final int below = player.getData(AbyssalDepths.TICKS_BELOW) + 1;
            player.setData(AbyssalDepths.TICKS_BELOW, below);
            if (below < GRACE_TICKS) {
                return;
            }
        }
        // the instance already ticked down by one this tick: +2 makes the timer count up by one
        final int duration = current == null ? 1 : current.getDuration() + 2;
        player.addEffect(new MobEffectInstance(AbyssalDepths.DEEP_PRESSURE, duration, 0, false, false, true));
    }
}
