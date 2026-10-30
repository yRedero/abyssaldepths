package redero.abyssaldepths.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.artifact.InterludeField;

/** A player slowed by someone's Interlude turns their view slower too: by the pace they are slowed to. */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class InterludeClient {
    private InterludeClient() {
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
