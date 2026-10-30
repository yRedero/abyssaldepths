package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import redero.abyssaldepths.artifact.Shades;

/**
 * Shades pick nothing up (items, experience, arrows), and their own sounds (steps, landing) are not sent
 * to others; their own client still plays them ({@link Shades}).
 */
@Mixin(Player.class)
public abstract class ShadePlayerMixin {
    @Inject(method = "touch", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$shadeTouchesNothing(Entity entity, CallbackInfo ci) {
        if (Shades.isShade((Player) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$shadeIsSilent(SoundEvent sound, float volume, float pitch, CallbackInfo ci) {
        final Player self = (Player) (Object) this;
        if (!self.level().isClientSide && Shades.isShade(self)) {
            ci.cancel();
        }
    }
}
