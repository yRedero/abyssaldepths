package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.Entity;
import redero.abyssaldepths.artifact.Shades;

/**
 * The sounds a shade creature makes (steps, calls, hurt) are marked while they are sent, so they reach only
 * shades ({@code ShadeSoundMixin}): the server sends them without saying who made them.
 */
@Mixin(Entity.class)
public abstract class ShadeCreatureSoundMixin {
    @Inject(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V", at = @At("HEAD"))
    private void abyssaldepths$byShade(CallbackInfo ci) {
        final Entity self = (Entity) (Object) this;
        if (!self.level().isClientSide && Shades.isShade(self)) {
            Shades.SOUND_BY_SHADE.set(true);
        }
    }

    @Inject(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V", at = @At("RETURN"))
    private void abyssaldepths$byShadeDone(CallbackInfo ci) {
        Shades.SOUND_BY_SHADE.set(false);
    }
}
