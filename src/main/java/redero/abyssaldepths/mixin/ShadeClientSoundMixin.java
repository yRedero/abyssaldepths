package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientLevel;
import redero.abyssaldepths.client.ShadeView;

/**
 * Marks the sounds that may play in the shadow ({@link ShadeView#allowSounds}): those the server sends
 * (it keeps them to their side already) and the player's own, both of which come through here.
 */
@Mixin(ClientLevel.class)
public abstract class ShadeClientSoundMixin {
    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"))
    private void abyssaldepths$allowAt(CallbackInfo ci) {
        ShadeView.allowSounds = true;
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("RETURN"))
    private void abyssaldepths$allowAtDone(CallbackInfo ci) {
        ShadeView.allowSounds = false;
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"))
    private void abyssaldepths$allowOn(CallbackInfo ci) {
        ShadeView.allowSounds = true;
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("RETURN"))
    private void abyssaldepths$allowOnDone(CallbackInfo ci) {
        ShadeView.allowSounds = false;
    }
}
