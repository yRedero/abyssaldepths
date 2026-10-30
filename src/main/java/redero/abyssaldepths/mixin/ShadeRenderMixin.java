package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import redero.abyssaldepths.client.ShadeView;

/**
 * Who sees whom ({@link ShadeView#hidden}): shades are not drawn for those outside the shadow (no body,
 * name, shadow or fire); in the shadow, only other shades are, unless the shade's artifact lets it see the
 * world.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class ShadeRenderMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void abyssaldepths$hideShades(E entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (ShadeView.hidden(entity)) {
            cir.setReturnValue(false);
        }
    }
}
