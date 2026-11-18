package redero.abyssaldepths.cinema;

import java.util.Locale;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import redero.abyssaldepths.network.EffectPayload;

/**
 * The cutscene library, server side: plays a cutscene for a player. Cutscenes are data, not code: a JSON file in
 * {@code assets/<namespace>/cutscenes/<name>.json} (any mod or resource pack can ship its own; see
 * {@code client.cinema.CutscenePlayer} for the format), played on the client at an anchor (a place and a heading the
 * cutscene's positions are relative to), so the same cutscene works anywhere.
 * <p>
 * For add-ons: {@link #play(ServerPlayer, String, Vec3)} / {@link #play(ServerPlayer, String, Vec3, float)} from the
 * server; {@code CutscenePlayer.play(...)} on the client; actors that should act out named animations implement
 * {@link CutsceneActor}.
 */
public final class Cutscenes {
    private Cutscenes() {
    }

    /** Plays a cutscene (e.g. "abyssaldepths:keeper") for a player, anchored at a place, facing the way they look. */
    public static void play(ServerPlayer player, String id, Vec3 anchor) {
        play(player, id, anchor, player.getYRot());
    }

    /** Plays a cutscene for a player, anchored at a place and a heading (yaw, degrees). */
    public static void play(ServerPlayer player, String id, Vec3 anchor, float yaw) {
        PacketDistributor.sendToPlayer(player, new EffectPayload(String.format(Locale.ROOT, "cutscene|%s|%.3f|%.3f|%.3f|%.2f",
                id, anchor.x, anchor.y, anchor.z, yaw), 0));
    }
}
