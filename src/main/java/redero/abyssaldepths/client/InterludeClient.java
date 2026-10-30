package redero.abyssaldepths.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.artifact.InterludeField;

/**
 * Interlude on the client: a player slowed by someone's Interlude turns their view slower too (by the pace
 * they are slowed to); every tick the fields are noted for the particles.
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class InterludeClient {
    private InterludeClient() {
    }

    /** Every tick: the fields for the particles (see {@link InterludeField#clientSnapshot}). */
    @SubscribeEvent
    public static void onTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            InterludeField.clientPrune(mc.level.getGameTime());
            InterludeField.clientSnapshot(mc.level);
        }
    }

    @SubscribeEvent
    public static void onTurn(CalculatePlayerTurnEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        final float pace = mc.player == null ? 1.0F : InterludeField.clientOwnPace(mc.player);
        if (pace >= 1.0F) {
            return;
        }
        // the game turns by (s * 0.6 + 0.2)^3 * 8 per mouse step: scale that cubed term by the pace
        final double f = event.getMouseSensitivity() * 0.6 + 0.2;
        event.setMouseSensitivity((f * Math.cbrt(pace) - 0.2) / 0.6);
    }
}
