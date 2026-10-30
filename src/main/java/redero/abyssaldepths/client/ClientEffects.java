package redero.abyssaldepths.client;

import redero.abyssaldepths.ClientConfig;
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
                final String[] parts = effect.substring("interlude/".length()).split("/");
                final float strength = parts.length > 1 ? Integer.parseInt(parts[1]) / 100.0F : 0.5F;
                InterludeField.clientActive(Integer.parseInt(parts[0]), payload.ticks(), mc.level.getGameTime(), strength);
            }
        } else if (effect.startsWith("shade/")) {
            final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level != null) {
                final int id = Integer.parseInt(effect.substring("shade/".length()));
                final net.minecraft.world.entity.Entity entity = mc.level.getEntity(id);
                final boolean wasHidden = entity != null && ShadeView.seenHidden(entity);
                Shades.clientShade(id, payload.ticks(), mc.level.getGameTime());
                if (entity instanceof net.minecraft.world.entity.LivingEntity living && !(entity instanceof net.minecraft.world.entity.player.Player)
                        && wasHidden != ShadeView.seenHidden(entity)) {
                    ShadeVanish.creature(living, wasHidden); // creatures dissolve (and come back) pixel by pixel
                }
                ShadeVanish.check(); // at once: until the next tick the player would be drawn as they are now
            }
        } else if (effect.startsWith("waypoints/")) {
            net.minecraft.client.Minecraft.getInstance().setScreen(new WaypointScreen(effect.substring("waypoints/".length())));
        } else if (effect.equals("showcase")) {
            net.minecraft.client.Minecraft.getInstance().setScreen(new ArtifactShowcaseScreen());
        } else if (effect.startsWith("style/")) {
            final String[] parts = effect.split("/");
            if (parts.length == 3) {
                ClientConfig.setStyle(parts[1], parts[2]);
            }
        } else if (effect.startsWith("mirage/")) {
            MirageClient.set(Integer.parseInt(effect.substring("mirage/".length())), payload.ticks());
        } else if (effect.startsWith("vanish_style/")) {
            ClientConfig.setShadeVanish(ClientConfig.ShadeVanishStyle.valueOf(effect.substring("vanish_style/".length())));
        } else if (effect.startsWith("shade_look/")) {
            ClientConfig.setShadeLook(ClientConfig.ShadeLook.valueOf(effect.substring("shade_look/".length())));
        } else if (effect.startsWith("vanish_play/")) {
            final String style = effect.substring("vanish_play/".length());
            ShadeVanish.preview(style.isEmpty() ? ClientConfig.shadeVanish() : ClientConfig.ShadeVanishStyle.valueOf(style));
        } else if (effect.startsWith("haven_out/")) {
            WaypointShots.capture(Long.parseLong(effect.substring("haven_out/".length()))); // the place left, as it looks
            HavenFade.out(payload.ticks());
        } else if (effect.startsWith("waypoint_marks/")) {
            WaypointMarks.set(effect.substring("waypoint_marks/".length()));
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
