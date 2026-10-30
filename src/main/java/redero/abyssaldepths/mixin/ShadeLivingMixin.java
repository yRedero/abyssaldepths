package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import redero.abyssaldepths.artifact.Shades;

/**
 * Across the sides of the shadow nothing pushes and nothing can be aimed at; shades raise no dust landing
 * ({@link Shades}).
 */
@Mixin(LivingEntity.class)
public abstract class ShadeLivingMixin {
    @Inject(method = "doPush", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$noShadePush(Entity other, CallbackInfo ci) {
        if (Shades.apart((Entity) (Object) this, other)) {
            ci.cancel();
        }
    }

    /** The dust of a hard landing is sent to everyone: not for a shade. */
    @Redirect(method = "checkFallDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"))
    private <T extends ParticleOptions> int abyssaldepths$noShadeDust(ServerLevel level, T particle, double x, double y, double z, int count,
            double dx, double dy, double dz, double speed) {
        return Shades.isShade((Entity) (Object) this) ? 0 : level.sendParticles(particle, x, y, z, count, dx, dy, dz, speed);
    }

    @Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$shadeNotPickable(CallbackInfoReturnable<Boolean> cir) {
        // on a client: what is on the other side of the shadow from its view cannot be aimed at
        final Entity self = (Entity) (Object) this;
        if (self.level().isClientSide && Shades.isShade(self) != Shades.clientViewInShadow) {
            cir.setReturnValue(false);
        }
    }
}
