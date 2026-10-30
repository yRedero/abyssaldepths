package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.player.LocalPlayer;
import redero.abyssaldepths.client.ShadeView;

/**
 * The player's own sounds (steps, landing, eating, hits) are played by their client as "local" sounds,
 * like a block's crackling; marked here, they are heard in the shadow too ({@link ShadeView#allowSounds}).
 */
@Mixin(LocalPlayer.class)
public abstract class ShadeOwnSoundMixin {
    @Inject(method = {"playSound(Lnet/minecraft/sounds/SoundEvent;FF)V", "playNotifySound"}, at = @At("HEAD"))
    private void abyssaldepths$own(CallbackInfo ci) {
        ShadeView.allowSounds = true;
    }

    @Inject(method = {"playSound(Lnet/minecraft/sounds/SoundEvent;FF)V", "playNotifySound"}, at = @At("RETURN"))
    private void abyssaldepths$ownDone(CallbackInfo ci) {
        ShadeView.allowSounds = false;
    }
}
