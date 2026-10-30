package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import redero.abyssaldepths.artifact.Shades;

/**
 * Particles a shade creature sends while it is updated reach only shades ({@link Shades#PARTICLES_BY_SHADE}),
 * as its sounds do.
 */
@Mixin(ServerLevel.class)
public abstract class ShadeServerParticlesMixin {
    @Inject(method = "sendParticles(Lnet/minecraft/server/level/ServerPlayer;ZDDDLnet/minecraft/network/protocol/Packet;)Z", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$keepToSide(ServerPlayer player, boolean longDistance, double x, double y, double z, Packet<?> packet, CallbackInfoReturnable<Boolean> cir) {
        if (Shades.PARTICLES_BY_SHADE.get() && !Shades.isShade(player)) {
            cir.setReturnValue(false);
        }
    }
}
