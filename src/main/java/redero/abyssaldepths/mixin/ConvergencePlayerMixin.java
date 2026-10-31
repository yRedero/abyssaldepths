package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import redero.abyssaldepths.artifact.ConvergenceItem;

/** Inside a wall a phasing player still stands upright (the game would make them crawl). */
@Mixin(Player.class)
abstract class ConvergencePlayerMixin {
    @Inject(method = "canPlayerFitWithinBlocksAndEntitiesWhen", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$phase(Pose pose, CallbackInfoReturnable<Boolean> cir) {
        if (ConvergenceItem.phasing((Player) (Object) this)) {
            cir.setReturnValue(true);
        }
    }
}
