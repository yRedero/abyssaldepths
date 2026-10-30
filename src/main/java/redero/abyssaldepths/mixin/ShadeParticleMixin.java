package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import redero.abyssaldepths.client.ShadeView;

/**
 * No particles from what this client does not see ({@link ShadeView#hidden}): a shade sprinting or
 * splashing into water leaves no dust or spray for those outside the shadow (and the other way round).
 */
@Mixin(Entity.class)
public abstract class ShadeParticleMixin {
    @Inject(method = "canSpawnSprintParticle", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$noHiddenDust(CallbackInfoReturnable<Boolean> cir) {
        final Entity self = (Entity) (Object) this;
        if (self.level().isClientSide && ShadeView.hidden(self)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "doWaterSplashEffect", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$noHiddenSplash(CallbackInfo ci) {
        final Entity self = (Entity) (Object) this;
        if (self.level().isClientSide && ShadeView.hidden(self)) {
            ci.cancel();
        }
    }
}
