package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import redero.abyssaldepths.artifact.Shades;

/** Projectiles fly through what is on the other side of the shadow from them ({@link Shades}). */
@Mixin(Projectile.class)
public abstract class ShadeProjectileMixin {
    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$throughShades(Entity target, CallbackInfoReturnable<Boolean> cir) {
        final Projectile self = (Projectile) (Object) this;
        if (Shades.isShade(self) != Shades.isShade(target) || Shades.apart(self.getOwner() != null ? self.getOwner() : self, target)) {
            cir.setReturnValue(false);
        }
    }
}
