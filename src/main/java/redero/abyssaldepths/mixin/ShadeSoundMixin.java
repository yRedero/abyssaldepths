package redero.abyssaldepths.mixin;

import javax.annotation.Nullable;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import redero.abyssaldepths.artifact.Shades;

/**
 * Sounds keep to their side of the shadow ({@link Shades}): a shade hears only what other shades make, and
 * what a shade makes only other shades hear. Applies to the sounds (and level events, mostly sounds) the
 * server sends to the players around a place; who made one is the player it is not sent to, the entity a
 * sound is on, or a shade creature that marked it ({@code ShadeCreatureSoundMixin}) or is being updated (a
 * Wither's skull shots are a world event sent from its update).
 */
@Mixin(PlayerList.class)
public abstract class ShadeSoundMixin {
    @Shadow
    @Final
    private java.util.List<ServerPlayer> players;
    @Shadow
    @Final
    private net.minecraft.server.MinecraftServer server;

    @Inject(method = "broadcast", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$soundsKeepToTheirSide(@Nullable Player except, double x, double y, double z, double radius,
            ResourceKey<Level> dimension, Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ClientboundSoundPacket || packet instanceof ClientboundSoundEntityPacket || packet instanceof ClientboundLevelEventPacket)) {
            return;
        }
        final boolean byShade = except != null && Shades.isShade(except) || Shades.SOUND_BY_SHADE.get() || Shades.PARTICLES_BY_SHADE.get()
                || packet instanceof ClientboundSoundEntityPacket sound && server.getLevel(dimension) != null
                        && Shades.isShade(server.getLevel(dimension).getEntity(sound.getId()));
        for (ServerPlayer player : players) {
            if (player != except && player.level().dimension() == dimension && Shades.isShade(player) == byShade
                    && player.distanceToSqr(x, y, z) < radius * radius) {
                player.connection.send(packet);
            }
        }
        ci.cancel();
    }
}
