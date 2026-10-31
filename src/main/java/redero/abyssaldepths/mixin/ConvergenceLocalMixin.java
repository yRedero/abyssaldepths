package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.player.LocalPlayer;
import redero.abyssaldepths.artifact.ConvergenceItem;

/** A phasing player is not nudged out of the blocks they stand in. */
@Mixin(LocalPlayer.class)
abstract class ConvergenceLocalMixin {
    @Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$phase(double x, double z, CallbackInfo ci) {
        if (ConvergenceItem.phasing((LocalPlayer) (Object) this)) {
            ci.cancel();
        }
    }
}
