package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import redero.abyssaldepths.artifact.Shades;

/** Shades are not drawn for those outside the shadow: no body, name, shadow or fire ({@link Shades}). */
@Mixin(EntityRenderDispatcher.class)
public abstract class ShadeRenderMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void abyssaldepths$hideShades(E entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && entity != mc.player && Shades.isShade(entity) && !Shades.isShade(mc.player)) {
            cir.setReturnValue(false);
        }
    }
}
