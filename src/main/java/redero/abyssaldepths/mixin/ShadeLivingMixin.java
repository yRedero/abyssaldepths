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

/** Shades neither push nor get pushed, cannot be aimed at, and raise no dust landing ({@link Shades}). */
@Mixin(LivingEntity.class)
public abstract class ShadeLivingMixin {
    @Inject(method = "doPush", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$noShadePush(Entity other, CallbackInfo ci) {
        if (Shades.isShade((Entity) (Object) this) || Shades.isShade(other)) {
            ci.cancel();
        }
    }

    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$shadeNotPushable(CallbackInfoReturnable<Boolean> cir) {
        if (Shades.isShade((Entity) (Object) this)) {
            cir.setReturnValue(false);
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
        if (Shades.isShade((Entity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
