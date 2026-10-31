package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import redero.abyssaldepths.artifact.Firmament;

/** Standing on Firmament's blocks is not flying: the server does not kick their user for floating. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class FirmamentFloatMixin {
    @Inject(method = "noBlocksAround", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$firmament(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (Firmament.holdsUp(entity)) {
            cir.setReturnValue(false);
        }
    }
}
