package redero.abyssaldepths.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import redero.abyssaldepths.client.ShadeView;

/**
 * What an entity this client does not see shows on an event from the server (a death poof, hearts,
 * smoke) is not shown ({@link ShadeView#quietParticles}). Only around the entity's own handling, so the
 * flag never stays set.
 */
@Mixin(ClientPacketListener.class)
public abstract class ShadeEntityEventMixin {
    @Inject(method = "handleEntityEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;handleEntityEvent(B)V"),
            locals = LocalCapture.CAPTURE_FAILHARD)
    private void abyssaldepths$quietBefore(ClientboundEntityEventPacket packet, CallbackInfo ci, Entity entity) {
        ShadeView.quietParticles = ShadeView.hidden(entity);
    }

    @Inject(method = "handleEntityEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;handleEntityEvent(B)V", shift = At.Shift.AFTER))
    private void abyssaldepths$quietAfter(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        ShadeView.quietParticles = false;
    }
}
