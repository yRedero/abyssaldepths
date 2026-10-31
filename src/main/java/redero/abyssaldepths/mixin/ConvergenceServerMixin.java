package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import redero.abyssaldepths.artifact.ConvergenceItem;

/** The server lets a phasing player move into blocks (it would set them back). */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ConvergenceServerMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "isPlayerCollidingWithAnythingNew", at = @At("HEAD"), cancellable = true)
    private void abyssaldepths$phase(LevelReader level, AABB box, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (ConvergenceItem.phasing(player)) {
            cir.setReturnValue(false);
        }
    }
}
