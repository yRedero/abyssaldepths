package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import redero.abyssaldepths.artifact.ConvergenceItem;

/** A player phasing with Convergence moves through blocks (on their client and on the server alike). */
@Mixin(Entity.class)
abstract class ConvergenceMixin {
    @Inject(method = "collide", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$phase(Vec3 move, CallbackInfoReturnable<Vec3> cir) {
        final Entity self = (Entity) (Object) this;
        if (ConvergenceItem.phasing(self)) {
            cir.setReturnValue(ConvergenceItem.collide(self, move));
        }
    }
}
