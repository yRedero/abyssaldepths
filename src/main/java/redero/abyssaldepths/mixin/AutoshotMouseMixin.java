package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MouseHandler;

/**
 * Only for the unattended test game (tools/autoshot.sh, started with -Dabyssaldepths.autoshot): it never grabs or
 * releases the mouse, since both move the real cursor (grabbing locks it into the game window, releasing puts it in
 * the window's middle) and would take the mouse from whoever is working beside it. Without that property it does
 * nothing.
 */
@Mixin(MouseHandler.class)
public abstract class AutoshotMouseMixin {
    private static final boolean AUTOSHOT = System.getProperty("abyssaldepths.autoshot") != null;

    @Inject(method = {"grabMouse", "releaseMouse"}, at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$leaveTheMouse(CallbackInfo ci) {
        if (AUTOSHOT) {
            ci.cancel();
        }
    }
}
