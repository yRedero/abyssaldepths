package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.player.LocalPlayer;
import redero.abyssaldepths.artifact.FirmamentItem;

/** Holding Firmament does not slow its user down to a crawl, nor stop their sprint, as using items does. */
@Mixin(LocalPlayer.class)
abstract class FirmamentPaceMixin {
    @WrapOperation(method = {"aiStep", "canStartSprinting"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"))
    private boolean abyssaldepths$firmament(LocalPlayer player, Operation<Boolean> original) {
        return original.call(player) && !(player.getUseItem().getItem() instanceof FirmamentItem);
    }
}
