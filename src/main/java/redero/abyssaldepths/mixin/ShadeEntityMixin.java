package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import redero.abyssaldepths.artifact.Shades;

/** Shades do not set off pressure plates, tripwires and the like ({@link Shades}). */
@Mixin(Entity.class)
public abstract class ShadeEntityMixin {
    @Inject(method = "isIgnoringBlockTriggers", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$shadeTriggersNothing(CallbackInfoReturnable<Boolean> cir) {
        if (Shades.isShade((Entity) (Object) this)) {
            cir.setReturnValue(true);
        }
    }
}
