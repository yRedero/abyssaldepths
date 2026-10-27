package redero.abyssaldepths.client;

import redero.abyssaldepths.network.EffectPayload;

/** Runs the effects the server asks for ({@link EffectPayload}): dune storms and the test commands. */
public final class ClientEffects {
    private ClientEffects() {
    }

    public static void handle(EffectPayload payload) {
        final String effect = payload.effect();
        if (effect.equals("storm")) {
            DuneStormClient.set(payload.ticks());
        } else if (effect.startsWith("hallucinate/")) {
            DeepPressureClient.hallucinate(effect.substring("hallucinate/".length()));
        } else if (effect.startsWith("ambience/")) {
            LayerAmbience.trigger(effect.substring("ambience/".length()));
        }
    }
}
