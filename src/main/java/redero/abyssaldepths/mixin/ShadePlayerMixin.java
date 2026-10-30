package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import redero.abyssaldepths.artifact.Shades;

/**
 * Shades pick nothing up: items, experience, arrows ({@link Shades}). Their sounds are kept to the shadow
 * by {@code ShadeSoundMixin}.
 */
@Mixin(Player.class)
public abstract class ShadePlayerMixin {
    @Inject(method = "touch", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$shadeTouchesNothing(Entity entity, CallbackInfo ci) {
        if (Shades.isShade((Player) (Object) this)) {
            ci.cancel();
        }
    }
}
