package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import redero.abyssaldepths.artifact.Shades;

/** Shades neither push nor get pushed, and cannot be aimed at ({@link Shades}). */
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

    @Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$shadeNotPickable(CallbackInfoReturnable<Boolean> cir) {
        if (Shades.isShade((Entity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
