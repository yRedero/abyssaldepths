package redero.abyssaldepths.client;

import redero.abyssaldepths.artifact.InterludeField;
import redero.abyssaldepths.artifact.Shades;
import redero.abyssaldepths.network.EffectPayload;

/** Runs the effects the server asks for ({@link EffectPayload}): dune storms and the test commands. */
public final class ClientEffects {
    private ClientEffects() {
    }

    public static void handle(EffectPayload payload) {
        final String effect = payload.effect();
        if (effect.startsWith("interlude/")) {
            final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level != null) {
                InterludeField.clientActive(Integer.parseInt(effect.substring("interlude/".length())), payload.ticks(), mc.level.getGameTime());
            }
        } else if (effect.startsWith("shade/")) {
            final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level != null) {
                Shades.clientShade(Integer.parseInt(effect.substring("shade/".length())), payload.ticks(), mc.level.getGameTime());
            }
        } else if (effect.equals("haven_out")) {
            HavenFade.out(payload.ticks());
        } else if (effect.equals("haven_in")) {
            HavenFade.in(payload.ticks());
        } else if (effect.equals("reprise_out")) {
            RepriseCover.start();
        } else if (effect.equals("reprise_done")) {
            RepriseCover.done();
        } else if (effect.equals("storm")) {
            DuneStormClient.set(payload.ticks());
        } else if (effect.startsWith("hallucinate/")) {
            DeepPressureClient.hallucinate(effect.substring("hallucinate/".length()));
        } else if (effect.startsWith("ambience/")) {
            LayerAmbience.trigger(effect.substring("ambience/".length()));
        }
    }
}
