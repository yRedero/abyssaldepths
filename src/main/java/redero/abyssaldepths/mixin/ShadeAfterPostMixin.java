package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.GameRenderer;
import redero.abyssaldepths.client.ShadeView;

/** Right after the post effect: the shades drawn again over the drained picture ({@link ShadeView#afterPost}). */
@Mixin(GameRenderer.class)
public abstract class ShadeAfterPostMixin {
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/PostChain;process(F)V", shift = At.Shift.AFTER))
    private void abyssaldepths$shadesOverDrain(CallbackInfo ci) {
        ShadeView.afterPost();
    }
}
